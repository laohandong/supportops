package io.supportops.feishu.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.lark.oapi.ws.Client;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.service.FeishuService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/** 确定性验证关闭与迟到握手竞争，不连接外部平台。 */
class FeishuConnectionTest {
    /** 握手尚未返回时关闭，迟到结果不得重新标记在线或保留连接。 */
    @Test
    void shutdownClosesLateHandshakeAndKeepsStoppedState() throws Exception {
        Client client = mock(Client.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch closedTwice = new CountDownLatch(2);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(client).start();
        doAnswer(invocation -> {
            closedTwice.countDown();
            return null;
        }).when(client).close();
        try (MockedConstruction<Client.Builder> construction = mockConstruction(Client.Builder.class, (builder, context) -> {
            when(builder.eventHandler(any())).thenReturn(builder);
            when(builder.onReconnecting(any())).thenReturn(builder);
            when(builder.onReconnected(any())).thenReturn(builder);
            when(builder.build()).thenReturn(client);
        })) {
            FeishuConnection connection = new FeishuConnection(
                    new FeishuProperties(true, "cli_fixture", "synthetic-secret", "tenant_fixture"),
                    mock(FeishuService.class), mock(FeishuEventAdapter.class));
            try {
                connection.start();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                connection.close();
                released.countDown();
                assertThat(closedTwice.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(connection.state()).isEqualTo("STOPPED");
                assertThat(construction.constructed()).hasSize(1);
            } finally {
                released.countDown();
                connection.close();
            }
        }
    }
}
