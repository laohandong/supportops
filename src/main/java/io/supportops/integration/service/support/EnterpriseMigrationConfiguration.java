package io.supportops.integration.service.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 企业已有守卫必须先于迁移检查，防止第二版本在旧实例运行时改动结构。 */
@Configuration(proxyBeanMethods = false)
public class EnterpriseMigrationConfiguration {
    /** 声明迁移前检查配置，不读取或修改业务资源。 */
    public EnterpriseMigrationConfiguration() {}
    /** 初始化基础设施可以使用固定JDBC探针；业务服务仍只通过Mapper访问数据库。 */
    @Bean
    FlywayMigrationStrategy enterpriseMigrationStrategy(
            @Value("${supportops.enterprise.enabled:false}") boolean enabled,
            @Value("${supportops.enterprise.deployment-id:}") String deploymentId) {
        return flyway -> {
            if (enabled) {
                String deployment = IntegrationInputs.uuid(deploymentId);
                try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                        PreparedStatement exists = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='deployment_runtime_guard'")) {
                    try (ResultSet table = exists.executeQuery()) {
                        table.next();
                        if (table.getInt(1) > 0) {
                            try (PreparedStatement query = connection.prepareStatement("SELECT deployment_id,holder_runtime_id FROM deployment_runtime_guard WHERE id=1");
                                    ResultSet guard = query.executeQuery()) {
                                if (!guard.next()) { throw new IllegalStateException("DEPLOYMENT_GUARD_MISSING"); }
                                String saved = guard.getString(1);
                                if (saved != null && !deployment.equals(saved)) { throw new IllegalStateException("DEPLOYMENT_ID_MISMATCH"); }
                                if (guard.getString(2) != null) { throw new IllegalStateException("DEPLOYMENT_ALREADY_RUNNING_OR_UNKNOWN"); }
                            }
                        }
                    }
                } catch (RuntimeException exception) { throw exception; }
                catch (Exception exception) { throw new IllegalStateException("DEPLOYMENT_MIGRATION_GUARD_UNAVAILABLE"); }
            }
            flyway.migrate();
        };
    }
}
