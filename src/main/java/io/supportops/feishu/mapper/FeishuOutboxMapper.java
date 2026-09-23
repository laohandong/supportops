package io.supportops.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.feishu.entity.FeishuOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/** 持久化回复正文及固定发送标识；发送失败不能重新运行诊断。 */
@Mapper
public interface FeishuOutboxMapper extends BaseMapper<FeishuOutboxEntity> {}
