package io.supportops.api.support;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** 非法重复键不能在拒绝回执路径重新解释为已有成功命令。 */
class FixedCommandJsonTest {
    private final FixedCommandJson json = new FixedCommandJson(new ObjectMapper(), mock(Validator.class));

    /** 两个请求号无法可靠归属，拒绝提取身份，不能复用最后一个请求的回执。 */
    @Test
    void duplicateRequestCannotAliasSuccessfulReceipt() {
        String body = "{\"requestId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"requestId\":\"22222222-2222-4222-8222-222222222222\",\"enabled\":true}";
        assertNull(json.rejected(body, "SOURCE_IMPORT", "source-imports"));
        assertEquals("fallback", json.field(body, "targetId", "fallback"));
    }

    /** 身份唯一的非法字段仍可保存终局拒绝，不削弱已有失败回执恢复。 */
    @Test
    void uniqueRequestRetainsRejectedCommandIdentity() {
        FixedCommandJson.Rejected rejected = json.rejected(
                "{\"requestId\":\"11111111-1111-4111-8111-111111111111\",\"unknown\":true}",
                "SOURCE_IMPORT", "source-imports");
        assertNotNull(rejected);
        assertEquals("11111111-1111-4111-8111-111111111111", rejected.requestId());
    }
}
