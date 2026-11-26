# TDD-商户门店与员工权限对齐平台基础库

状态：第一步已实现（接到平台句柄，不改表）· 第二步（换表）未做，与登录与账号一起
关联：[TDD-登录与账号对齐平台基础库](TDD-登录与账号对齐平台基础库.md)（为什么四个模块要一起换表）· [ADR-066](ADR/ADR-066-业务模块按配置选数据源.md)
范围：商户与门店、员工与权限、门店设置与设备三个业务模块
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

这三个模块的代码几乎都在 `backend/shop-merchant`。它的 Mapper 全挤在一个包里，48 个里只有 18 个的表属于平台基础库，
其余在门店交易库、支付商户库、账务结算库等，所以不能按包绑定。本次用“标记接口”把这 18 个 Mapper 绑到平台句柄，
并把平台基础库这一侧的 28 个类的事务与手写 SQL 接到句柄上。不改表，不绑定时行为不变。

## 验收标准

- P1：Given 没有给平台模块绑定数据源 When 跑商户、门店、员工相关的现有测试 Then 全部通过。
- P2：Given 商户域里一个属于平台基础库的类有事务没点名平台句柄 When 跑闸门 Then 失败并指出文件与行。
- P3：Given 平台模块绑到另一个数据源 When 取 18 个带标记的 Mapper 的工厂 Then 是平台模块的；同包其余 Mapper 仍是主库的。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| P1 | 句柄未绑定时是主库 bean 的别名 |
| P2 | `ModuleTxManagerConventionTest` 增加 `shop-merchant`，带一份“不属于平台基础库”的类名单 |
| P3 | `PlatformTableMapper`、`PlatformSqlMapper` 两个标记；`MybatisPlusConfig` 按标记排除，`ModuleMapperScanConfig` 按标记收取 |

## §1 现状

### 1.1 一个包里混着五个库

| 归属 | Mapper | 例子 |
|---|---:|---|
| 平台基础库 | 18 | 主体、门店、B 端账号、门店角色、角色、人员与任职、资质、服务范围、履约方式 |
| 门店交易库 | 约 14 | 资源、排班、预约占用、门店分类 |
| 支付商户库、账务结算库 | 约 7 | 收款商户、出款账户、准入、欠款、保证金 |
| 平台商城库、基础能力库、平台服务库 | 约 5 | 主体套餐、行业字典、授权码、违规 |
| 设计里没有的表 | 5 | 预约时段、员工日志、门店审核、门店码打印、服务范围网格 |

### 1.2 每个类属于哪一边

按类里用到的 Mapper 与手写 SQL 里的表名分了档：

| 档 | 类数 | 本次怎么处理 |
|---|---:|---|
| 只碰平台基础库 | 19 | 接到平台句柄 |
| 两边都碰、以平台基础库为主 | 9 | 接到平台句柄；它们就是第二步要拆的跨库点（见 §1.3） |
| 两边都碰、以别的库为主，或只碰别的库 | 20 | 不动，留在主库句柄，写进守卫的名单 |
| 在调用方事务里加锁的两个端口 | 2 | 不动，写进名单；换库时单独处理 |

### 1.3 第二步要拆的跨库点

| 类 | 同一个事务里还写了哪个库 |
|---|---|
| `MerchantGovernServiceImpl`、`StoreAdminServiceImpl`、`MerchantPortImpl` | 收款商户（支付商户库）、违规（平台服务库）、授权码与行业字典（基础能力库）、主体套餐（平台商城库） |
| `PersonnelServiceImpl`、`PersonnelFactsPortImpl` | 人员的服务资格、资源、预约归属（门店交易库） |
| `MerchantStaffServiceImpl`、`MerchantStoreServiceImpl` | 员工日志、门店审核（设计里没有这两张表，去向是统一审计与审核服务） |
| `ManagementRepository`（品牌与管理空间，约 180 条手写 SQL） | 收款商户、支付准备快照（支付商户库） |
| `StoreOwnershipWritePortImpl`、`StoreDirectoryPortImpl` | 被交易、促销、美业、餐饮共 25 处在**调用方的事务里**调用来给门店、主体加锁 |

另有约 90 处别的模块直接查这些表，几乎都在 `shop-gateway` 的各个桥接包里（门店换主体、B 端登录、顾客报表、收款来源联表、建店授权）。

### 1.4 门店设置与设备

这个模块**没有自己的代码**。新设计的 `mch_store_setting`、`mch_store_device` 在代码里不存在；
它们要并入的旧表分散在会员（`mbr_setting`）、打印（`prn_agent`）、餐饮（点餐设备、桌码）、支付（终端）、发票（门店开票设置）。
这一步没有可接的东西；换表那一步要新建这两张表的代码并从五处收拢，是新增功能量级的工作。

## §2 方案

### 2.1 契约变更

没有。

### 2.2 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-store-mybatis/.../config/PlatformTableMapper.java`、`PlatformSqlMapper.java` | 标记接口：这个 Mapper 的表在平台基础库 |
| 新增 | `shop-store-mybatis/.../config/PlatformJdbcConfiguration.java` | 平台句柄上的 `JdbcTemplate`；同时显式声明主库的那一个，否则 Spring Boot 自动配置的会退让 |
| 修改 | `shop-merchant/.../mapper/MerchantMappers.java`、`ReachMatchMapper.java` | 18 个 Mapper 加标记 |
| 修改 | `shop-app/.../config/MybatisPlusConfig.java`、`ModuleMapperScanConfig.java` | 全局扫描按标记排除，平台扫描按标记收取 |
| 修改 | `shop-merchant` 里 28 个类 | 事务点名 `platformTxManager`；手写 SQL 用平台句柄的 `JdbcTemplate` 或数据源 |
| 修改 | `shop-app/.../arch/ModuleTxManagerConventionTest.java` | 守卫加入 `shop-merchant` 与名单 |
| 修改 | 14 个测试的装配 | 引入模块句柄 |

## §3 选型

| 方案 | 结论 |
|---|---|
| 把 18 个 Mapper 搬到新包再按包绑定 | ❌ 它们是同一个外部类的嵌套接口，搬走要改上百个文件的 import |
| 按类名正则逐个排除 | ❌ 嵌套类的类名带 `$`，写错不报错 |
| 标记接口 | ✅ 一个 Mapper 归哪个库写在它自己的声明上；扫描两头都按类型，写错了编译或启动时就知道 |

## §4 风险

| 风险 | 缓解 |
|---|---|
| 容器里多了一个 `JdbcTemplate`，按类型注入的地方拿错 | 主库的那一个显式声明为首选；平台的只能点名取 |
| 名单过时：别的库的类被当成平台基础库的 | 名单在守卫里，分档依据写在 §1.2；换表那一步会逐类再核 |
| 分档是脚本按 Mapper 名与 SQL 里的表名做的 | 动态拼的表名看不到；换表前要人工过一遍 9 个混用类 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| P1 | 商户、门店、员工、身份相关的现有测试 | ✅ 178 个测试类全绿（H2） |
| P2 | `ModuleTxManagerConventionTest#module_transactions_name_their_own_manager` | ✅ |
| P3 | `MapperSmokeTest`（每个 Mapper 能查到自己的表）在未绑定状态下通过；绑定状态没有端到端测试 | 部分 |

没跑成：13 个 `Personnel*IT`、`Resource*IT`、`Schedule*IT`，它们要一套专用的测试库（系统属性 `personnel.test.admin-url` 等），本机没有配，
装配已经跟着改了但没有实际验证。没跑：shop-app 全量、真 MySQL 的测试。

## §6 对账二 · 设计 → 实现

见提交记录。偏差：`StoreSnapshotReader`、`PersonnelFactsPortImpl` 原来按类型注入数据源与事务管理器，改成了点名平台句柄。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 第一步已实现并推送到 v2 |
