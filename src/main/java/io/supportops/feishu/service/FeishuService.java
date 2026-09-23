package io.supportops.feishu.service;

import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.vo.FeishuViews;
import java.util.List;

/** 飞书收件、诊断准入与回复恢复的业务边界。 */
public interface FeishuService {
    /** 在短事务中保存消息或绑定操作，成功返回后 SDK 才能确认事件。 */
    void receive(FeishuIncomingMessage message);
    /** 推进有界批次的待处理消息与投递；不在数据库事务中等待模型或平台网络。 */
    void process();
    /** 管理员查询最近一百条收件及回复状态，不返回诊断正文和密钥。 */
    List<FeishuViews.Message> messages();
}
