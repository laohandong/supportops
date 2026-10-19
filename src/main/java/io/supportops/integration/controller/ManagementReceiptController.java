package io.supportops.integration.controller;

import io.supportops.integration.service.support.ManagementCommands;
import io.supportops.integration.vo.ManagementReceipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理命令响应不确定时，读取原请求事实。 */
@RestController
@RequestMapping("/api/enterprise/requests")
@Tag(name = "企业管理回执")
public class ManagementReceiptController {
    private final ManagementCommands commands;
    /** 注入回执业务边界。 */
    public ManagementReceiptController(ManagementCommands commands) { this.commands = commands; }
    /** 密钥签发回执只返回 keyId，无法恢复原文。 */
    @GetMapping("/{requestId}") @Operation(summary = "查询企业管理回执", description = "仅本管理员；404保持原请求，签发秘密不回放")
    public ManagementReceipt receipt(@io.swagger.v3.oas.annotations.Parameter(description = "固定请求UUID") @PathVariable String requestId) { return commands.receipt(requestId); }
}
