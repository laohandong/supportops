package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员可见的实际 JVM 身份，不包含路径、连接信息或凭据。 */
@Schema(name = "RuntimeView", description = "实际运行的程序与 JVM 身份；unknown 表示无法核实。")
public record RuntimeView(
        @Schema(description = "运行记录协议版本。") int schemaVersion,
        @Schema(description = "本次启动的唯一身份；企业直接启动会生成UUID，旧本地直接启动无法核实时为unknown。") String runtimeId,
        @Schema(description = "当前 JVM 的实际 PID。") long pid,
        @Schema(description = "操作系统报告的进程启动 UTC 时间；不可读时为空。") String processStartedAt,
        @Schema(description = "实际运行 JAR 的 SHA-256；非 JAR 启动时为 unknown。") String jarSha256,
        @Schema(description = "实际 JAR 的应用版本。") String applicationVersion,
        @Schema(description = "可核实的构建修订；本包未嵌入修订时为 unknown。") String buildRevision,
        @Schema(description = "构建源码状态；未嵌入证明时为 unknown，不代表干净发布。") String sourceState,
        @Schema(description = "STARTING、READY 或 STOPPED；READY 来自 ApplicationReadyEvent。") String phase,
        @Schema(description = "应用真实就绪 UTC 时间；尚未就绪时为空。") String applicationReadyAt,
        @Schema(description = "应用实际监听端口；尚未绑定时为零。") int port) {}
