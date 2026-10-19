package io.supportops.remediation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证崩溃夹具只连接父进程明确声明的回环测试数据库。 */
class RemediationCrashProcessTest {
    /** 随机端口兼容，同时拒绝其他端点、业务库与 URL 参数逃逸。 */
    @Test
    void acceptsOnlyDeclaredLoopbackAndExactRandomSchema() {
        String declared = "jdbc:mysql://127.0.0.1:59922/";
        String suffix = "so_it_0123456789abcdef?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
        assertThatCode(() -> RemediationCrashProcess.validateDatabase(declared + suffix, declared)).doesNotThrowAnyException();
        for (String jdbc : new String[]{"jdbc:mysql://127.0.0.1:23306/" + suffix,
                declared + "supportops?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC",
                declared + suffix + "&socketFactory=unsafe", "jdbc:mysql://other-host:59922/" + suffix}) {
            assertThatThrownBy(() -> RemediationCrashProcess.validateDatabase(jdbc, declared))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("ISOLATED_DATABASE_REQUIRED");
        }
        for (String invalid : new String[]{"jdbc:mysql://other-host:59922/", "jdbc:mysql://127.0.0.1:0/", "jdbc:mysql://127.0.0.1:65536/"}) {
            assertThatThrownBy(() -> RemediationCrashProcess.validateDatabase(invalid + suffix, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("ISOLATED_DATABASE_REQUIRED");
        }
    }
}
