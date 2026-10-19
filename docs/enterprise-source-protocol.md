# 标准只读诊断源协议 1.0

连接类型为 `STANDARD_REST`。诊断源属于被诊断系统，负责认证、脱敏和真实身份；SupportOps 只调用下表四个固定 GET 路径。基础地址无用户信息、查询、片段、百分号或路径逃逸；企业模式要求 HTTPS。

| 能力 | 固定路径 | data字段 |
| --- | --- | --- |
| APP_INFO | `/v1/app-info` | applicationName，capabilities；必须声明APP_INFO |
| EFFECTIVE_CONFIG | `/v1/effective-config` | items数组：key、value、redacted、valueSource |
| RECENT_LOGS | `/v1/recent-logs?from=…&to=…&limit=…` | entries数组：timestamp、level、message、traceId；truncated |
| DOWNSTREAM_HEALTH | `/v1/downstream-health` | dependencies数组：name、status、observedAt、responseCode、reason |

请求头含 `Authorization: Bearer <现场秘密>`、`X-SupportOps-Request-Id` 和诊断时的 `X-SupportOps-Run-Id`。返回 `Content-Type: application/json`，身份和 requestId 必须与请求及管理资源完全一致。

```json
{
  "protocolVersion":"1.0",
  "requestId":"11111111-1111-4111-8111-111111111111",
  "target":{"systemCode":"erp","environmentCode":"production","productKey":"example-erp","productVersion":"2.0"},
  "observedAt":"2026-10-04T00:00:00Z",
  "source":{"kind":"APPLICATION","instanceId":"erp-instance-01"},
  "data":{"applicationName":"ERP","capabilities":["APP_INFO","DOWNSTREAM_HEALTH"]}
}
```

APP_INFO observation 最多早于当前时间5分钟或晚于当前时间1分钟。每次响应都检查协议、目标与观测时间；现场版本漂移不能以管理端版本替代。日志历史事件时间与响应观测时间分别解释。

正文最多1MiB，JSON最大深度20。拒绝未知字段、重复字段、错误类型、空的必需基本类型、压缩正文和跨地址重定向。配置最多200项，key128字符，value4096字符；涉及 password/secret/token/apikey/credential 的键必须 redacted=true、value=null。日志最多200条，消息4096字符，traceId128字符；级别 TRACE/DEBUG/INFO/WARN/ERROR。依赖最多50项，状态UP/DOWN/UNKNOWN，响应码100至599或null，reason512字符。完整字段定义见样例旁 `openapi.json`。

白名单包含最多16个精确IPv4/IPv6地址；当前DNS解析的全部地址必须获准。实际连接固定到获准地址，TLS仍使用原主机名/SNI。禁止链路本地、元数据、未指定和组播地址。仅非企业合成回环测试可使用显式明文HTTP。平台不使用系统代理、自动重试或重定向。

连接超时3秒、读取空闲5秒、总体10秒，DNS另有有界线程及2秒预算。错误只返回安全原因码，不能回显现场正文或Bearer。模型调用前先读取APP_INFO；现场能力采集与进入模型的批准分别判断。连接测试最多取5条日志，不把测试事实发送模型。

## 独立 Java 样例

`java-diagnostic-source/src/DiagnosticSource.java` 使用JDK21内置HTTP/HTTPS服务，不依赖SupportOps业务类。离线企业包附预编译 `diagnostic-source.jar`；开发目录可独立编译：

```sh
javac --release 21 -encoding UTF-8 -d classes src/DiagnosticSource.java
jar --create --file diagnostic-source.jar --main-class DiagnosticSource -C classes .
java -jar diagnostic-source.jar
```

通过私有环境设置 `SOURCE_SYSTEM`、`SOURCE_ENVIRONMENT`、`SOURCE_PRODUCT`、`SOURCE_VERSION`、`SOURCE_SECRET_FILE`、`SOURCE_PORT`、`SOURCE_BIND_ADDRESS`。Bearer文件内容为43至128位URL安全字符。HTTPS提供 `SOURCE_TLS_KEYSTORE`（PKCS12）和 `SOURCE_TLS_PASSWORD_FILE`；监听非回环必须启用HTTPS。就绪输出只含端口，不输出秘密。

默认样例仅合成配置、日志及503依赖状态，未连接真实ERP。部署第二套样例使用不同系统编码、资料和Bearer，接入只需要新增资源配置。企业源码适配时应将合成数据替换为现场安全读取，并按本协议脱敏，不能把样例内容作为企业事实。
