package io.supportops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.config.dto.UpdateModelSettingsRequest;
import io.supportops.config.vo.ModelSettingsView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

/** 验证配置持久化、并发修订检查与密钥隔离，不调用真实模型服务。 */
class ModelSettingsTest {
    @TempDir Path directory;
    MockEnvironment env;
    ProviderRegistry registry;
    Path file;

    /** 为每个测试创建独立配置文件与环境。 */
    @BeforeEach
    void setup() throws Exception {
        env = new MockEnvironment();
        for (PropertySource<?> source :
                new YamlPropertySourceLoader()
                        .load("defaults", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        file = directory.resolve("settings.json");
        registry = new ProviderRegistry(env, new LocalModelSettings(file, new ObjectMapper()));
    }

    /** 基于最新修订标记构造测试配置请求。 */
    UpdateModelSettingsRequest update(
            String provider, String model, String base, String key, String action) {
        return new UpdateModelSettingsRequest(
                provider, model, base, key, action, registry.settingsView().revision());
    }

    /** 保存仅供测试使用的合成配置和凭据。 */
    void save() {
        registry.saveSettings(
                "model",
                update(
                        "deepseek",
                        "fixture-model",
                        "https://api.deepseek.com",
                        "fixture-secret-only",
                        "replace"));
    }

    /** 验证重新加载后配置有效且响应与对象日志不包含密钥。 */
    @Test
    void persistsAcrossRestartAndNeverReturnsSecret() throws Exception {
        save();
        ProviderRegistry restarted =
                new ProviderRegistry(env, new LocalModelSettings(file, new ObjectMapper()));
        assertThat(restarted.dialogue().model()).isEqualTo("fixture-model");
        assertThat(restarted.dialogue().apiKey()).isEqualTo("fixture-secret-only");
        assertThat(new ObjectMapper().writeValueAsString(restarted.settingsView()))
                .doesNotContain("fixture-secret-only");
        assertThat(restarted.dialogue().toString()).doesNotContain("fixture-secret-only");
    }

    /** 验证只有服务商和完整地址相同才保留原密钥。 */
    @Test
    void keepOnlyPreservesSecretForSameProviderAndBaseUrl() {
        save();
        registry.saveSettings(
                "model",
                update("deepseek", "second-model", "https://api.deepseek.com/", "", "keep"));
        assertThat(registry.dialogue().apiKey()).isEqualTo("fixture-secret-only");
        registry.saveSettings(
                "model",
                update("deepseek", "second-model", "https://another.example/v1", "", "keep"));
        assertThat(registry.dialogue().apiKey()).isEmpty();
        save();
        registry.saveSettings(
                "model", update("openai", "other-model", "https://api.deepseek.com", "", "keep"));
        assertThat(registry.dialogue().apiKey()).isEmpty();
    }

    /** 验证显式移除密钥不回退环境变量，恢复文件配置才重新启用环境值。 */
    @Test
    void removingKeyDoesNotFallBackToEnvironmentAndResetRestoresEnvironment() {
        env.setProperty("DASHSCOPE_API_KEY", "fixture-environment-only");
        registry.saveSettings("model", update("bailian", "qwen-plus", "", "", "keep"));
        assertThat(registry.dialogue().apiKey()).isEqualTo("fixture-environment-only");
        registry.saveSettings("model", update("bailian", "qwen-plus", "", "", "remove"));
        assertThat(registry.dialogue().configured()).isFalse();
        registry.resetSettings("model", registry.settingsView().revision());
        assertThat(registry.dialogue().apiKey()).isEqualTo("fixture-environment-only");
    }

    /** 验证非法草稿和过期修订不会覆盖已经生效的配置。 */
    @Test
    void invalidDraftAndStaleRevisionCannotOverwriteWorkingSettings() throws Exception {
        save();
        String bytes = Files.readString(file);
        UpdateModelSettingsRequest stale =
                update("openai", "new", "", "new-fixture-key", "replace");
        assertThatThrownBy(
                        () ->
                                registry.saveSettings(
                                        "model",
                                        update(
                                                "openai",
                                                "new",
                                                "https://gateway.example/v1?key=never-log-this",
                                                "",
                                                "keep")))
                .hasMessageContaining("MODEL_INVALID_BASE_URL")
                .hasMessageNotContaining("never-log-this");
        assertThat(Files.readString(file)).isEqualTo(bytes);
        save();
        assertThatThrownBy(() -> registry.saveSettings("model", stale))
                .hasMessageContaining("MODEL_SETTINGS_CHANGED");
        assertThat(registry.dialogue().provider()).isEqualTo("deepseek");
    }

    /** 验证向量角色可独立关闭或缺少密钥而不影响对话角色。 */
    @Test
    void embeddingCanBeDisabledOrLeftUnconfiguredIndependently() {
        save();
        registry.saveSettings("embedding", update("disabled", "", "", "", "keep"));
        assertThat(registry.dialogue().configured()).isTrue();
        assertThat(registry.embedding().error()).isEqualTo("EMBEDDING_DISABLED");
        registry.saveSettings(
                "embedding", update("openai", "text-embedding-3-small", "", "", "keep"));
        assertThat(registry.embedding().error()).isEqualTo("EMBEDDING_NOT_CONFIGURED");
        assertThat(registry.dialogue().configured()).isTrue();
    }

    /** 验证磁盘写入失败时原内存配置继续生效。 */
    @Test
    void failedDiskWriteLeavesActiveConfigurationUnchanged() throws Exception {
        Path parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "fixture");
        registry =
                new ProviderRegistry(
                        env,
                        new LocalModelSettings(
                                parent.resolve("settings.json"), new ObjectMapper()));
        ModelSettingsView before = registry.settingsView();
        assertThatThrownBy(this::save).hasMessageContaining("MODEL_SETTINGS_SAVE_FAILED");
        assertThat(registry.settingsView()).isEqualTo(before);
    }

    /** 推理等级覆盖文件参数、重启保留，空字符串显式省略，旧文件继续读取环境值。 */
    @Test
    void reasoningEffortPersistsAndCanBeOmitted() throws Exception {
        env.setProperty("supportops.model.reasoning-effort", "medium");
        save();
        registry.saveSettings(
                "model",
                new UpdateModelSettingsRequest(
                        "deepseek",
                        "fixture-model",
                        "https://api.deepseek.com",
                        "",
                        "keep",
                        registry.settingsView().revision(),
                        "high"));
        ProviderRegistry restarted =
                new ProviderRegistry(env, new LocalModelSettings(file, new ObjectMapper()));
        assertThat(restarted.dialogue().generationDescriptor().reasoningEffort()).isEqualTo("high");
        assertThat(restarted.settingsView().model().reasoningEffort()).isEqualTo("high");
        registry.saveSettings(
                "model",
                new UpdateModelSettingsRequest(
                        "deepseek",
                        "fixture-model",
                        "https://api.deepseek.com",
                        "",
                        "keep",
                        registry.settingsView().revision(),
                        ""));
        assertThat(registry.dialogue().generationDescriptor().reasoningEffort()).isEmpty();
        com.fasterxml.jackson.databind.node.ObjectNode old =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        new ObjectMapper().readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) old.path("roles").path("model"))
                .remove("reasoning-effort");
        Files.writeString(file, old.toString());
        assertThat(
                        new ProviderRegistry(env, new LocalModelSettings(file, new ObjectMapper()))
                                .dialogue()
                                .generationDescriptor()
                                .reasoningEffort())
                .isEqualTo("medium");
    }

    /** 目录请求复用同端点凭据但不修改配置，换地址或过期页面被拒绝。 */
    @Test
    void discoveryUsesDraftWithoutSavingOrLeakingCredentials() throws Exception {
        save();
        String before = Files.readString(file);
        assertThat(
                        registry.discoveryEndpoint(
                                        "model",
                                        update(
                                                "deepseek",
                                                "",
                                                "https://api.deepseek.com",
                                                "",
                                                "keep"))
                                .apiKey())
                .isEqualTo("fixture-secret-only");
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                registry.discoveryEndpoint(
                                        "model",
                                        update(
                                                "deepseek",
                                                "",
                                                "https://another.example/v1",
                                                "",
                                                "keep")))
                .hasMessageContaining("MODEL_NOT_CONFIGURED");
        UpdateModelSettingsRequest stale =
                update("deepseek", "", "https://api.deepseek.com", "", "keep");
        save();
        assertThatThrownBy(() -> registry.discoveryEndpoint("model", stale))
                .hasMessageContaining("MODEL_SETTINGS_CHANGED");
    }

    /** 验证损坏配置的错误消息不泄露文件内容。 */
    @Test
    void corruptSettingsFailWithSafeMessage() throws Exception {
        Files.writeString(file, "invalid-fixture-secret");
        assertThatThrownBy(() -> new LocalModelSettings(file, new ObjectMapper()))
                .hasMessage("MODEL_SETTINGS_READ_FAILED");
    }
}
