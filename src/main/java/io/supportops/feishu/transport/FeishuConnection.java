package io.supportops.feishu.transport;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.service.FeishuService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 应用就绪后建立出站长连接并扫描持久化消息，默认停用，不开放公网入站接口。 */
@Component
@ConditionalOnProperty(name = "supportops.feishu.enabled", havingValue = "true")
public class FeishuConnection {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuConnection.class);
    private final FeishuProperties properties;
    private final FeishuService service;
    private final FeishuEventAdapter adapter;
    private final ExecutorService connector = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("feishu-delivery").factory());
    private volatile String state = "CONNECTING";
    private volatile Client client;
    private volatile boolean closed;

    /** 注入消息适配和恢复服务，构造期间不连接外部平台。 */
    public FeishuConnection(FeishuProperties properties, FeishuService service, FeishuEventAdapter adapter) {
        this.properties = properties;
        this.service = service;
        this.adapter = adapter;
    }

    /** 应用完成 Flyway 和诊断重启恢复后才允许收到新事件。 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (closed || client != null) {
            return;
        }
        if (!properties.configured()) {
            state = "ERROR";
            LOG.warn("FEISHU_NOT_CONFIGURED");
            return;
        }
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    /** 短事务保存收件后返回，模型执行和回复由独立工作者推进。 */
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        adapter.handle(event);
                    }
                }).build();
        client = new Client.Builder(properties.appId(), properties.appSecret()).eventHandler(dispatcher)
                .onReconnecting(() -> updateState("RECONNECTING"))
                .onReconnected(() -> updateState("CONNECTED")).build();
        worker.scheduleWithFixedDelay(this::processSafely, 1, 1, TimeUnit.SECONDS);
        connector.submit(() -> {
            if (closed) {
                return;
            }
            try {
                client.start();
                client.awaitReady(20_000);
                updateState("CONNECTED");
            } catch (Exception exception) {
                if (!closed) {
                    updateState("ERROR");
                    LOG.warn("FEISHU_CONNECTION_FAILED");
                }
            } finally {
                if (closed) {
                    client.close();
                }
            }
        });
    }

    /** 关闭后的迟到握手或重连回调不能将停止状态改回已连接。 */
    private synchronized void updateState(String value) {
        if (!closed) {
            state = value;
        }
    }

    /** 数据库暂时不可用时保留记录并继续下一次扫描，不打印原始异常。 */
    private void processSafely() {
        try {
            service.process();
        } catch (Exception exception) {
            LOG.warn("FEISHU_WORKER_RETRY");
        }
    }

    /** 提供脱敏连接状态，不将凭据或平台响应放入后台接口。 */
    public String state() {
        return state;
    }

    /** 关闭长连接与扫描器，未确认发送的消息留在数据库供下次启动核查。 */
    @PreDestroy
    public synchronized void close() {
        closed = true;
        state = "STOPPED";
        if (client != null) {
            client.close();
        }
        connector.shutdownNow();
        worker.shutdownNow();
    }
}
