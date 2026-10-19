# 独立诊断源示例

本示例实现 SupportOps 标准只读源协议，使用 JDK 21 自带 HTTP/HTTPS 服务，不依赖工作台代码。返回值全部为合成数据，部署到实际系统前应替换安全读取逻辑并完成源端脱敏。

企业离线包包含已编译的 `diagnostic-source.jar`。开发时设置 `JAVA_HOME` 指向 JDK 21，再执行：

```sh
node build.mjs --output=/absolute/path/new-build-directory
java -jar /absolute/path/new-build-directory/diagnostic-source.jar
```

输出目录必须不存在。默认监听回环 HTTP；企业接入需要 HTTPS，非回环监听不允许明文。设置 `SOURCE_TLS_KEYSTORE` 指向 PKCS12 文件、`SOURCE_TLS_PASSWORD_FILE` 指向私有密码文件、`SOURCE_SECRET_FILE` 指向私有 Bearer 文件。文件内容不打印、不放入命令参数。Bearer 使用 43 至 128 个 URL 安全字符。

通过 `SOURCE_SYSTEM`、`SOURCE_ENVIRONMENT`、`SOURCE_PRODUCT`、`SOURCE_VERSION` 指定身份；通过 `SOURCE_PORT` 与 `SOURCE_BIND_ADDRESS` 指定监听。平台资源编码和版本必须与源端一致。部署第二套源使用另一组系统身份和 Bearer，平台只新增资源及连接配置。

四个 GET 能力与严格字段定义见 [openapi.json](openapi.json)，完整限制见企业包内《标准源协议》。服务只输出就绪端口。连接测试与诊断效果验证分别执行。
