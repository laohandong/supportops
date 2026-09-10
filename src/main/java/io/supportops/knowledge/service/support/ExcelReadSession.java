package io.supportops.knowledge.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.supportops.knowledge.dto.CompiledSql;
import io.supportops.knowledge.mapper.ExcelReadMapper;

import jakarta.annotation.PreDestroy;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 独立只读数据库身份；不注册为主 DataSource，也不复用业务写连接。 */
@Component
public class ExcelReadSession {
    private final HikariDataSource datasource;
    private final ExcelReadMapper reader;

    /** 读取账户仅授权 Excel 数据域，SQL 策略另行限定具体可见数据表。 */
    public ExcelReadSession(
            @Value("${spring.datasource.url}") String url,
            @Value("${supportops.knowledge.excel.schema:supportops_excel}") String schema,
            @Value("${supportops.knowledge.excel.query-user:supportops_reader}") String user,
            @Value("${supportops.knowledge.excel.query-password:}") String password)
            throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url.replaceFirst("/[^/?]+(?=\\?|$)", "/" + schema));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(5000);
        config.setPoolName("excel-readonly");
        datasource = new HikariDataSource(config);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ExcelReadMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setConfiguration(configuration);
        reader = new SqlSessionTemplate(factory.getObject()).getMapper(ExcelReadMapper.class);
    }

    /** 只执行 AST 编译器生成且绑定参数的 SQL。 */
    public List<Map<String, Object>> query(CompiledSql query) {
        return reader.query(query);
    }

    /** 关闭独立查询连接池。 */
    @PreDestroy
    public void close() {
        datasource.close();
    }
}
