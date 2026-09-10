package io.supportops.agent.service.support;

import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.RunStreamSnapshot;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/** 将持久化事件适配为可续接的只读 SSE；连接结束不会取消诊断。 */
@Component
public final class RunEventStream {
    private final RunService runs;
    private final RunStreamSignals signals;

    /** 通过业务服务读取状态，不直接操作持久化层。 */
    public RunEventStream(RunService runs, RunStreamSignals signals) {
        this.runs = runs;
        this.signals = signals;
    }

    /** 校验任务后建立有限时长连接，客户端使用最后事件编号续接。 */
    public SseEmitter open(String id, long after) {
        runs.get(id);
        SseEmitter emitter = new SseEmitter(180_000L);
        RunStreamSignals.Subscription subscription = signals.subscribe(id);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        Thread.startVirtualThread(() -> send(id, after, emitter, subscription));
        return emitter;
    }

    /** 提交信号触发查询，先取状态再取事件；空闲心跳不查询数据库。 */
    private void send(
            String id, long after, SseEmitter emitter, RunStreamSignals.Subscription subscription) {
        long cursor = after;
        long deadline = System.nanoTime() + 175_000_000_000L;
        try (subscription) {
            boolean changed = true;
            while (!subscription.isClosed() && System.nanoTime() < deadline) {
                if (!changed) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } else {
                    DiagnosisRun run = runs.get(id);
                    List<DiagnosisEvent> events = runs.events(id, cursor);
                    if (!events.isEmpty()) {
                        cursor = events.getLast().id();
                    }
                    emitter.send(
                            SseEmitter.event()
                                    .name("snapshot")
                                    .id(Long.toString(cursor))
                                    .data(new RunStreamSnapshot(run, events)));
                    if (!"RUNNING".equals(run.status()) && !"QUEUED".equals(run.status())) {
                        break;
                    }
                }
                changed = subscription.awaitChange(15_000);
            }
            emitter.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException exception) {
            emitter.completeWithError(exception);
        }
    }
}
