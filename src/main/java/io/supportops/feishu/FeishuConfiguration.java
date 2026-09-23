package io.supportops.feishu;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

/** 注册飞书配置，账号管理和历史查询在渠道停用时仍可访问。 */
@Configuration
@EnableConfigurationProperties(FeishuProperties.class)
public class FeishuConfiguration {
    /** 使用 UTC 时钟计算绑定码有效期和发送恢复窗口。 */
    @Bean
    Clock feishuClock() {
        return Clock.systemUTC();
    }
}
