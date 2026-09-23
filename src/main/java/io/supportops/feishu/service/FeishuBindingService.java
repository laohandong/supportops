package io.supportops.feishu.service;

import io.supportops.feishu.entity.FeishuBindingEntity;
import io.supportops.feishu.entity.FeishuInboxEntity;
import io.supportops.feishu.vo.FeishuViews;
import io.supportops.user.vo.UserView;
import java.util.List;

/** 管理飞书身份到本地账号的显式绑定，不自动创建用户或授予管理员身份。 */
public interface FeishuBindingService {
    /** 为已有账号签发十分钟有效的随机绑定码。 */
    FeishuViews.BindingCode issue(String userId);
    /** 返回当前应用和企业最近一百条绑定。 */
    List<FeishuViews.Binding> list();
    /** 撤销绑定，后续准入与尚未开始的投递均拒绝该身份。 */
    void revoke(String id);
    /** 查询当前应用中的已绑定平台身份。 */
    FeishuBindingEntity find(String openId);
    /** 在收件事务内消费绑定码；无效、过期或身份冲突时返回 null。 */
    FeishuBindingEntity consume(String code, String openId);
    /** 重新核实已受理记录的绑定和用户，失效返回 null。 */
    UserView authorize(FeishuInboxEntity message);
}
