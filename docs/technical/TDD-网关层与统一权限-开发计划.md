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
| 0.2 | 端点快照测试：启动测试上下文，从 Spring MVC 取全部处理方法，把「方法、地址、处理类、`@PreAuthorize` 原文」排序后写成一份 JSON；测试断言与仓库里的快照文件相同 | `shop-app` 测试 `EndpointSnapshotTest` + 快照文件 | 改一个注解，测试变红 |
| 0.3 | 认证链快照测试：对每条 `SecurityFilterChain`，记录匹配前缀与过滤器类名的顺序；另对每端各取几条代表地址，记录未登录、错端令牌时的状态码 | `SecurityChainSnapshotTest` + 快照文件 | 调换两个过滤器的顺序，测试变红 |
| 0.4 | 列出写死路径的脚本、清单与测试的完整名单（§1 第 4、5 项），逐个标注改法 | 本文 §2.2 任务 1.6、1.7 的清单 | — |

出口：两份快照入库，基线数字记下。

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
| M0 | — | `EndpointSnapshotTest`、`SecurityChainSnapshotTest`；基线数字 | — |
| M1 | AC9、AC7 的一部分 | 两份快照；`ArchitectureTest` 新三条；pre-push 全套 | — |
| M2 | AC2、AC33 的一部分 | 两份快照；只开一端的上下文测试；架构测试的基类规则 | — |
| M3 | AC10、AC26 | 属性默认值测试；`strict` 校验；停一端 Redis 的链路测试 | — |
| M4 | AC21、AC22、AC23、AC25、AC15 | 对账测试；403 先于参数错误；零查库；停用开关；三端现有的端点权限测试原样通过 | — |

## §6 对账二 · 计划 → 实现

每个里程碑合并后，把 `git show --stat` 贴在这里，与 §2 的任务表逐行比。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-18 | 草稿：核对三点，拆出第一批五个里程碑 |
| 2026-11-18 | 计划确认，待开工；从 M0 开始 |
