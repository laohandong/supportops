package io.supportops.config;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.GenerateOptions.Builder;
import io.supportops.config.dto.UpdateModelSettingsRequest;
import io.supportops.config.vo.GenerationOptions;
import io.supportops.config.vo.ModelRoute;
import io.supportops.config.vo.ModelSettingsView;
import io.supportops.config.vo.ProviderPreset;
import io.supportops.config.vo.RoleSettings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 仅解析所选服务商的路由、凭据和参数，不要求未使用平台的密钥。 */
@Component
public class ProviderRegistry {
    /** 从配置绑定的服务商预设，描述协议、模型、凭据来源与参数兼容性。 */
    public record Provider(
            String api,
            String baseUrl,
            String apiKeyEnv,
            String chatModel,
            String embeddingModel,
            Boolean embeddings,
            String maxTokensField,
            String temperature,
            String thinking) {}

    /** 单次调用使用的不可变端点快照；密钥仅用于发起请求，描述信息不包含密钥。 */
    public static final class Endpoint {
        private final String provider, api, baseUrl, model, credentialEnv, apiKey, error;
        private final String maxTokensField, temperature, thinking, reasoningEffort;
        private final int maxOutputTokens;

        /** 保存已经解析的路由、凭据和生成参数，供单次调用固定使用。 */
        private Endpoint(
                String provider,
                String api,
                String baseUrl,
                String model,
                String credentialEnv,
                String apiKey,
                String error,
                String maxTokensField,
                String temperature,
                String thinking,
                String reasoningEffort,
                int maxOutputTokens) {
            this.provider = provider;
            this.api = api;
            this.baseUrl = baseUrl;
            this.model = model;
            this.credentialEnv = credentialEnv;
            this.apiKey = apiKey;
            this.error = error;
            this.maxTokensField = maxTokensField;
            this.temperature = temperature;
            this.thinking = thinking;
            this.reasoningEffort = reasoningEffort;
            this.maxOutputTokens = maxOutputTokens;
        }

        /** 检查本地配置是否满足执行要求，不发起远程模型请求。 */
        public boolean configured() {
            return error.isEmpty();
        }

        /** 返回服务商标识。 */
        public String provider() {
            return provider;
        }

        /** 返回所选接口的基础地址，调用前仍需通过配置完整性检查。 */
        public String baseUrl() {
            return baseUrl;
        }

        /** 返回当前选择的模型名称。 */
        public String model() {
            return model;
        }

        /** 读取仅供请求鉴权使用的密钥，不得写入日志或接口响应。 */
        public String apiKey() {
            return apiKey;
        }

        /** 返回本地配置错误码，配置完整时为空字符串。 */
        public String error() {
            return error;
        }

        /** 返回凭据来源的名称或引用标记，不返回凭据值。 */
        public String credentialEnv() {
            return credentialEnv;
        }

        /** 配置不完整时中止调用，避免向错误端点发送请求。 */
        public void requireConfigured() {
            if (!configured()) {
                throw new IllegalStateException(error);
            }
        }

        /** 生成不含密钥的路由视图，非法地址不会进入响应。 */
        public ModelRoute descriptor() {
            String visibleBaseUrl =
                    error.isEmpty() || error.endsWith("NOT_CONFIGURED") ? baseUrl : null;
            return new ModelRoute(
                    provider, api, model, credentialEnv, configured(), error, visibleBaseUrl);
        }

        /** 返回实际采用的兼容参数，用于界面展示和任务证据快照。 */
        public GenerationOptions generationDescriptor() {
            return new GenerationOptions(
                    maxTokensField, maxOutputTokens, temperature, thinking, reasoningEffort);
        }

        /** 按当前服务商协议选择输出 Token 字段与可选生成参数。 */
        public GenerateOptions generationOptions() {
            requireConfigured();
            Builder options = GenerateOptions.builder();
            if ("max_completion_tokens".equals(maxTokensField)) {
                options.maxCompletionTokens(maxOutputTokens);
            } else {
                options.maxTokens(maxOutputTokens);
            }
            if (!temperature.isEmpty() && !temperature.equals("omit")) {
                options.temperature(Double.valueOf(temperature));
            }
            if (!thinking.isEmpty()) {
                options.additionalBodyParam("thinking", Map.of("type", thinking));
            }
            if (!reasoningEffort.isEmpty()) {
                options.reasoningEffort(reasoningEffort);
            }
            return options.build();
        }

        /** 只输出安全路由描述，防止对象日志包含密钥。 */
        @Override
        public String toString() {
            return descriptor().toString();
        }
    }

    private final Environment env;
    private final Map<String, Provider> providers;
    private final LocalModelSettings settings;

    /** 绑定服务商目录、环境变量来源与本地配置覆盖存储。 */
    public ProviderRegistry(Environment env) {
        this(env, new LocalModelSettings((Path) null, new ObjectMapper()));
    }

    /** 绑定服务商目录、环境变量来源与本地配置覆盖存储。 */
    @Autowired
    public ProviderRegistry(Environment env, LocalModelSettings settings) {
        this.env = env;
        this.settings = settings;
        this.providers =
                Binder.get(env)
                        .bind("supportops.providers", Bindable.mapOf(String.class, Provider.class))
                        .orElse(Map.of());
    }

    /** 解析当前对话角色，不检查向量服务凭据。 */
    public Endpoint dialogue() {
        return resolve(false);
    }

    /** 独立解析向量角色，未配置不会阻止无向量功能。 */
    public Endpoint embedding() {
        return resolve(true);
    }

    /** 读取并规范化一个配置值，缺失时返回空字符串。 */
    private String value(String path) {
        return env.getProperty(path, "").trim();
    }

    /** 按优先级返回第一个非空白字符串，均为空时返回空字符串。 */
    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 根据角色覆盖与服务商预设解析端点，并返回可安全展示的配置错误。 */
    private Endpoint resolve(boolean embedding) {
        return resolve(embedding, settings.role(embedding ? "embedding" : "model"));
    }

    /** 读取角色覆盖值，未覆盖的字段才回退到进程配置。 */
    private String roleValue(String prefix, String key, Map<String, String> saved) {
        return saved != null && saved.containsKey(key) ? saved.get(key) : value(prefix + key);
    }

    /** 根据角色覆盖与服务商预设解析端点，并返回可安全展示的配置错误。 */
    private Endpoint resolve(boolean embedding, Map<String, String> saved) {
        String role = embedding ? "embedding" : "model";
        String prefix = "supportops." + role + ".";
        String code = embedding ? "EMBEDDING_" : "MODEL_";
        String selected = first(roleValue(prefix, "provider", saved), "bailian");
        Provider provider = providers.get(selected);
        if (provider == null || selected.equals("disabled")) {
            return failed(
                    selected,
                    code + (selected.equals("disabled") ? "DISABLED" : "UNKNOWN_PROVIDER"));
        }
        String api = first(provider.api(), "openai-completions");
        String baseOverride = roleValue(prefix, "base-url", saved);
        String base = first(baseOverride, provider.baseUrl()).replaceAll("/+$", "");
        String model =
                first(
                        roleValue(prefix, "name", saved),
                        embedding ? provider.embeddingModel() : provider.chatModel());
        String keyOverride = roleValue(prefix, "api-key", saved);
        String keyReference = saved == null ? value(prefix + "api-key-env") : "LOCAL_MODEL_API_KEY";
        String credential = first(keyReference, provider.apiKeyEnv());
        // 界面显式保存的空密钥也属于覆盖值，不能回退读取环境变量中的旧密钥。
        String key = resolveCredential(saved, keyOverride, credential);
        if (!keyOverride.isEmpty() && saved == null) {
            credential = embedding ? "SUPPORTOPS_EMBEDDING_API_KEY" : "SUPPORTOPS_API_KEY";
        }
        String field =
                embedding
                        ? ""
                        : first(
                                value(prefix + "max-tokens-field"),
                                provider.maxTokensField(),
                                "max_tokens");
        String temperature =
                embedding ? "" : first(value(prefix + "temperature"), provider.temperature());
        String thinking = embedding ? "" : first(value(prefix + "thinking"), provider.thinking());
        String effort = embedding ? "" : roleValue(prefix, "reasoning-effort", saved);
        int maxTokens = 2500;
        String error = "";
        if (!api.equals("openai-completions")) {
            error = code + "UNSUPPORTED_PROTOCOL";
        } else if (embedding && Boolean.FALSE.equals(provider.embeddings())) {
            error = code + "UNSUPPORTED_PROVIDER";
        } else if (model.isEmpty()) {
            error = code + "NAME_REQUIRED";
        } else if (!validBaseUrl(base)) {
            error = code + "INVALID_BASE_URL";
        } else if (!credential.matches("[A-Z][A-Z0-9_]*_API_KEY")) {
            error = code + "INVALID_CREDENTIAL_REFERENCE";
        }
        // 地址覆盖不能把内置平台的密钥自动转发到其他来源，必须有明确凭据绑定。
        else if (keyOverride.isEmpty()
                && keyReference.isEmpty()
                && !baseOverride.isEmpty()
                && validBaseUrl(provider.baseUrl())
                && !sameOrigin(base, provider.baseUrl())) {
            error = code + "CREDENTIAL_BINDING_REQUIRED";
        }
        if (!embedding && error.isEmpty()) {
            try {
                maxTokens = Integer.parseInt(first(value(prefix + "max-output-tokens"), "2500"));
                if (maxTokens < 1
                        || maxTokens > 32768
                        || !Set.of("max_tokens", "max_completion_tokens").contains(field)) {
                    throw new IllegalArgumentException();
                }
                if (!temperature.isEmpty() && !temperature.equals("omit")) {
                    double number = Double.parseDouble(temperature);
                    if (!Double.isFinite(number) || number < 0 || number > 2) {
                        throw new IllegalArgumentException();
                    }
                }
                if (!thinking.isEmpty() && !Set.of("enabled", "disabled").contains(thinking)) {
                    throw new IllegalArgumentException();
                }
                if (!effort.isEmpty() && !effort.matches("[a-z_]{1,20}")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                error = code + "INVALID_OPTIONS";
            }
        }
        if (error.isEmpty() && key.isEmpty()) {
            error = code + "NOT_CONFIGURED";
        }
        return new Endpoint(
                selected,
                api,
                base,
                model,
                credential,
                key,
                error,
                field,
                temperature,
                thinking,
                effort,
                maxTokens);
    }

    /** 按界面覆盖、直接密钥、凭据变量的顺序读取，只允许受约束的密钥变量名。 */
    private String resolveCredential(
            Map<String, String> saved, String keyOverride, String credential) {
        if (saved != null || !keyOverride.isEmpty()) {
            return keyOverride;
        }
        if (!credential.isEmpty() && credential.matches("[A-Z][A-Z0-9_]*_API_KEY")) {
            return value(credential);
        }
        return "";
    }

    /** 组合服务商预设、两个角色与修订标记，不探测远程服务。 */
    public synchronized ModelSettingsView settingsView() {
        Map<String, ProviderPreset> catalog = new LinkedHashMap<>();
        providers.forEach(
                (id, preset) ->
                        catalog.put(
                                id,
                                new ProviderPreset(
                                        validBaseUrl(preset.baseUrl())
                                                ? first(preset.baseUrl())
                                                : "",
                                        first(preset.chatModel()),
                                        first(preset.embeddingModel()),
                                        !Boolean.FALSE.equals(preset.embeddings()))));
        return new ModelSettingsView(
                settings.revision(),
                Map.copyOf(catalog),
                roleSettings("model"),
                roleSettings("embedding"));
    }

    /** 组合单个角色的安全路由与配置来源，不复制密钥原文。 */
    private RoleSettings roleSettings(String role) {
        Endpoint endpoint = resolve(role.equals("embedding"));
        ModelRoute route = endpoint.descriptor();
        return new RoleSettings(
                route.provider(),
                route.api(),
                route.model(),
                route.credentialEnv(),
                route.configured(),
                route.error(),
                route.baseUrl(),
                settings.role(role) != null,
                !endpoint.apiKey().isEmpty(),
                endpoint.generationDescriptor().reasoningEffort());
    }

    /** 校验草稿和密钥动作，保存成功后立即发布新路由；变更端点不沿用旧密钥。 */
    public synchronized ModelSettingsView saveSettings(
            String role, UpdateModelSettingsRequest update) {
        Map<String, String> candidate = settingsCandidate(role, update);
        settings.save(role, candidate, update.revision());
        return settingsView();
    }

    /** 解析模型目录请求草稿，不保存；空模型名不阻止获取目录。 */
    public synchronized Endpoint discoveryEndpoint(String role, UpdateModelSettingsRequest update) {
        if (update == null || !Objects.equals(settings.revision(), update.revision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MODEL_SETTINGS_CHANGED");
        }
        UpdateModelSettingsRequest probe =
                new UpdateModelSettingsRequest(
                        update.provider(),
                        "catalog-probe",
                        update.baseUrl(),
                        update.apiKey(),
                        update.keyAction(),
                        update.revision(),
                        "");
        Endpoint endpoint = resolve(role.equals("embedding"), settingsCandidate(role, probe));
        if (!endpoint.configured()) {
            throw new ResponseStatusException(BAD_REQUEST, endpoint.error());
        }
        return endpoint;
    }

    /** 保存与目录探测共享地址校验和凭据隔离规则。 */
    private Map<String, String> settingsCandidate(String role, UpdateModelSettingsRequest update) {
        checkRole(role);
        if (update == null
                || update.provider() == null
                || !providers.containsKey(update.provider())
                        && !(role.equals("embedding") && update.provider().equals("disabled"))) {
            throw new ResponseStatusException(BAD_REQUEST, "MODEL_SETTINGS_INVALID_PROVIDER");
        }
        if (!Set.of("keep", "replace", "remove").contains(first(update.keyAction()))) {
            throw new ResponseStatusException(BAD_REQUEST, "MODEL_SETTINGS_INVALID_KEY_ACTION");
        }
        String model = first(update.model());
        String base = first(update.baseUrl()).replaceAll("/+$", "");
        String key = first(update.apiKey());
        if (model.length() > 200
                || base.length() > 1000
                || key.length() > 4096
                || key.chars().anyMatch(c -> c < 33 || c > 126)
                || (update.keyAction().equals("replace") && key.isEmpty())) {
            throw new ResponseStatusException(BAD_REQUEST, "MODEL_SETTINGS_INVALID_INPUT");
        }
        boolean embedding = role.equals("embedding");
        Endpoint current = resolve(embedding);
        Provider preset = providers.get(update.provider());
        base = first(base, preset == null ? "" : preset.baseUrl()).replaceAll("/+$", "");
        String presetModel = "";
        if (preset != null) {
            presetModel = embedding ? preset.embeddingModel() : preset.chatModel();
        }
        model = first(model, presetModel);
        if (update.keyAction().equals("keep")) {
            // 保留密钥要求服务商与完整基础地址都相同，任一变化都清除旧凭据。
            key =
                    current.provider().equals(update.provider()) && current.baseUrl().equals(base)
                            ? current.apiKey()
                            : "";
        } else if (update.keyAction().equals("remove")) {
            key = "";
        }
        if (update.provider().equals("disabled")) {
            model = "";
            base = "";
            key = "";
        }
        String effort = "";
        if (!embedding) {
            effort = update.reasoningEffort() == null
                    ? current.generationDescriptor().reasoningEffort()
                    : update.reasoningEffort().trim();
        }
        if (!Set.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                .contains(effort)) {
            throw new ResponseStatusException(BAD_REQUEST, "MODEL_INVALID_OPTIONS");
        }
        Map<String, String> candidate =
                Map.of(
                        "provider",
                        update.provider(),
                        "name",
                        model,
                        "base-url",
                        base,
                        "api-key",
                        key,
                        "reasoning-effort",
                        effort);
        Endpoint endpoint = resolve(embedding, candidate);
        if (!endpoint.configured()
                && !endpoint.error().endsWith("NOT_CONFIGURED")
                && !endpoint.error().equals("EMBEDDING_DISABLED")) {
            throw new ResponseStatusException(BAD_REQUEST, endpoint.error());
        }
        return candidate;
    }

    /** 删除指定角色的界面覆盖，恢复当前进程已有的文件与环境变量配置。 */
    public synchronized ModelSettingsView resetSettings(String role, String revision) {
        checkRole(role);
        settings.save(role, null, revision);
        return settingsView();
    }

    /** 只允许修改对话或向量角色，拒绝未知角色标识。 */
    private static void checkRole(String role) {
        if (!Set.of("model", "embedding").contains(role)) {
            throw new ResponseStatusException(BAD_REQUEST, "MODEL_SETTINGS_INVALID_ROLE");
        }
    }

    /** 构造不包含地址与密钥的失败端点，供界面安全报告配置问题。 */
    private static Endpoint failed(String provider, String error) {
        return new Endpoint(provider, "", "", "", "", "", error, "", "", "", "", 0);
    }

    /** 校验协议、地址及路径，拒绝远程明文传输、内嵌凭据和带查询参数的地址。 */
    private static boolean validBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                    && ("https".equals(uri.getScheme())
                            || ("http".equals(uri.getScheme())
                                    && Set.of("localhost", "127.0.0.1", "[::1]")
                                            .contains(uri.getHost())))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !uri.getPath().endsWith("/chat/completions")
                    && !uri.getPath().endsWith("/embeddings");
        } catch (Exception e) {
            return false;
        }
    }

    /** 比较两个基础地址的协议、主机与端口，用于检查凭据绑定边界。 */
    private static boolean sameOrigin(String left, String right) {
        URI a = URI.create(left);
        URI b = URI.create(right);
        return Objects.equals(a.getScheme(), b.getScheme())
                && Objects.equals(a.getHost(), b.getHost())
                && a.getPort() == b.getPort();
    }
}
