package io.supportops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.model.GenerateOptions;
import io.supportops.config.ProviderRegistry;
import io.supportops.config.ProviderRegistry.Endpoint;
import io.supportops.knowledge.service.EmbeddingClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 验证独立角色的服务商解析、凭据来源与协议参数选择。 */
class ProviderRegistryTest {
    /** 使用内置预设和指定覆盖构造隔离的路由测试环境。 */
    private ProviderRegistry registry(Map<String, Object> overrides) throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> source :
                new YamlPropertySourceLoader()
                        .load("defaults", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        env.getPropertySources().addFirst(new MapPropertySource("case", overrides));
        return new ProviderRegistry(env);
    }

    /** 验证无凭据启动和仅配置对话角色的行为。 */
    @Test
    void defaultsStartWithoutCredentialsAndNeverRequireAnUnusedEmbeddingKey() throws Exception {
        ProviderRegistry providers = registry(Map.of());
        assertThat(providers.dialogue().error()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(providers.embedding().configured()).isFalse();
        providers =
                registry(
                        Map.of(
                                "supportops.model.provider",
                                "deepseek",
                                "DEEPSEEK_API_KEY",
                                "test-deepseek"));
        assertThat(providers.dialogue().configured()).isTrue();
        assertThat(providers.embedding().configured()).isFalse();
    }

    /** 验证切换平台不复用其他平台的密钥。 */
    @Test
    void providerSwitchDoesNotReuseAnotherPlatformsCredential() throws Exception {
        ProviderRegistry missing =
                registry(
                        Map.of(
                                "supportops.model.provider",
                                "deepseek",
                                "DASHSCOPE_API_KEY",
                                "test-bailian"));
        assertThat(missing.dialogue().error()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(missing.dialogue().credentialEnv()).isEqualTo("DEEPSEEK_API_KEY");
        ProviderRegistry providers =
                registry(
                        Map.of(
                                "supportops.model.provider",
                                "deepseek",
                                "DEEPSEEK_API_KEY",
                                "test-deepseek",
                                "supportops.embedding.provider",
                                "openai",
                                "OPENAI_API_KEY",
                                "test-openai"));
        assertThat(providers.dialogue().apiKey()).isEqualTo("test-deepseek");
        assertThat(providers.dialogue().baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(providers.embedding().apiKey()).isEqualTo("test-openai");
        assertThat(providers.embedding().model()).isEqualTo("text-embedding-3-small");
    }

    /** 验证兼容旧配置覆盖，同时确保安全描述不包含密钥。 */
    @Test
    void legacyRoleOverridesRemainIndependentAndSecretsDoNotEnterDescriptors() throws Exception {
        ProviderRegistry providers =
                registry(
                        Map.of(
                                "SUPPORTOPS_API_KEY",
                                "test-role-secret",
                                "SUPPORTOPS_MODEL",
                                "custom-chat",
                                "SUPPORTOPS_MODEL_BASE_URL",
                                "https://gateway.example/v1",
                                "SUPPORTOPS_EMBEDDING_API_KEY",
                                "test-vector-secret"));
        assertThat(providers.dialogue().configured()).isTrue();
        assertThat(providers.dialogue().model()).isEqualTo("custom-chat");
        assertThat(providers.embedding().apiKey()).isEqualTo("test-vector-secret");
        String descriptors =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "model",
                                        providers.dialogue().descriptor(),
                                        "embedding",
                                        providers.embedding().descriptor()));
        assertThat(descriptors + providers.dialogue())
                .doesNotContain("test-role-secret", "test-vector-secret");
    }

    /** 验证自定义目录的凭据引用与不支持协议拒绝行为。 */
    @Test
    void customCatalogUsesExplicitCredentialReferenceAndRejectsUnknownProtocols() throws Exception {
        HashMap<String, Object> config = new HashMap<String, Object>();
        config.put("supportops.model.provider", "gateway");
        config.put("supportops.providers.gateway.api", "openai-completions");
        config.put("supportops.providers.gateway.base-url", "http://127.0.0.1:12345/v1");
        config.put("supportops.providers.gateway.api-key-env", "TEAM_API_KEY");
        config.put("supportops.providers.gateway.chat-model", "team-model");
        config.put("TEAM_API_KEY", "test-team");
        assertThat(registry(config).dialogue().configured()).isTrue();
        config.put("supportops.providers.gateway.api", "anthropic-messages");
        assertThat(registry(config).dialogue().error()).isEqualTo("MODEL_UNSUPPORTED_PROTOCOL");
        config.put("supportops.model.provider", "typo");
        assertThat(registry(config).dialogue().error()).isEqualTo("MODEL_UNKNOWN_PROVIDER");
    }

    /** 验证关闭或不支持的向量路由不会阻止对话配置。 */
    @Test
    void disabledOrUnsupportedEmbeddingRouteDoesNotBlockDialogue() throws Exception {
        for (String provider : List.of("disabled", "deepseek", "missing")) {
            ProviderRegistry registry =
                    registry(
                            Map.of(
                                    "SUPPORTOPS_API_KEY",
                                    "test-only",
                                    "supportops.embedding.provider",
                                    provider));
            assertThat(registry.dialogue().configured()).isTrue();
            assertThat(registry.embedding().configured()).isFalse();
            assertThatThrownBy(() -> registry.embedding().requireConfigured())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 验证新端点需要明确凭据绑定，非法地址不会进入描述信息。 */
    @Test
    void endpointOverridesRequireExplicitCredentialBindingAndRejectUnsafeUrls() throws Exception {
        HashMap<String, Object> config =
                new HashMap<String, Object>(
                        Map.of(
                                "supportops.model.provider",
                                "openai",
                                "OPENAI_API_KEY",
                                "test-openai",
                                "supportops.model.base-url",
                                "https://gateway.example/v1"));
        assertThat(registry(config).dialogue().error())
                .isEqualTo("MODEL_CREDENTIAL_BINDING_REQUIRED");
        config.put("supportops.model.api-key-env", "OPENAI_API_KEY");
        assertThat(registry(config).dialogue().configured()).isTrue();
        for (String url :
                List.of(
                        "http://remote.example/v1",
                        "https://user:secret@example.com/v1",
                        "https://example.com/v1?api_key=secret")) {
            config.put("supportops.model.base-url", url);
            Endpoint endpoint = registry(config).dialogue();
            assertThat(endpoint.error()).isEqualTo("MODEL_INVALID_BASE_URL");
            assertThat(
                            new ObjectMapper()
                                    .readTree(
                                            new ObjectMapper()
                                                    .writeValueAsString(endpoint.descriptor()))
                                    .has("baseUrl"))
                    .isFalse();
        }
    }

    /** 验证输出 Token 字段和生成参数按服务商选择并支持明确覆盖。 */
    @Test
    void compatibilityParametersAreSelectedByRouteAndCanBeOverridden() throws Exception {
        GenerateOptions openai =
                registry(
                                Map.of(
                                        "supportops.model.provider",
                                        "openai",
                                        "OPENAI_API_KEY",
                                        "test-only"))
                        .dialogue()
                        .generationOptions();
        assertThat(openai.getMaxCompletionTokens()).isEqualTo(2500);
        assertThat(openai.getMaxTokens()).isNull();
        assertThat(openai.getTemperature()).isNull();
        GenerateOptions deepseek =
                registry(
                                Map.of(
                                        "supportops.model.provider",
                                        "deepseek",
                                        "DEEPSEEK_API_KEY",
                                        "test-only"))
                        .dialogue()
                        .generationOptions();
        assertThat(deepseek.getMaxTokens()).isEqualTo(2500);
        assertThat(deepseek.getMaxCompletionTokens()).isNull();
        assertThat(deepseek.getAdditionalBodyParams())
                .containsEntry("thinking", Map.of("type", "disabled"));
        GenerateOptions override =
                registry(
                                Map.of(
                                        "SUPPORTOPS_API_KEY",
                                        "test-only",
                                        "supportops.model.max-output-tokens",
                                        "1024",
                                        "supportops.model.max-tokens-field",
                                        "max_completion_tokens",
                                        "supportops.model.temperature",
                                        "omit"))
                        .dialogue()
                        .generationOptions();
        assertThat(override.getMaxCompletionTokens()).isEqualTo(1024);
        assertThat(override.getTemperature()).isNull();
        assertThat(registry(Map.of("supportops.model.max-output-tokens", "-1")).dialogue().error())
                .isEqualTo("MODEL_INVALID_OPTIONS");
    }

    /** 验证索引指纹随向量路由变化且不包含凭据。 */
    @Test
    void changingEmbeddingRouteChangesIndexIdentityWithoutIncludingCredentials() throws Exception {
        EmbeddingClient before =
                new EmbeddingClient(
                        registry(Map.of("DASHSCOPE_API_KEY", "test-only")), new ObjectMapper());
        EmbeddingClient after =
                new EmbeddingClient(
                        registry(
                                Map.of(
                                        "supportops.embedding.provider",
                                        "openai",
                                        "OPENAI_API_KEY",
                                        "test-other")),
                        new ObjectMapper());
        assertThat(before.fingerprint())
                .isNotEqualTo(after.fingerprint())
                .doesNotContain("test-only");
        assertThat(after.fingerprint()).doesNotContain("test-other");
    }

    /** 验证外部配置扩展服务商目录时保留内置预设。 */
    @Test
    void externalYamlAddsProvidersWithoutDiscardingBuiltInCatalog() throws Exception {
        MockEnvironment env = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source :
                loader.load("defaults", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        for (PropertySource<?> source :
                loader.load("local", new FileSystemResource("providers.example.yml"))) {
            env.getPropertySources().addFirst(source);
        }
        env.setProperty("DEEPSEEK_API_KEY", "test-deepseek");
        ProviderRegistry providers = new ProviderRegistry(env);
        assertThat(providers.dialogue().configured()).isTrue();
        assertThat(providers.dialogue().provider()).isEqualTo("deepseek");
        assertThat(providers.embedding().error()).isEqualTo("EMBEDDING_DISABLED");
        env.getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "selection",
                                Map.of(
                                        "SUPPORTOPS_MODEL_PROVIDER",
                                        "company-gateway",
                                        "COMPANY_API_KEY",
                                        "test-company")));
        assertThat(providers.dialogue().configured()).isTrue();
        assertThat(providers.dialogue().model()).isEqualTo("your-chat-model");
    }
}
