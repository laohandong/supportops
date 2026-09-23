package io.supportops.feishu.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.agent.constant.RunLimits;
import io.supportops.agent.service.RunService;
import io.supportops.agent.service.support.DiagnosisTimeFormatter;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.entity.FeishuBindingEntity;
import io.supportops.feishu.entity.FeishuInboxEntity;
import io.supportops.feishu.entity.FeishuOutboxEntity;
import io.supportops.feishu.mapper.FeishuInboxMapper;
import io.supportops.feishu.mapper.FeishuOutboxMapper;
import io.supportops.feishu.service.FeishuBindingService;
import io.supportops.feishu.service.FeishuSender;
import io.supportops.feishu.service.FeishuService;
import io.supportops.feishu.vo.FeishuViews;
import io.supportops.user.vo.UserView;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 私聊消息先落库，独立扫描推进诊断及投递；跨进程重复事件不重复创建模型任务。 */
@Service
public class FeishuServiceImpl implements FeishuService {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuServiceImpl.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 5;
    // 飞书 uuid 去重保证一小时；本地只在首次尝试后五十分钟内重试，留出网络余量。
    private static final long RETRY_WINDOW_MS = 50 * 60_000L;
    private static final Set<String> ADMISSION_ERRORS = Set.of(
            "MODEL_NOT_CONFIGURED", "INVALID_QUESTION", "DIAGNOSIS_IN_PROGRESS", "EXTERNAL_REQUEST_CONFLICT");
    private final FeishuInboxMapper inbox;
    private final FeishuOutboxMapper outbox;
    private final FeishuBindingService bindings;
    private final RunService runs;
    private final FeishuSender sender;
    private final TransactionTemplate transactions;
    private final FeishuProperties properties;
    private final Clock clock;

    /** 注入渠道持久化和现有诊断服务，平台 SDK 只通过发送接口参与。 */
    public FeishuServiceImpl(FeishuInboxMapper inbox, FeishuOutboxMapper outbox,
            FeishuBindingService bindings, RunService runs, FeishuSender sender,
            TransactionTemplate transactions, FeishuProperties properties, Clock clock) {
        this.inbox = inbox;
        this.outbox = outbox;
        this.bindings = bindings;
        this.runs = runs;
        this.sender = sender;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** 只接收指定企业的用户文本私聊；忽略群聊、机器人、自身消息和过期事件。 */
    @Override
    public void receive(FeishuIncomingMessage message) {
        if (!allowed(message)) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                if (existing(message)) {
                    return;
                }
                storeIncoming(message);
            });
        } catch (DuplicateKeyException exception) {
            // 只有已确认同一平台消息落库才确认重复；身份唯一约束冲突必须交由平台重投。
            if (!existing(message)) {
                throw new IllegalStateException("FEISHU_RECEIVE_RETRY");
            }
        }
    }

    /** 在模型执行前固定消息编号和归属，绑定命令不写入问题正文。 */
    private void storeIncoming(FeishuIncomingMessage message) {
        FeishuInboxEntity record = new FeishuInboxEntity();
        record.setId(UUID.randomUUID().toString());
        record.setAppId(message.appId());
        record.setTenantKey(message.tenantKey());
        record.setMessageId(message.messageId());
        record.setOpenId(message.openId());
        record.setQuestion("");
        record.setState("FINISHED");
        record.setErrorCode("");
        record.setCreatedAt(clock.instant().toString());
        String text = message.text().strip();
        String notice = null;
        FeishuBindingEntity binding;
        if (text.equals("绑定") || text.startsWith("绑定 ")) {
            binding = bindings.consume(text.substring(2).strip(), message.openId());
            notice = binding == null ? "绑定未完成：绑定码无效、已过期或当前飞书账号已绑定。请联系管理员。"
                    : "绑定成功。请直接发送问题描述，每条消息会创建一个独立诊断。";
        } else {
            binding = bindings.find(message.openId());
            if (binding == null) {
                notice = "请先联系工作台管理员获取一次性绑定命令，并在此私聊发送。绑定后才能开始诊断。";
            } else if (text.isBlank() || text.length() > RunLimits.QUESTION_LENGTH) {
                notice = "请发送 1 至 6000 个字符的问题描述。";
            } else {
                record.setQuestion(text);
                record.setState("RECEIVED");
            }
        }
        if (binding != null) {
            record.setBindingId(binding.getId());
            record.setUserId(binding.getUserId());
        }
        requireWrite(inbox.insert(record));
        if (notice != null) {
            enqueue(record, "NOTICE", notice);
        }
    }

    /** 标识来自 SDK 事件，仍严格匹配配置；防止错误企业或群聊进入诊断。 */
    private boolean allowed(FeishuIncomingMessage message) {
        return properties.enabled() && properties.configured() && message != null
                && properties.appId().equals(message.appId()) && properties.tenantKey().equals(message.tenantKey())
                && FeishuProperties.validIdentifier(message.messageId())
                && FeishuProperties.validIdentifier(message.openId())
                && "user".equals(message.senderType()) && "p2p".equals(message.chatType())
                && "text".equals(message.messageType()) && message.text() != null
                && message.createdAtMillis() >= clock.millis() - 30 * 60_000L
                && message.createdAtMillis() <= clock.millis() + 5 * 60_000L;
    }

    /** 数据库唯一键和查询均使用应用、企业、消息编号的完整范围。 */
    private boolean existing(FeishuIncomingMessage message) {
        return inbox.selectCount(Wrappers.<FeishuInboxEntity>lambdaQuery()
                .eq(FeishuInboxEntity::getAppId, message.appId())
                .eq(FeishuInboxEntity::getTenantKey, message.tenantKey())
                .eq(FeishuInboxEntity::getMessageId, message.messageId())) > 0;
    }

    /** 单实例扫描串行执行，每条故障独立保留恢复状态，不中断其他消息。 */
    @Override
    public synchronized void process() {
        if (!properties.enabled() || !properties.configured()) {
            return;
        }
        List<FeishuInboxEntity> pending = inbox.selectList(Wrappers.<FeishuInboxEntity>lambdaQuery()
                .in(FeishuInboxEntity::getState, "RECEIVED", "WAITING")
                .orderByAsc(FeishuInboxEntity::getCreatedAt).orderByAsc(FeishuInboxEntity::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (FeishuInboxEntity message : pending) {
            try {
                advance(message);
            } catch (Exception exception) {
                // 不记录平台响应、问题正文或凭据；未完成状态留待下一轮恢复。
                LOG.warn("FEISHU_INBOX_RETRY");
            }
        }
        List<FeishuOutboxEntity> deliveries = outbox.selectList(Wrappers.<FeishuOutboxEntity>lambdaQuery()
                .eq(FeishuOutboxEntity::getState, "PENDING")
                .le(FeishuOutboxEntity::getNextAttemptAt, clock.millis())
                .orderByAsc(FeishuOutboxEntity::getCreatedAt).orderByAsc(FeishuOutboxEntity::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (FeishuOutboxEntity delivery : deliveries) {
            try {
                deliver(delivery);
            } catch (Exception exception) {
                LOG.warn("FEISHU_OUTBOX_RETRY");
            }
        }
    }

    /** 受理和关联更新之间可重启；相同请求编号读取原任务，绝不创建第二次诊断。 */
    private void advance(FeishuInboxEntity message) {
        UserView owner = bindings.authorize(message);
        if (owner == null) {
            changeInbox(message, "BLOCKED", "FEISHU_BINDING_REVOKED");
            return;
        }
        if ("RECEIVED".equals(message.getState())) {
            try {
                runs.startExternal(message.getId(), message.getQuestion(), owner);
                transactions.executeWithoutResult(status -> {
                    enqueue(message, "ACCEPTED", "已受理诊断，任务编号：" + message.getId()
                            + "。完成后会在此私聊返回结果。每条新消息会创建独立诊断。");
                    changeInbox(message, "WAITING", "");
                });
            } catch (ResponseStatusException exception) {
                if (!ADMISSION_ERRORS.contains(exception.getReason())) {
                    throw exception;
                }
                finishMessage(message, admissionNotice(exception.getReason()), exception.getReason());
            }
            return;
        }
        DiagnosisRun run = runs.getOwned(message.getId(), owner.id());
        if (!Set.of("QUEUED", "RUNNING").contains(run.status())) {
            finishMessage(message, resultText(run), "");
        }
    }

    /** 回复和收件终态在同一短事务提交，崩溃后不会丢失结果投递。 */
    private void finishMessage(FeishuInboxEntity message, String text, String errorCode) {
        transactions.executeWithoutResult(status -> {
            enqueue(message, "RESULT", text);
            changeInbox(message, "FINISHED", errorCode);
        });
    }

    /** 冻结回复正文和去重编号，唯一约束阻止相同阶段重复创建。 */
    private void enqueue(FeishuInboxEntity message, String kind, String text) {
        FeishuOutboxEntity delivery = new FeishuOutboxEntity();
        delivery.setId(UUID.randomUUID().toString());
        delivery.setInboxId(message.getId());
        delivery.setKind(kind);
        delivery.setContent(text);
        delivery.setState("PENDING");
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(clock.millis());
        delivery.setErrorCode("");
        delivery.setCreatedAt(clock.instant().toString());
        requireWrite(outbox.insert(delivery));
    }

    /** 比较旧状态后更新，防止重复工作者覆盖已终止的收件记录。 */
    private void changeInbox(FeishuInboxEntity message, String state, String error) {
        requireWrite(inbox.update(Wrappers.<FeishuInboxEntity>lambdaUpdate()
                .eq(FeishuInboxEntity::getId, message.getId()).eq(FeishuInboxEntity::getState, message.getState())
                .set(FeishuInboxEntity::getState, state).set(FeishuInboxEntity::getErrorCode, error)));
    }

    /** 先落库发送尝试再联网；超出平台去重窗口后停止，避免不确定结果被盲目重发。 */
    private void deliver(FeishuOutboxEntity delivery) throws Exception {
        FeishuInboxEntity message = inbox.selectById(delivery.getInboxId());
        if (!deliveryAllowed(message, delivery)) {
            updateDelivery(delivery, "BLOCKED", "FEISHU_BINDING_REVOKED", null, clock.millis());
            return;
        }
        if (delivery.getFirstAttemptAt() != null
                && clock.millis() - delivery.getFirstAttemptAt() >= RETRY_WINDOW_MS) {
            updateDelivery(delivery, "UNKNOWN", "FEISHU_DELIVERY_UNCONFIRMED", null, clock.millis());
            return;
        }
        if (delivery.getAttempts() >= MAX_ATTEMPTS) {
            updateDelivery(delivery, "FAILED", "FEISHU_DELIVERY_FAILED", null, clock.millis());
            return;
        }
        // 受理提示必须先进入终结状态，避免恢复时在结果之后发送迟到的“处理中”。
        if ("RESULT".equals(delivery.getKind()) && outbox.selectCount(Wrappers.<FeishuOutboxEntity>lambdaQuery()
                .eq(FeishuOutboxEntity::getInboxId, message.getId()).eq(FeishuOutboxEntity::getKind, "ACCEPTED")
                .eq(FeishuOutboxEntity::getState, "PENDING")) > 0) {
            return;
        }
        long firstAttempt = delivery.getFirstAttemptAt() == null ? clock.millis() : delivery.getFirstAttemptAt();
        int attempts = delivery.getAttempts() + 1;
        int claimed = outbox.update(Wrappers.<FeishuOutboxEntity>lambdaUpdate()
                .eq(FeishuOutboxEntity::getId, delivery.getId()).eq(FeishuOutboxEntity::getState, "PENDING")
                .eq(FeishuOutboxEntity::getAttempts, delivery.getAttempts())
                .set(FeishuOutboxEntity::getAttempts, attempts)
                .set(FeishuOutboxEntity::getFirstAttemptAt, firstAttempt)
                .set(FeishuOutboxEntity::getNextAttemptAt, clock.millis() + 60_000));
        if (claimed != 1) {
            return;
        }
        delivery.setAttempts(attempts);
        try {
            String platformId = sender.send(message.getOpenId(), delivery.getContent(), delivery.getId());
            if (!FeishuProperties.validIdentifier(platformId)) {
                throw new IllegalStateException("FEISHU_INVALID_SEND_RESPONSE");
            }
            updateDelivery(delivery, "SENT", "", platformId, clock.millis());
        } catch (Exception exception) {
            updateDelivery(delivery, attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING",
                    "FEISHU_DELIVERY_FAILED", null, clock.millis() + Math.min(300_000, 5_000L << attempts));
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 未绑定提示只允许发送固定说明；诊断和绑定成功提示必须再次核验绑定。 */
    private boolean deliveryAllowed(FeishuInboxEntity message, FeishuOutboxEntity delivery) {
        if (message == null || !Objects.equals(properties.appId(), message.getAppId())
                || !Objects.equals(properties.tenantKey(), message.getTenantKey())) {
            return false;
        }
        if (message.getBindingId() == null) {
            return "NOTICE".equals(delivery.getKind()) && message.getUserId() == null;
        }
        return bindings.authorize(message) != null;
    }

    /** 保存发送确认或恢复时间，不将错误响应正文持久化。 */
    private void updateDelivery(FeishuOutboxEntity delivery, String state, String error,
            String platformId, long nextAttempt) {
        requireWrite(outbox.update(Wrappers.<FeishuOutboxEntity>lambdaUpdate()
                .eq(FeishuOutboxEntity::getId, delivery.getId()).eq(FeishuOutboxEntity::getState, "PENDING")
                .eq(FeishuOutboxEntity::getAttempts, delivery.getAttempts())
                .set(FeishuOutboxEntity::getState, state).set(FeishuOutboxEntity::getErrorCode, error)
                .set(FeishuOutboxEntity::getPlatformMessageId, platformId)
                .set(FeishuOutboxEntity::getNextAttemptAt, nextAttempt)));
    }

    /** 用明确的中文说明受理失败，不把全局忙碌伪装成排队成功。 */
    private String admissionNotice(String error) {
        return switch (error) {
            case "DIAGNOSIS_IN_PROGRESS" -> "当前已有诊断正在执行，本次未受理。请稍后重新发送问题。";
            case "MODEL_NOT_CONFIGURED" -> "对话模型尚未配置，本次未受理。请联系工作台管理员。";
            case "INVALID_QUESTION" -> "问题描述不符合长度要求，本次未受理。";
            default -> "本次消息受理失败，请联系工作台管理员查看记录。";
        };
    }

    /** 限制聊天长度并明确节选；完整答案、引用和事件仍保留在原诊断记录。 */
    private String resultText(DiagnosisRun run) {
        String label = switch (run.status()) {
            case "COMPLETED" -> "诊断完成";
            case "LIMIT_REACHED" -> "达到步数上限，以下结果可能不完整";
            case "TIMED_OUT" -> "诊断超时";
            case "CANCELLED" -> "诊断已取消";
            case "INTERRUPTED" -> "服务重启或关闭，诊断已中断";
            default -> "诊断失败";
        };
        // 只格式化发往飞书的展示文本，数据库中的诊断回答保留模型原文。
        String answer = run.answer() == null ? "" : DiagnosisTimeFormatter.format(run.answer());
        int points = answer.codePointCount(0, answer.length());
        if (points > 3500) {
            answer = answer.substring(0, answer.offsetByCodePoints(0, 3500))
                    + "\n\n（以上为回答节选，完整诊断请在本机工作台按任务编号查看。）";
        }
        return label + "\n任务编号：" + run.id() + (answer.isBlank() ? "" : "\n\n" + answer)
                + (run.errorCode().isBlank() ? "" : "\n原因代码：" + run.errorCode());
    }

    /** 后台只返回状态，不通过渠道管理页面扩大诊断正文的传播范围。 */
    @Override
    public List<FeishuViews.Message> messages() {
        return inbox.selectList(Wrappers.<FeishuInboxEntity>lambdaQuery()
                .eq(FeishuInboxEntity::getAppId, properties.appId())
                .eq(FeishuInboxEntity::getTenantKey, properties.tenantKey())
                .orderByDesc(FeishuInboxEntity::getCreatedAt).orderByDesc(FeishuInboxEntity::getId)
                .last("LIMIT 100")).stream().map(message -> new FeishuViews.Message(
                        message.getId(), message.getMessageId(), message.getUserId(), message.getState(),
                        message.getErrorCode(), message.getCreatedAt(), deliveries(message.getId()))).toList();
    }

    /** 查询指定收件的有限回复状态，响应不包含冻结正文。 */
    private List<FeishuViews.Delivery> deliveries(String id) {
        return outbox.selectList(Wrappers.<FeishuOutboxEntity>lambdaQuery()
                .eq(FeishuOutboxEntity::getInboxId, id).orderByAsc(FeishuOutboxEntity::getCreatedAt))
                .stream().map(delivery -> new FeishuViews.Delivery(delivery.getId(), delivery.getKind(),
                        delivery.getState(), delivery.getAttempts(), delivery.getErrorCode())).toList();
    }

    /** 条件更新失败必须中止当前推进，留待下次读取数据库确定真实状态。 */
    private void requireWrite(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("FEISHU_STATE_CHANGED");
        }
    }
}
