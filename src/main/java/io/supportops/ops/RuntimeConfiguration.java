package io.supportops.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.convert.ConversionException;

/** 仅解析启动环境与端口，不创建应用上下文、连接存储或运行消费者。 */
public final class RuntimeConfiguration {
    /** 端口解析结果；无效配置时端口为空，不回显原始配置内容。 */
    private record PortResult(boolean valid, Integer port) {}

    /** 禁止实例化只读配置入口。 */
    private RuntimeConfiguration() {}

    /** 复用 Spring 的命令参数、JSON、系统环境和 ConfigData 优先级。 */
    public static StandardEnvironment environment(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        prepare(environment, args);
        return environment;
    }

    /** 准备显式环境；测试替换属性源时也使用相同的正式解析流程。 */
    static void prepare(StandardEnvironment environment, String[] args) {
        if (args.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        }
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
    }

    /**
     * 读取实际配置端口；零是明确配置的随机端口，其他值必须在 TCP 范围内。
     * @throws IllegalArgumentException 配置无法转换或超出端口范围，异常不含原值
     */
    static int port(StandardEnvironment environment) {
        int port;
        try {
            port = environment.getProperty("server.port", Integer.class, 18080);
        } catch (ConversionException exception) {
            throw new IllegalArgumentException("PORT_INVALID");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("PORT_INVALID");
        }
        return port;
    }

    /** 输出受控协议结果；解析失败不打印包含配置或凭据的异常正文。 */
    public static void main(String[] args) throws Exception {
        PortResult result;
        try {
            result = new PortResult(true, port(environment(args)));
        } catch (Exception exception) {
            result = new PortResult(false, null);
        }
        System.out.println("OPS:" + new ObjectMapper().writeValueAsString(result));
    }
}
