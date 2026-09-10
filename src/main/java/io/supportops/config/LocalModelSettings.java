package io.supportops.config;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

/** 以原子文件替换保存本地模型配置，磁盘写入成功后才发布内存快照。 */
@Component
public class LocalModelSettings {
    private final Path file;
    private final ObjectMapper json;
    private volatile State state = new State(UUID.randomUUID().toString(), Map.of());

    /** 配置修订标记与角色覆盖值组成的不可变快照。 */
    private record State(String revision, Map<String, Map<String, String>> roles) {}

    /** 加载并校验本机配置文件；文件不存在时使用空覆盖值。 */
    @Autowired
    public LocalModelSettings(Environment env, ObjectMapper json) {
        this(
                Path.of(env.getProperty("supportops.settings.file", ".local/model-settings.json")),
                json);
    }

    /** 加载并校验本机配置文件；文件不存在时使用空覆盖值。 */
    LocalModelSettings(Path file, ObjectMapper json) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.json = json;
        if (file != null && Files.exists(file)) {
            try {
                JsonNode tree = json.readTree(Files.readString(file));
                if (tree.path("format").asInt() != 1 || !tree.path("roles").isObject()) {
                    throw new IllegalArgumentException();
                }
                LinkedHashMap<String, Map<String, String>> roles =
                        new LinkedHashMap<String, Map<String, String>>();
                Iterator<Entry<String, JsonNode>> fields = tree.path("roles").fields();
                while (fields.hasNext()) {
                    Entry<String, JsonNode> entry = fields.next();
                    if (!Set.of("model", "embedding").contains(entry.getKey())
                            || !entry.getValue().isObject()) {
                        throw new IllegalArgumentException();
                    }
                    LinkedHashMap<String, String> role = new LinkedHashMap<String, String>();
                    for (String key : List.of("provider", "name", "base-url", "api-key")) {
                        if (!entry.getValue().path(key).isTextual()) {
                            throw new IllegalArgumentException();
                        }
                        role.put(key, entry.getValue().get(key).asText());
                    }
                    if (entry.getValue().has("reasoning-effort")) {
                        if (!entry.getValue().get("reasoning-effort").isTextual()) {
                            throw new IllegalArgumentException();
                        }
                        role.put(
                                "reasoning-effort",
                                entry.getValue().get("reasoning-effort").asText());
                    }
                    roles.put(entry.getKey(), Map.copyOf(role));
                }
                state = new State(UUID.randomUUID().toString(), Map.copyOf(roles));
            } catch (Exception e) {
                throw new IllegalStateException("MODEL_SETTINGS_READ_FAILED");
            }
        }
    }

    /** 读取指定角色的不可变覆盖值，不存在时返回 null。 */
    Map<String, String> role(String role) {
        return state.roles().get(role);
    }

    /** 返回当前配置版本标记，用于拒绝过期页面的覆盖写入。 */
    String revision() {
        return state.revision();
    }

    /** 校验修订标记并原子保存角色配置；失败时保留原磁盘数据与内存快照。 */
    synchronized void save(String role, Map<String, String> value, String revision) {
        if (!state.revision().equals(revision)) {
            throw new ResponseStatusException(CONFLICT, "MODEL_SETTINGS_CHANGED");
        }
        LinkedHashMap<String, Map<String, String>> roles = new LinkedHashMap<>(state.roles());
        if (value == null) {
            roles.remove(role);
        } else {
            roles.put(role, Map.copyOf(value));
        }
        State next = new State(UUID.randomUUID().toString(), Map.copyOf(roles));
        // 在同一目录写临时文件后原子替换，失败时不提前发布新修订或角色覆盖值。
        if (file != null) {
            Path temporary = null;
            try {
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), ".model-settings-", ".tmp");
                if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(
                            temporary, PosixFilePermissions.fromString("rw-------"));
                }
                Files.writeString(
                        temporary,
                        json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(Map.of("format", 1, "roles", roles)));
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new ResponseStatusException(
                        INTERNAL_SERVER_ERROR, "MODEL_SETTINGS_SAVE_FAILED");
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // 磁盘提交成功后才更新不可变内存快照；后续请求由此立即获得新配置。
        state = next;
    }
}
