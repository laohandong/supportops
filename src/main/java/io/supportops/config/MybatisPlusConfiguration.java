package io.supportops.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 注册明确标记的 MyBatis-Plus Mapper，避免将业务服务接口误识别为持久化接口。 */
@Configuration
@MapperScan(basePackages = "io.supportops", annotationClass = Mapper.class)
public class MybatisPlusConfiguration {}
