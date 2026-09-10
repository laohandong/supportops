package io.supportops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.supportops.agent.service.RunService;
import io.supportops.agent.service.support.RunEventStream;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.agent.vo.DiagnosisRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证流式唤醒的合并、任务隔离、关闭与空闲时不轮询数据库。 */
class RunStreamSignalsTest {
    /** 查询前到达的信号会保留，突发片段合并为一次唤醒且不串用其他任务。 */
    @Test
    void retainsAndCoalescesSignalsAndClosesIndependently() throws Exception {
        RunStreamSignals signals = new RunStreamSignals();
        try (RunStreamSignals.Subscription first = signals.subscribe("first");
                RunStreamSignals.Subscription second = signals.subscribe("second")) {
            for (int index = 0; index < 1000; index++) {
                signals.changed("first");
            }
            assertThat(first.awaitChange(1)).isTrue();
            assertThat(first.awaitChange(1)).isFalse();
            assertThat(second.awaitChange(1)).isFalse();
            first.close();
            assertThat(first.isClosed()).isTrue();
            assertThat(first.awaitChange(1)).isTrue();
            signals.changed("first");
            assertThat(first.awaitChange(1)).isFalse();
        }
    }

    /** 空闲连接不再定时读取数据库，提交信号会主动触发下一次快照。 */
    @Test
    void readsOnlyOnCommitSignalsAndFinishesOnTerminalState() throws Exception {
        RunService runs = mock(RunService.class);
        RunStreamSignals signals = new RunStreamSignals();
        AtomicReference<String> status = new AtomicReference<>("RUNNING");
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch next = new CountDownLatch(1);
        when(runs.get("run"))
                .thenAnswer(
                        invocation ->
                                new DiagnosisRun(
                                        "run",
                                        "session",
                                        "问题",
                                        status.get(),
                                        "",
                                        "",
                                        "2026-09-07T00:00:00Z",
                                        null,
                                        0,
                                        0,
                                        0));
        when(runs.events("run", 0))
                .thenAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() == 1) {
                                initial.countDown();
                            } else {
                                next.countDown();
                            }
                            return List.of();
                        });
        new RunEventStream(runs, signals).open("run", 0);
        try {
            assertThat(initial.await(5, TimeUnit.SECONDS)).isTrue();
            // 旧实现会在此期间继续查询；现在只能由提交信号唤醒。
            assertThat(next.await(350, TimeUnit.MILLISECONDS)).isFalse();
            status.set("COMPLETED");
            signals.changed("run");
            assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reads.get()).isEqualTo(2);
        } finally {
            status.set("COMPLETED");
            signals.changed("run");
        }
    }
}
