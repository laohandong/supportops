package io.supportops.user.controller;

import io.supportops.user.CurrentUser;
import io.supportops.user.dto.Credentials;
import io.supportops.user.dto.LoginRequest;
import io.supportops.user.dto.CreateUserRequest;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.SetupStatus;
import io.supportops.user.vo.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 账号登录与管理员创建账号的 HTTP 入口。 */
@RestController
@RequestMapping("/api")
@ApiResponse(responseCode = "200", description = "操作成功，返回公开账号资料或初始化状态；退出登录为空响应。")
@Tag(name = "用户与登录")
public class UserController {
    public static final String SESSION_USER = "supportops.userId";
    private final UserService users;
    /** 注入用户业务服务。 */
    public UserController(UserService users) {
        this.users = users;
    }
    /** 查询是否需要首次创建管理员，不返回账号列表。 */
    @GetMapping("/auth/setup")
    @Operation(summary = "查询首次初始化状态", description = "匿名可读；setupRequired 为 true 时可在本机创建首个管理员。")
    public SetupStatus setupStatus() {
        return new SetupStatus(users.setupRequired());
    }
    /** 首次初始化成功后轮换会话，避免会话固定。 */
    @PostMapping("/auth/setup")
    @Operation(summary = "创建首个管理员", description = "仅限尚无账号时；成功建立登录会话；已初始化返回 409 SETUP_COMPLETED。")
    public UserView setup(@Valid @RequestBody Credentials input, HttpServletRequest request) {
        return signedIn(users.setup(input.username(), input.password()), request);
    }
    /** 验证账号密码并创建服务端会话。 */
    @PostMapping("/auth/login")
    @Operation(summary = "登录工作台", description = "验证密码并设置 HttpOnly 会话 Cookie；认证失败返回 401 INVALID_CREDENTIALS；请求过多返回 429 LOGIN_RATE_LIMITED。")
    public UserView login(@Valid @RequestBody LoginRequest input, HttpServletRequest request) {
        return signedIn(users.login(input.username(), input.password()), request);
    }
    /** 返回认证过滤器确认的当前身份。 */
    @GetMapping("/auth/me")
    @Operation(summary = "查询当前账号", description = "未登录或会话失效返回 401 AUTHENTICATION_REQUIRED。")
    public UserView me() {
        return CurrentUser.get();
    }
    /** 销毁当前会话，保留已有诊断任务与历史记录。 */
    @PostMapping("/auth/logout")
    @Operation(summary = "退出登录", description = "销毁当前服务端会话；不会取消已受理诊断。")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
    /** 管理员读取账号公开信息。 */
    @GetMapping("/users")
    @Operation(summary = "查询用户列表", description = "仅管理员可用，普通用户返回 403 ADMIN_REQUIRED；按登录名排序，不返回密码摘要。")
    public List<UserView> list() {
        return users.list();
    }
    /** 管理员创建指定角色的账号。 */
    @PostMapping("/users")
    @Operation(summary = "创建用户", description = "仅管理员可用；同名返回 409 USERNAME_EXISTS，非法角色或密码返回 400；不改变管理员当前登录会话。")
    public UserView create(@Valid @RequestBody CreateUserRequest input) {
        return users.create(input.username(), input.password(), input.role());
    }
    /** 登录时销毁旧会话，服务端仅保存用户编号。 */
    private UserView signedIn(UserView user, HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(8 * 60 * 60);
        session.setAttribute(SESSION_USER, user.id());
        return user;
    }
}
