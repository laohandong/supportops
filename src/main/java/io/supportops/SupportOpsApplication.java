package io.supportops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** SupportOps 模块化单体应用启动入口。 */
@SpringBootApplication
public class SupportOpsApplication {
    /** 启动 Spring Boot 并加载应用配置与业务组件。 */
    public static void main(String[] args) {
        SpringApplication.run(SupportOpsApplication.class, args);
    }
}
