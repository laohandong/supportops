# 应用诊断客户端

使用 Node.js 22 或更高版本，在管理员创建应用、批准目标环境并签发访问密钥后运行。机器接口与工作台 Cookie 分离；协议见随附 `openapi.json` 和企业接入手册。

在所属账号专用文件中保存密钥，限制为仅本人读取。部署地址必须为 HTTPS；私有 CA 通过 `NODE_EXTRA_CA_CERTS` 指向批准的 PEM 文件，不关闭证书校验。

```sh
export SUPPORTOPS_API_ORIGIN=https://supportops.example.internal:8443
export SUPPORTOPS_APPLICATION_SECRET_FILE=/secure/application-key
export SUPPORTOPS_ENVIRONMENT_ID=<管理员提供的环境UUID>
export SUPPORTOPS_QUESTION='核对应用版本和下游状态'
export SUPPORTOPS_CLIENT_STATE=/secure/diagnosis-state.json
export NODE_EXTRA_CA_CERTS=/secure/enterprise-ca.pem
node diagnosis-client.mjs
```

首次运行先把 requestId、原问题和环境保存为固定状态，再提交任务。再次运行继续读取同一任务；需要一条新诊断时使用新的状态文件。事件有界分页，结果与游标保存到私有状态文件，终端只显示安全状态。

受理响应丢失时先查询原 requestId。查询失败保持 UNKNOWN；只有明确404才重发同键同正文。409不生成替代键，429尊重重试时间。服务器返回的应用身份、地址或本地输入发生变化时停止恢复，避免串用其他应用或目标。

取消已有任务时设置 `SUPPORTOPS_CANCEL=true` 后运行同一状态文件。取消需要应用的 CANCEL 权限，取消受理并不等于执行线程已退出。独占锁阻止多个客户端进程同时操作同一状态；异常留下的锁文件应先核实原进程已退出后处理。

状态文件含问题、事件和结果，按企业资料保护。程序不向终端打印访问密钥，不承诺真实模型的诊断准确率。
