package io.supportops.user;
import io.supportops.user.vo.UserView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
/** 从服务端认证请求读取身份；后台处理无请求时不冒充任何用户。 */
public final class CurrentUser {
    public static final String ATTRIBUTE = CurrentUser.class.getName();
    /** 工具类不允许实例化。 */
    private CurrentUser() {}
    /** 返回认证过滤器写入的身份；内部任务或离线迁移为 null。 */
    public static UserView get() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return (UserView) request.getAttribute(ATTRIBUTE);
        }
        return null;
    }
    /** 返回提交人的编号，内部导入和历史迁移保留 null。 */
    public static String id() {
        UserView user = get();
        return user == null ? null : user.id();
    }
}
