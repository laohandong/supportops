package io.supportops.user.service.impl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.user.PasswordHash;
import io.supportops.user.entity.UserEntity;
import io.supportops.user.mapper.UserMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
/** 管理固定两类账号，初始化使用短事务锁，密码派生在事务外完成。 */
@Service
public class UserServiceImpl implements UserService {
    private final UserMapper users;
    private final TransactionTemplate transactions;
    private final String dummyHash = PasswordHash.encode(UUID.randomUUID().toString());
    /** 注入账号持久化与事务边界。 */
    public UserServiceImpl(UserMapper users, TransactionTemplate transactions) {
        this.users = users;
        this.transactions = transactions;
    }
    /** 未创建任何账号时开放本机首次初始化。 */
    @Override
    public boolean setupRequired() {
        return users.selectCount(null) == 0;
    }
    /** 数据库锁防止并发初始化生成多个首个管理员。 */
    @Override
    public UserView setup(String username, String password) {
        UserEntity user = prepare(username, password, "ADMIN");
        return transactions.execute(status -> {
            users.lockSetup();
            if (!setupRequired()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "SETUP_COMPLETED");
            }
            users.insert(user);
            return view(user);
        });
    }
    /** 始终执行密码派生，未知账号不返回不同认证错误。 */
    @Override
    public UserView login(String username, String password) {
        String normalized = normalize(username);
        if (password == null || password.isBlank() || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        UserEntity user = users.selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, normalized));
        boolean valid = PasswordHash.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !valid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        return view(user);
    }
    /** 唯一索引防止同名账号，密码和角色在写入前校验。 */
    @Override
    public UserView create(String username, String password, String role) {
        UserEntity user = prepare(username, password, role);
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "USERNAME_EXISTS");
        }
        return view(user);
    }
    /** 每个请求重新读取账号角色，不信任客户端角色。 */
    @Override
    public UserView find(String id) {
        UserEntity user = users.selectById(id);
        return user == null ? null : view(user);
    }
    /** 不将密码实体序列化到账号列表中。 */
    @Override
    public List<UserView> list() {
        return users.selectList(Wrappers.<UserEntity>lambdaQuery().orderByAsc(UserEntity::getUsername))
                .stream().map(this::view).toList();
    }
    /** 校验并生成账号，时间和 UUID 均由服务端生成。 */
    private UserEntity prepare(String username, String password, String role) {
        String normalized = normalize(username);
        validatePassword(password);
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(normalized);
        user.setPasswordHash(PasswordHash.encode(password));
        user.setRole(role);
        user.setCreatedAt(Instant.now().toString());
        return user;
    }
    /** 先限定原始 ASCII 字符，再统一大小写，避免 Unicode 折叠旁路。 */
    private String normalize(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME");
        }
        return username.toLowerCase(Locale.ROOT);
    }
    /** 限制密码长度，同时约束哈希计算输入。 */
    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD");
        }
    }
    /** 映射公开账号字段。 */
    private UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt());
    }
}
