package io.supportops.user.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
/** 用户账号持久化映射，角色仅支持管理员与普通用户。 */
@TableName("app_users")
public class UserEntity {
    /** 用户 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 唯一登录名，统一使用小写 ASCII。 */
    private String username;
    /** 随机盐 PBKDF2 摘要，不作为响应返回。 */
    private String passwordHash;
    /** ADMIN 管理员，USER 普通用户。 */
    private String role;
    /** 账号创建时间，ISO 8601。 */
    private String createdAt;
    /** 返回用户 UUID。 */
    public String getId() {
        return id;
    }
    /** 设置用户 UUID。 */
    public void setId(String id) {
        this.id = id;
    }
    /** 返回唯一登录名，统一使用小写 ASCII。 */
    public String getUsername() {
        return username;
    }
    /** 设置唯一登录名，统一使用小写 ASCII。 */
    public void setUsername(String username) {
        this.username = username;
    }
    /** 返回随机盐 PBKDF2 摘要，不作为响应返回。 */
    public String getPasswordHash() {
        return passwordHash;
    }
    /** 设置随机盐 PBKDF2 摘要，不作为响应返回。 */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
    /** 返回ADMIN 管理员，USER 普通用户。 */
    public String getRole() {
        return role;
    }
    /** 设置ADMIN 管理员，USER 普通用户。 */
    public void setRole(String role) {
        this.role = role;
    }
    /** 返回账号创建时间，ISO 8601。 */
    public String getCreatedAt() {
        return createdAt;
    }
    /** 设置账号创建时间，ISO 8601。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
