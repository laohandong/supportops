package io.supportops.integration.controller;

import io.supportops.integration.dto.DataPolicyCommand;
import io.supportops.integration.service.DataPolicyService;
import io.supportops.integration.vo.DataPolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员分别批准资料对话与向量外发。 */
@RestController
@RequestMapping("/api/enterprise/data-policy")
@Tag(name = "企业资料外发策略")
public class DataPolicyController {
    private final DataPolicyService policies;
    /** 注入策略业务边界。 */
    public DataPolicyController(DataPolicyService policies) { this.policies = policies; }
    /** 新系统缺省两项禁止，版本0用于首次保存。 */
    @GetMapping @Operation(summary = "查询资料外发策略", description = "systemId为系统UUID；仅管理员；对话与向量批准独立")
    public DataPolicyView policy(@io.swagger.v3.oas.annotations.Parameter(description = "系统UUID") @RequestParam String systemId) { return policies.policy(systemId); }
    /** 不影响资料管理和关键词查询，只决定外部模型发送。 */
    @PostMapping @Operation(summary = "保存资料外发批准", description = "固定请求与expectedVersion；撤销后工具、模型及向量调用重新检查")
    public DataPolicyView save(@RequestBody DataPolicyCommand input) { return policies.save(input); }
}
