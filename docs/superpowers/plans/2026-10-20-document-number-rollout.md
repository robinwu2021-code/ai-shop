# 业务编号生成器全模块接入计划

> **For agentic workers:** Use superpowers:executing-plans and dispatching-parallel-agents for independent file scopes. Keep tests targeted.

**Goal:** 按NUM-01～14与29类类型登记，将人工业务单号全面接入新生成器，保留内部身份、幂等与外部协议。
**Architecture:** 复用DocumentNumberGenerator与本地映射。新增DocumentNumberCoordinator，支持在现有业务事务外预留多个类型／数量的号码，并在所属服务写事务内绑定；不改变原有跨服务事实查询和资金事务边界。各模块在新建事实处绑定，DTO显式返回documentNo；查询不补历史号，页面不回退展示长内部号。
**Tech Stack:** Java21、Spring事务、MyBatis/JDBC、Vue、TypeScript、H2/JUnit。
**Spec:** docs/technical/TDD-业务编码统一生成.md §8～11。

## Global Constraints

- 前缀+yyyyMMdd+至少6位不回绕；默认Asia/Shanghai；Core/Pay各自本地数据库。
- 原单号、内部关联、幂等指纹、事件与安全凭证保留；新增人工号映射而非批量改主键。订单专项例外：新建主／子单直接采用 OD／OS，历史 O 单身份不改。
- 任何持有业务事务时不能补领号段；嵌套调用只能消耗外层已预留号码。
- 历史无映射返回空；读接口不创建映射；按短号定位后仍走原权限检查。
- 本轮不部署、不执行真实收款；每批编译和基本定向单测，不运行全量回归。

## Review Focus

- 批量、循环、组合核销及结算涉及多个新事实时须完整预留，不能隐式续号。
- 异步恢复、重复请求、冲正、跨日重放复用已绑定号且不改变资金结果。
- 原事务边界、来源解析与远程查询顺序不得因编号包装而改变。
- 主子单、支付尝试及外部回跳显式关联；不再依赖去除数字后缀猜单。
- 新旧营销源键隔离，DTO、导出、页面与旧查询合同兼容。

## 公共编排接口

`DocumentNumberCoordinator`（store模块，各服务按限定名装配）：
- `<T> T withReservation(Map<DocumentType,Integer> counts, Supplier<T> action)`：外层事务外预留；嵌套复用已有上下文，不开启业务事务。
- `<T> T transaction(Map<DocumentType,Integer> counts, Supplier<T> action)`：先预留，再在所属事务中执行action。
- `String bind(DocumentType type, DocumentSource source)`：所属事务中新事实绑定，先复用已有映射，再消费预留号码；缺少预留失败，不能事务内发号。
- `Optional<String> find(DocumentType type, DocumentSource source)`：只读查询。

## 执行包

### Task A: 公共编排与储值展现（root）
- [x] 先写事务外预留、嵌套复用、数量不足、绑定回滚的失败测试，再实现Coordinator及两个配置bean。
- [x] 补储值收支DTO、历史恢复显示、短号查询适配及必要定向测试。

### Task B: Pay权益、储值账务与积分（pay_rights）
- [x] EGD/EGB/EU/SV/PTV实际新建点接入，保留内部UUID／摘要语义。
- [x] 原事务外预留、写事务绑定，正确处理批次、组合核销及嵌套调用。
- [x] DTO及查询返回短号，针对新修改编译与基本测试。

### Task C: Pay迁移、对账、异常与财务（pay_operations）
- [x] LCI/MGP/MGI/MGS/ERC/ERD/EEX/EAC/WD/STB/STS/SIR/PY接入实际创建点。
- [x] 结算调用积分等下游时，外层提供足够预留；与Task B协调边界。
- [x] 对应DTO／查询兼容与定向验证。

### Task D: Core营销与交易申请（core_marketing）
- [x] PT/PC/PI/PUC、AS/IRQ接入新建点；旧、新营销源系统区分。
- [x] 编辑、版本、重试不换号，原实体键与接口定位不变。
- [x] 对应DTO／查询兼容与定向验证。

### Task E: Core库存、订单和前端契约（root）
- [x] ISY/IAL、OD/OS绑定，订单取消四位回绕风险并保持旧链接兼容。
- [x] 支付回跳使用真实关联；前端明确订单号与支付单号，短号替换默认长号展示。
- [x] 对修改范围做编译、基本测试及类型检查。

### Task F: 集成审查与交付（root）
- [x] 按29类生成静态接入清单并核对每类实际新建点，区分可选明细与保留内部标识。
- [x] 合并阶段验证、修复新增问题；已通过旧链路不重复回归。
- [x] 更新文档与剩余任务，提交授权远端分支，明确部署未执行。


## 完成记录

已完成 A～F 代码与集成审查，真实创建点矩阵、订单专项差异、兼容边界和各阶段测试证据统一见 TDD §12。按用户要求仅测试新增／修复范围，不跑全量、不重复已经通过的业务回归；没有执行部署或真实资金操作。提交目标：origin/codex/ai-shop-codex。
