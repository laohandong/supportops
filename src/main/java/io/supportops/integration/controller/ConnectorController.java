package io.supportops.integration.controller;

import io.supportops.integration.dto.ConnectorAction;
import io.supportops.integration.dto.ConnectorCommand;
import io.supportops.integration.service.ConnectorService;
import io.supportops.integration.vo.ConnectorView;
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

/** 管理员维护、测试和启用标准只读连接。 */
@RestController
@RequestMapping("/api/enterprise/connectors")
@Tag(name = "企业只读连接")
@ApiResponse(responseCode = "403", description = "仅管理员可用")
@ApiResponse(responseCode = "409", description = "请求或版本冲突、测试过期、环境仍占用")
public class ConnectorController {
    private final ConnectorService connectors;
    /** 注入连接业务边界。 */
    public ConnectorController(ConnectorService connectors) { this.connectors = connectors; }
    /** 查看所有不可变修订。 */
    @GetMapping
    @Operation(summary = "查询连接修订", description = "environmentId 为环境 UUID；不返回秘密；状态和最近测试独立显示")
    public List<ConnectorView> list(@io.swagger.v3.oas.annotations.Parameter(description = "环境UUID") @RequestParam String environmentId) { return connectors.list(environmentId); }
    /** 保存新草稿。 */
    @PostMapping
    @Operation(summary = "创建连接草稿", description = "必需 APP_INFO，HTTPS 默认；每次追加修订并加密独立 Bearer，固定请求可恢复")
    public ConnectorView create(@RequestBody ConnectorCommand input) { return connectors.create(input); }
    /** 只读测试既定能力。 */
    @PostMapping("/test")
    @Operation(summary = "测试连接", description = "只访问批准地址与固定接口，日志最多5条；返回状态和安全错误代码，不返回事实正文")
    public ConnectorView test(@RequestBody ConnectorAction input) { return connectors.test(input); }
    /** 通过环境旧版本启用成功测试过的修订。 */
    @PostMapping("/enable")
    @Operation(summary = "启用连接修订", description = "要求最近30分钟相同身份测试通过；expectedVersion 为环境旧版本；环境执行资源仍占用时拒绝")
    public ConnectorView enable(@RequestBody ConnectorAction input) { return connectors.activate(input); }
}
