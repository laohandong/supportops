package io.supportops.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 独立的只读 OS 探针；不启动 Spring、不加载业务消费者、不结束进程。 */
public final class ProcessProbe {
    /** 进程信息中的空字段表示系统不可读，不能据此猜测身份。 */
    public record Identity(String state, long pid, String processStartedAt, String command, List<String> arguments) {}

    /** 禁止实例化纯诊断入口。 */
    private ProcessProbe() {}

    /** 仅核对指定 PID；JSON 不包含目标环境变量和输出。 */
    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Identity identity;
        try {
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) {
                identity = new Identity("EXITED", pid, null, null, null);
            } else {
                ProcessHandle.Info info = process.get().info();
                identity = new Identity("ALIVE", pid, info.startInstant().map(Instant::toString).orElse(null),
                        info.command().orElse(null), info.arguments().map(List::of).orElse(null));
            }
        } catch (SecurityException exception) {
            identity = new Identity("UNKNOWN", pid, null, null, null);
        }
        System.out.println("OPS:" + new ObjectMapper().writeValueAsString(identity));
    }
}
