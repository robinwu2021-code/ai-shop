# JB-015 支付权益发行容量实现计划

状态：按2026-10-19用户调整后置至第二阶段；仅有计划，尚未实施Java代码。当前先执行JB-028起的活动阶段。

## 目标与边界

实现 pay 侧跨版本模板发行授权：按模板控制总份数和累计授权敞口，按模板+用户控制个人已预留/已发行份数。核心活动预算仍归 core；本任务只实现 pay 的持久容量内核，不接 HTTP、批量发放或具体 GrantService。预留/确认/释放必须加入调用方 pay 事务，后续 GrantService 以 grant 状态转换保证每个预留只结算一次。

## 实施与验证

1. 先写聚焦的策略单测，覆盖总容量/敞口/单用户上限、关闭只阻止新增、扩容不得小于占用量，以及 reserve→commit/release 计数守恒；观察缺少实现时失败。
2. 新增 V430、实体/Mapper 和 `EntitlementIssuePolicyService`/Impl：条件更新+模板行锁防并发超发；接入命令幂等；持久更新与recipient容量检查串行化。
3. 扩展隔离 MySQL验证器，验证真实约束和有限并发下不超发；只运行本模块定向测试与原生 MySQL 场景，不运行全量测试。
4. 更新任务表、数据库/API/类清单与生成文档；运行文档/SQL闸门并提交。

## 关键约束

- 发行开关关闭只挡新预留，不妨碍已有 grant 完成 commit/release。
- `limit_count`校验`held_count+issued_count`；`max_exposure_minor`校验`held_exposure_minor+used_exposure_minor`。
- 新个人上限不得低于任一用户的`held_count+issued_count`。
- 所有写路径显式使用`payTxManager`；跨事务结算由 grant 状态转移防重复，不在本任务增加第二套reserve账本。
