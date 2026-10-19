package io.supportops.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.agent.service.RunService;
import io.supportops.demo.mapper.DemoEnvironmentEventMapper;
import io.supportops.demo.service.impl.DemoEnvironment;
import io.supportops.demo.service.impl.DemoScenarioServiceImpl;
import io.supportops.demo.vo.DemoEnvironmentEvent;
import io.supportops.demo.vo.DemoState;
import io.supportops.persistence.MapperTestDatabase;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.user.CurrentUser;
import io.supportops.user.mapper.UserMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.service.impl.UserServiceImpl;
import io.supportops.user.vo.UserView;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** CR-F03：正式Mapper和隔离MySQL核对场景审计历史、故障和重启读取。 */
class DemoScenarioPersistenceTest {
    /** 同名重置连续追加，重建环境后仍可读取旧实例原修订且查询没有副作用。 */
    @Test
    void sameScenarioResetsAndRestartPreserveCompleteOrderedHistory() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase()) {
            UserService users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
            UserView admin = users.setup("scene_admin", "synthetic-scene-password");
            identity(admin);
            DemoEnvironment environment = new DemoEnvironment(new ObjectMapper());
            DemoEnvironment restarted = null;
            try {
                DemoScenarioServiceImpl service = service(database, users, environment, database.mapper(DemoEnvironmentEventMapper.class));
                String instance = environment.instanceId();
                long revision = environment.snapshot().revision();
                service.configure("migration");
                service.configure("migration");
                List<DemoEnvironmentEvent> history = service.events(0);
                assertThat(history).hasSize(2);
                assertThat(history.getFirst().beforeRevision()).isEqualTo(revision);
                assertThat(history.getLast().afterRevision()).isEqualTo(revision + 2);
                assertThat(history).allMatch(event -> event.actorId().equals(admin.id()) && event.instanceId().equals(instance)
                        && event.status().equals("APPLIED") && event.beforeJson().contains("orders.path")
                        && event.afterJson().contains("httpStatus"));
                assertThat(service.events(history.getFirst().id())).containsExactly(history.getLast());
                assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM demo_environment_events WHERE after_revision=before_revision+1", Integer.class)).isEqualTo(2);
                restarted = new DemoEnvironment(new ObjectMapper());
                DemoScenarioServiceImpl reader = service(database, users, restarted, database.mapper(DemoEnvironmentEventMapper.class));
                assertThat(restarted.instanceId()).isNotEqualTo(instance);
                long startupRevision = restarted.snapshot().revision();
                int startupRequests = restarted.logs().entries().size();
                assertThat(reader.events(0)).isEqualTo(history);
                assertThat(restarted.snapshot().revision()).isEqualTo(startupRevision);
                assertThat(restarted.logs().entries()).hasSize(startupRequests);
            } finally {
                ReflectionTestUtils.invokeMethod(environment, "close");
                if (restarted != null) {
                    ReflectionTestUtils.invokeMethod(restarted, "close");
                }
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /** 意图保存失败零变更；结果保存失败保留STARTED，不将现场已改变包装为回滚。 */
    @Test
    void auditFailuresNeverLeaveAnUnregisteredChangeOrPretendRollback() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase()) {
            UserService users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
            identity(users.setup("scene_fault_admin", "synthetic-scene-password"));
            DemoEnvironment environment = new DemoEnvironment(new ObjectMapper());
            try {
                long before = environment.snapshot().revision();
                DemoEnvironmentEventMapper mapper = database.mapper(DemoEnvironmentEventMapper.class);
                DemoEnvironmentEventMapper failInsert = MapperTestDatabase.intercept(DemoEnvironmentEventMapper.class, mapper, (method, args) -> {
                    if (method.getName().equals("insert")) {
                        throw new IllegalStateException("SYNTHETIC_INSERT_FAILURE");
                    }
                });
                assertThatThrownBy(() -> service(database, users, environment, failInsert).configure("healthy")).hasMessageContaining("SYNTHETIC_INSERT_FAILURE");
                assertThat(environment.snapshot().revision()).isEqualTo(before);
                assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM demo_environment_events", Integer.class)).isZero();
                DemoEnvironmentEventMapper failFinish = MapperTestDatabase.intercept(DemoEnvironmentEventMapper.class, mapper, (method, args) -> {
                    if (method.getName().equals("update")) {
                        throw new IllegalStateException("SYNTHETIC_FINISH_FAILURE");
                    }
                });
                assertThatThrownBy(() -> service(database, users, environment, failFinish).configure("healthy")).hasMessageContaining("SCENARIO_RESULT_NOT_PERSISTED");
                assertThat(environment.snapshot().revision()).isEqualTo(before + 1);
                assertThat(database.jdbc.queryForObject("SELECT status FROM demo_environment_events", String.class)).isEqualTo("STARTED");
                assertThat(database.jdbc.queryForObject("SELECT after_json FROM demo_environment_events", String.class)).isNull();
                assertThat(service(database, users, environment, mapper).events(0)).hasSize(1);
                assertThat(environment.snapshot().revision()).isEqualTo(before + 1);
            } finally {
                ReflectionTestUtils.invokeMethod(environment, "close");
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /** 普通角色与非法场景不能登记或修改，角色在服务层重新查询。 */
    @Test
    void unauthorizedAndInvalidScenesHaveNoAuditOrEnvironmentWrites() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase()) {
            UserService users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
            UserView admin = users.setup("scene_role_admin", "synthetic-scene-password");
            DemoEnvironment environment = new DemoEnvironment(new ObjectMapper());
            try {
                identity(admin);
                DemoScenarioServiceImpl service = service(database, users, environment, database.mapper(DemoEnvironmentEventMapper.class));
                long before = environment.snapshot().revision();
                assertThatThrownBy(() -> service.configure("arbitrary")).hasMessageContaining("INVALID_INPUT");
                identity(users.create("scene_reader", "synthetic-scene-password", "USER"));
                assertThatThrownBy(() -> service.configure("healthy")).hasMessageContaining("ADMIN_REQUIRED");
                assertThatThrownBy(() -> service.events(0)).hasMessageContaining("ADMIN_REQUIRED");
                assertThat(environment.snapshot().revision()).isEqualTo(before);
                assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM demo_environment_events", Integer.class)).isZero();
            } finally {
                ReflectionTestUtils.invokeMethod(environment, "close");
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /** 正式准入之外只使用同步执行夹具，避免调用任何模型。 */
    private DemoScenarioServiceImpl service(MapperTestDatabase database, UserService users, DemoEnvironment environment,
            DemoEnvironmentEventMapper mapper) {
        RunService runs = mock(RunService.class);
        when(runs.whenIdle(any())).thenAnswer(call -> {
            Supplier<DemoState> operation = call.getArgument(0);
            return operation.get();
        });
        return new DemoScenarioServiceImpl(environment, runs, users, mapper, database.transactions, new RemediationJson());
    }

    /** 固定认证属性引用真实测试账号，客户端不能指定操作者。 */
    private static void identity(UserView user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
