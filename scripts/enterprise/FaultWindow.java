import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.MethodEntryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 只用于隔离最终JAR的故障注入，在真实事务提交后、调度器调用前暂停独立JVM。 */
public final class FaultWindow {
    /** 禁止实例化验证入口。 */
    private FaultWindow() {
    }
    /** 附着专属容器内部调试端口，暂停标记只保存方法身份，不读取现场数据或秘密。 */
    public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(value -> "dt_socket".equals(value.transport().name())).findFirst().orElseThrow();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(args[0]);
        arguments.get("port").setValue(args[1]);
        VirtualMachine machine = connector.attach(arguments);
        MethodEntryRequest request = machine.eventRequestManager().createMethodEntryRequest();
        request.addClassFilter("io.supportops.agent.service.impl.RunServiceImpl$*");
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.enable();
        Files.writeString(Path.of(args[2]), "ATTACHED");
        boolean held = false;
        while (true) {
            EventSet events = machine.eventQueue().remove(180000);
            if (events == null) {
                throw new IllegalStateException("FAULT_WINDOW_TIMEOUT");
            }
            for (Event event : events) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    return;
                }
                if (event instanceof MethodEntryEvent entry && "afterCommit".equals(entry.method().name())) {
                    Files.writeString(Path.of(args[3]), entry.method().declaringType().name() + "#afterCommit");
                    held = true;
                }
            }
            // 保持暂停直到父级已核对独立SQL并终止该JVM，不能提前断开调试器而恢复执行。
            if (!held) {
                events.resume();
            }
        }
    }
}
