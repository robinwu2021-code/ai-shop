# TDD-网关层与统一权限-开发计划

状态：已确认（用户 2026-11-18 确认；尚未开工）
关联需求：[TDD-网关层与统一权限-五层架构对齐](TDD-网关层与统一权限-五层架构对齐.md)（已确认）· [ADR-060](ADR/ADR-060-网关层独立模块与鉴权不进路由网关.md) · [ADR-061](ADR/ADR-061-三端认证与权限各自一套及权限库独立.md)
创建：2026-11-18 · 最后更新：2026-11-18

---

## 一句话

第一批分五个里程碑，每个里程碑一个可单独合并、可单独回退的 PR：先立护栏，再纯搬文件，再换成基类装配，再做每端隔离，最后上运营端的链接入库与提前判权。前四个里程碑不改变任何对外行为，用同一份端点快照逐个证明。

## §0 对账一 · 方案 → 计划

| 方案里的期次 | 本计划的里程碑 | 细到什么程度 |
|---|---|---|
| P 模块独立与每端隔离 | M0 护栏、M1 搬迁、M2 基类装配、M3 每端隔离 | 任务级 |
| D1 运营端链接入库与提前判权 | M4 | 任务级 |
| D2、D3、F、I1–I3、S、后 | §2.6 | 只列进入条件与主要工作；开工前各补一份任务级计划 |

**孤立项**

- 方案里有、本计划第一批不做的：透传、接口资源页、B 端与 C 端的提前判权、权限库搬迁、签名与守卫。都在 §2.6。
- 本计划有、方案里没有的：M0 的端点快照、M4 的「先观察后拦截」开关。两者都是为了让改动可验证、可回退，不改变方案。

## §1 现状与影响面：开工前核对的结果

方案定稿时留了三点没核实。2026-11-18 在最新的 main 上核对过了，其中第一点**改变了方案里的一处做法**。

| # | 核对的问题 | 结果 | 对计划的影响 |
|---|---|---|---|
| 1 | `shop-base-auth` 里哪些类能搬进网关模块 | **都不搬。** 令牌过滤器、`PermChecker`、令牌存储的各个实现，被留在原处的类（`LoginUser`、`GlobalExceptionHandler`、`BizContextFilter` 等）、`shop-core` 的两个运营端入口、以及餐饮和美业的测试引用：测试里 `MemoryTokenStore` 82 处、`MerchantTokenAuthFilter` 22 处、`PermChecker` 16 处 | 方案 §2.19「搬约 17 个类」取消，这些类留在 `shop-base-auth`，网关模块直接用。要改的两处原地改：`RedisTokenStore` 的键前缀、`PermChecker` 记录通过的权限码 |
| 2 | 出口包与桥接包是否依赖按部署形态切换的构建配置 | 不依赖。`shop-app` 的构建配置只增减行业插件的 web、core、channel 模块；主代码在不带任何配置时就能编译 | 这些构建配置原样留在 `shop-app`；`shop-gateway` 的 pom 只需要 `shop-app` 现有 38 个常规依赖中的一部分，M1 编译时确定 |
| 3 | 桥接包与 `shop-app` 其余部分的装配关系 | 组件扫描与 Mapper 扫描的根都是 `ai.neargo.shop`。搬过去的类包名不变，仍被扫到，不需要改装配 | 新写的网关代码放在 `ai.neargo.shop.gateway` 下，同样自动被扫到，不必写自动装配登记 |

核对中另外发现的四件事，都进了任务清单：

| # | 发现 | 证据 | 处理 |
|---|---|---|---|
| 4 | 约 20 个脚本与清单把 `shop-app/src/main/java/...` 下的出口或桥接路径写死了 | `scripts/release/*.json` 10 份、`scripts/verify-*.mjs` 3 份、`scripts/lib/persistence-owners.json`、`packages/shared/tests` 2 份等 | M1 任务 1.6 |
| 5 | `shop-app` 有 16 个测试按源码路径读文件；`ArchitectureTest` 有两条规则按 `src/main/java` 路径扫描 | `ArchitectureTest.java` 第 259、723 行 | M1 任务 1.7。不改的话这些规则对搬走的类**静默失效** |
| 6 | `shop-app` 自己也有一个 `ai.neargo.shop.auth` 包（`automation`、`captcha` 两个子包），桥接和配置类都在用 | 目录清单 | 随桥接一起搬 |
| 7 | 桥接里有一处引用 `shop-app` 的配置包（`commercebridge` → `config`） | import 计数 | M1 任务 1.2 先切断 |

**会被改到的**：`shop-app` 的出口包、桥接包、`SecurityConfig`、`TokenStoreConfig`；`backend/pom.xml`；上表第 4、5 项涉及的脚本与测试。
**不受影响的**：所有 URL、权限码、表、令牌格式；`shop-base-auth` 的类位置；`shop-app` 的构建配置；nginx；支付与库存服务。

## §2 计划

### 2.0 通用约定

| 约定 | 防住什么 |
|---|---|
| 每个里程碑从最新 main 开分支，一个 PR；合并后再开下一个 | 一个大分支挂两周，与其他会话的改动反复冲突 |
| 搬文件的提交只做 `git mv`，不夹带任何内容修改；改内容另起提交 | 评审时分不清哪些是搬、哪些是改；`git log --follow` 断掉 |
| M1 的搬迁提交在同步 main 之后一次做完、当天合并；合并前在共享目录里知会其他会话 | 660 个文件的路径变化与进行中的分支大面积冲突。搬的文件内容不变，其他分支合并时 git 能按重命名自动对上 |
| 每个里程碑结束跑完整的 pre-push 闸门，并在本文 §5 贴真实输出 | 「应该没问题」 |
| M0–M3 的验收标准是同一句话：端点快照与认证链快照和 M0 的基线逐字相同 | 重构顺手改了行为 |

### 2.1 M0 护栏（不动产品代码）

| # | 任务 | 产出 | 验证 |
|---|---|---|---|
| 0.1 | 记录基线：在最新 main 上跑架构测试、三端的端点权限测试、认证相关测试，存下通过数 | 本文 §5 的基线一栏 | — |
| 0.2 | 端点快照测试：启动测试上下文，从 Spring MVC 取全部处理方法，把「方法、地址、处理类与方法、`@PreAuthorize` 原文」排序后落盘；给了基线目录就逐行比对 | `shop-app` 测试 `EndpointSnapshotTest`、`GatewaySnapshot` | 改掉基线里一行，测试变红 |
| 0.3 | 认证链快照测试：对每条 `SecurityFilterChain`，记录匹配条件与过滤器类名的顺序；另对三端、支付运营面、机器入口各取一个地址，记录不带令牌与带各端令牌时的状态 | `SecurityChainSnapshotTest` | 同上 |
| 0.4 | 列出写死路径的脚本、清单与测试的完整名单（§1 第 4、5 项），逐个标注改法 | 本文 §2.2 任务 1.6、1.7 的清单 | — |

出口：三份快照文件能稳定生成，基线数字记下。

**相对初稿的偏差：快照不入库，基线取自合并基点。** 初稿写的是把快照文件提交进仓库、测试断言与它相同。实现时改了：端点每天都在增减，入库的快照会让每个加端点的人都来改它，而它要回答的只是「这一个重构分支有没有改变行为」。现在的用法是：

```bash
# 在重构分支的合并基点上跑一遍，把产物留下
mvn -pl shop-app -am test -Dtest='EndpointSnapshotTest,SecurityChainSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false
cp -r shop-app/target/gateway-snapshot /tmp/gateway-baseline
# 在重构分支上带着基线再跑一遍
mvn -pl shop-app -am test -Dtest='EndpointSnapshotTest,SecurityChainSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dgateway.snapshot.baseline=/tmp/gateway-baseline
```

不带基线参数时，这两个测试只落盘并做几条自检（取到了端点、三端的令牌过滤器都在、B 端与运营端不带令牌被拒），不会因为别人加了端点而变红。

### 2.2 M1 建模块与搬迁（纯搬，行为不变）

| # | 任务 | 涉及 | 验证 |
|---|---|---|---|
| 1.1 | 新建 `backend/shop-gateway`：pom、空的 `ai.neargo.shop.gateway` 包；加进 `backend/pom.xml`，排在 `shop-app` 之前；`shop-app` 依赖它 | 2 个 pom + 1 个新 pom | 全量编译 |
| 1.2 | 切断桥接对 `shop-app` 的反向引用：`commercebridge` → `config` 1 处（把被引用的那个类一起搬，或改为注入接口，读代码后定） | 1–2 个文件 | 编译 |
| 1.3 | `git mv` 桥接包：`payclient` `paybridge` `invbridge` `commercebridge` `reportbridge` `merchantbridge` `printbridge` `printing` `promotion` `points` `logisticsbridge` `svc`，以及 `auth/automation`、`auth/captcha` | 约 470 个文件 | 编译 |
| 1.4 | `git mv` 出口包：`portal/mp` `portal/biz` `portal/ops`；`portal` 下的 `common` `config` `device` `link` `port` 与 4 个打印相关类，按「谁在用」逐个决定搬或留 | 约 180 个文件 | 编译 |
| 1.5 | 确定 `shop-gateway` 的依赖：从 `shop-app` 的 38 个常规依赖里，挑出搬过来的代码实际需要的 | 1 个 pom | `mvn dependency:analyze` 无未声明的直接依赖 |
| 1.6 | 更新写死路径的脚本与清单（约 20 个） | `scripts/`、`packages/shared/tests` | 各脚本自带的检查、`packages/shared` 的 vitest |
| 1.7 | 更新按路径读源码的 16 个测试；`ArchitectureTest` 的两条路径规则把 `shop-gateway` 的源码目录也扫进去 | `shop-app/src/test` | 架构测试通过；故意在 `shop-gateway` 里放一个违规类，规则能抓到 |
| 1.8 | 架构测试加三条：`shop-gateway` 不依赖 `shop-app`；`portal.mp`、`portal.biz`、`portal.ops` 互不引用；桥接包不引用 `portal` | `ArchitectureTest` | 违规样例能抓到。若现状已有违规，记进基线文件并只许变短 |
| 1.9 | 留在 `shop-app` 的：启动类、`config`、`portal/internal`、`sessionjob`、迁移脚本、全部测试 | — | — |

出口：端点快照与认证链快照和 M0 逐字相同；pre-push 全绿；本机启动后三端各登录一次、各打一个需要权限的接口。

**任务 1.6、1.7 的完整名单**（M0 任务 0.4 的产出，2026-11-18 在 main 上清点）：

| 类别 | 数量 | 名单 | 改法 |
|---|---|---|---|
| 发布清单 | 9 | `scripts/release/` 下 `brand-p1` 至 `brand-p6`、`points-bc`、`points-ops` 的 `.json`，以及 `scripts/lib/persistence-owners.json` | 把 `shop-app/src/main/java/` 换成 `shop-gateway/src/main/java/`，只换命中要搬的包的那些行（共 97 行） |
| 校验与生成脚本 | 8 | `scripts/verify-manual-service-admission.mjs`、`verify-manual-counter-source.mjs`、`verify-personnel-operator.mjs`、`gen-beauty-api-design.py`、`release/points-jobs.mjs`、`release/points-bc.mjs`、`release/points-jobs.test.mjs`、`tests/test-document-number-rollout.py`、`tests/legacy-coupon-inventory.test.mjs` | 同上；改完各跑一遍自带的检查 |
| 前端共享测试 | 2 | `packages/shared/tests/entity-alignment.test.ts`、`stock-shared-source-label.test.ts` | 同上；跑 `packages/shared` 的 vitest |
| 读 Java 源码路径的后端测试 | 24 | `shop-app` 的 `arch/` 下 19 个（`ArchitectureTest`、`BizEndpointPermTest`、`OpsEndpointPermTest`、`ControllerProfileTest`、`FunctionPointPermAlignmentTest`、`BizResourceAuthorization`、`BackendI18nParityTest`、`BizKeyConventionTest`、`ClientIpSingleSourceTest`、`CrossDomainWriteConventionTest`、`LoginAuditWiringTest`、`OrphanModuleTest`、`OutboxWiringTest`、`PayAuditTrailTest`、`PayCallerConstructionTest`、`PayHasNoControllerTest`、`ScheduledJobConventionTest`、`SessionTablesAreSelfContainedTest`、`TableHasProducerTest`）；`shop-app` 其他目录 4 个（`PermSeedParityE2eTest`、`InvMirrorEventCoverageTest`、`ExecutionBeanNamesTest`、`PricingConfigServiceTest`）；商城插件 1 个（`CommerceArchitectureTest`） | 逐个读：扫的目录里有要搬的包，就把 `shop-gateway` 的源码目录加进扫描面；只读某个固定文件的，改路径 |
| 只读资源路径的测试 | 约 50 | 迁移脚本、种子等 | 不用改：资源不搬 |

读 Java 源码路径的那 24 个是重点：它们多数是「扫一遍源码找违规」的守卫，路径不改不会报错，只会少扫一块。每改一个，都用「在 `shop-gateway` 里放一个违规样例看能不能抓到」确认。

风险最大的是任务 1.8 的第二、三条：现状很可能已有违规（比如某个 B 端入口引用了运营端的类）。处理方式是按仓库已有的棘轮做法记基线，不在这个里程碑里修。

### 2.3 M2 基类装配（行为不变）

| # | 任务 | 新写的类 | 验证 |
|---|---|---|---|
| 2.1 | 配置：`GatewayProperties`、`GatewayEndProperties`。这一步只有 `enabled` 与 `public-paths` 两项，其余在 M3 加 | 2 | 属性绑定单测 |
| 2.2 | 端的抽象：`AbstractGatewayEnd`、`GatewayEndSpec` | 2 | — |
| 2.3 | 安全框架接入：`GatewaySecurityAdapter`、`SpringSecurityAdapter`、`GatewayChainBuilder`；CORS 配置从 `SecurityConfig` 搬出 | 3 + 1 搬 | — |
| 2.4 | 三个端子类：`MpGatewayEnd`、`BizGatewayEnd`、`OpsGatewayEnd`。把 `SecurityConfig` 里 `consumerChain`、`merchantChain`、`operatorChain`、`payOpsChain`、`publicChain` 的内容迁进来；`requestMatchers(...)` 的放行清单变成各端的 `public-paths` 默认值 | 3 | 认证链快照不变 |
| 2.5 | `SecurityConfig` 只剩支付接入的 4 条链，改名为 `PayPortalSecurityConfig`，留在 `shop-app` | 0 | 认证链快照不变 |
| 2.6 | 开关：`shop.gateway.<端>.enabled`；`api` 配置段里开 `mp` 与 `biz`，`ops` 配置段里开 `ops`，与今天按配置分进程的结果一致 | 0 | 只开一端的上下文测试：另外两端的地址 404（方案 AC2） |
| 2.7 | 架构测试加规则：基类里固定流程的方法是 `final`；继承不超过两层；`gateway.core` 不引用 `gateway.{mp,biz,ops}` | 0 | 违规样例能抓到（方案 AC33 的一部分） |

出口：两份快照不变；`SecurityConfig` 原来的 9 条链在新装配下一条不多、一条不少、顺序相同。

这一步判定器还没接入：判权仍是今天的 `anyRequest().authenticated()` 加方法注解。`AbstractAccessDecider` 在 M4 才出现，避免 M2 里放一个没人调用的基类。

### 2.4 M3 每端隔离（默认配置下行为不变）

| # | 任务 | 新写的类 | 验证 |
|---|---|---|---|
| 3.1 | `GatewayEndProperties` 加 `datasource`、`redis`、`session`；新键以现有键为默认值 | 0 | 不配任何新键时，生效值与今天相同 |
| 3.2 | 每端运行时：`GatewayEndRuntime`、`GatewayEndRedis`；数据源与会话工厂 `IamDataSources`（没配独立数据源时落回主库） | 3 | 单测 |
| 3.3 | `RedisTokenStore` 的键前缀从常量改为构造参数，**默认值仍是 `shop:session:`** | 原地改 | 现有会话相关测试不变 |
| 3.4 | `TokenStoreConfig` 从「全局选一种」改为「每端各选一种」，搬到 `gateway.core.session`；`RealmRoutingTokenStore` 仍按令牌前缀分发 | 1 搬改 | 三端各配不同存储方式的上下文测试 |
| 3.5 | `GatewayIsolationCheck`：`strict` 下三端的 Redis 或数据源重合则启动失败；默认 `relaxed` | 1 | 重合与不重合各一个用例（方案 AC26 前半） |
| 3.6 | `GatewayEndHealthIndicator`：每端一项 | 1 | 健康端点里出现三项 |
| 3.7 | 一端 Redis 不可用时只影响该端 | 0 | 把运营端的连接工厂指向不通的地址：`/ops` 回 503，`/mp`、`/biz` 正常（方案 AC26 后半） |

出口：默认配置下两份快照不变；`strict` 的用例通过。

**上线时要单独安排的一件事：** 某一端真的切到独立 Redis 或新的键前缀时，该端的存量会话全部失效，用户要重新登录。这不是代码问题，是切换动作；每端切换放在该端的低峰时段，切换说明写进发布交接。本里程碑合并本身不触发切换。

### 2.5 M4 运营端：链接入库与提前判权

| # | 任务 | 新写的类 | 验证 |
|---|---|---|---|
| 4.1 | 迁移：`db/gateway/ops` 下建 `sys_api_resource`、`sys_api_resource_point`；运营端权限库用独立的迁移目录与迁移历史表（仿库存的做法）。数据源没单独配时，表建在主库 | 迁移脚本 1 份 + 装配 1 个类 | 在本机 MySQL 9 上从空库建表 |
| 4.2 | 资源模型：`ApiResource`、`Access`、`ResourceSource`、`PermRule` | 4 | — |
| 4.3 | `PermRuleParser`：把 `@PreAuthorize` 解析成权限码的与或式；不认识的节点标为「留给方法判」 | 1 | 单测覆盖现有四种写法（单码 558、与 8、或 3、与或混合 3）和那 1 个用到方法参数的表达式 |
| 4.4 | 链接来源：`ResourceProvider`、`LocalScanResourceProvider` | 2 | 扫出的条数等于端点快照里 `/ops` 与 `/pay-ops` 的条数 |
| 4.5 | 索引：`ResourceIndex`、`ResourceIndexBuilder`；启动检查（同端重复、权限码不在功能点表） | 2 | 重复与缺码各一个失败用例 |
| 4.6 | 存取与同步：`ResourceStore`（接口）、`ApiResourceStore`、两个实体与 Mapper、`ResourceSyncRunner`（只增改、不删、内容不变不写、失败只告警） | 6 | 连续启动两次，第二次零写入 |
| 4.7 | 快照：`ResourceSnapshot`，60 秒过期；第一期只承载 `status`（停用） | 1 | 改库里一行的 `status`，过期后生效；改其他列不影响判权（方案 AC25） |
| 4.8 | 请求上下文与定位：`GatewayExchange`、`GatewayRouteFilter`；查不到的地址交给后面照旧处理，暂不直接回 404 | 2 | — |
| 4.9 | 判定：`AccessDecider`、`Decision`、`AbstractAccessDecider`、`OpsAccessDecider`、`GatewayAuthorizationManager`、`GatewayErrorWriter`；接进 `SpringSecurityAdapter`，只对运营端生效 | 6 | 见 4.11 |
| 4.10 | `PermChecker` 把通过的权限码记到请求属性上（原地加一行） | 原地改 | 现有测试不变 |
| 4.11 | 开关 `shop.gateway.ops.precheck`：`off` / `observe` / `enforce`。`observe` 时照常放行，只在提前判定与方法注解的结论不一致时记一条告警 | 0 | 三种取值各一个链路测试 |
| 4.12 | 对账测试：索引里每行的权限式，等于该端点注解解析出的权限式（方案 AC21） | 测试 | 全部 641 个端点 |
| 4.13 | 行为测试：没权限且请求体不合法时得到 403 而不是参数错误，入口方法没执行（AC22）；缓存预热后定位与判权不查库（AC23） | 测试 | — |
| 4.14 | `OpsEndpointPermTest` 从逐行扫源码改为对索引断言 | 改测试 | 原有断言一条不少 |

出口与上线顺序：

1. 合并时默认 `off`，快照不变。
2. 166 上由 root 先建两张表，再发布，开 `observe`。
3. 观察期内不一致告警为零，再改 `enforce`。出现不一致就先查是索引错了还是注解错了，不带着不一致切 `enforce`。

「查不到的地址先不回 404」（任务 4.8）是有意保守：第一期索引只有运营端，等三端都建了索引、并且观察期确认索引是全的，再把查不到改成直接 404。

### 2.6 第一批之后（开工前各补任务级计划）

| 期 | 进入条件 | 主要工作 |
|---|---|---|
| D2 透传与接口资源页 | M4 在 166 上已 `enforce` | `GatewayInvokeFilter`、`AbstractServiceInvoker` 及两个子类；`StoredResourceProvider`；种子脚本与清单文件；运营端「接口资源」页及其后端；改 `ops-web/lib/nav.ts` 并重跑界面清单 |
| D3 B 端同构 | D2 完成 | `BizAccessDecider`（按当前门店判）；B 端的资源表与功能点表；清点 B 端落进「留给方法判」的表达式数量 |
| F 功能点按模块登记 | M4 完成；可与 D2 并行 | `FunctionModule` 及登记；行业权限码的声明搬回行业模块 |
| I1 运营端权限库独立 | D2 完成；库已由 root 建好 | 权限配置代码搬进 `gateway.iam`；读 `sys_ops_staff` 的 10 个文件改走目录接口；数据搬迁与回退方案 |
| I2 B 端权限库独立 | I1 稳定运行 | 改写 4 个文件里的 join；清点跨库写入的流程（入驻建店、邀请店员、账号注销）；数据搬迁与回退方案 |
| S 签名与守卫 | D2 完成 | `svc-client` 加约定与守卫两个包；出站签名；支付内部入口加验签开关，默认只比对。打开拒绝前另行确认 |
| I3 C 端权限库独立 | I2 稳定运行 | 拆 `usr_account`；数据搬迁与回退方案 |
| 后 | 有微服务需要自动同步时 | 清单接口与注解 |

### 2.7 规模

| 里程碑 | 新写的类 | 搬动的文件 | 改动的已有文件 | 是否改行为 |
|---|---|---|---|---|
| M0 | 2 个测试 | — | — | 否 |
| M1 | 0 | 约 650 | 约 40（脚本、清单、测试、3 个 pom） | 否 |
| M2 | 10 | 1 | `SecurityConfig`、`application.yml` | 否 |
| M3 | 6 | 1 | `RedisTokenStore`、`application.yml` | 默认否 |
| M4 | 约 24 + 迁移 | — | `PermChecker`、`OpsEndpointPermTest` | 开关打开后是 |

## §3 选型

| 问题 | 方案 | 结论 |
|---|---|---|
| 搬迁与基类装配要不要合成一个 PR | A 合并 · B 分开 | ✅ B。搬迁的 PR 有 650 个文件但零内容变化，评审靠快照；基类的 PR 文件少但全是逻辑。合在一起两头都看不清 |
| 提前判权怎么上线 | A 直接生效 · B 先观察后拦截 | ✅ B。索引是新东西，先证明它和 1300 多处注解的结论完全一致，再让它拦人 |
| `shop-base-auth` 的类搬不搬 | A 按方案搬约 17 个 · B 不搬 | ✅ B，理由见 §1 第 1 项。「新代码都在网关模块」不受影响；不搬的是下层代码和行业测试正在引用的现有类 |
| 第一期索引里查不到的地址 | A 直接 404 · B 交给后面照旧处理 | ✅ B，直到三端索引建全 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| M1 的大搬迁与其他会话进行中的分支冲突 | 别人合并时一片冲突 | 当天合并；提前知会；文件内容不变，git 能按重命名对上。真冲突只会出在同一天也改了同一个文件的地方 |
| 路径规则静默失效（§1 第 5 项） | 架构测试还是绿的，但已经不看搬走的类 | 任务 1.7 用「放一个违规类看能不能抓到」验证，不只看测试是否通过 |
| 任务 1.8 新规则在现状上就不成立 | M1 卡住 | 记基线，只许变短，不在 M1 里修 |
| 端点快照测试启动整个应用上下文，变慢或不稳定 | 闸门变慢 | 复用现有端点权限测试用的那个测试上下文；跑一次看耗时再决定是否进 pre-push |
| 索引与注解不一致 | `enforce` 后误拦或漏拦 | 任务 4.12 全量对账；`observe` 期零告警才切 |
| 独立 Redis 切换让存量会话失效 | 该端用户集体重新登录 | 与合并解耦；每端单独安排在低峰切 |
| 166 上建表要 root | 发布卡住 | 迁移脚本提前交给有权限的人执行；发布交接里单列 |
| 运营端权限库落回主库时，独立的迁移历史表与主迁移并存 | 两套迁移顺序混乱 | 独立目录、独立历史表、只管自己的两张表；库存已经是这样跑的 |

## §5 对账三 · 实现 → 方案（测试）

开工后逐栏填真实输出。

| 里程碑 | 对应方案的验收标准 | 测试 | 跑过 |
|---|---|---|---|
| M0 | — | `EndpointSnapshotTest`、`SecurityChainSnapshotTest`；基线数字 | ✅ 2026-11-18，见下 |
| M1 | AC9、AC7 的一部分 | 两份快照；`ArchitectureTest` 新增与扩展的规则；pre-push 的闸门脚本 | ✅ 2026-11-18，见下 |
| M2 | AC2、AC33 的一部分 | 两份快照；只开一端的上下文测试；架构测试的基类规则 | ✅ 2026-11-18，见下 |
| M3 | AC10、AC26 | `strict` 校验；键前缀；一端 Redis 不通的链路测试；两份快照 | ✅ 2026-11-18，见下 |
| M4 | AC21、AC22、AC23、AC25、AC15 | 索引覆盖；403 先于参数错误；三档开关；三端现有的端点权限测试原样通过 | ◐ 2026-11-18 完成不入库的那一半（M4a），见下 |

**M0 的真实输出**（2026-11-18，分支 `feat/gateway-m0-snapshots`，基于当天的 main；扫描面是默认构建下 `test` 配置装配的全部端点，不含餐饮、美业、商城内嵌这些构建配置下才有的行业入口）：

```
# 基线：架构、端点权限、认证相关测试
Tests run: 23, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.BizEndpointPermTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.ControllerProfileTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.DeploymentProfileTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.EndpointSnapshotTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.FunctionPointPermAlignmentTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.InternalEndpointTokenTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.MpEndpointAuthTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.OpsEndpointPermTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.RealmIsolationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.SecurityChainSnapshotTest
```

| 快照文件 | 行数 | 内容 |
|---|---|---|
| `endpoints.txt` | 1578 | `/biz` 612、`/ops` 550、`/internal` 192、`/mp` 174、`/pay-ops` 10，其余为公共、回调与支付入口；其中 1101 行带权限表达式 |
| `security-chains.txt` | 133 | 9 条链各自的匹配条件与过滤器顺序 |
| `security-probes.txt` | 20 | 五个前缀各一个地址 × 不带令牌与三端令牌。三端只认自己的令牌，别的端的令牌一律 401 |

| 验证 | 结果 |
|---|---|
| 拿第一次的产物当基线再跑一遍 | 通过：三份文件逐行相同，说明输出是稳定的 |
| 把基线里退款复核的权限码改掉 | `EndpointSnapshotTest` 失败，报出 `+ POST /ops/payments/refunds/{payRefundNo}/review … @perm.can('pay:refund:review')` |
| 把基线里 B 端链上两个过滤器调换顺序 | `SecurityChainSnapshotTest` 失败，报出「顺序不同，自第 9 行起」 |

**M1 的真实输出**（2026-11-18，分支 `feat/gateway-m1-module-move`，叠在 M0 之上）：

```
# 整个 arch 包 + 6 个受搬迁影响的测试类，M0（搬迁前）与 M1（搬迁后）逐类比对
M0: classes 62 run 199 fail 5 err 0 skip 2
M1: classes 62 run 201 fail 5 err 0 skip 2
唯一差异：
< Tests run: 23, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
> Tests run: 25, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
# 带着 M0 的快照当基线跑：
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.EndpointSnapshotTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.SecurityChainSnapshotTest
```

| 验证 | 结果 |
|---|---|
| 端点快照、认证链快照、探测结果 | 与 M0 基线逐行相同 |
| `git mv` 的提交 | 675 个文件，0 行增删 |
| 23 个 pre-push 闸门脚本，搬迁前后逐个对比 | 退出码全部相同 |
| 临时放两个违规样例（B 端入口引用运营端的类；桥接类引用 C 端入口并用全限定名写注解） | `ArchitectureTest` 恰好 3 条失败：`noFullyQualifiedReferences`、`portalEndsMustNotDependOnEachOther`、`bridgesMustNotDependOnPortals`；样例已删 |

搬迁前就红、与本次无关的（M0 与 M1 上完全相同）：`MediaRefCoverageTest` 1 条、`OrphanModuleTest` 1 条、`MallHostConfigurationTest` 1 条、`MallUntrackedHardwareTest` 2 条；闸门里的 SQL 方言（19 个存量文件）与运营端前端的 2 条守卫测试。

**M1 相对计划的偏差：**

| 计划 | 实际 | 原因 |
|---|---|---|
| 任务 1.2：先切断 `commercebridge` → `config` 的反向引用 | 编译时冒出两处，各搬一个类解决，没有改内容：`config/MallOperatorSessions`（一行的记录类）随商城桥接搬进 `shop-gateway`；`paybridge/PaymentCollectionExceptionHandler` 留在 `shop-app` | 后者同时引用一个 B 端入口和一个机器入口，只有 `shop-app` 两边都看得见。架构规则里把它列为桥接包的唯一例外 |
| 任务 1.4：`portal` 下其余子包逐个决定搬或留 | 除 `internal` 外全部搬走，多了一个计划里没列的 `portal/service` | 它们都只被三端出口使用 |
| 任务 1.5：挑出实际需要的依赖 | 从 28 个减到 16 个；另直接声明 `job-store` 与 `pdfbox` | Flyway、actuator、调度锁存储、密钥、通知与外部通道只有启动装配需要，留在 `shop-app`；`shop-app` 自己的依赖声明没动，运行时类路径不变 |
| 任务 1.7：24 个读源码路径的测试逐个改 | 多数不用改：它们本来就遍历 `backend/` 下所有模块。实际改了 5 个路径（`ArchitectureTest` 的全限定名规则、`BackendI18nParityTest`、`InvMirrorEventCoverageTest`、`PayHasNoControllerTest`、`PayDeploymentModePairingTest`），外加两处由路径改写脚本带过的（`BizResourceAuthorization` 38 处、`PayAuditTrailTest` 1 处） | — |
| 名单里没有的 | 7 处写死的**模块清单**加上了 `shop-gateway`：`PaymentLedgerCoverageTest` 的扫描模块；餐饮、美业独立服务「不得带上宿主模块」的 4 处清单；`StandalonePayFixture`；`scripts/verify-beauty-bm04-wire.mjs` | 这类清单不含路径，按路径找找不到。第一处不改会少扫 `shop-gateway`，其余不改会让独立服务悄悄带上宿主代码而不报错 |
| 任务 1.8：三条新规则，现状可能已有违规要记基线 | 两条新规则（三端出口互不引用；桥接包不引用出口），现状零违规，没有基线。「`shop-gateway` 不依赖 `shop-app`」不写规则 | 那是模块依赖方向，反过来 Maven 直接报循环依赖 |
| 历史任务记录 `.superpowers/sdd/**` | 其中被脚本读取的 `.json` 清单改了路径；叙述性的 `.md` 报告没有动 | 报告记录的是当时的状态 |

没有做的两项，留到合并前：本机启动后三端各登录一次的冒烟（快照里的探测已覆盖「各端只认自己的令牌」，但没有走真实登录）；餐饮、美业插件模块里改过的 3 个测试文件只在带对应构建配置时才编译，本次默认构建没有编到它们。

**M2 的真实输出**（2026-11-18，分支 `feat/gateway-m2-end-base`，叠在 M1 之上）：

```
# arch 包 + gateway 包 + 受影响的安全相关测试，带着 M0 的快照当基线
classes 71 run 243 fail 5 err 0 skip 5        # 5 条失败与 M0、M1 上完全相同，与本次无关
Tests run: 27, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.EndpointSnapshotTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.SecurityChainSnapshotTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.GatewayEndClosedTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.GatewayEndSwitchTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 2 -- in ai.neargo.shop.commercebridge.CommerceAccessSecurityFlowTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.payclient.PayBizOnboardingHttpTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.config.ClientVersionGateFilterTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.ClientVersionGateFlowTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.OpsStreamTest
```

| 验证 | 结果 |
|---|---|
| 端点快照、认证链快照（9 条链的顺序与每条链的过滤器顺序）、探测结果 | 与 M0 基线逐行相同 |
| 只开运营端：`shop.gateway.mp.enabled=false`、`shop.gateway.biz.enabled=false`，入口仍被装配 | C 端、B 端前缀下不带令牌、带本端有效令牌、打登录入口都是 404；运营端照常 401（方案 AC2） |
| 端开关的四种组合 | 没配跟部署面；入口在而开关关是「封住」；入口不在是「不装」；显式打开是「装」 |
| 23 个 pre-push 闸门脚本 | 与基线相比只少红一道（生成文档，文档分支上已修）；其余退出码相同 |
| 临时放三个违规样例（公共件里的基类引用 B 端的端子类、带一个非 `final` 的公开方法、子类之下再有子类） | `ArchitectureTest` 恰好 2 条失败，报出两条具体违规；样例已删 |

**快照在 M2 抓到的一处真实差异：** 第一次跑时，支付接入的四条链（渠道回调、开放接口、终端、收银台）少了 `CorsFilter`。原因是 Spring Security 在造每个 `HttpSecurity` 时，按「容器里有没有 `UrlBasedCorsConfigurationSource` 类型的 bean」决定是否默认加 CORS；原来那个 bean 的工厂方法返回类型写的是接口，能不能被认出来取决于它是否恰好先被创建。拆分前它和所有链在同一个配置类里、排在最前，拆分后创建顺序变了。已把返回类型改成具体类型，不再依赖创建顺序，四条链的过滤器与基线一致。

顺带看到一件拆分前就存在、这次没有动的事：渠道回调那条链的注释写的是「不开 CORS」，但它实际一直带着 `CorsFilter`（就是上面这个机制默认加的）。要不要真的去掉，留给支付侧决定。

**M2 相对计划的偏差：**

| 计划 | 实际 | 原因 |
|---|---|---|
| 任务 2.3：`GatewayChainBuilder` 单独一个类 | 并进 `SpringSecurityAdapter` | 它只有适配器一个使用者，单独成类只是多一层转发 |
| 任务 2.4：三个端子类 | 四个：另有 `PayOpsGatewayEnd` | 支付运营面是一条独立的链（前缀 `/pay-ops`、顺序 8、多一个支付守卫过滤器），与运营端共用开关。公共地址那条链没有令牌认证，不走端基类，单独是 `PublicGatewayChain` |
| 任务 2.5：`SecurityConfig` 改名为 `PayPortalSecurityConfig` | 照做；另加一个装配入口 `GatewaySecurityConfiguration` | 四个切片测试原来只列 `SecurityConfig.class` 就能拿到全部链，现在列这两个。端子类不带 `@Configuration`，只经装配入口引入，于是「只在 Web 应用里装」这一个条件对它们全部生效 |
| 任务 2.6：在 `application.yml` 的 `api`、`ops` 配置段里设开关 | 没有改 `application.yml`。开关是三态：没配时跟着部署面走；入口在而开关关时装一条只回 404 的链 | 切片测试只设部署面、不加载 `application.yml`，在它们那里开关键一个都没有。另外，入口还在时只撤掉认证链会让请求绕过认证，所以「关」必须是「封住」 |
| 任务 2.7：端子类只覆盖基类声明的方法 | 没有做成规则 | 每个端子类带一个登记认证链的 `@Bean` 方法，不是覆盖。另两条（流程方法 `final`、子类之下不再有子类）与「公共件不引用各端」已是规则 |
| 名单里没有的 | `config/ClientVersionGateFilter` 搬进 `shop-gateway`（包名不变）；`ArchitectureTest` 的顶层包名单登记了 `gateway` | 前者被 B 端的端子类直接创建；后者是现有规则「新开顶层包必须登记」要求的 |

新写的类 18 个（计划估 10 个）：配置属性 2、端开关与条件 3、端基类与描述 3、安全框架适配 3、CORS 与公共地址链 2（搬）、端子类 4、装配入口 1。

仍然没有做：本机启动后三端真实登录的冒烟。

**M3 的真实输出**（2026-11-18，分支 `feat/gateway-m3-end-isolation`，叠在 M2 之上）：

```
# arch、gateway、config、auth 四个包 + 受影响的安全相关测试，带着 M0 的快照当基线
classes 93 run 336 fail 5 err 0 skip 9        # 5 条失败与 M0 起完全相同，与本次无关
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.GatewayEndIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.GatewayEndDedicatedRedisTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.EndpointSnapshotTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.SecurityChainSnapshotTest
```

| 验证 | 结果 |
|---|---|
| 默认配置（三端都没配自己的 Redis 与数据源）下的两份快照与探测 | 与 M0 基线逐行相同 |
| `strict` 且两端的 Redis 指向同一处 | 启动失败，报错点名是哪两端、指向哪 |
| `strict` 且三端各有自己的 Redis；`relaxed` 下共用 | 都能起；什么都没配时不建任何连接 |
| 一端自己的 Redis 的键前缀 | 默认 `gw:<端名>:`；不走专属时仍是原来的 `shop:session:` |
| 运营端的会话放在它自己的 Redis 上、而那个 Redis 连不上 | 运营端用专属存储、另两端仍是共用的；C 端带令牌的请求照常 200；运营端带令牌的请求连接失败 |
| 23 个 pre-push 闸门脚本 | 与 M2 相同 |

**M3 相对计划的偏差：**

| 计划 | 实际 | 原因 |
|---|---|---|
| 任务 3.1：每端加 `session` 配置块 | 没有加 | 「哪一端的会话用哪种存储」已有两个现成的键：`shop.auth.token-store-by-realm.<端>` 与全局的 `shop.auth.token-store`。再加一块等于同一件事两处配。新增的只有每端的 `redis` 与 `datasource` |
| 任务 3.2：`GatewayEndRuntime`、`GatewayEndRedis`、`IamDataSources` 三个类 | 合成一个 `GatewayEndRuntimes` | 三者都只是「按端建连接并在关闭时释放」，分开只是多两层转发。权限表用的数据源到 I1 才需要，届时从这里取 |
| 任务 3.4：`TokenStoreConfig` 搬进网关并改成每端各选一种 | 没有搬。会话装配留在 `shop-app`，改为向 `GatewayEndRuntimes` 要某一端自己的 Redis 或数据源，要不到就用共用的 | 现状已经是按端选存储；这几个配置类之间靠包内可见性互相引用，测试也在同一个包里。新增一个 `GatewayEndTokenStoreConfig`，只在「没有端落库、但有端配了自己的 Redis」时装配 |
| 任务 3.6：健康检查每端一项 | 做了，但整体状态始终是 UP，各端状况在明细里 | 把整个实例报成 DOWN 会让负载均衡把另外两端的流量也摘走，正好违背隔离的目的 |
| 任务 3.7：运营端 Redis 不可用时 `/ops` 回 503 | 隔离成立（另两端不受影响），但运营端此时是未处理的连接异常，不是 503 | 把存储不可用映射成 503 要改 `shop-base-auth` 里的令牌过滤器，属于新增对外行为，没有放进「默认行为不变」的这个里程碑。记为待办 |

新写的类 7 个：`SessionStoreKinds`、`GatewayIsolationCheck`、`GatewayEndRuntimes`、`GatewayEndHealthIndicator`（网关模块）；`GatewayEndTokenStores`、`GatewayEndTokenStoreConfig`（`shop-app`）；另有 2 个测试类。原地改了 `RedisTokenStore`（键前缀成为构造参数，默认值不变）、`DbSessionConfig`、`TokenStoreSelection`。

没有验证的：每端独立**数据源**这条路只有编译和「没配时落回主库」被测到；真的指向另一个库需要一套带会话表的库，本次没有搭。

**M4a 的真实输出**（2026-11-18，分支 `feat/gateway-m4-ops-precheck`，叠在 M3 之上）。M4 拆成了两半：**M4a 是索引与提前判权，不碰数据库；M4b 是入库与停用开关，还没做。**

```
# arch、gateway、config 三个包 + 受影响的安全相关测试，带着 M0 的快照当基线；提前判权默认是关
classes 87 run 289 fail 5 err 0 skip 9        # 5 条失败与 M0 起完全相同，与本次无关
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.PermRuleParserTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.OpsPrecheckEnforceTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.gateway.OpsPrecheckObserveTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.EndpointSnapshotTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.SecurityChainSnapshotTest
```

| 验证 | 结果 |
|---|---|
| 档位是关（默认）时的两份快照与探测 | 与 M0 基线逐行相同：关着时认证链上不多任何过滤器 |
| 索引覆盖（方案 AC21） | `/ops` 与 `/pay-ops` 下，索引的键与 Spring MVC 登记的端点完全一致；多数带权限式；提前判不了的不超过 5 条 |
| 表达式解析 | 单码、与、或、与或混合（含括号与优先级）都认得；方法参数、别的 bean、别的方法、取反、语法错误一律返回「认不得」 |
| 拦截档：没权限且请求体不合法（方案 AC22） | 得到「没有操作权限」而不是参数错误 |
| 观察档：同样的请求 | 先看到的仍是参数错误，与不开时相同；只多一条日志 |
| 拦截档下的公开入口与未登录请求 | 登录入口照常进到入口方法；未登录仍是 401 |
| 23 个 pre-push 闸门脚本 | 与 M3 相同 |

**实现时才知道的两件事：**

- **同一个「方法 + 地址」可以登记多个处理方法**（靠请求参数或请求头再区分，运营端的 `GET /ops/promotion/activities` 就是）。提前判权只看方法与地址，分不清会落到哪一个：权限要求相同就并成一行，不同就标成「留给方法判」，不猜。
- **过滤器里借不了 Spring MVC 的异常处理来输出响应**：过滤器在处理方法匹配之前，全局异常处理定位不到。改为直接按同一个错误码、同一句文案输出，状态码同样不变，端上分不出是提前判权拒的还是方法注解拒的。

**M4 相对计划的偏差：**

| 计划 | 实际 | 原因 |
|---|---|---|
| 一个里程碑 | 拆成 M4a（索引与提前判权）和 M4b（入库） | 判权读的本来就是本实例扫出来的索引，库里那份是副本（方案 §2.13）。两半互不依赖，而入库要动迁移、测试用的表结构文件和 166 上的建表，单独一个 PR 更好评审 |
| 任务 4.1、4.6、4.7：两张表、同步、停用开关 | 没做，归 M4b | 同上 |
| 任务 4.9：`GatewayAuthorizationManager` 接在 `authorizeHttpRequests` 上 | 换成一个过滤器 `GatewayPrecheckFilter`，排在令牌认证与端专属过滤器之后 | 档位是关时不加这个过滤器，认证链与今天逐个相同；接在判权规则上则关着时也会改变链的装配。没登录的请求它不处理，仍由认证链回 401 |
| 任务 4.8：`GatewayRouteFilter` 单独一个 | 并进提前判权的过滤器 | 定位的结果目前只有判权一个使用者；透传（D2）出现时再拆 |
| 任务 4.10：`PermChecker` 记录通过的权限码 | 没做 | 它只给出站签名用，S 期才需要 |
| 任务 4.11：观察档在两处结论不一致时告警 | 观察档记「会拒绝」的日志 | 方法注解拒绝时状态码也是 200，过滤器里看不出入口方法最后拒没拒。核对办法是把这条日志与全局异常处理的「判权拒绝」日志按请求对一遍 |
| 任务 4.13：缓存预热后不查库（AC23） | 没有单独的测试 | M4a 的索引建在内存里、不读库；判权用的权限码仍走现有的快照。AC23 留给 M4b |
| 任务 4.14：`OpsEndpointPermTest` 改为对索引断言 | 没改 | 它今天扫源码也覆盖得到 `shop-gateway`（M1 已确认）。改写放到 M4b，与入库后的对账一起 |

新写的类 14 个：资源模型与索引 7（`ApiResource`、`PermRule`、`PermRuleParser`、`ResourceProvider`、`LocalScanResourceProvider`、`ResourceIndex`、`ResourceIndexes`），判定与过滤器 6（`AccessDecider`、`AbstractAccessDecider`、`Decision`、`GatewayExchange`、`GatewayPrecheckFilter`、`GatewayErrorWriter`），运营端判定 1（`OpsAccessDecider`）；另有 3 个测试类。

上线顺序不变：合并时默认关；166 上先开观察，核对日志无出入后再开拦截。M4a 不需要建表。

## §6 对账二 · 计划 → 实现

每个里程碑合并后，把 `git show --stat` 贴在这里，与 §2 的任务表逐行比。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-18 | 草稿：核对三点，拆出第一批五个里程碑 |
| 2026-11-18 | 计划确认，待开工；从 M0 开始 |
| 2026-11-18 | M0 完成：三个测试类、基线、写死路径的名单；快照改为不入库（§2.1 偏差） |
| 2026-11-18 | M1 完成（本地，未推送）：675 个文件搬进 `shop-gateway`，快照与基线逐行相同；偏差见 §5 |
| 2026-11-18 | M2 完成（本地，未推送）：端基类、安全框架适配、四个端子类；`SecurityConfig` 拆分；快照与基线逐行相同；偏差见 §5 |
| 2026-11-18 | M3 完成（本地，未推送）：每端自己的 Redis 与数据源、`strict` 校验、健康明细；默认配置下快照不变；偏差见 §5 |
| 2026-11-18 | M4a 完成（本地，未推送）：运营端资源索引与提前判权，三档开关默认关；入库（M4b）未做；偏差见 §5 |
