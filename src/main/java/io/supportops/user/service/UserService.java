package io.supportops.user.service;
import io.supportops.user.vo.UserView;
import java.util.List;
/** 账号创建、密码验证与公开资料查询的业务边界。 */
public interface UserService {
    /** 是否允许首次创建管理员。 */
    boolean setupRequired();
    /** 仅在没有账号时创建首个管理员。 */
    UserView setup(String username, String password);
    /** 验证密码，失败返回统一认证错误。 */
    UserView login(String username, String password);
    /** 创建具有明确角色的新账号。 */
    UserView create(String username, String password, String role);
    /** 返回用户公开资料，不存在时返回 null。 */
    UserView find(String id);
    /** 按登录名排序返回账号列表。 */
    List<UserView> list();
}
