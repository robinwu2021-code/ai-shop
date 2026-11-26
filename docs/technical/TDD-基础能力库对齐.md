# TDD-基础能力库对齐

状态：草稿（2026-11-26 勘查完成，待定事项见 §3）
关联：[ADR-066](ADR/ADR-066-业务模块按配置选数据源.md) · [TDD-平台基础库换表](TDD-平台基础库换表.md) · [TDD-登录与账号对齐平台基础库](TDD-登录与账号对齐平台基础库.md)
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

基础能力库 `n_common` 约 21 张表，分为权限、开放应用、事件、字典、审批、发号、审计、设置八组。
现在这些能力几乎每一组都有多套实现，散在 5 到 10 个模块里。对齐分两件事：
一是把每组收敛成一个唯一实现、一个端口；二是给它一个模块句柄，让它能像发票、平台基础库那样按配置换库。

## 验收标准

- K1：Given 公共能力的某一组 When 搜索它的旧表名 Then 只在该组的唯一实现里出现（用守卫扫描，同平台基础库的做法）。
- K2：Given 公共能力模块绑到 `n_common` When 跑权限、审计、设置的测试 Then 全部通过。
- K3：Given 运营后台的事务 When 扫描 Then 同一事务不同时写平台基础库和基础能力库。

## §1 现状（2026-11-26 盘点）

### 1.1 各组的旧表、读写面与重复实现

| 组 | 旧表数 | 读写文件数（量级） | 集中处 | 重复实现 |
|---|---:|---:|---|---|
| 权限 | 7 | 19 | `shop-core` 的 `platform/perm` | 网关 `iam` 的 `JdbcResourceStore` 管接口资源表；`FunctionModuleSync` 直接写功能点 |
| 开放应用 | 3 | 约 40 | 无 | 支付 `pay_app`、库存 `inv_open_credential`、运营 `sys_ops_api_key`（设计归密钥库） |
| 事件 | 24 | 约 234 | `shop-store-mybatis` 的 `event` 包 | 平台与库存两套前缀、支付权益、积分、业务通知、商城内核、物流推送等 7 套；消费去重 6 套，另有已退役的 `sys_idempotent`（约 50 个文件在用） |
| 字典 | 13 | 约 107 | 无 | 行业三套（`sys_industry`、`sys_store_industry`、`sys_business_type`）；渠道三套（`sys_pay_channel`、`PayChannels` 常量、`cim_provider`） |
| 审批 | 3 | 22 | `shop-opskit` 的 `approval_link` 与 `ApprovalPort` | 支付两人复核、储值活动审批、门店审核、改价 |
| 发号 | 7 | 34 | `shop-base` 的 `DocumentNumberService` | 一份仓储代码对应两组表（CORE、PAY）；`sys_sequence` 有两个 Mapper（`TradeMappers`、`SeqMapper`）；另有三种按范围计数 |
| 审计 | 4 | 约 107 | `AuditLogPort` | `OpsServiceImpl` 绕过端口直接写；各域自有操作日志（员工、支付商户状态、库存、美业、餐饮、打印） |
| 设置 | 3 | 58 | `SettingService` | 支付 `PaySettingService` 另一套 |

### 1.2 运营后台跨两个库的事务

`OpsServiceImpl` 有 19 个事务，其中 13 处同时写平台基础库（运营账号、会话、主体）和基础能力库（角色成员、审计、审批、发件箱）。
设置账号角色、新建账号、设置多角色这三处把角色**双写**：`ops_staff.roles` 的 JSON 用于判权，`sys_role_member` 用于菜单。
`PermConfigServiceImpl` 的 9 个事务只写基础能力库，例外是强制下线一个角色的账号（`forceLogoutRole`，会写运营会话，即登录 TDD 的 X5）。

### 1.3 事件：拆库的主要阻力

用发件箱的业务文件有 86 个、发布调用 152 处，都是“业务表与发件箱同一个事务”。
餐饮的发件箱配置明确要求“同一个主库事务和数据源”。基础能力库一旦和业务库分开，这些写法要么换语义，要么发件箱留在业务库。
先例：发票已改成提交后再发（`InvoiceServiceImpl` 的 `afterCommit`）；打印计划同样处理（发票打印 TDD 的 T5）。

## §2 顺序（建议）

| 步 | 做什么 | 依赖 |
|---|---|---|
| 1 | **权限**：加公共能力模块句柄 `common` 与标记 `CommonTableMapper`；`platform/perm` 7 张表的 Mapper 与网关 `iam` 两个类接进来；拆掉运营后台的角色双写（判权改读 `sys_role_member`，`ops_staff.roles` 停写）；强制下线改成提交后再吊销会话 | Q2、Q3 |
| 2 | **审计、设置**：`OpsServiceImpl` 改走 `AuditLogPort`；审计改为业务提交后写；`pay_setting` 并进 `SettingService` | Q5 |
| 3 | **字典、审批、开放应用**：一组一个端口，删掉重复的常量与表 | — |
| 4 | **发号**：合并两份 `SeqMapper`；仓储去掉按归属切表 | — |
| 5 | **事件与去重**：按 Q1 的结论收敛；删 `Source` 枚举与 `inv_` 前缀，支付权益、积分、商城内核三套并入；`sys_idempotent` 的使用方改用统一防重 | Q1 |

可以先做、不依赖任何决定的：合并两份 `SeqMapper`；`OpsServiceImpl` 的审计改走端口（行为不变）；
新增“公共能力表只经唯一实现访问”的守卫，以现状为基线，只准变短。

## §3 待你定

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 发件箱放哪：每个业务库一张、基础能力库只负责投递与汇总；还是只有基础能力库一张、所有发布改为提交后再发 | 每个业务库一张（保持同事务语义），投递器统一在 `shop-store-mybatis`；基础能力库只放投递记录与死信。它决定第 5 步的全部做法 |
| Q2 | 公共能力是新建一个 `common` 句柄，还是先挂在主库句柄下 | 新建句柄，未绑定时指向主库，同平台基础库 |
| Q3 | `ops_staff.roles` 何时停写 | 随第 1 步一起停 |
| Q4 | 基础能力库设计评审 R1–R6（事件号长度、`sys_dict_item` 与 `sys_sequence` 的唯一键等），以及是否授权改 61 上已建的表 | 先在本机测试库的补充脚本里改，61 不动 |
| Q5 | 各域自有的操作日志（员工、餐饮、美业、打印、库存）是否并入统一审计 | 不并：它们是业务流水；只有“谁在运营端改了什么”进统一审计 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| K1 | 待建守卫 | — |
| K2 | `OpsPermConfigFlowTest`、`OpsStaffWriteFlowTest`、`OpsPlatformConfigFlowTest` 等 | — |
| K3 | 待建守卫 | — |
