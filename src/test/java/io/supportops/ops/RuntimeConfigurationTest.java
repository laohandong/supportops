package io.supportops.ops;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证只读端口解析与 Spring 配置优先级，不修改宿主系统属性。 */
class RuntimeConfigurationTest {
    /** 用明确的合成属性源代替宿主环境，保持正式 ConfigData 解析器。 */
    private StandardEnvironment configured(Map<String, Object> values, String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values));
        String[] all = new String[args.length + 1];
        all[0] = "--spring.config.import=";
        System.arraycopy(args, 0, all, 1, args.length);
        RuntimeConfiguration.prepare(environment, all);
        return environment;
    }

    /** 未给出端口时使用原应用默认值；PORT 与 SERVER_PORT 都保持原优先级。 */
    @Test
    void preservesEnvironmentAndDefault() {
        assertThat(RuntimeConfiguration.port(configured(Map.of()))).isEqualTo(18080);
        assertThat(RuntimeConfiguration.port(configured(Map.of("PORT", "45101")))).isEqualTo(45101);
        assertThat(RuntimeConfiguration.port(configured(Map.of("PORT", "45101", "SERVER_PORT", "45102")))).isEqualTo(45102);
    }

    /** Spring JSON 高于环境值，显式命令参数仍有最高优先级。 */
    @Test
    void preservesJsonAndExplicitOverride() {
        Map<String, Object> values = Map.of("PORT", "45101", "SPRING_APPLICATION_JSON", "{\"server\":{\"port\":45103}}");
        assertThat(RuntimeConfiguration.port(configured(values))).isEqualTo(45103);
        assertThat(RuntimeConfiguration.port(configured(values, "--server.port=45104"))).isEqualTo(45104);
    }

    /** 随机端口必须明确配置；格式错误和 TCP 超限均拒绝。 */
    @Test
    void validatesPortRange() {
        assertThat(RuntimeConfiguration.port(configured(Map.of("PORT", "0")))).isZero();
        for (String value : new String[]{"bad", "-1", "65536"}) {
            assertThatThrownBy(() -> RuntimeConfiguration.port(configured(Map.of("PORT", value))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
