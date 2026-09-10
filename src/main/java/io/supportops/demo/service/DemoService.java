package io.supportops.demo.service;

import io.supportops.demo.vo.DemoAppInfo;
import io.supportops.demo.vo.DemoConfiguration;
import io.supportops.demo.vo.DemoHealth;
import io.supportops.demo.vo.DemoLogs;
import io.supportops.demo.vo.DemoState;
import io.supportops.demo.vo.SyncResult;

/** 自建 OrderBridge 环境的业务边界；人工写操作不会注册为模型工具。 */
public interface DemoService {
    /** 切换合成故障条件并实际发起一次同步。 */
    DemoState configure(String name);

    /** 人工应用新版配置并实际验证同步结果。 */
    SyncResult repairConfiguration();

    /** 根据当前有效配置向本地下游发送一次请求。 */
    SyncResult synchronizeOrder();

    /** 读取应用的真实版本与环境信息。 */
    DemoAppInfo appInfo();

    /** 读取提供配置与生效配置，不返回凭据。 */
    DemoConfiguration config();

    /** 读取最近的实际请求日志，不返回场景标签或预设原因。 */
    DemoLogs logs();

    /** 发起实际 HTTP 健康检查。 */
    DemoHealth health();

    /** 返回仅供人员操作页面使用的环境快照。 */
    DemoState operatorView();
}
