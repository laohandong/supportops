# SupportOps 排障演示

约 79 秒，1600 × 900，30 fps，H.264 MP4，中文字幕，无配音。

演示使用项目自建的 OrderBridge 合成订单同步环境，画面来自真实工作台操作和已持久化的模型回答。推理等待片段已加速，并在字幕中标明。模型没有执行修复，配置调整由人员通过示例环境页面完成。

## 演示内容

1. 三份版本文档完成关键词与向量索引。
2. 升级后同步请求仍访问 `/v1/orders`，返回 HTTP 410。
3. Agent 查询配置、日志和下游状态，并引用适用的 2.0 文档。
4. 根据旧键与新键的迁移规则，给出有证据支持的处理建议。
5. 人员应用 `sync.targetPath=/v2/orders`，新的同步请求返回 HTTP 200。
6. Agent 再次读取现场证据，确认本次观测的请求已恢复。

这是一次合成场景演示，不代表所有故障、订单或生产环境都已验证。实际模型调用的等待时间分别约 60 秒和 40 秒。

## 文件

- [演示视频（MP4）](https://github.com/laohandong/supportops/raw/refs/heads/main/docs/demo/supportops-demo-zh.mp4)：已内嵌中文字幕的视频。
- [独立中文字幕（SRT）](supportops-demo-zh.srt)：独立字幕，供重新剪辑使用。
- [演示封面（PNG）](supportops-demo-cover.png)：视频中的真实诊断画面，可用作封面。

项目源码与启动说明：[laohandong/supportops](https://github.com/laohandong/supportops)。
