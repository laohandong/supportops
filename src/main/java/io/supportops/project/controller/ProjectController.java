package io.supportops.project.controller;

import io.supportops.project.dto.GrantCommand;
import io.supportops.project.dto.ResourceCommand;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.GrantView;
import io.supportops.project.vo.ResourceView;
import io.supportops.project.vo.TargetView;
import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 企业资源管理及普通用户目标选择入口。 */
@RestController
@RequestMapping("/api")
@Tag(name = "企业项目与环境")
@ApiResponse(responseCode = "409", description = "固定请求异参、资源版本冲突或资源已归档")
@ApiResponse(responseCode = "403", description = "管理操作仅管理员可用")
public class ProjectController {
    private final ProjectService projects;
    /** 注入项目业务服务。 */
    public ProjectController(ProjectService projects) { this.projects = projects; }
    /** 管理员读取资源树。 */
    @GetMapping("/enterprise/resources")
    @Operation(summary = "查询企业资源", description = "仅管理员；包含停用、归档状态，不返回连接地址或凭据")
    public List<ResourceView> resources() { return projects.resources(); }
    /** 当前用户读取完整目标列表。 */
    @GetMapping("/diagnosis-targets")
    @Operation(summary = "查询可用诊断环境", description = "当前用户同时具备项目和环境授权；只展示可用且有启用连接的目标")
    public List<TargetView> targets() {
        UserView user = CurrentUser.get();
        return projects.targets(user.id(), user.isAdmin());
    }
    /** 创建或更新项目。 */
    @PostMapping("/enterprise/projects")
    @Operation(summary = "保存项目", description = "固定请求 UUID；更新须提交旧版本，归档不可恢复；项目编码创建后固定")
    public ResourceView project(@RequestBody ResourceCommand input) { return projects.save("PROJECT", input); }
    /** 创建或更新系统。 */
    @PostMapping("/enterprise/systems")
    @Operation(summary = "保存系统", description = "创建时指定项目、系统编码及产品标识；更新须提交旧版本；归属和编码固定")
    public ResourceView system(@RequestBody ResourceCommand input) { return projects.save("SYSTEM", input); }
    /** 创建或更新环境。 */
    @PostMapping("/enterprise/environments")
    @Operation(summary = "保存环境", description = "创建时指定系统及预期版本；版本最多30字符；更新须提交旧版本")
    public ResourceView environment(@RequestBody ResourceCommand input) { return projects.save("ENVIRONMENT", input); }
    /** 设置用户项目成员资格。 */
    @PostMapping("/enterprise/project-members")
    @Operation(summary = "设置项目成员", description = "granted=false 撤销；立即使该项目下所有环境权限失效")
    public GrantView member(@RequestBody GrantCommand input) { return projects.grant("PROJECT", input); }
    /** 设置用户环境授权。 */
    @PostMapping("/enterprise/environment-grants")
    @Operation(summary = "设置用户环境授权", description = "授权前须具备项目成员资格；撤销后新请求与后续工具调用均重新检查")
    public GrantView grant(@RequestBody GrantCommand input) { return projects.grant("ENVIRONMENT", input); }
}
