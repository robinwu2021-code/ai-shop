# TDD-登录与账号对齐平台基础库

状态：第一步已实现（模块句柄，不改表）· 第二步（换表）未做，等平台基础库四个模块一起切
关联：[ADR-066](ADR/ADR-066-业务模块按配置选数据源.md) · [TDD-多数据源与发票打印对齐新库](TDD-多数据源与发票打印对齐新库.md)
设计依据：数据库设计方案（分支 `docs/db-split-plan`）的 `库/01-平台基础库.md`、`schema/ddl/01-n_platform.sql`
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

登录与账号是按业务顺序对齐的第一个模块，目标库是平台基础库 `n_platform`。勘查的结论是：
**这个模块不能单独换库**。它和商户与门店、门店设置与设备、员工与权限同在一个库，互相之间有同事务的写和直接联表；
换表、换库只能四个模块一起做。所以分两步：第一步把代码接到“平台基础库”的模块句柄上（本次已做，不改表）；
第二步四个模块都接好之后一次换表、绑定新库。

## 验收标准

仓库里没有登录与账号的需求文档，这里只写对齐工作本身的验收标准；对用户可见的登录行为不变。

- L1：Given 没有给平台模块绑定数据源 When 跑现有的登录、会话、账号测试 Then 全部通过，读写仍在主库。
- L2：Given C 端账号域（`ai.neargo.shop.user`）里有一处事务没点名平台模块的事务管理器 When 跑闸门 Then 失败并指出文件与行。
- L3：Given 给平台模块绑定了另一个数据源 When 取会话存储、登录日志、会话清理、支付侧会话校验、登录票据、C 端账号 Mapper 用的数据源 Then 都是平台模块的那一个。
- L4（第二步）：Given 平台模块绑到 `n_platform` When 跑 L1 的同一批测试 Then 全部通过。

不做：迁移线上数据、部署；改 61 上已建的表。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| L1 | 句柄未绑定时是主库 bean 的别名（`ModuleDataSourceRegistrar`） |
| L2 | `ModuleTxManagerConventionTest` 增加 `shop-core/.../shop/user` |
| L3 | `ModuleDataSources.PLATFORM`；`LoginAuditConfig#authJdbcClient`、`LoginTicketServiceImpl`、`AccountCredentialServiceImpl`、`ModuleMapperScanConfig` |
| L4 | 第二步，见 §2.3 |

孤立项：无。

## §1 现状与影响面

### 1.1 代码在哪

| 位置 | 类数 | 管什么 |
|---|---:|---|
| `shop-auth-store` | 13 | 三端共用的会话与登录日志存储，`JdbcClient` 手写 SQL，表名由端的配置给出 |
| `shop-base-auth` | 41 | 三端的令牌过滤器、会话存储的几种实现、只读的“新鲜读”数据源 |
| `shop-core` 的 `shop.user` | 59 | C 端账号域：账号、凭证、人档、登录票据、小程序会话 |
| `shop-gateway` | 约 30 | 三端过滤链装配、B 端登录与多身份、换号与注销、支付侧会话校验 |
| `shop-app` 的 `config` | 9 | 装配：会话存储、登录日志、令牌存储的选择、会话清理任务 |

会话校验是每个请求都走的路径：令牌 → 进程内缓存（C 端 5 分钟，B 端与运营端 60 秒）→ 没命中查一次会话表 → 节流写回最后活跃时间。
B 端在这之后还要无缓存地查账号、门店角色、门店、主体，每个请求都查。

### 1.2 为什么不能单独换库

| 类型 | 例子 |
|---|---|
| 同事务写两个模块的表 | B 端“选身份进入”同一个事务里锁票据、建会话、改 `mch_account`；注销同事务改 `usr_*`、`mch_account`、`mch_store`；停用员工同事务改 `mch_account` 并吊销两张会话表 |
| 直接联表 | 支付侧会话校验 `mch_session JOIN usr_account JOIN mch_account`；顾客报表 `mch_account JOIN usr_account JOIN mch_store`；建店时锁 `usr_account` |
| 每请求联查 | B 端身份解析每个请求读 `mch_account`、`mch_store_role`、`mch_role`、`mch_store`、`mch_entity` |

这些表在新设计里都在 `n_platform`，同库之后上面的写法仍然成立。只有四个模块一起切过去，才不会出现“一半在新库一半在旧库”的中间状态。

### 1.3 切库之后才会断的地方（跨到别的库）

| # | 现在 | 位置 | 对方的库 |
|---|---|---|---|
| X1 | 登录时同事务认领会员 `mbr_member`、写裂变邀请 `mkt_fission_invite` | `AuthServiceImpl#login`、`#loginByWxPhone` | 会员营销库 |
| X2 | 换手机号触发人档合并，再认领会员 | `AccountCredentialServiceImpl#changePhone` | 会员营销库 |
| X3 | 下单事务里锁 `usr_account` 做限购 | `PurchaseLimitGuard` | 门店交易库锁平台基础库的行 |
| X4 | 顾客报表 `usr_person JOIN mbr_member` | `CustomerReportingServiceImpl` :100 | 会员营销库 |
| X5 | 按角色强制下线：读 `sys_role_member`，写 `ops_session` | `PermConfigServiceImpl#forceLogoutRole` | 基础能力库 |
| X6 | 停用运营账号同事务写审计 | `OpsServiceImpl#setStaffEnabled` | 基础能力库 |

第二步要逐处改成“各自提交 + 可重入”，做法同发票的 TDD §2.3。

## §2 方案

### 2.1 契约变更

第一步：没有。新增的只是模块名 `platform`，可以写 `shop.modules.platform.datasource=<名字>`，不写就是主库。

### 2.2 第一步（已做）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-store-mybatis/.../ModuleDataSources.java` | 增加模块 `platform` 与三个句柄常量。四个模块共用这一个句柄 |
| 修改 | `shop-app/.../config/LoginAuditConfig.java` | `authJdbcClient` 改从平台句柄取数据源。会话存储、登录日志、会话清理、支付侧会话校验都用它 |
| 修改 | `shop-core/.../user/service/impl/LoginTicketServiceImpl.java`、`AccountCredentialServiceImpl.java` | 手写 SQL 与事务改用平台句柄 |
| 修改 | `shop-core/.../shop/user` 下 6 个类的 19 处事务 | 点名 `platformTxManager` |
| 修改 | `shop-app/.../config/MybatisPlusConfig.java`、`ModuleMapperScanConfig.java` | `shop.user` 的 Mapper 从全局扫描排除，绑到平台句柄 |
| 修改 | `shop-app/.../arch/ModuleTxManagerConventionTest.java` | 守卫增加 `shop.user` |
| 修改 | 3 个测试里的构造调用 | 票据服务的构造参数从 `JdbcTemplate` 改成了数据源 |

没有接到句柄上的（留给后面的模块或第二步）：

- 三端按端配置的会话库（`shop.gateway.<端>.datasource`，ADR-061）：仍然优先于平台句柄，没配时落到平台句柄。两套并存，第二步要定留哪一套。
- 商城运营会话（`MallOperatorSessionConfiguration`）：它校验的是主库的“新鲜读”数据源，仍直接用主库。
- B 端账号 `mch_account`、运营账号 `sys_ops_staff` 的访问：属于员工与权限、运营后台，做到那两个模块时接。
- `shop-gateway` 里 B 端登录与多身份的事务：同上。

### 2.3 第二步（未做）：12 张表怎么来

| 新表 | 来自 | 做法 |
|---|---|---|
| `usr_person`、`usr_account`、`usr_identity` | 同名 | 列对得上，原样切；状态值要换（见 §2.4） |
| `usr_login_log`、`mch_login_log` | 同名 | 原样切 |
| `usr_session` | 同名 ＋ `usr_mp_session`（小程序的 openid 并成一列） | 发会话的接口要多带一个 openid；支付取付款人 openid 的路径跟着改 |
| `mch_session` | 同名 ＋ 支付终端上的收银员会话 | 新表注释写主体类型恒为商户，而代码里老板的 B 端会话是用户主体，要对 |
| `auth_ticket` | `usr_login_ticket` ＋ 会员码票据 ＋ 收银 PIN 找回 ＋ 美业到店验证 | 四张合一，涉及会员、收银、美业插件三处代码；用途取值与长度都要重排 |
| `usr_guest_session` | 餐饮的顾客会话 ＋ 身份授权 ＋ 桌码凭证 | 三张合一，代码在餐饮插件里 |
| `agr_agreement_version`、`agr_agreement_consent` | 新建 | 现在只有票据上的一个“已同意”布尔值和入驻申请上的同意时间，要补写入 |
| `mch_trusted_device` | 新建 | 现在没有这个功能，表先空着 |

### 2.4 字段草案里发现的问题（只记录，未改任何库）

**会话表的唯一性与非空被降级，这是热路径上的硬伤**

| 表 | 问题 | 代码怎么依赖 |
|---|---|---|
| `usr_session`、`mch_session` | 令牌摘要的唯一键降成了普通索引；令牌摘要、用户号、签发与过期时间都变成可空 | 按摘要查会话要求至多一行，查到两行直接抛错；B 端“选身份进入”靠按摘要加行锁来串行 |
| `auth_ticket` | 票据摘要的唯一键降成普通索引；全部列可空 | 票据核销要求恰好更新一行 |
| `usr_guest_session` | 会话号、令牌摘要两个唯一键降成普通索引；排序规则从区分大小写变成不区分 | 令牌摘要按值精确匹配 |

同一份建表语句里 `ops_session` 保留了非空与唯一键，所以上面几处更像是生成时出的错，建议设计那边直接修。

**其他**

| 项 | 问题 |
|---|---|
| 状态超长 | `usr_account.status`、`usr_person.status` 的 `DEREGISTERED`（12 位）放不下。新表注释给的是 `ACTIVE / DISABLED / CLOSED`，与代码的 `NORMAL / BANNED / DEREGISTERED` 是两套，要定换值表 |
| 票据用途 | `CUSTOMER_REFERENCE`、`CUSTOMER_SELECTION`（18 位）放不下 `varchar(16)`，后者在新取值里没有对应项 |
| 凭证类型 | 新表注释里没有 `EMAIL`、`PASSWORD` |
| 吊销原因 | 新取值有 `KICKED`、`PWD_CHANGED`、`EXPIRED`，代码实际只写过 `LOGOUT`、`DISABLED` |
| 丢了默认值 | `tenant_no`（原默认 `MAIN`）、`usr_account.status`、`usr_person.status`、会话的主体类型。租户号是靠库默认值填的，没有默认值插入会失败 |
| 长度 64 → 32 | 用户号、人档号、小区号、主体号 |
| 票据合并后没有落点的列 | 会员码票据的签发人与范围、PIN 找回的挑战号与重置摘要、到店验证的验证码与唯一键 |
| 票据的身份列表 | 注释写“JSON 数组”，代码存的是逗号分隔 |
| 账号变更记录 `usr_account_change_log` | 新库没有这张表（去向应是统一审计） |
| 只读的“新鲜读”连接 | 现在挂在主库数据源上（最多 2 个连接、500 毫秒超时），平台模块绑到新库后要在新数据源上重建 |

### 2.5 第二步的前置条件

1. 商户与门店、门店设置与设备、员工与权限三个模块做完各自的第一步（接到平台句柄）。
2. §1.2 之外的越界访问清掉：别的库的模块不再直接查 `usr_*`、`mch_*`。
3. §2.4 的唯一键与默认值由设计修掉，或像发票那样先用补充脚本在本机测试库补上。
4. X1–X6 改写完。

## §3 选型

| 方案 | 结论 |
|---|---|
| A. 登录与账号单独换到新库 | ❌ 与 `mch_account`、`mch_store` 的同事务写和联表立刻断，每个请求的身份解析要跨库 |
| B. 四个模块共用一个句柄，先接句柄、后一起换表 | ✅ 每一步提交后都能跑；换库那天只是改一条绑定 |
| C. 在主库上用迁移把旧表改成新结构，再整库搬 | ❌ 要动已上线的主库结构，与“新库已建好、旧库照常在用”的前提相反 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 会话表没有唯一键 | 重复令牌摘要时每个请求报错；串行化失效 | 第二步之前必须补上，见 §2.4 |
| 平台库连接池不够 | B 端每个请求无缓存地查五张表 | 第二步按现有主库的连接占用单独定池大小 |
| 两套“会话在哪个库”的配置并存 | 踢人轮询与会话存储指到不同的库时，踢了人还能用 | 第二步定留一套；在那之前不要同时配两套 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| L1 | 现有的登录、会话、账号测试（清单见下） | ✅ |
| L2 | `ModuleTxManagerConventionTest#module_transactions_name_their_own_manager` | ✅ |
| L3 | `ModuleDataSourceRegistrarTest#bound_module_reads_and_writes_its_own_database`（注册器对任何模块一视同仁）；装配点是读代码确认的，没有单独的测试 | 部分 |
| L4 | 第二步 | — |

输出（2026-11-26，H2，`mvn -o -pl shop-app -am test -Dtest=…`，BUILD SUCCESS，全部 0 失败 0 错误）：

```
会话与日志存储：SessionDaoTest 11、LoginLogDaoTest 5、ThreeRealmIsolationTest 5、DbTokenStoreTest 17
C 端账号：IdentityLoaderTest 8、ProfileAndPasswordTest 9、EmailOtpAuthTest 18、TestIdentityServiceTest 4
场景：DbSessionFlowTest 7、StagedSessionCutoverTest（两组）3、LoginFailureAuditFlowTest 4、
      IdentityUnificationFlowTest 5、DeregisterFlowTest 4、WxPhoneLoginFlowTest 8、SwitchToMerchantTest 9、
      BizAccountFlowTest 11、MultiEntityIdentityFlowTest 11、PersonBindFlowTest 10、PhoneBindFlowTest 4、
      ProfileEndpointsFlowTest 7、AutomationLoginFlowTest 5、OtpRateLimitFlowTest 6、OpsLoginProtectionFlowTest 6
支付侧会话校验：PayBizSessionVerifierTest 4
守卫：ModuleTxManagerConventionTest 2、MapperSmokeTest 2、ArchitectureTest 27（跳过 1）、
      SessionTablesAreSelfContainedTest 4、PoolIsolationAfterA7Test 4、ConditionalBeanWiringTest、InventoryDisabledContextTest
```

没跑：真 MySQL 的登录测试（`BizLoginStoreNativeMysqlTest`、`BizIdentityNativeMysqlTest` 等，要本机模板库）、shop-app 全量。

## §6 对账二 · 设计 → 实现

见提交记录；偏差说明：L3 没有写“平台模块绑到第二个库后会话存储真的落在那个库”的端到端测试，
因为那需要第二个库里有全部会话与账号表，等第二步建测试库时一起做。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 用户要求按业务顺序从登录做起，设计写完直接做；第一步已实现 |
