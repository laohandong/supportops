package io.supportops.demo.service.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** 完整响应预算覆盖正文收取；逐块限长，取消后不接纳迟到正文。 */
public final class BoundedDemoHttp {
    /** 工具只接受调用方构造的固定回环请求。 */
    private BoundedDemoHttp() {}

    /** 单次有界请求，无重试；未完整取得的响应不是业务成功证据。 */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request, Duration budget, int maxBytes)
            throws IOException, InterruptedException, TimeoutException {
        if (budget.isNegative() || budget.isZero()) {
            throw new TimeoutException("VERIFICATION_BUDGET_EXCEEDED");
        }
        AtomicReference<LimitedBody> current = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, info -> {
            LimitedBody body = new LimitedBody(maxBytes);
            current.set(body);
            if (cancelled.get()) {
                body.cancel();
            }
            return body;
        });
        try {
            return response.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IOException("REMEDIATION_EVIDENCE_TOO_LARGE".equals(cause.getMessage())
                    ? "REMEDIATION_EVIDENCE_TOO_LARGE" : "INCOMPLETE_DOWNSTREAM_RESPONSE", cause);
        } finally {
            cancelled.set(true);
            response.cancel(true);
            LimitedBody body = current.get();
            if (body != null) {
                body.cancel();
            }
        }
    }

    /** 内存累计不超过字节预算，订阅和结果互斥完成。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /** 接收服务端固定的正文上限。 */
        private LimitedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** 完整正文或读取失败才结束阶段。 */
        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        /** 背压逐批读取，取消先发生时不再收取。 */
        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (result.isDone()) {
                value.cancel();
            } else {
                value.request(1);
            }
        }

        /** 分配数组和写缓存之前检查上限。 */
        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - bytes.size()) {
                    result.completeExceptionally(new IOException("REMEDIATION_EVIDENCE_TOO_LARGE"));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        /** 失败保留不完整事实。 */
        @Override
        public synchronized void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        /** 只接纳取消之前完整到达的正文。 */
        @Override
        public synchronized void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }

        /** 超时主动关闭订阅，迟到回调不能覆盖结果。 */
        private synchronized void cancel() {
            result.completeExceptionally(new IOException("DOWNSTREAM_READ_CANCELLED"));
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}
