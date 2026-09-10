package io.supportops.persistence;

import io.supportops.user.mapper.UserMapper;
import io.supportops.user.entity.UserEntity;
import io.supportops.user.PasswordHash;
import io.supportops.user.service.UserService;
import io.supportops.user.service.impl.UserServiceImpl;
import io.supportops.user.vo.UserView;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实隔离 MySQL 验证历史兼容、初始化互斥、唯一账号和事务回滚。 */
class UserPersistenceTest {
    /** 全量基线不依赖数据库默认排序规则，重复启动不改写已保存的未归属记录。 */
    @Test
    void baselinePreservesRowsUnderDifferentDefaultCollation() throws Exception {
        try (KnowledgeTestResources resources = new KnowledgeTestResources();
                Connection connection = DriverManager.getConnection(resources.url(), resources.user, resources.password);
                Statement sql = connection.createStatement()) {
            sql.execute("ALTER DATABASE `" + resources.database + "` COLLATE utf8mb4_0900_ai_ci");
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password)
                    .target("3").load().migrate().migrationsExecuted).isEqualTo(1);
            sql.execute("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,elapsed_ms,input_tokens,output_tokens) "
                    + "VALUES ('legacy-run','legacy-session','合成历史问题','COMPLETED','合成历史回答','','2026-09-01T00:00:00Z',1,2,3)");
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password)
                    .load().migrate().migrationsExecuted).isEqualTo(1);
            try (ResultSet rows = sql.executeQuery("SELECT archived_at FROM runs WHERE id='legacy-run'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNull();
            }
            try (ResultSet rows = sql.executeQuery("SELECT question,answer,user_id,input_tokens FROM runs WHERE id='legacy-run'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("question")).isEqualTo("合成历史问题");
                assertThat(rows.getString("answer")).isEqualTo("合成历史回答");
                assertThat(rows.getString("user_id")).isNull();
                assertThat(rows.getLong("input_tokens")).isEqualTo(2);
            }
            // 二次执行没有补建或改写已有历史。
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).load().migrate().migrationsExecuted).isZero();
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(159);
            }
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history' "
                    + "AND column_comment NOT REGEXP '[一-龥]'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history' "
                    + "AND table_comment REGEXP '[一-龥]'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(16);
            }
        }
    }

    /** 已导入的短密码可以登录，但新建账号仍执行原有长度限制。 */
    @Test
    void importedCredentialsDoNotWeakenNewAccountPasswordPolicy() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase()) {
            UserMapper mapper = database.mapper(UserMapper.class);
            UserEntity imported = new UserEntity();
            imported.setId("11111111-1111-4111-8111-111111111111");
            imported.setUsername("imported_admin");
            imported.setPasswordHash(PasswordHash.encode("short-pass"));
            imported.setRole("ADMIN");
            imported.setCreatedAt("2026-09-08T00:00:00Z");
            mapper.insert(imported);
            UserService users = new UserServiceImpl(mapper, database.transactions);
            assertThat(users.login("imported_admin", "short-pass").id()).isEqualTo(imported.getId());
            assertThatThrownBy(() -> users.login("imported_admin", "incorrect"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> users.create("new_user", "short-pass", "USER"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    /** 并发首次初始化只能成功一次；账号保存和关联校验使用实际 MySQL。 */
    @Test
    void setupIsSerializedAndPasswordsAndTransactionsPersistCorrectly() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase();
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            UserService users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
            Callable<Boolean> initialize = () -> {
                try {
                    users.setup("first_admin", "synthetic-initial-password");
                    return true;
                } catch (ResponseStatusException exception) {
                    assertThat(exception.getStatusCode().value()).isEqualTo(409);
                    return false;
                }
            };
            List<Future<Boolean>> results = workers.invokeAll(List.of(initialize, initialize));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class)).isEqualTo(1);
            UserView ordinary = users.create("Alice", "synthetic-user-password", "USER");
            assertThat(users.login("ALICE", "synthetic-user-password").id()).isEqualTo(ordinary.id());
            assertThatThrownBy(() -> users.create("alice", "synthetic-user-password", "ADMIN"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> users.create("bad_role", "synthetic-user-password", "SUPERUSER"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> database.transactions.executeWithoutResult(status -> {
                users.create("rolled_back", "synthetic-user-password", "USER");
                throw new IllegalStateException("SYNTHETIC_ROLLBACK");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='rolled_back'", Integer.class)).isZero();
            assertThat(users.list()).extracting(UserView::username).containsExactly("alice", "first_admin");
        }
    }
}
