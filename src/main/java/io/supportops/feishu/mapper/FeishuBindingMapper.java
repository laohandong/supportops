package io.supportops.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.feishu.entity.FeishuBindingEntity;
import org.apache.ibatis.annotations.Mapper;

/** 飞书身份绑定与一次性绑定码摘要；绑定码原文只返回管理员一次。 */
@Mapper
public interface FeishuBindingMapper extends BaseMapper<FeishuBindingEntity> {}
