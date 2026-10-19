package io.supportops.integration.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import io.supportops.integration.entity.ManagementRequestEntity;
import io.supportops.integration.mapper.ManagementRequestMapper;
import io.supportops.integration.mapper.IntegrationAuditEventMapper;
import io.supportops.integration.entity.IntegrationAuditEventEntity;
import io.supportops.integration.vo.ManagementReceipt;
import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 在同一事务内保存管理变更与安全回执；单实例锁保护同键首次受理。 */
@Component
public final class ManagementCommands {
    private final ManagementRequestMapper requests;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final IntegrationAuditEventMapper audit;
    /** 注入回执存储、事务及稳定 JSON 编码器。 */
    public ManagementCommands(ManagementRequestMapper requests, TransactionTemplate transactions, ObjectMapper json, IntegrationAuditEventMapper audit) {
        this.requests = requests;
        this.transactions = transactions;
        this.json = json;
        this.audit = audit;
    }
    /** 管理员身份必须来自认证链，不能通过请求对象指定。 */
    public static String administrator() {
        UserView user = CurrentUser.get();
        if (user == null || !user.isAdmin()) {
            throw IntegrationInputs.error(403, "ADMIN_REQUIRED");
        }
        return user.id();
    }
    /** 相同请求重放安全回执，同键异参拒绝；禁止在操作函数中进行网络调用。 */
    public synchronized <T> T execute(String requestId, String operation, Object input, Class<T> resultType, Supplier<T> action) {
        String actor = administrator();
        String fixedId = IntegrationInputs.uuid(requestId);
        String hash = payloadHash(input);
        return transactions.execute(status -> {
            ManagementRequestEntity existing = requests.selectOne(Wrappers.<ManagementRequestEntity>lambdaQuery()
                    .eq(ManagementRequestEntity::getActorUserId, actor).eq(ManagementRequestEntity::getRequestId, fixedId));
            if (existing != null) {
                if (!operation.equals(existing.getOperation()) || !hash.equals(existing.getPayloadHash())) {
                    throw IntegrationInputs.error(409, "REQUEST_CONFLICT");
                }
                return decode(existing.getReceiptJson(), resultType);
            }
            T result = action.get();
            ManagementRequestEntity receipt = new ManagementRequestEntity();
            receipt.setId(UUID.randomUUID().toString());
            receipt.setActorUserId(actor);
            receipt.setRequestId(fixedId);
            receipt.setOperation(operation);
            receipt.setPayloadHash(hash);
            receipt.setReceiptJson(encode(result));
            receipt.setCreatedAt(Instant.now().toString());
            requests.insert(receipt);
            IntegrationAuditEventEntity entry = new IntegrationAuditEventEntity(); entry.setActorType("USER"); entry.setActorId(actor);
            entry.setAction(operation); entry.setResourceType(operation.startsWith("RESOURCE_") ? operation.substring(9) : "MANAGEMENT");
            JsonNode safeResult = json.valueToTree(result);
            String resourceId = safeResult.path("id").asText(safeResult.path("key").path("id").asText(null));
            entry.setResourceId(resourceId); entry.setRequestId(fixedId);
            entry.setDetailJson("{}"); entry.setCreatedAt(receipt.getCreatedAt()); audit.insert(entry);
            return result;
        });
    }
    /** 在只读网络测试之前恢复同键结果，防止重放再次访问目标。 */
    public <T> Optional<T> existing(String requestId, String operation, Object input, Class<T> type) {
        String actor = administrator();
        ManagementRequestEntity value = requests.selectOne(Wrappers.<ManagementRequestEntity>lambdaQuery()
                .eq(ManagementRequestEntity::getActorUserId, actor)
                .eq(ManagementRequestEntity::getRequestId, IntegrationInputs.uuid(requestId)));
        if (value == null) { return Optional.empty(); }
        if (!operation.equals(value.getOperation()) || !payloadHash(input).equals(value.getPayloadHash())) {
            throw IntegrationInputs.error(409, "REQUEST_CONFLICT");
        }
        return Optional.of(decode(value.getReceiptJson(), type));
    }
    /** 管理输入集合采用确定顺序，进程重启不能改变固定请求摘要。 */
    private String payloadHash(Object input) { return IntegrationInputs.digest(encode(canonical(json.valueToTree(input)))); }
    /** 对象字段和集合成员排序；管理契约中的数组全部表示无序授权集合。 */
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> fields = new ArrayList<>(); value.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            for (String field : fields) { result.set(field, canonical(value.get(field))); }
            return result;
        }
        if (value.isArray()) {
            List<JsonNode> items = new ArrayList<>(); value.forEach(item -> items.add(canonical(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode result = json.createArrayNode(); items.forEach(result::add); return result;
        }
        return value;
    }
    /** 只查询本管理员的固定回执，404不能解除客户端 UNKNOWN 或改换请求键。 */
    public ManagementReceipt receipt(String requestId) {
        String actor = administrator();
        ManagementRequestEntity value = requests.selectOne(Wrappers.<ManagementRequestEntity>lambdaQuery()
                .eq(ManagementRequestEntity::getActorUserId, actor).eq(ManagementRequestEntity::getRequestId, IntegrationInputs.uuid(requestId)));
        if (value == null) { throw IntegrationInputs.error(404, "REQUEST_NOT_FOUND"); }
        return new ManagementReceipt(value.getRequestId(), value.getOperation(), decode(value.getReceiptJson(), com.fasterxml.jackson.databind.JsonNode.class), value.getCreatedAt());
    }
    /** 序列化内部确定的类型，不保存明文秘密。 */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("RECEIPT_ENCODING_FAILED", exception);
        }
    }
    /** 按操作声明的响应类型恢复历史回执。 */
    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("RECEIPT_INVALID", exception);
        }
    }
}
