package io.supportops.integration.controller;

import io.supportops.integration.dto.AccessKeyCommand;
import io.supportops.integration.dto.ApplicationCommand;
import io.supportops.integration.dto.RevokeKeyCommand;
import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.vo.AccessKeyIssuance;
import io.supportops.integration.vo.AccessKeyView;
import io.supportops.integration.vo.ApplicationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员配置调用应用和一次性访问密钥。 */
@RestController
@RequestMapping("/api/enterprise/applications")
@Tag(name = "企业调用应用")
@ApiResponse(responseCode = "403", description = "仅管理员可用")
@ApiResponse(responseCode = "409", description = "版本、固定请求或密钥数量冲突")
public class ApplicationController {
    private final ApplicationService applications;
    /** 注入应用业务边界。 */
    public ApplicationController(ApplicationService applications) { this.applications = applications; }
    /** 查询安全应用配置。 */
    @GetMapping @Operation(summary = "查询应用", description = "显示项目、状态及授权，不返回密钥摘要")
    public List<ApplicationView> list() { return applications.list(); }
    /** 创建或条件更新应用。 */
    @PostMapping @Operation(summary = "保存应用", description = "只接受 DIAGNOSE、READ、CANCEL；环境须属于同项目；更新提供 expectedVersion")
    public ApplicationView save(@RequestBody ApplicationCommand input) { return applications.save(input); }
    /** 只读密钥状态。 */
    @GetMapping("/keys") @Operation(summary = "查询访问密钥状态", description = "applicationId 为应用 UUID；原文及摘要均不可读取")
    public List<AccessKeyView> keys(@io.swagger.v3.oas.annotations.Parameter(description = "调用应用UUID") @RequestParam String applicationId) { return applications.keys(applicationId); }
    /** 签发或轮换，一次性返回秘密。 */
    @PostMapping("/keys") @Operation(summary = "签发访问密钥", description = "默认30天、1至90天；最多2把活动密钥，轮换重叠最多24小时；同键重放只有状态")
    public AccessKeyIssuance issue(@RequestBody AccessKeyCommand input) { return applications.issue(input); }
    /** 紧急撤销并保留历史状态。 */
    @PostMapping("/keys/revoke") @Operation(summary = "撤销访问密钥", description = "固定请求 UUID；下一次认证、工具和模型外发重新检查")
    public AccessKeyView revoke(@RequestBody RevokeKeyCommand input) { return applications.revoke(input); }
}
