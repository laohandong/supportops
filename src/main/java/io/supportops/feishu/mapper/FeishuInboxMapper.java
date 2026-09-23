package io.supportops.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.feishu.entity.FeishuInboxEntity;
import org.apache.ibatis.annotations.Mapper;

/** 飞书私聊收件记录；先保存稳定请求编号，再受理诊断。 */
@Mapper
public interface FeishuInboxMapper extends BaseMapper<FeishuInboxEntity> {}
