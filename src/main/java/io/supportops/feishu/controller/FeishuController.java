package io.supportops.feishu.controller;

import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.dto.CreateFeishuBinding;
import io.supportops.feishu.service.FeishuBindingService;
import io.supportops.feishu.service.FeishuService;
import io.supportops.feishu.transport.FeishuConnection;
import io.supportops.feishu.vo.FeishuViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 飞书渠道后台管理；继承现有 Cookie、同源和管理员权限过滤，不提供公共回调。 */
@RestController
@RequestMapping("/api/feishu")
@Tag(name = "飞书机器人", description = "管理员配置检查、一次性账号绑定和投递状态查询")
@ApiResponse(responseCode = "200", description = "操作成功；查询不返回应用密钥或绑定码摘要。")
public class FeishuController {
    private final FeishuProperties properties;
    private final FeishuBindingService bindings;
    private final FeishuService service;
    private final ObjectProvider<FeishuConnection> connection;

    /** 注入业务服务与可选的长连接状态。 */
    public FeishuController(FeishuProperties properties, FeishuBindingService bindings,
            FeishuService service, ObjectProvider<FeishuConnection> connection) {
        this.properties = properties;
        this.bindings = bindings;
        this.service = service;
        this.connection = connection;
    }

    /** 只返回公开标识及连接状态，密钥不序列化到配置接口。 */
    @GetMapping("/status")
    @Operation(summary = "查询飞书接入状态", description = "仅管理员；configured 仅表示本地配置完整，CONNECTED 表示长连接已建立。")
    public FeishuViews.Status status() {
        FeishuConnection active = connection.getIfAvailable();
        return new FeishuViews.Status(properties.enabled(), properties.configured(), properties.appId(),
                properties.tenantKey(), active == null ? "DISABLED" : active.state());
    }

    /** 创建绑定码，明文仅在本次响应出现。 */
    @PostMapping("/bindings")
    @Operation(summary = "签发飞书绑定码", description = "仅管理员；已有账号使用十分钟有效的一次性码在机器人私聊完成绑定。用户不存在返回 404 USER_NOT_FOUND；配置缺失返回 409 FEISHU_NOT_CONFIGURED。")
    public FeishuViews.BindingCode issue(@Valid @RequestBody CreateFeishuBinding request) {
        return bindings.issue(request.userId());
    }

    /** 查询绑定资料，不返回明文码或摘要。 */
    @GetMapping("/bindings")
    @Operation(summary = "查询飞书账号绑定", description = "仅管理员；当前应用和企业最近一百条，按创建时间倒序。")
    public List<FeishuViews.Binding> bindings() {
        return bindings.list();
    }

    /** 逻辑撤销绑定，保留历史消息和诊断。 */
    @PostMapping("/bindings/{id}/delete")
    @Operation(summary = "撤销飞书账号绑定", description = "仅管理员；重复撤销幂等。阻止后续受理和未开始的结果发送，不删除诊断或撤回已经发送的消息。不存在返回 404 FEISHU_BINDING_NOT_FOUND。")
    public void revoke(@Parameter(description = "绑定记录 UUID", required = true) @PathVariable String id) {
        bindings.revoke(id);
    }

    /** 同时展示收件与投递状态，避免把诊断成功误认为回复已送达。 */
    @GetMapping("/messages")
    @Operation(summary = "查询飞书消息处理记录", description = "仅管理员；当前应用和企业最近一百条，按接收时间倒序；不返回问题或回答正文。UNKNOWN 表示需要人工核查平台送达情况。")
    public List<FeishuViews.Message> messages() {
        return service.messages();
    }
}
