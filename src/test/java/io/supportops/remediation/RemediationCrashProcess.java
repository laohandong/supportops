package io.supportops.remediation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.agent.service.RunService;
import io.supportops.demo.service.impl.DemoEnvironment;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.Create;
import io.supportops.remediation.mapper.RemediationDecisionMapper;
import io.supportops.remediation.mapper.RemediationEventMapper;
import io.supportops.remediation.mapper.RemediationExecutionMapper;
import io.supportops.remediation.mapper.RemediationPlanMapper;
import io.supportops.remediation.mapper.RemediationRequestMapper;
import io.supportops.remediation.service.impl.RemediationServiceImpl;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.remediation.service.support.RemediationProcessIdentity;
import io.supportops.remediation.vo.RemediationViews.Detail;
import io.supportops.user.CurrentUser;
import io.supportops.user.mapper.UserMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.service.impl.UserServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** R15专用子JVM；真实受理提交后在不同阶段强制退出，不可在生产启用。 */
public final class RemediationCrashProcess {
    /** 测试入口类不能实例化。 */
    private RemediationCrashProcess() {}

    /** 只允许本组随机隔离数据库，故障不通过生产HTTP参数开放。 */
    public static void main(String[] args) throws Exception {
        validateDatabase(args[0], System.getProperty("supportops.test.mysql", "jdbc:mysql://127.0.0.1:23306/"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(args[0], "supportops", "synthetic-knowledge-app");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMappers("io.supportops.remediation.mapper");
        configuration.addMappers("io.supportops.user.mapper");
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UserService users = new UserServiceImpl(session.getMapper(UserMapper.class), transactions);
        MockHttpServletRequest identity = new MockHttpServletRequest();
        identity.setAttribute(CurrentUser.ATTRIBUTE, users.find(args[1]));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(identity));
        DemoEnvironment target = new DemoEnvironment(new ObjectMapper()) {
            /** 在受理后/应用后直接退出，不返回伪造结果。 */
            @Override
            public synchronized void apply(String operationId) {
                if (args[2].equals("ACCEPTED")) {
                    Runtime.getRuntime().halt(23);
                }
                super.apply(operationId);
                if (args[2].equals("APPLIED")) {
                    Runtime.getRuntime().halt(23);
                }
            }

            /** 完成真实HTTP后、完成事务前退出。 */
            @Override
            public synchronized void verify(String operationId) {
                super.verify(operationId);
                Runtime.getRuntime().halt(23);
            }
        };
        RunService runs = mock(RunService.class);
        when(runs.whenIdle(org.mockito.ArgumentMatchers.<Supplier<Detail>>any())).thenAnswer(call -> {
            Supplier<Detail> operation = call.getArgument(0);
            return operation.get();
        });
        RemediationServiceImpl service = new RemediationServiceImpl(session.getMapper(RemediationPlanMapper.class),
                session.getMapper(RemediationDecisionMapper.class), session.getMapper(RemediationExecutionMapper.class),
                session.getMapper(RemediationRequestMapper.class), session.getMapper(RemediationEventMapper.class),
                transactions, target, runs, users, new RemediationJson(), new RemediationProcessIdentity());
        Detail plan = service.create(new Create(UUID.randomUUID().toString(), RemediationContract.ACTION, "进程中断回归", "合成测试", null, null));
        Detail approved = service.approve(plan.id(), new Command(UUID.randomUUID().toString(), plan.version(), plan.planHash(), null));
        service.execute(plan.id(), new Command(UUID.randomUUID().toString(), approved.version(), approved.planHash(), null));
        throw new IllegalStateException("CRASH_POINT_NOT_REACHED");
    }

    /** 子进程只接受父测试声明的同一回环服务与随机测试库，不接受任意 URL 后缀。 */
    static void validateDatabase(String jdbc, String declared) {
        if (!declared.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("ISOLATED_DATABASE_REQUIRED");
        }
        int separator = declared.lastIndexOf(':');
        int port = Integer.parseInt(declared.substring(separator + 1, declared.length() - 1));
        String suffix = jdbc.startsWith(declared) ? jdbc.substring(declared.length()) : "";
        if (port < 1 || port > 65535
                || !suffix.matches("so_it_[a-f0-9]{16}\\?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC")) {
            throw new IllegalArgumentException("ISOLATED_DATABASE_REQUIRED");
        }
    }
}
