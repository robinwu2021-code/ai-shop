# TDD-支付服务-I0地基

状态：已实现（2026-10-25 · `e1caec533` + `c57a25d90`）
档位：2 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I0 · [完整设计方案](./TDD-支付服务-完整设计方案.md) D1–D3 · ADR-041 · ADR-042
关联需求：[PRD-支付域](../requirements/PRD-支付域.md)（新市场与开放接口部分见完整设计方案 §1 AC 表）
创建：2026-10-25 · 最后更新：2026-10-25

I0 不交付业务功能，只立后续 I1–I7 都要踩的地基：新模块、新迁移目录、新实体基类、共享枚举、守卫。
**完成后 CN 现有下单 / 支付 / 退款行为一个字节都不变。**

开工决策（开发计划 §0）按推荐项执行：P1 `pay-portal` + 进件归支付 + 回调前缀 `/pay/callback`；P2 独立迁移目录 `db/pay`；
P3 取消主体级默认号；P4 Resilience4j；P5 请求记录全记；P6 本机 MySQL 9.7。P3–P5 在 I2 / I4 才落代码。

## §0 对账一 · 需求 → 设计

| AC | 验收标准（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 一个继承 `PayBaseEntity` 的支付新实体 When 插入 Then 审计四列被填、**没有 `tenant_no` 列可写** | `ai.neargo.shop.pay.entity.PayBaseEntity` |
| AC2 | Given 支付新表的迁移放在 `db/pay` When 应用启动 Then 由独立的 Flyway 执行、历史记在 `pay_flyway_history`、排在平台迁移之后，**平台 Flyway 照常执行**（不被 `@ConditionalOnMissingBean(Flyway)` 关掉） | `PayDataSourceConfig#payFlyway`（shop-app）· `pay-domain/src/main/resources/db/pay/` |
| AC3 | Given `pay-portal` 模块 When 主应用启动 Then 它被内嵌装配；它的 controller 只能住 `ai.neargo.shop.pay.api.<入口>`，类级映射只能落在该入口的前缀下 | `pay-portal` · `PortalFace` · `PortalRouteRules` |
| AC4 | Given 支付领域与通道模块 When 出现任何 Web 映射注解 Then 守卫红（去掉旧版「文件里含 `/internal` 字样即放行」的口子，R27） | `PayHasNoControllerTest` |
| AC5 | Given 独立进程 `pay-svc` When 启动 Then 它的每个 HTTP 映射都在 `/internal/**` 下（按运行时解析后的路径判，不按源码文本） | `PayApplicationBootTest#onlyInternalRoutes` |
| AC6 | Given 支付模块的 MyBatis Mapper When 不在 `ai.neargo.shop.pay.mapper..` Then 守卫红（只有该包被 `paySqlSessionFactory` 扫描，放错包会连到平台连接池） | `PayMapperPackageTest` |
| AC7 | Given 支付模块引用一个**只在支付之外实现**的 spi Port When 它不在 `known-pay-reverse-deps.txt` 或次数超出 Then 守卫红（扫全部 spi Port，R28） | `PayReverseDependencyBudgetTest` |
| AC8 | Given 资金路径 DIRECT / AGGREGATED When 积分与后续支付单使用 Then 只有一份定义 `ai.neargo.shop.spi.pay.FundsPath` | `shop-base` spi |
| AC9 | Given 支付单、退款、记账凭证 When I3 起开始编号 Then 单号类型已登记、前缀不与现有 33 种冲突 | `DocumentType.PAY_ORDER / PAY_REFUND / LEDGER_VOUCHER` |
| AC10 | Given I0 全部改动 When 全量测试 Then 结果不劣于[开发计划 §10.2](./开发计划-支付服务.md) 基线；支付三模块全绿 | 全量 `mvn test` |

**孤立项**：无。原计划 I0 的「独立形态启动类」与「`ControllerNamingTest` 登记 `Open` / `Terminal` / `Checkout` 前缀」见 §6 偏差说明。

## §1 现状与影响面

- **支付模块**：`backend/pay/{pay-channel,pay-domain,pay-svc}`；`pay-domain` 零 controller，`pay-svc` 有 36 个 `/internal` 端点。
- **可复用**：仓库自有 `ai.neargo.shop.common.BaseEntity`（含 `tenantNo`）；单号机制 `ai.neargo.shop.numbering.DocumentType`（已有 33 种、前缀 2–3 位大写唯一）；
  进销存的独立 Flyway 写法（`InventoryDataSourceConfig#invFlyway`，返回非 `Flyway` 类型的 bean）。
- **会被改到的**：
  - 积分 5 个主代码文件 + 11 个测试改引用 `FundsPath`（只换类型来源，行为不变）；
  - `ArchitectureTest` 的「领域不得碰 Web 运行时」规则加 `pay.api..` 豁免（ADR-042 §2）；
  - `PayHasNoControllerTest`、`PayReverseDependencyBudgetTest`、`known-pay-reverse-deps.txt` 重写或扩充；
  - `shop-app/pom.xml`、`backend/pay/pom.xml` 增加模块与依赖。
- **明确不受影响**：现有 496 条平台迁移、`flyway_schema_history`、全部端点、权限码、i18n 词条、对外 JSON。

## §2 方案

### 契约变更
- 端点：无（`pay-portal` 只立骨架，零 controller）。
- 库表：新增 Flyway 历史表 `pay_flyway_history`（由 Flyway 自建）；`db/pay` 目录今天为空。
- 配置项：`shop.pay.flyway.enabled`（默认 `true`）、`shop.pay.flyway.locations`（默认 `classpath:db/pay`）；H2 测试 profile 置 `false`。
- 权限码 / i18n：无。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-base/.../spi/pay/FundsPath.java` | 资金路径唯一定义 |
| 修改 | `pay-domain/.../points/port/PointsFundsGateway.java` 等 5 个 · 测试 11 个 | 删嵌套枚举，改引 spi |
| 新增 | `pay-domain/.../pay/entity/PayBaseEntity.java` | 无 `tenantNo` 的审计基类 |
| 修改 | `shop-app/.../config/PayDataSourceConfig.java` | 加 `payFlyway`：独立 Flyway，返回 `PayMigrated` 记录，排在平台迁移之后；`paySqlSessionFactory` 依赖它 |
| 新增 | `shop-app/src/test/.../config/PayMigrationTest.java` · `src/test/resources/db/pay-fixture/V1__pay_fixture.sql` | 独立历史表、幂等、空目录、开关 |
| 新增 | `pay-domain/src/main/resources/db/pay/README.md` | 目录约定（命名、方言、禁 uca1400） |
| 新增 | `pay-domain/src/test/.../entity/PayBaseEntityTest.java` | 基类字段 = 模型标准列；v4 新表实体必须继承它 |
| 修改 | `shop-base/.../numbering/DocumentType.java` | 登记 `PAY_ORDER(PAO)`、`PAY_REFUND(PRF)`、`LEDGER_VOUCHER(LGV)` |
| 新增 | `backend/pay/pay-portal/pom.xml` | 新模块 |
| 新增 | `pay-portal/.../pay/api/PortalFace.java` | 五个入口与前缀 |
| 新增 | `pay-portal/.../pay/api/PortalRouteRules.java` | 判一个 controller 类是否落在本入口前缀下 |
| 新增 | `pay-portal/.../pay/api/auth/PortalAuthenticator.java` · `PortalPrincipal.java` | 鉴权接口（内嵌 / 独立两种实现，I5） |
| 新增 | `pay-portal/src/test/.../PortalRouteRulesTest.java` | 规则正反例 + 扫本模块真实 controller |
| 修改 | `backend/pay/pom.xml` · `shop-app/pom.xml` | 加模块、内嵌依赖 |
| 修改 | `shop-app/src/test/.../arch/PayHasNoControllerTest.java` | 按注解判、覆盖 pay-channel、去掉文本白名单 |
| 修改 | `pay-svc/src/test/.../PayApplicationBootTest.java` | 运行时映射全在 `/internal/**` |
| 新增 | `shop-app/src/test/.../arch/PayMapperPackageTest.java` | Mapper 只在 `pay.mapper..` |
| 修改 | `shop-app/src/test/.../arch/PayReverseDependencyBudgetTest.java` · `backend/known-pay-reverse-deps.txt` | 自动识别「只在支付外实现」的 spi Port |
| 修改 | `shop-app/src/test/.../arch/ArchitectureTest.java` | `pay.api..` 豁免 Web 运行时规则 |
| 修改 | `shop-app/src/test/resources/application-h2db.yml` | 关闭支付 Flyway（H2 用 `schema-test.sql`）；pay-svc 不跑迁移（共库，迁移由 shop-app 执行），无需改 |

### 关键接口

```java
public enum PortalFace {           // ai.neargo.shop.pay.api
    OPS("ops", "/pay-ops/v1"), OPEN("open", "/open/pay/v1"), TERMINAL("terminal", "/terminal/pay/v1"),
    BIZ("biz", "/biz/pay"), CHECKOUT("checkout", "/checkout/v1");
}
public interface PortalAuthenticator {   // ai.neargo.shop.pay.api.auth
    PortalFace face();
    Optional<PortalPrincipal> authenticate(HttpServletRequest request);
}
public abstract class PayBaseEntity {    // id / createdAt / createdBy / updatedAt / updatedBy / version / deleted，无 tenantNo
}
```

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 支付 Flyway 挂在 pay-domain 自带的配置类上 | 跟着模块走 | pay-svc 没有 Flyway 依赖、迁移关着（共库，由 shop-app 执行）；挂在 pay-domain 等于给独立进程加一个永远不用的迁移器 | ❌ 挂 shop-app 的 `PayDataSourceConfig`，迁移文件仍在 pay-domain |
| 支付迁移继续放 `db/migration`（平台历史） | 零新机制 | 与 10+ 个并行会话抢迁移号；独立部署时要拆历史 | ❌ |
| **`db/pay` + 独立历史表（同库）** | 号段独立；独立部署时整目录跟走；照抄进销存已验证的写法 | 多一个 Flyway 实例；H2 测试建表要另行覆盖（I1 起） | ✅ P2 |
| `PayBaseEntity` 放 `shop-store-mybatis` | 与 `BaseEntity` 并排 | 那是全仓公共层，放一个只属于支付的约定进去 | ❌ 放 `pay-domain` |
| 包名 `pay.portal..` | 与模块名一致 | 被 `controllersInDomainApiOrPortal` 拒 | ❌ 用 `pay.api..`（ADR-042） |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 新 `Flyway` bean 让平台 Flyway 退让 | 平台迁移静默不跑 | bean 类型是 `PayMigrated` 记录，不是 `Flyway`；`ArchitectureTest#noBeanMayExposeFlyway` 已守 |
| `pay.api..` 豁免被扩大 | 领域代码偷用 Servlet | 豁免判据写死子包名，`ArchitectureTest` 正反例覆盖 |
| `FundsPath` 搬家与积分会话冲突 | 合并冲突 | 只换 import / 限定名，不动逻辑；提交单独成一个 commit |
| 生产首次启动建 `pay_flyway_history` | 多一张表 | `baselineOnMigrate=true`、`baselineVersion=0`，空目录只建历史表 |

## §5 对账三 · 实现 → 需求（测试）

2026-10-25 本机（JDK 21.0.13、`neargo-build-parent:2.0.0-SNAPSHOT`）定向运行：

```
DocumentNumberFormatterTest      Tests run: 3,  Failures: 0
PayBaseEntityTest                Tests run: 3,  Failures: 0
PointsFundsAdapterTest           Tests run: 15, Failures: 0
PortalRouteRulesTest             Tests run: 6,  Failures: 0
PayApplicationBootTest           Tests run: 6,  Failures: 0
PayMigrationTest                 Tests run: 3,  Failures: 0
PayHasNoControllerTest           Tests run: 5,  Failures: 0
PayMapperPackageTest             Tests run: 2,  Failures: 0
PayReverseDependencyBudgetTest   Tests run: 4,  Failures: 0
ArchitectureTest                 Tests run: 20, Failures: 2   ← 均为基线既有（PricingFeeRuleServiceImpl 不在 .port、StoredValueFinanceAppServiceImpl 全限定名），与 I0 无关
```

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `PayBaseEntityTest#fieldsMatchStandardColumns` · `#newTableEntitiesExtendPayBaseEntity` · `#detectorFiresOnOffender` | ✅ | 给 `PayBaseEntity` 加回 `tenantNo` → `fieldsMatchStandardColumns` 红 ✅ |
| AC2 | `PayMigrationTest#appliesIntoOwnHistoryTable` · `#realLocationIsEmptyButValid` · `#disabledTouchesNothing` | ✅ | 历史表名改成 `flyway_schema_history` → 前两条红 ✅ |
| AC3 | `PortalRouteRulesTest`（6 条） | ✅ | `PortalFace#owns` 恒返回 true → `rejectsCrossFacePrefix`、`prefixIsSegmentAware` 红 ✅ |
| AC4 | `PayHasNoControllerTest#payDomainHasNoController` · `#detectorFiresOnCodeNotComments` | ✅ | 给 `StlFeeRule` 加 `@org.springframework...RestController` → **第一次没红**（判据只认短名），修判据后红 ✅ |
| AC5 | `PayApplicationBootTest#onlyInternalRoutes` | ✅ | 未做：要在 pay-svc 里加一个非 `/internal` 端点才能消融；断言读的是 Spring 实际注册的映射，「非空」已断言（防恒真） |
| AC6 | `PayMapperPackageTest#mappersLiveUnderPayMapper` · `#detectorRecognisesBothStyles` | ✅ | 判据正反例覆盖两种写法；真实 Mapper 计数 > 20 已断言 |
| AC7 | `PayReverseDependencyBudgetTest#everyReversePortIsBudgeted` | ✅ | 补登前即为红（列出 12 个未登记 Port）；补登后删 `UserInboxPort` 一行 → 红 ✅ |
| AC8 | `PointsFundsAdapterTest`（15 条）+ 积分场景测试（全量） | ✅ | 类型搬家，编译即验证；行为由原有测试覆盖 |
| AC9 | `DocumentNumberFormatterTest`（前缀不重复）+ `DocumentType` 静态校验 | ✅ | 前缀重复时枚举类加载即抛 `IllegalStateException`（既有机制） |
| AC10 | 全量 `mvn test` 与[开发计划 §10.2](./开发计划-支付服务.md) 基线比 | 见 §7 | — |

## §6 对账二 · 设计 → 实现

`git show --stat e1caec533 c57a25d90`（摘要；`e1caec533` 是 `FundsPath` 搬家，19 个文件只换 import）：

```
e1caec533 refactor(pay): FundsPath 提到 spi.pay，积分改引用
 .../main/java/ai/neargo/shop/spi/pay/FundsPath.java | 20 +++
 （积分主代码 5 个、pay-domain 测试 1 个、shop-app 控制器 1 个、shop-app 测试 11 个，各 1–4 行 import）
 19 files changed, 57 insertions(+), 33 deletions(-)

c57a25d90 feat(pay): I0 地基
 backend/known-pay-reverse-deps.txt                    |  17 +
 pay-domain/.../pay/entity/PayBaseEntity.java          |  49 +
 pay-domain/src/main/resources/db/pay/README.md        |  11 +
 pay-domain/src/test/.../entity/PayBaseEntityTest.java | 107 +
 pay-portal/pom.xml                                    |  35 +
 pay-portal/.../pay/api/PortalFace.java                |  56 +
 pay-portal/.../pay/api/PortalRouteRules.java          |  51 +
 pay-portal/.../pay/api/auth/PortalAuthenticator.java  |  20 +
 pay-portal/.../pay/api/auth/PortalPrincipal.java      |  25 +
 pay-portal/src/test/.../PortalRouteRulesTest.java     |  92 +
 pay-svc/src/test/.../PayApplicationBootTest.java      |  21 +
 backend/pay/pom.xml                                   |   4 +-
 backend/shop-app/pom.xml                              |   6 +
 shop-app/.../config/PayDataSourceConfig.java          |  46 +-
 shop-app/src/test/.../arch/ArchitectureTest.java      |  34 +-
 shop-app/src/test/.../arch/PayHasNoControllerTest.java|  91 +-
 shop-app/src/test/.../arch/PayMapperPackageTest.java  |  80 +
 shop-app/src/test/.../arch/PayReverseDependencyBudgetTest.java | 90 +
 shop-app/src/test/.../config/PayMigrationTest.java    |  76 +
 shop-app/src/test/resources/application-h2db.yml      |   4 +
 shop-app/src/test/resources/db/pay-fixture/V1__pay_fixture.sql | 5 +
 shop-base/.../numbering/DocumentType.java             |   3 +
 docs/technical/ADR/ADR-041 · ADR-042 · TDD-支付服务-I0地基.md
 docs/technical/reference/{glossary.json, 中英文对照, 依赖清单, 静态常量清单}.md
 29 files changed, 1159 insertions(+), 48 deletions(-)
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的文件 | `docs/technical/reference/` 四份生成物 —— `gen-glossary.mjs` 重新生成（新增模块、实体、枚举、常量），差异逐行核过只含本次内容 |
| TDD 列了、实际没动的文件 | 无 |

### 偏差说明

1. **支付 Flyway 挂在 shop-app 的 `PayDataSourceConfig`，不在 pay-domain 自带配置类**：pay-svc 没有 Flyway 依赖、迁移关着（与 shop-app 共库，迁移一直由 shop-app 执行）。迁移文件仍在 pay-domain 的 `db/pay`，随模块走（§3 已记）。
2. **包名 `pay.api..` 而非设计稿的 `pay.portal..`**：`ArchitectureTest#controllersInDomainApiOrPortal` 只认 `..api..` 与 `shop.portal..`（ADR-042 §2-2）。
3. **独立形态启动类推迟到 I5**：今天模块里零 controller、零 Remote 实现，放一个 `@SpringBootApplication` 进被 shop-app 扫描的 jar 反而会触发二次组件扫描。
4. **`ControllerNamingTest` 登记 `Open` / `Terminal` / `Checkout` 前缀**：仓库里没有这条守卫（命名规则只在 java.md §七）；入口归属已由 `PortalRouteRules` 按包 + 前缀判，命名守卫随 I5 第一个 controller 一起立。
5. **消融时发现 AC4 的判据漏认全限定注解**（`@org.springframework...RestController`），当场补上并加反例 —— 这正是消融这一列存在的理由。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-25 | 用户「按照规划实施」；P1–P6 按推荐项；开始实现 |
| 2026-10-25 | 已实现。定向守卫全绿（ArchitectureTest 2 条为基线既有）；全量 `mvn test` 3983 条、0 次 OOM（配合 `3b6d57a88`），失败 43 条均与 I0 无关：与基线相比新出现的 7 条在基线时因 OOM 未跑到，其中 6 条单独跑全绿（顺序串扰），`StoredValueOfflineCreditResultTest` 去掉 I0 改动照样红（储值存量）。扫描面：`backend/pay/**`、`shop-base/.../spi/**`、`shop-app` 主代码与测试 |
