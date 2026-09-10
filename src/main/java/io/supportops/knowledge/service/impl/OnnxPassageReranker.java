package io.supportops.knowledge.service.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.supportops.knowledge.dto.RerankResult;
import io.supportops.knowledge.service.PassageReranker;
import io.supportops.knowledge.service.support.RerankingException;
import io.supportops.knowledge.vo.RankingInfo;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 复用本地 ONNX 及分词资源，串行限流并将取消信号传递给原生推理。 */
@Component
public final class OnnxPassageReranker implements PassageReranker, AutoCloseable {
    private final boolean enabled;
    private final Path modelPath;
    private final Path tokenizerPath;
    private final int candidates;
    private final int maxTokens;
    private final int threads;
    private final long timeoutMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("rerank-cancellation").factory());
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private String modelId;
    private boolean closed;

    /** 启动时只读取配置；首次有候选时加载模型，未启用时不要求本地文件。 */
    public OnnxPassageReranker(Environment environment) {
        enabled = environment.getProperty("supportops.reranking.enabled", Boolean.class, false);
        modelPath = Path.of(environment.getProperty("supportops.reranking.model-path", ".local/models/reranker/model_quantized.onnx"));
        tokenizerPath = Path.of(environment.getProperty("supportops.reranking.tokenizer-path", ".local/models/reranker/tokenizer.json"));
        candidates = environment.getProperty("supportops.reranking.candidates", Integer.class, 20);
        maxTokens = environment.getProperty("supportops.reranking.max-input-tokens", Integer.class, 512);
        threads = environment.getProperty("supportops.reranking.threads", Integer.class, 2);
        timeoutMs = environment.getProperty("supportops.reranking.timeout-ms", Long.class, 30000L);
        if (candidates < 5 || candidates > 100 || maxTokens < 32 || maxTokens > 8192
                || threads < 1 || threads > 16 || timeoutMs < 1 || timeoutMs > 120000) {
            throw new RerankingException("RERANK_CONFIGURATION_INVALID");
        }
    }

    /** 返回候选预算，避免把全部召回结果无界送入本地模型。 */
    @Override
    public int candidateLimit() {
        return candidates;
    }

    /** 返回显式开关，不根据推理失败自动关闭模型。 */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /** 等待资源、加载和整个候选集合共用一次时间预算，失败不返回部分分数。 */
    @Override
    public RerankResult score(String query, List<String> passages) {
        if (!enabled || passages.isEmpty()) {
            return new RerankResult(List.of(), new RankingInfo(
                    passages.isEmpty() ? "EMPTY" : "RRF", "", passages.size(), 0, 0));
        }
        if (query == null || query.isBlank() || query.length() > 2000 || passages.size() > candidates) {
            throw new RerankingException("RERANK_INPUT_INVALID");
        }
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new RerankingException("RERANK_TIMED_OUT");
            }
            checkBudget(deadline);
            if (closed) {
                throw new RerankingException("RERANK_UNAVAILABLE");
            }
            initialize();
            checkBudget(deadline);
            List<Double> scores = infer(query, passages, deadline);
            return new RerankResult(List.copyOf(scores), new RankingInfo("ONNX", modelId,
                    passages.size(), maxTokens, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RerankingException("RERANK_CANCELLED");
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    /** 原生资源只有全部就绪并通过签名检查后才发布，失败时回收已创建部分。 */
    private void initialize() {
        if (session != null) {
            return;
        }
        if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(tokenizerPath)) {
            throw new RerankingException("RERANK_MODEL_MISSING");
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            session = OrtEnvironment.getEnvironment().createSession(modelPath.toString(), options);
            validateSignature();
            tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerPath)
                    .optAddSpecialTokens(true).optPadding(false).optTruncation(true)
                    .optMaxLength(maxTokens).build();
            modelId = "onnx-sha256:" + digest(modelPath) + ";tokenizer-sha256:" + digest(tokenizerPath);
        } catch (Exception | LinkageError exception) {
            release();
            throw new RerankingException("RERANK_MODEL_INVALID");
        }
    }

    /** 本适配器仅支持两个 INT64 输入和单个浮点相关性输出，拒绝误用嵌入模型。 */
    private void validateSignature() throws OrtException {
        Map<String, NodeInfo> inputs = session.getInputInfo();
        if (!inputs.keySet().equals(Set.of("input_ids", "attention_mask"))) {
            throw new RerankingException("RERANK_MODEL_INVALID");
        }
        for (NodeInfo input : inputs.values()) {
            if (!(input.getInfo() instanceof TensorInfo tensor)
                    || tensor.type != OnnxJavaType.INT64 || tensor.getShape().length != 2) {
                throw new RerankingException("RERANK_MODEL_INVALID");
            }
        }
        NodeInfo output = session.getOutputInfo().get("logits");
        if (output == null || !(output.getInfo() instanceof TensorInfo tensor)
                || tensor.type != OnnxJavaType.FLOAT || tensor.getShape().length != 2
                || tensor.getShape()[1] != 1) {
            throw new RerankingException("RERANK_MODEL_INVALID");
        }
    }

    /** 成对分词保留模型的特殊 token 模板，逐条推理限制中间张量内存。 */
    private List<Double> infer(String query, List<String> passages, long deadline) {
        List<Double> scores = new ArrayList<>();
        try (OrtSession.RunOptions options = new OrtSession.RunOptions()) {
            Cancellation cancellation = new Cancellation(Thread.currentThread(), deadline, options);
            ScheduledFuture<?> monitor = watchdog.scheduleAtFixedRate(cancellation::check, 0, 25, TimeUnit.MILLISECONDS);
            try {
                for (String passage : passages) {
                    checkBudget(deadline);
                    Encoding encoded = tokenizer.encode(query, passage);
                    if (encoded.getIds().length == 0 || encoded.getIds().length > maxTokens
                            || encoded.getAttentionMask().length != encoded.getIds().length) {
                        throw new RerankingException("RERANK_INPUT_INVALID");
                    }
                    checkBudget(deadline);
                    try (OnnxTensor ids = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{encoded.getIds()});
                            OnnxTensor mask = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{encoded.getAttentionMask()});
                            OrtSession.Result result = session.run(Map.of("input_ids", ids, "attention_mask", mask), options)) {
                        float[][] logits = (float[][]) result.get("logits").orElseThrow().getValue();
                        if (logits.length != 1 || logits[0].length != 1 || !Float.isFinite(logits[0][0])) {
                            throw new RerankingException("RERANK_OUTPUT_INVALID");
                        }
                        scores.add((double) logits[0][0]);
                    }
                }
                checkBudget(deadline);
                return scores;
            } finally {
                // 同步等待回调退出，再关闭 RunOptions，避免回调访问已释放的原生句柄。
                cancellation.stop();
                monitor.cancel(false);
            }
        } catch (RerankingException exception) {
            throw exception;
        } catch (Exception | LinkageError exception) {
            checkBudget(deadline);
            throw new RerankingException("RERANK_INFERENCE_FAILED");
        }
    }

    /** Java 中断保持在当前线程上，供诊断任务与外层执行器继续观察。 */
    private static void checkBudget(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RerankingException("RERANK_CANCELLED");
        }
        if (System.nanoTime() >= deadline) {
            throw new RerankingException("RERANK_TIMED_OUT");
        }
    }

    /** 流式计算文件身份，不将大模型整体读入 Java 堆。 */
    private static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 在持有资源锁时释放本实例句柄，不关闭进程共享的 OrtEnvironment。 */
    private void release() {
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (OrtException exception) {
                throw new RerankingException("RERANK_CLOSE_FAILED");
            } finally {
                session = null;
            }
        }
    }

    /** 等待正在执行的有界推理退出后关闭资源，后续请求明确失败。 */
    @PreDestroy
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            watchdog.shutdownNow();
            release();
        } finally {
            lock.unlock();
        }
    }

    /** 在独立线程上观察调用线程的取消及截止时间，并终止原生运算。 */
    private static final class Cancellation {
        private final Thread owner;
        private final long deadline;
        private final OrtSession.RunOptions options;
        private boolean stopped;

        /** 固定本次推理的调用线程和原生终止选项。 */
        private Cancellation(Thread owner, long deadline, OrtSession.RunOptions options) {
            this.owner = owner;
            this.deadline = deadline;
            this.options = options;
        }

        /** 句柄生命周期由同一监视器保护，终止失败会中断调用线程以阻止接受结果。 */
        private synchronized void check() {
            if (!stopped && (owner.isInterrupted() || System.nanoTime() >= deadline)) {
                try {
                    options.setTerminate(true);
                } catch (OrtException exception) {
                    owner.interrupt();
                }
            }
        }

        /** 返回后保证没有回调仍在使用 RunOptions。 */
        private synchronized void stop() {
            stopped = true;
        }
    }
}
