package io.supportops.feishu.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.entity.FeishuBindingEntity;
import io.supportops.feishu.entity.FeishuInboxEntity;
import io.supportops.feishu.mapper.FeishuBindingMapper;
import io.supportops.feishu.service.FeishuBindingService;
import io.supportops.feishu.vo.FeishuViews;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 使用高熵一次性码完成绑定，数据库仅保存摘要，身份以应用和企业为边界。 */
@Service
public class FeishuBindingServiceImpl implements FeishuBindingService {
    private final FeishuBindingMapper bindings;
    private final UserService users;
    private final FeishuProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 注入账号服务、绑定持久化和时钟。 */
    public FeishuBindingServiceImpl(FeishuBindingMapper bindings, UserService users,
            FeishuProperties properties, Clock clock) {
        this.bindings = bindings;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** 绑定码只允许关联已存在的工作台账号，不在飞书侧创建或升级角色。 */
    @Override
    public FeishuViews.BindingCode issue(String userId) {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FEISHU_NOT_CONFIGURED");
        }
        if (users.find(userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = HexFormat.of().formatHex(bytes);
        FeishuBindingEntity binding = new FeishuBindingEntity();
        binding.setId(UUID.randomUUID().toString());
        binding.setAppId(properties.appId());
        binding.setTenantKey(properties.tenantKey());
        binding.setUserId(userId);
        binding.setCodeHash(hash(code));
        binding.setCodeExpiresAt(clock.millis() + 600_000);
        binding.setRevoked(false);
        binding.setCreatedAt(clock.instant().toString());
        requireWrite(bindings.insert(binding));
        return new FeishuViews.BindingCode(binding.getId(), userId, "绑定 " + code, binding.getCodeExpiresAt());
    }

    /** 按创建时间倒序返回公开字段，禁止回显摘要。 */
    @Override
    public List<FeishuViews.Binding> list() {
        return bindings.selectList(Wrappers.<FeishuBindingEntity>lambdaQuery()
                .eq(FeishuBindingEntity::getAppId, properties.appId())
                .eq(FeishuBindingEntity::getTenantKey, properties.tenantKey())
                .orderByDesc(FeishuBindingEntity::getCreatedAt).orderByDesc(FeishuBindingEntity::getId)
                .last("LIMIT 100")).stream().map(binding -> new FeishuViews.Binding(
                        binding.getId(), binding.getUserId(), binding.getOpenId(), binding.getRevoked(),
                        binding.getCodeExpiresAt(), binding.getCreatedAt())).toList();
    }

    /** 撤销同时销毁绑定码并释放平台身份的唯一约束，历史收件保留原身份快照。 */
    @Override
    public void revoke(String id) {
        FeishuBindingEntity binding = bindings.selectById(id);
        if (binding == null || !Objects.equals(properties.appId(), binding.getAppId())
                || !Objects.equals(properties.tenantKey(), binding.getTenantKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FEISHU_BINDING_NOT_FOUND");
        }
        if (binding.getRevoked()) {
            return;
        }
        requireWrite(bindings.update(Wrappers.<FeishuBindingEntity>lambdaUpdate()
                .eq(FeishuBindingEntity::getId, id).eq(FeishuBindingEntity::getRevoked, false)
                .set(FeishuBindingEntity::getRevoked, true).set(FeishuBindingEntity::getOpenId, null)
                .set(FeishuBindingEntity::getCodeHash, null).set(FeishuBindingEntity::getCodeExpiresAt, null)));
    }

    /** 应用、企业和 open_id 必须共同匹配，不能仅以显示名或聊天输入识别用户。 */
    @Override
    public FeishuBindingEntity find(String openId) {
        return bindings.selectOne(Wrappers.<FeishuBindingEntity>lambdaQuery()
                .eq(FeishuBindingEntity::getAppId, properties.appId())
                .eq(FeishuBindingEntity::getTenantKey, properties.tenantKey())
                .eq(FeishuBindingEntity::getOpenId, openId).eq(FeishuBindingEntity::getRevoked, false));
    }

    /** 行锁与唯一索引保证一个绑定码只能消费一次，一个飞书身份只对应一个本地账号。 */
    @Override
    public FeishuBindingEntity consume(String code, String openId) {
        if (!code.matches("[a-f0-9]{48}") || find(openId) != null) {
            return null;
        }
        FeishuBindingEntity binding = bindings.selectOne(Wrappers.<FeishuBindingEntity>lambdaQuery()
                .eq(FeishuBindingEntity::getAppId, properties.appId())
                .eq(FeishuBindingEntity::getTenantKey, properties.tenantKey())
                .eq(FeishuBindingEntity::getCodeHash, hash(code))
                .eq(FeishuBindingEntity::getRevoked, false).last("FOR UPDATE"));
        if (binding == null || binding.getOpenId() != null || binding.getCodeExpiresAt() <= clock.millis()
                || users.find(binding.getUserId()) == null) {
            return null;
        }
        requireWrite(bindings.update(Wrappers.<FeishuBindingEntity>lambdaUpdate()
                .eq(FeishuBindingEntity::getId, binding.getId()).isNull(FeishuBindingEntity::getOpenId)
                .eq(FeishuBindingEntity::getRevoked, false)
                .set(FeishuBindingEntity::getOpenId, openId).set(FeishuBindingEntity::getCodeHash, null)
                .set(FeishuBindingEntity::getCodeExpiresAt, null)));
        binding.setOpenId(openId);
        binding.setCodeHash(null);
        binding.setCodeExpiresAt(null);
        return binding;
    }

    /** 发送前再次检查绑定；撤销、换企业或换应用均不能向旧身份发送诊断。 */
    @Override
    public UserView authorize(FeishuInboxEntity message) {
        if (message.getBindingId() == null || !Objects.equals(properties.appId(), message.getAppId())
                || !Objects.equals(properties.tenantKey(), message.getTenantKey())) {
            return null;
        }
        FeishuBindingEntity binding = bindings.selectById(message.getBindingId());
        if (binding == null || binding.getRevoked() || !Objects.equals(binding.getOpenId(), message.getOpenId())
                || !Objects.equals(binding.getUserId(), message.getUserId())
                || !Objects.equals(binding.getAppId(), message.getAppId())
                || !Objects.equals(binding.getTenantKey(), message.getTenantKey())) {
            return null;
        }
        return users.find(binding.getUserId());
    }

    /** 对高熵随机码作单向摘要，数据库与日志均不保存明文。 */
    private String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("BINDING_DIGEST_UNAVAILABLE");
        }
    }

    /** 并发状态变化必须可见，不能将未落库的绑定报告为成功。 */
    private void requireWrite(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("FEISHU_BINDING_STATE_CHANGED");
        }
    }
}
