package io.supportops.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.agent.mapper.DiagnosisRunMapper;
import io.supportops.agent.mapper.RunEventMapper;
import io.supportops.config.ProviderRegistry;
import io.supportops.knowledge.mapper.DocumentChunkMapper;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.DocumentVersionMapper;
import io.supportops.knowledge.mapper.ExcelColumnMapper;
import io.supportops.knowledge.mapper.ExcelDatasetMapper;
import io.supportops.knowledge.mapper.ExcelTableMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.mapper.KnowledgeDocumentMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.mapper.SourceFileMapper;
import io.supportops.knowledge.mapper.TaskAttemptMapper;
import io.supportops.knowledge.mapper.UploadRecordMapper;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.impl.DocumentImportServiceImpl;
import io.supportops.knowledge.service.impl.DocumentIndexServiceImpl;
import io.supportops.knowledge.service.impl.DocumentLibraryServiceImpl;
import io.supportops.knowledge.service.impl.DocumentRevisionServiceImpl;
import io.supportops.knowledge.service.impl.ExcelDataServiceImpl;
import io.supportops.knowledge.service.impl.KnowledgeTaskServiceImpl;
import io.supportops.knowledge.service.impl.MinioOriginalFileStore;
import io.supportops.knowledge.service.support.DocumentParser;
import io.supportops.knowledge.service.support.ExcelReadSession;
import io.supportops.knowledge.service.support.ReadOnlySqlCompiler;
import io.supportops.memory.mapper.ProjectMemoryMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

/** 独立 MySQL 与真实 MyBatis-Plus Mapper 夹具；JDBC 仅用于造数据和独立核对结果。 */
public final class MapperTestDatabase implements AutoCloseable {
    public final JdbcTemplate jdbc;
    public final TransactionTemplate transactions;
    public final DiagnosisRunMapper runs;
    public final RunEventMapper events;
    public final ProjectMemoryMapper memories;
    public final KnowledgeDocumentMapper documents;
    public final DocumentChunkMapper chunks;
    public final KnowledgeTestResources resources;
    public final KnowledgeTaskService tasks;
    public final EmbeddingClient embedding;
    public final ElasticKnowledgeIndex elastic;
    public final MockEnvironment environment = new MockEnvironment();
    public final ExcelReadSession excelReader;
    private final SqlSessionTemplate session;

    /** 使用原初始化结构启动随机命名数据库，并加载正式 Mapper XML。 */
    public MapperTestDatabase() throws Exception {
        resources = new KnowledgeTestResources();
        resources.migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(resources.url(), resources.user, resources.password);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addMapper(DiagnosisRunMapper.class);
        configuration.addMapper(RunEventMapper.class);
        configuration.addMapper(ProjectMemoryMapper.class);
        configuration.addMappers("io.supportops.knowledge.mapper");
        configuration.addMappers("io.supportops.user.mapper");
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:/mapper/**/*.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        session = new SqlSessionTemplate(sessionFactory);
        runs = session.getMapper(DiagnosisRunMapper.class);
        events = session.getMapper(RunEventMapper.class);
        memories = session.getMapper(ProjectMemoryMapper.class);
        documents = session.getMapper(KnowledgeDocumentMapper.class);
        chunks = session.getMapper(DocumentChunkMapper.class);
        tasks =
                new KnowledgeTaskServiceImpl(
                        mapper(IndexTaskMapper.class),
                        mapper(TaskAttemptMapper.class),
                        mapper(ProcessingBatchMapper.class),
                        transactions,
                        120000,
                        5,
                        60000);
        ObjectMapper json = new ObjectMapper();
        environment
                .withProperty("supportops.providers.custom.api", "openai-completions")
                .withProperty("supportops.providers.custom.embeddings", "true")
                .withProperty("supportops.providers.custom.api-key-env", "CUSTOM_API_KEY");
        embedding = new EmbeddingClient(new ProviderRegistry(environment), json);
        elastic = new ElasticKnowledgeIndex(json, resources.es, "", "", resources.prefix);
        excelReader =
                new ExcelReadSession(
                        resources.url(),
                        resources.excelSchema,
                        resources.queryUser,
                        resources.queryPassword);
    }

    /** 释放随机测试数据库，不访问当前工作台文件。 */
    @Override
    public void close() {
        excelReader.close();
        resources.close();
    }

    /** 获取当前真实会话注册的 Mapper。 */
    public <T> T mapper(Class<T> type) {
        return session.getMapper(type);
    }

    /** 构造正式上传编排，仅分片 Mapper 允许定点注入故障。 */
    public DocumentLibraryService library(DocumentChunkMapper chunkMapper) {
        ObjectMapper json = new ObjectMapper();
        ExcelDataServiceImpl excel =
                new ExcelDataServiceImpl(
                        mapper(ExcelDatasetMapper.class),
                        mapper(ExcelColumnMapper.class),
                        mapper(ExcelTableMapper.class),
                        mapper(KnowledgeCatalogMapper.class),
                        tasks,
                        excelReader,
                        new ReadOnlySqlCompiler(),
                        transactions,
                        json,
                        resources.excelSchema);
        MinioOriginalFileStore originals =
                new MinioOriginalFileStore(
                        resources.minio,
                        "synthetic-local",
                        "synthetic-knowledge-minio",
                        resources.prefix);
        DocumentRevisionServiceImpl revisions =
                new DocumentRevisionServiceImpl(
                        documents,
                        mapper(SourceFileMapper.class),
                        mapper(DocumentVersionMapper.class),
                        mapper(ProcessingBatchMapper.class),
                        mapper(DocumentReleaseMapper.class),
                        mapper(KnowledgeCatalogMapper.class),
                        originals);
        // 与 Spring 使用同样的阶段协作关系；故障分片 Mapper 必须注入真正执行写入的导入服务。
        DocumentImportServiceImpl imports =
                new DocumentImportServiceImpl(
                        mapper(SourceFileMapper.class),
                        mapper(UploadRecordMapper.class),
                        mapper(DocumentVersionMapper.class),
                        mapper(ProcessingBatchMapper.class),
                        chunkMapper,
                        tasks,
                        new DocumentParser(),
                        excel,
                        transactions,
                        json,
                        revisions);
        DocumentIndexServiceImpl indexing =
                new DocumentIndexServiceImpl(
                        mapper(ProcessingBatchMapper.class),
                        mapper(DocumentReleaseMapper.class),
                        mapper(IndexTaskMapper.class),
                        mapper(KnowledgeCatalogMapper.class),
                        tasks,
                        embedding,
                        elastic,
                        transactions,
                        revisions);
        return new DocumentLibraryServiceImpl(
                documents,
                mapper(SourceFileMapper.class),
                mapper(UploadRecordMapper.class),
                mapper(DocumentVersionMapper.class),
                mapper(ProcessingBatchMapper.class),
                chunkMapper,
                mapper(DocumentReleaseMapper.class),
                mapper(IndexTaskMapper.class),
                tasks,
                originals,
                embedding,
                elastic,
                excel,
                transactions,
                json,
                revisions,
                imports,
                indexing,
                event -> {});
    }

    /** 在真实 Mapper 调用前注入指定故障，其余方法仍通过原会话执行实际 SQL。 */
    public static <T> T intercept(
            Class<T> contract, T delegate, BiConsumer<Method, Object[]> before) {
        Object proxy =
                Proxy.newProxyInstance(
                        contract.getClassLoader(),
                        new Class<?>[] {contract},
                        (instance, method, arguments) -> {
                            before.accept(method, arguments);
                            try {
                                return method.invoke(delegate, arguments);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
        return contract.cast(proxy);
    }
}
