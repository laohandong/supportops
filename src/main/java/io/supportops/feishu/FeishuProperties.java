package io.supportops.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 飞书自建应用配置；固定官方端点，禁用时不创建外部连接，密钥不进入管理接口。 */
@ConfigurationProperties(prefix = "supportops.feishu")
public record FeishuProperties(
        boolean enabled, String appId, String appSecret, String tenantKey) {
    /** 仅检查本地配置完整性，不代表已通过飞书鉴权。 */
    public boolean configured() {
        return validIdentifier(appId) && validIdentifier(tenantKey)
                && appSecret != null && !appSecret.isBlank();
    }

    /** 限定平台标识的长度和字符，不允许路径或控制字符。 */
    public static boolean validIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    /** 防止框架或调试输出通过 record 默认方法打印应用密钥。 */
    @Override
    public String toString() {
        return "FeishuProperties[enabled=" + enabled + ", configured=" + configured() + "]";
    }
}
