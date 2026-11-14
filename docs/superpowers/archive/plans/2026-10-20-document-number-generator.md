# 统一业务单号组件实施计划

> **For agentic workers:** Use superpowers:executing-plans to implement this plan in the current session. Steps use checkbox syntax for tracking.

**Goal:** 提供配置驱动的统一人工编号组件及Core/Pay本地实现，首批接入门店方案储值充值结果。

**Architecture:** shop-base定义类型、格式、接口；shop-store-mybatis提供通用JDBC号段及映射实现；Core/Pay装配各自数据源和事务。储值编排在事务外预留号码，同一Pay事务创建充值并绑定短号，保持内部原单不变。

**Tech Stack:** Java21、Spring事务、JdbcTemplate、MySQL/H2、JUnit5。

**Spec:** docs/technical/TDD-业务编码统一生成.md §8～10。

## Global Constraints

- 前缀2～3位、yyyyMMdd、序列至少6位；默认Asia/Shanghai；号码不回绕。
- Core/Pay拥有各自号段；任何业务事务内禁止预留，即使命中缓存。
- 不改旧订单、幂等键、安全令牌、第三方编号；不批量迁移历史数据。
- 分阶段只运行本次相关单测与编译，不跑全量回归。

## Review Focus

- 多实例、号段尾、序列溢出与实例重启不重复。
- 小连接池与已有事务误用立即拒绝，不能等待第二条连接。
- 同源并发映射胜出、跨租户查号及不同类型前缀校验。
- 事务回滚不留下映射，重试与历史空映射兼容。
- 储值创建与确认保持原资金事务，不能提前提交或重复入账。

### Task 1: 基础类型与格式

**Files:** shop-base/src/{main,test}/java/ai/neargo/shop/numbering/。
**Interfaces:** DocumentType、DocumentOwner、DocumentNumberFormatter、ReservedDocumentNumber、DocumentSource、DocumentNumberGenerator、DocumentNumberService。

- [x] 先写格式／登记测试并观察缺类失败。
- [x] 实现类型登记、严格日期与序列校验、保留号码与源键契约。
- [x] 定向测试通过。

### Task 2: 通用号段、JDBC映射与本地装配

**Files:** shop-store-mybatis/src/{main,test}/java/ai/neargo/shop/numbering/store/；Core/Pay numbering配置；V542迁移及schema-test.sql。
**Interfaces:** SegmentDocumentNumberGenerator.reserve(type,date)、JdbcDocumentNumberRepository.bind/findBySource/resolve；两个服务限定owner且明确DataSource及TransactionManager。

- [x] 先写H2真实事务测试：多实例、回滚不复用、并发同源绑定、跨租户、事务内拒绝、小连接池及重启。
- [x] 实现号段独立提交、同源唯一映射、批量预留及Core/Pay装配；号码映射绑定要求已有同域事务。
- [x] 加迁移并定向编译、单测通过。

### Task 3: 储值首批接入

**Files:** Pay numbering/StoredValueNumberedPlanRechargeService.java、StoredValuePort.RechargeView、对应单测及场景测试。
**Interfaces:** @Primary装饰既有StoredValuePlanRechargeService；返回可空documentNo，保留原构造方法和原服务逻辑。

- [x] 先写测试：首次储值绑定PR、重复请求复用、异常回滚映射、历史查询不补号。
- [x] 在事务外预留，Pay事务内调用原服务并绑定，查询／重放返回已存短号；取消前未创建原单不绑定号码。
- [x] 编译Core/Pay服务并运行新增编号测试与一条储值确认场景，不重复既有全量回归。

### Task 4: 审查与交付

- [x] 核查迁移、实际数据源/事务、格式和旧接口兼容；记录测试结果并更新设计实施状态。
- [x] 提交并同步授权远端分支，部署另行执行。


## 执行记录

- 已按缺失类／方法观察失败，再实现基础格式、JDBC与储值接入；补充并修复并发绑定结果及BFF DTO透传用例。
- 验证：Java21下Core和独立Pay编译；16项相关测试通过；B端vue-tsc通过；不执行全量回归。
- 独立只读审查未发现其余可确认P1/P2；DTO丢短号问题已修复并通过新增用例。
- 本轮范围为公共组件与门店方案充值首批接入，其他业务接入及HTTP短号查询按原分包计划后续实施。
