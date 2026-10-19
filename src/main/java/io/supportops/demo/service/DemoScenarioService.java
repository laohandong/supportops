package io.supportops.demo.service;

import io.supportops.demo.vo.DemoEnvironmentEvent;
import io.supportops.demo.vo.DemoState;
import java.util.List;

/** 人工场景切换的准入、身份与持久化审计边界，不开放给模型。 */
public interface DemoScenarioService {
    /** 登记审计后才改变现场；同名重置也追加新记录。 */
    DemoState configure(String scenario);
    /** 管理员按游标读取所有历史实例的固定目标变更，重启后仍可读取。 */
    List<DemoEnvironmentEvent> events(long after);
}
