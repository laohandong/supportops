package io.supportops.agent.service.support;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 只传递提交后的唤醒信号；事件正文和重连游标始终以数据库为准。 */
@Component
public final class RunStreamSignals {
    private final Set<Subscription> subscriptions = new HashSet<>();

    /** 先订阅再读取快照，避免读取与开始等待之间遗漏更新。 */
    public synchronized Subscription subscribe(String id) {
        Subscription subscription = new Subscription(id);
        subscriptions.add(subscription);
        return subscription;
    }

    /** 非阻塞合并唤醒，慢浏览器不会阻塞模型回调或形成无限队列。 */
    public synchronized void changed(String id) {
        for (Subscription subscription : subscriptions) {
            if (subscription.id.equals(id)) {
                subscription.pending.offer(Boolean.TRUE);
            }
        }
    }

    /** 单条连接的有限信号队列，关闭时立即解除等待并移除登记。 */
    public final class Subscription implements AutoCloseable {
        private final String id;
        private final ArrayBlockingQueue<Boolean> pending = new ArrayBlockingQueue<>(1);
        private volatile boolean closed;

        /** 绑定任务编号，队列只保存是否需要重新读取。 */
        private Subscription(String id) {
            this.id = id;
        }

        /** 等待提交或关闭信号，超时仅用于发送连接心跳。 */
        public boolean awaitChange(long timeoutMillis) throws InterruptedException {
            return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS) != null;
        }

        /** 查询连接是否已完成、断开或超时。 */
        public boolean isClosed() {
            return closed;
        }

        /** 幂等清理订阅；不取消诊断任务。 */
        @Override
        public void close() {
            synchronized (RunStreamSignals.this) {
                closed = true;
                subscriptions.remove(this);
                pending.offer(Boolean.TRUE);
            }
        }
    }
}
