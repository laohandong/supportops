package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.UploadRecordEntity;

import org.apache.ibatis.annotations.Mapper;

/** 服务端受理的上传请求及成功、失败和重复命中记录的数据访问边界。 */
@Mapper
public interface UploadRecordMapper extends BaseMapper<UploadRecordEntity> {}
