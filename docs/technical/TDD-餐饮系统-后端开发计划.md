# 餐饮系统后端详细开发计划

> 实施进度：NEXT-01～05、NEXT-08A/B/C、NEXT-09和TBL-02桌台配置已交付；NEXT-06基础接线与桌区业务AC已验证；平台API081及NEXT-07独立交易合同仍有缺口。实际验证见[M0实施记录](./TDD-餐饮系统-M0基础工程与接入方案.md)。
> 状态：桌台可独立交付切片已实现；完整MOD-01仍待资源、订单、冻结与后付合同 · 2026-10-21。
> **执行方式：** 按M0方案逐切片执行；TBL-02保持原六接口和TableService、双部署合同。后续先细化桌码会话需求，再实施，不将配置完成视为营业闭环。

**目标：** 复用商品、订单、支付、库存及账号门店底座，按“桌台 → 菜品餐牌 → 点餐订单”逐模块交付，最终覆盖在线、线下、后付、打印、日结报表、第三方和离线营业。

**架构：** 独立Java reactor增加food-web共五模块；餐饮三端Controller源码归food-web，由shop-app统一挂载，food-api承载Service接口/DTO，food-core承载业务实现。独立food-server只内部HTTP，沿平台现有接入方案，支付/库存通过权威Port联通独立系统。[最新统一对齐](./TDD-餐饮系统-三端API与独立服务对齐.md)。

**技术栈：** Java 21、Spring Boot 4.0.x、MyBatis-Plus、Flyway，版本沿平台父 POM；复用现有鉴权、任务、outbox、报表设施。

**依据：** [完整需求](../requirements/餐饮系统-完整需求文档.md) · [业务功能清单](../requirements/餐饮系统-后端业务模块功能清单.md) · [数据库/API评审](./TDD-餐饮系统-数据库与API评审对齐.md) · [模块类清单](./design/餐饮系统-按业务模块代码清单.md) · [精确方法/入出参](./design/餐饮系统-Controller-Service-Mapper方法清单.md)。

**阅读顺序：** §0看范围和交付次序；§1看全局约束；§2按模块领取任务；§5看测试与上线门槛；[任务对照索引](./design/餐饮系统-开发任务对照索引.md)查141个API、109项需求和精确文件路径。输入输出不另抄一份，以API及方法清单为权威。

## §0 现状与交付范围

### 0.1 已完成和仍待实施

| 项 | 当前基线 | 本计划中的工作 |
|---|---|---|
| 需求 | 13业务模块、109项需求 | 每项归入模块任务；18项DESIGN_GAP另设补约任务 |
| API | 141个目标接口、52个Controller、33组Service/Impl | 按功能切片逐个落地，目标设计不等于现成接口 |
| 持久化 | 本机同商品订单的ai_shop已初始化73张fnb表 | 接入实体、Mapper、事务、锁和迁移装配；不重复建表 |
| 共享候选表 | 5张prn与1张rpt尚未创建 | 归共享打印/报表域实施，不能计作餐饮现成设施 |
| Java业务 | 五模块、Controller和Service均未开始实现 | 首任务搭含food-web的最小运行骨架，随后进入桌台切片 |
| 核心能力 | 有商品、零售订单、支付、库存基础 | 源行、后付、部分退款等按合同核验及最小扩展 |

需求状态为32项DEFINED、59项CORE_CONTRACT、18项DESIGN_GAP。DEFINED只是设计已定义；CORE_CONTRACT须权威域合同通过；DESIGN_GAP须先补需求/API/库表及失败场景，再进入代码。计划覆盖全部目标，当前141个API不是未来接口总数上限。

### 0.2 业务推进顺序与里程碑

| 阶段 | 交付内容 | 完成判据 |
|---|---|---|
| M0 最小底座 | FND-01/02、GOV-01/02的当前切片 | 双部署可启动、禁用不影响零售、门店隔离和迁移归属通过 |
| M1 桌台首批 | TBL-01～05 | 桌区桌台→桌码会话→开台占用→查询→安全清台；并发和越权测试通过 |
| M2 菜品餐牌 | CAT-01～08 | 商品绑定、餐牌时段分类、选配套餐、工位、发布与停售可验收；未获报价能力的组合禁止售卖 |
| M3 点餐订单 | CORE-01、ORD-01～05 | 商家/顾客草稿→权威报价→幂等建单→源行绑定→查询取消与恢复 |
| M4 在线先付闭环 | KIT-01～04、PAY-01/02、RET-01/02、PRN-01/02 | 真核心+测试支付通道+厨房+退餐+打印联通；实机出纸另验 |
| M5 后付及桌台扩展 | CORE-02、ORD-06、TBL-06～08 | 获准后付受理、多批次加菜、预约/转并拼拆桌；资金事实不迁移 |
| M6 现金、日结、报表 | SHIFT-01/02、PAY-03、CLOSE-01/02、RPT-01/02 | 开班收款找零→盘点交班→营业日对账签核→报表复核 |
| M7 复杂计价与售后 | CORE-03/04、PAY-04、RET-03及对应GAP | 复杂计价、订金、分摊、按菜退款逐能力验收，不一次打开全部开关 |
| M8 完整范围扩展 | 专项、通知设备、第三方及离线；剩余GAP | 每种业态/供应商/离线模式分别完成合同、实机和资金对账验收 |

M1→M2→M3是主顺序；同模块先基础、后扩展。M4中先付支付联通后才能验收厨房自动派工。M6的现金班次必须前移到任何现金功能启用前；营业日基础归属在M3首单前接入，不等日结模块才决定订单归日。后付可在先付闭环后验收，但正式试点必须先通过M6。预约无资金基础可在M1后续做，预点/订金按补约和核心合同独立启用。

### 0.3 排期与工作量使用方式

本计划按可评审任务估算，不承诺未经核心审计的上线日期。建议先执行M0和M1首两个切片，取得真实吞吐后再排日历：小型配置/查询切片约0.5～1.5人日，带并发/恢复的业务切片约2～4人日，核心跨域/设备/离线任务必须先拆合同子任务再估算。区间包含开发、自测和评审修正，不含供应商等待、真实设备到货或支付开通；不是全量项目报价。

每周按“已验收切片 / 被合同阻塞切片 / 剩余人日区间”更新。核心合同、共享打印和第三方各有明确交付责任域；未满足条件的能力保持关闭，不能用mock验收替代联通。

## §1 全局约束与重点风险

### 1.1 全局约束

- 账号、主体、商户、门店、realm、BizContext、ApiResult、PageData、BaseEntity、业务号、权限与归档规则全部沿现有体系。`entityNo/storeNo/actorNo`来自可信上下文及已验证资源，不能信任body。
- 平台统一包名/类名以[Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)为准：`api.biz/mp/ops/internal`、`dto`、`service/impl`、`entity`、`mapper`；无Repository层、无13套独立Maven模块。
- Entity按完整表名驼峰命名；FoodMappers嵌套Mapper集中一个文件。报表沿shop-report Row/DAO；共享打印不复制到food。
- 跨域SPI唯一归属`ai.neargo.shop.spi.<权威域>`，适配实现位于`.port`；food-channel只负责协议，不持有餐饮状态机。TARGET_NOT_PRESENT的Port先补合同，不能当现成服务调用。
- 同物理库不等于可跨域直写；food禁止直接写商品、核心订单、支付和库存表。核心改动单独评审、提交和零售回归。
- 已应用`V1__food_business_tables.sql`冻结；只追加经过版本核验的迁移。food使用独立历史表`fnb_flyway_history`，不把V1复制进core默认`db/migration`。当前初始化目录尚非默认启动迁移，FND-02需正式装配且迁移只允许一个执行者。
- 使用现有门店`openHours`；餐饮日历负责接单限制，不再维护第二份营业时间。跨午夜、时区、营业日切点和结账自然日分别定义。
- 同键同参复用结果，同键异参拒绝；幂等范围含主体/门店/操作。外部调用不放长数据库事务；UNKNOWN按原键查回，使用短事务意图/outbox及恢复Job。
- Job遵守现有`JobHandler.name()`与`JobResult run(JobInvocation)`，不另发明调度接口。配置类只做声明装配，不凑空业务方法。
- 每切片只创建所需类、DTO、Mapper方法和测试；不一次生成52个空Controller或全部Entity壳。

### 1.2 必须有对应测试的五类风险

| 输入或故障 | 预期 | 主责任务与测试 |
|---|---|---|
| 换门店头、跨店业务号、伪造操作人 | 所有读写及顾客入口按真实授权拒绝 | FND-02/GOV-02：FoodStoreScopeTest#rejectCrossStorePath、#ignoreSpoofedActor |
| 两终端抢最后桌位、配额或并发退同一菜 | 最多一个成功或总量守恒；空查询锁不是稳定资源锁 | TBL-04、CAT-08、RET-03：并发占用、最后额度、双退测试 |
| 核心已成功，本地超时或断电 | 查原键恢复，不二次下单、扣款或退款 | ORD-03、PAY-03、RET-01：恢复测试 |
| 结账冻结时加菜/转桌/支付回调；跨午夜迟到退款 | 不漏收，不搬历史资金，营业日与日结快照可追溯 | PAY-01、TBL-08、CLOSE-01/02：冻结竞争和迟到事件测试 |
| 打印无回执、边缘双写、第三方重复乱序回调 | 保留未知态/原单关联，拒绝旧fence，按来源幂等回放 | PRN-02、INT-02、EDGE-01/02：未知回执、重放、分区测试 |

### 1.3 关键合同门槛

| 门槛 | 核验/交付 | 不满足时 |
|---|---|---|
| 身份与公开启动 | 匿名/顾客会话、员工代客、设备凭证；API023/026先门店或桌码引导再派生可信范围 | 只能员工授权配置；不得伪造统一顾客账号 |
| 资源仲裁 | 桌台/预约与现金钱箱明确唯一仲裁者、稳定资源锁及恢复；ResourcePort尚需核验/补齐 | 配置可做，占用/现金并发不得放行 |
| CORE-01 | 真实quote/place/findPlacement、源行分配、同键恢复、本地远程等价 | 草稿可做，真实建单不可验收 |
| CORE-02 | 后付库存承诺、未付款制作交付、取消释放及超时策略 | 只开先付模式 |
| CORE-03/04 | 权威应收调整/订金/支付分配及按行退款上限 | 仅获准单支付根及现有整单全退 |
| 共享打印/报表 | 唯一归属、真实共享合同、权限、迁移与任务设施 | 不宣称打印设备管理、报表导出已经存在 |
| 离线与第三方 | 供应商协议、租约fencing、离线收款授权及冲突对账 | 保留在线模式，专项开关关闭 |

## §2 按模块执行计划

### 2.1 文件责任与接口引用规则

模块、Java包、平台依赖及迁移资源归属按[独立项目与包依赖方案](./TDD-餐饮系统-独立项目与包依赖方案.md)逐项实施；保持既有零售命名与契约。

| 路径（仓库根目录起） | 责任 |
|---|---|
| `backend-plugins/shop-industry-food/pom.xml` | 五模块reactor（新增food-web）、父POM及共享依赖解析 |
| `backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/` | food.service接口、food.dto、spi.food内部契约；通用SPI保持唯一权威 |
| `backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/` | ServiceImpl、内部入口、Entity、FoodMappers及策略/事件/Job/配置；三端Controller在food-web同名包，由宿主挂载 |
| `backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/` | 模块业务、权限、真实数据库并发与恢复测试 |
| `backend-plugins/shop-industry-food/food-channel/` | 远程底座及供应商协议适配 |
| `backend-plugins/shop-industry-food/food-server/` | 独立业务启动、内部密钥与装配；无用户三端鉴权 |
| `backend/shop-app/src/main/java/ai/neargo/shop/industrybridge/food/port/` | 集成模式本地适配，调用权威Service |
| `backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql` | 已应用冻结迁移，只读核验 |
| `backend/shop-core/`及既有共享SPI提供模块 | CORE任务的权威扩展，实施前定位真实文件，不提前假定Port已存在 |
| `backend/shop-report/`及共享打印模块 | 报表Row/DAO、共享打印模型；位置按平台已有实现核验后登记 |

下表API编号省略`FOOD-API-`；每个编号只指定一个首次实施任务。无新增API的扩展任务复用已有入口，不意味着可以省略验收。精确文件、Controller和Service签名、Mapper锁/写分支以及Req/VO字段直接按[方法机器清单](./design/schema/food-method-inventory.json)与[类机器清单](./design/schema/food-module-code-inventory.json)中对应API读取；[任务索引](./design/餐饮系统-开发任务对照索引.md)给出任务到API和文件的入口。

### 2.2 每个任务统一的五步

任务表每行是可独立评审的功能切片；遇到多个独立核心合同或供应商协议，先按子能力拆小，不把整模块作为一个提交。

1. 实施开始先确认隔离分支/工作树和共享未提交改动边界；登记当前任务的API、FNB需求、精确受影响文件、方法签名和事务；核对平台规范、端口实际存在性与迁移历史。
2. 为该行命名场景写失败测试；验证因缺少目标行为而失败。并发用真实目标数据库和独立事务，不用H2替代锁语义。
3. 按Controller→Service接口/Impl→本域Mapper实现最小切片，使用既有DTO和错误码规则；跨域经Port。合同调整先改真源再生成文档。
4. 运行定向业务测试、必要数据库集成测试和受影响零售回归；关键守卫做一次消融，证明去掉守卫会失败。记录实际命令和结果。
5. 仅选择本任务文件提交独立commit，回填§6实际文件/测试/偏差。提交是实施阶段动作，本次计划编写不提交共享工作区。

### 2.3 最小底座与公共能力（按首批功能所需先行）

**下一步执行顺序：** [M0 §2.7首批实施计划](./TDD-餐饮系统-M0基础工程与接入方案.md)的NEXT-01～10；它们是既有FND/GOV/TBL的子任务，先工程/主库/范围，再桌区092～095，不重新选择架构。

详细接入方案见[M0基础工程与接入方案](./TDD-餐饮系统-M0基础工程与接入方案.md)：按实际源码展开FND-01/02，先交付五模块（含food-web）与装配，再接平台既有身份范围、主库和迁移；内部新增合同与原141业务API分账。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| FND-01 最小构建与双部署装配 | —（内部/复用） | 创建五模块、food-web三端接入及平台挂载与仅内部独立服务；开关关闭仍可启动；本地/远程适配互斥，food不依赖核心实现 | `FoodArchitectureTest#rejectCoreImplementationDependency；FoodDeploymentContractTest#startEmbeddedAndStandalone` | 无 |
| FND-02 迁移、身份及公共写入设施接通 | —（内部/复用） | 复用主DataSource，独立fnb_flyway_history；核验幂等、outbox、业务号、权限和错误码机制；只补本切片缺失能力 | `FoodStoreScopeTest#rejectCrossStorePath；FoodDeploymentContractTest#validateFoodHistoryWithoutRunningCoreMigrations` | FND-01 |
| GOV-01 营业策略、接单日历与能力开关 | 001、002、003、081 | 读取既有openHours，版本化餐饮接单策略；营业日切点独立；关闭能力拒绝写入 | `StoreOperationServiceTest#respectOvernightOpenHours；StoreOperationServiceTest#rejectStalePolicyVersion` | FND-02 |
| GOV-02 公共审计及金额权限 | —（内部/复用） | DiningAuditService记录操作人与原业务号；审计禁止由请求体伪造，平台诊断不等于收退款授权 | `FoodStoreScopeTest#ignoreSpoofedActor；FoodStoreScopeTest#denyMoneyActionWithoutPermission` | FND-02 |

FND-01新增五模块POM、`config/FoodAutoConfiguration.java`、`FoodProperties.java`、`FoodPortConfiguration.java`、自动装配声明及独立启动入口；FND-02新增`config/FoodPersistenceConfiguration.java`，根据现有平台鉴权/幂等/outbox接入方式补最少配置。Test路径以2.1为基准；双部署测试在实施时登记到能真实启动两种模式的测试模块，不以只测装配注解代替运行。

### MOD-01 本轮交付与未完成项 · 2026-10-21

桌区/桌台10个接口保留既有交付；本轮增加桌码会话4个、开台/清台/转并桌/拼桌/审计10个、预约查询1个。连同营业策略/日历3个，当前共有28个业务入口及29个typed内部入口。这里只声明已实现切片，完整桌台阶段未收口。

| 切片 | 本轮实际落地 | 尚未完成的范围 |
|---|---|---|
| TBL-03 | 025/026/101/139，匿名及原CONSUMER会话，凭证哈希与退出 | 获准设备认证、显式入席membership |
| TBL-04 | 031/032/102/103，PREPAY无交易接待及座位维护 | 138顾客进度、正式POSTPAY受理 |
| TBL-05 | 036/108，无交易全部/部分清台及流水 | 带单应收/厨房守卫、异常释放审批 |
| TBL-06 | 107，半开重叠窗口与范围过滤 | 027～030的真实resourceNo/hold/释放/到店接力；订金另属CORE-03 |
| TBL-07 | 033/104/106，无交易本地转并桌及原号查询 | 带单联验、外部资源接力恢复；未知操作仅展示持久化状态 |
| TBL-08 | 105，同桌独立check及共享容量 | 034/035完整批次membership合拆、AA资金分摊及冻结合同 |

V2仅增加occupancy.sharing_mode，历史占用按EXCLUSIVE回填；V1冻结字节不变。现有业务库未执行V2。外部资源引用/占位或未完成操作拒绝本地变更，不能用空实现替代外域成功。完整需求、实际类名及测试证据见[M0实施记录](./TDD-餐饮系统-M0基础工程与接入方案.md)。

### 2.4 MOD-01 桌台与就餐管理

首批范围TBL-01～05；其余作为同模块后续切片。主责11张表，Service为TableService、DiningSessionService、ReservationService、CheckService；审计复用MOD-13，不另建第二个审计Service。
| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| TBL-01 桌区查询、新建、修改和启停 | 092、093、094、095 | 分页和主体门店过滤；停用有占用资源的桌区按合同拒绝；同键重放不重复新建 | `TableServiceTest#replayAreaCreation；FoodStoreScopeTest#denyForeignAreaMutation` | GOV-01、GOV-02 |
| TBL-02 桌台配置、查询、状态与归档 | 024、096、097、098、099、100 | 容量、桌区归属、状态版本校验；有活动占用的桌台不得直接停用归档 | `TableServiceTest#rejectArchiveOccupiedTable；TableServiceTest#rejectStaleTableVersion` | TBL-01 |
| TBL-03 桌码轮换撤销及顾客短会话 | 025、026、101、139 | 令牌仅存哈希；公开启动先验桌码再解析主体门店；退出只结束获准会话 | `DiningSessionTest#rejectRotatedToken；DiningSessionTest#denyEndingAnotherSession` | TBL-02；顾客身份合同 |
| TBL-04 开台、台账、人数座位及顾客查询 | 031、032、102、103、138 | 锁定稳定资源并原子新增台账/占用/座位/审计；无桌外带不造假桌；顾客只读所属台账 | `TableOccupancyConcurrencyTest#allowOneExclusiveOccupancy；DiningSessionTest#denyOtherCheckRead` | TBL-03；资源仲裁合同 |
| TBL-05 清台与操作流水 | 036、108 | 释放服务占用但不删除订单应收；未结台账禁止普通清台，获准异常释放保留未结事实 | `TableServiceTest#rejectNormalClearWithOutstandingBalance；TableServiceTest#retainAuditAfterClear` | TBL-04；核心应收读取合同 |
| TBL-06 预约、取消、爽约、到店开台 | 027、028、029、030、107 | 时间段资源竞争与到店幂等；先交付无资金预约，订金核销按权威合同扩展 | `TableOccupancyConcurrencyTest#serializeReservationArrivalAndExpiry` | TBL-04；订金功能另需CORE-03 |
| TBL-07 转台、并用多桌及操作恢复 | 033、104、106 | 确定资源加锁顺序，先占目标后释放原占用；中断按operationNo恢复，不搬动订单和资金 | `TableTransferRecoveryTest#recoverAfterTargetReserved；TableTransferRecoveryTest#denyCrossStoreTransfer` | TBL-04；资源仲裁合同 |
| TBL-08 合并拆分台账与同桌拼单 | 034、035、105 | 批次归属变更锁原批次，追加/脱离membership；保留原交易归属与资金历史；拼桌按人数容量独立台账 | `TableTransferRecoveryTest#preserveOriginalBatchAndFunds；TableOccupancyConcurrencyTest#enforceSharedSeatCapacity` | ORD-03；结算冻结合同 |

#### 首批桌台的具体执行步骤

以下方法的完整入参/返回包装沿方法清单；只列实现步骤，不重写协议。`J`表示2.1中的food-core生产Java根，`A`表示food-api下ai/neargo/shop/food，`H`表示food-web下ai/neargo/shop/food，`T`表示scenario测试根；例如`H/api/biz/BizTableController.java`定位餐饮food-web的同名业务包，完整文件按最新机器清单。

- [ ] **TBL-01 / 红灯：** 在`T/TableServiceTest.java`增加`replayAreaCreation`：同主体门店、相同键参数连续创建返回同areaNo且只有一条；在`T/FoodStoreScopeTest.java`增加`denyForeignAreaMutation`：跨门店areaNo修改失败且原数据不变。用§5定向命令确认业务失败。
- [ ] **TBL-01 / 实现与绿灯：** 新增`H/api/biz/BizTableAreaController.java`、`A/service/TableService.java`、`J/service/impl/TableServiceImpl.java`对应092～095的方法；按清单增加Create/Save/SetStatus/ListTableAreas DTO，`J/entity/FnbTableArea.java`及`J/mapper/FoodMappers.java`的TableAreaMapper。验证分页包装、门店过滤、版本和幂等，再定向提交。
- [ ] **TBL-02 / 红灯：** 增加`rejectArchiveOccupiedTable`及`rejectStaleTableVersion`，断言活动占用时归档失败、旧版本修改不覆盖当前数据；归档后普通列表不返回该桌台。
- [ ] **TBL-02 / 实现与绿灯：** 新增`H/api/biz/BizTableController.java`、`J/entity/FnbTable.java`，扩展同一TableService/Impl和FoodMappers.TableMapper；实现024、096～100。权限检查在Controller，容量/状态规则在Service/Policy；不能将Req直接作为Entity更新。
- [ ] **TBL-03 / 红灯：** `T/DiningSessionTest.java`验证轮换前token、已撤销token、已过期session均拒绝；另一个会话不能结束当前会话。日志不出现明文桌码。
- [ ] **TBL-03 / 实现与绿灯：** 新增`H/api/mp/MpDiningSessionController.java`、`A/service/DiningSessionService.java`及Impl、`FnbTableToken.java`、`FnbGuestSession.java`及对应嵌套Mapper。公开启动调用`selectValidByTokenHash(byte[] tokenHash, Instant now)`后派生可信主体门店，不要求前端伪造entityNo。
- [ ] **TBL-04 / 红灯：** `T/TableOccupancyConcurrencyTest.java#allowOneExclusiveOccupancy`以两个独立事务同时开独占桌，断言一份活动占用/一份有效台账；共享桌另验人数守恒。顾客读其他checkNo必须拒绝。
- [ ] **TBL-04 / 实现与绿灯：** 新增`H/api/biz/BizCheckController.java`、`H/api/biz/BizCheckSeatController.java`、`H/api/mp/MpCheckController.java`、`A/service/CheckService.java`及Impl，`J/entity/FnbCheck.java`、`J/entity/FnbTableOccupancy.java`、`J/entity/FnbSeat.java`与嵌套Mapper，`J/policy/TableOccupancyPolicy.java`、`J/statemachine/FoodCheckStateMachine.java`、`J/assembler/TableViewAssembler.java`。`openCheck(String entityNo, String storeNo, String actorNo, OpenCheckReq req, String idempotencyKey)`返回CheckResultVO；TableMapper稳定桌行锁及资源合同必须验收，不靠空占用查询锁。开台新增占用，不对不存在的占用行做update。
- [ ] **TBL-05 / 红灯：** `rejectNormalClearWithOutstandingBalance`断言未知或未结资金状态均不允许普通清台；`retainAuditAfterClear`断言获准清台后订单/审计可追溯、重复操作不重复释放占用。
- [ ] **TBL-05 / 实现与绿灯：** 补CheckService.clearCheck、审计查询`H/api/biz/BizDiningActionLogController.java`及公共DiningAuditService/Impl、FnbActionLog与ActionLogMapper。需要释放后仍保留异常应收的场景先验权限/合同；不能先删台账再补审计。
- [ ] **M1验收：** 两种部署分别调用“建桌区→建桌→桌码会话→开台→座位→查询→清台”完整链路；复核越权、并发和恢复证据。只给已通过的功能开能力开关。

桌台高级切片增加`FnbReservation/FnbCheckRelation/FnbTableOperation/FnbCheckBatchMembership`及对应嵌套Mapper；TableOperationRecoveryJob按原operationNo恢复。TBL-07转桌与TBL-05清台不改batch membership，TBL-08合拆台账才在锁批次后处理membership。预约订金不通过改reservation状态模拟收退款。

### 2.5 MOD-02 菜品与餐牌管理

**文件/类范围：** 22张表；Menu、Dish、OptionGroup、Modifier、Bundle、Station、KitchenRoute、SalesRestriction、ConfigRevision各Service/Impl；发布及额度策略和FoodSalesRestrictionExpiryJob。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| CAT-01 菜品商品绑定、展示及查询 | 016、112、113 | goodsNo/skuNo取商品权威；餐饮字段只扩展展示与制作规则；拒绝失效及跨主体SKU | `MenuPublicationTest#rejectForeignOrInactiveSku` | 桌台首批验收；GoodsQueryPort签名核验 |
| CAT-02 餐牌定义、分类、门店渠道及时段 | 004、005、017、109、110、111 | 餐牌单独建模，分类属于餐牌；基础商品分类不承担餐牌时段版本；停用与草稿修改不改历史 | `MenuPublicationTest#keepMenuCategoriesIndependent；MenuPublicationTest#resolveMealPeriodBoundary` | CAT-01 |
| CAT-03 发布快照、回滚与多端可售目录 | 006、023、114、115、116 | ConfigRevisionService统一不可变版本；公开目录经门店解析；快照结合实时停售和核心可售，回滚仍重新校验 | `ConfigRevisionServiceTest#republishWithoutMutatingHistory；MenuPublicationTest#denyExpiredPublicCatalogScope` | CAT-02、GOV-01 |
| CAT-04 口味属性和选择组规则 | 007、008、009、019、118 | 复用基础属性字典表达免费口味；餐饮选择组定义必选互斥、数量上下限，不把收费配料当普通属性 | `SelectionRuleTest#enforceRequiredAndExclusiveOptions` | CAT-01、CAT-03 |
| CAT-05 收费配料及配料组合 | 020、117、120、141 | 收费配料引用SKU，组合组件定义数量；发布检测循环、不可售引用；交易计价由核心报价 | `SelectionRuleTest#rejectModifierCycle；SelectionRuleTest#preserveModifierComponentQuantity` | CAT-04；售卖需CORE-01 |
| CAT-06 固定套餐、自选套餐与组约束 | 010、011、012、018、119 | 套餐组与候选SKU分离；冻结所选组件；主商品和组件库存扣减口径以核心合同为准 | `SelectionRuleTest#enforceBundleChoiceBounds；SelectionRuleTest#freezeChosenComponents` | CAT-05；售卖需CORE-01 |
| CAT-07 工位、SKU路由和配置发布 | 013、014、015、021、121 | 工位作用域与启停、版本化路由；跨工位菜品组件分流，订单沿受理快照派工 | `MenuPublicationTest#rejectDisabledStationRoute` | CAT-01、CAT-03 |
| CAT-08 停售、沽清、限量与过期恢复 | 022、122 | 限制是可售规则，不修改商品实存；并发额度认领幂等，提交失败或过期按规则释放 | `SalesRestrictionTest#claimLastQuotaOnce；SalesRestrictionTest#expireRestrictionWithoutChangingStock` | CAT-03；额度与库存合同 |

首批支持商品绑定、餐牌/时段、基础选配和工位；套餐/配料组合配置可先验收，只有真实核心报价库存合同通过的组合才可销售。称重/导入分别经GAP-03/04，不能当作已有字段天然支持。

### 2.6 MOD-03 点餐与订单管理

**文件/类范围：** 8张表；CheckCartService、DiningOrderService/Impl；OrderPlacementPolicy、OrderLineAllocationPolicy、OrderBatchStateMachine、FoodPlacementRecoveryJob及支付受理事件。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| ORD-01 商家和顾客共享购物车 | 037、038、039、040、126、127、128、129、130 | 源行、选配、数量与CAS；归档不改已提交快照；顾客会话权限独立校验 | `CheckCartTest#rejectLostUpdate；CheckCartTest#denyArchivingCommittedSnapshot` | 菜品基础验收；TBL-04 |
| ORD-02 报价与版本失效 | 041、131 | 核心返回价格折扣税费；保存配置/报价有效期；收费配料与套餐参与真实报价，不由前端传价定价 | `OrderPlacementContractTest#rejectExpiredQuote；OrderPlacementContractTest#priceSelectionsFromAuthority` | ORD-01、CORE-01 |
| ORD-03 正式批次、核心绑定与恢复 | 042、132 | 短事务意图→核心建单→绑定/行分配/outbox；超时未知不重建；源行数量金额守恒 | `DiningRecoveryTest#recoverAfterCoreCreatedBeforeBinding；OrderPlacementContractTest#replayPlaceWithoutDuplicateOrder` | ORD-02 |
| ORD-04 批次列表详情与操作查询 | 043、123、125、133 | 仅当前授权门店/顾客会话可读；配置变化不覆盖已提交菜品组件选配快照 | `CheckCartTest#keepPlacedSnapshotStable；FoodStoreScopeTest#denyForeignBatchRead` | ORD-03 |
| ORD-05 取消批次与失败补偿 | 124 | 未支付取消走核心；UNKNOWN先查结果；取消后一次释放餐饮额度，厨房/退款另推进 | `DiningRecoveryTest#serializeCancelAndPayment；DiningRecoveryTest#releaseQuotaOnce` | ORD-03；CORE-01 |
| ORD-06 后付受理与多批次加菜 | —（内部/复用） | 复用点餐接口按门店能力选择先付/后付；获准后付才派工，多批次保留独立核心关联 | `DiningPostpayFlowTest#acceptPostpayOnce；DiningPostpayFlowTest#keepAddedBatchOutstanding` | CORE-02、ORD-03；联通KIT-01、PAY-01 |

草稿先独立验收；正式建单必须使用真实核心提供方。配置版本、选配快照、quote有效期、sourceLineRef、core binding和line allocation均保留；同SKU不同口味不得靠SKU反查源行。批次归属membership通过CheckService，不在订单域复制写入者。

#### 核心最小扩展任务（只为对应模块解锁）

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| CORE-01 报价、源行映射及幂等建单最小扩展 | —（内部/复用） | 在权威SPI补quote/place/findPlacement、源行及数量金额映射；兼容原整数Item；本地远程同合同，未知结果查原键 | `OrderPlacementContractTest#recoverUnknownPlacementByOriginalKey；OrderPlacementContractTest#keepDistinctSourceLines；RetailOrderCompatibilityTest#retainRetailPlacement` | 实施前核验真实核心签名 |
| CORE-02 后付库存承诺与堂食履约 | —（内部/复用） | 补受理/承诺库存/取消释放、未付款堂食制作交付、超时策略；核心不能照零售待支付超时撤单 | `DiningPostpayFlowTest#keepAcceptedUnpaidMealAfterRetailTimeout；RetailOrderCompatibilityTest#retainRetailTimeout` | CORE-01；核心独立变更及回归 |
| CORE-03 复杂应收、订金及支付分配合同 | —（内部/复用） | 按独立小变更补订金核销退款、跨根/AA/混合分配、应收调整及审批；各子能力单独开关，未实现保持禁用 | `DiningSplitPaymentTest#rejectUnsupportedAllocation；DiningSplitPaymentTest#conserveAllocatedMoney；RetailOrderCompatibilityTest#retainRetailPayments` | PAY-01；逐项签名评审后才实现 |
| CORE-04 按行部分退款与数量金额分摊 | —（内部/复用） | 补原成交行、已退/在途、折扣税费/支付分摊、库存归还及退款上限；零售全额合同保持 | `DiningPartialReturnTest#conserveDiscountAndRefundCap；RetailOrderCompatibilityTest#retainFullRefund` | CORE-01、CORE-03；独立核心回归 |

CORE-03不是一个大提交：按订金、单根调整、跨根分配、AA/混合逐项拆子任务，各自给出SPI签名、实际权威类路径、失败测试及零售回归；CORE-04同理先完成行退款合同。审计真实源码前不虚构不存在的权威方法。

### 2.7 MOD-04 厨房与履约管理

**文件/类范围：** 4张表；KitchenTaskService、CourseService/Impl，DiningOrderService.handoverBatch为跨模块复用；KitchenTaskPolicy、KitchenDispatchConsumer。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| KIT-01 受理事件、厨房单与工位任务 | 044 | 先付支付确认或获准后付事件才派工；消费者幂等，冻结路由；崩溃重放仍只有一份任务 | `KitchenTaskConcurrencyTest#dispatchOnceAfterAcceptance；DiningPrepayFlowTest#denyDispatchBeforePayment` | ORD-03、CAT-07；端到端需PAY-01 |
| KIT-02 制作、出品、上菜与交接 | 045、046、047、055 | 数量与状态CAS；制作、ready、serve、handover分轨；自取外带经核心合法交付入口 | `KitchenTaskConcurrencyTest#denyOverServe；DiningFulfillmentContractTest#handoverOnce` | KIT-01；核心交付合同 |
| KIT-03 等叫、叫起、催菜及道次 | 048、049、050、053、054 | 等叫不提前制作；道次放行幂等；催菜只追加服务事件及审计 | `KitchenTaskConcurrencyTest#urgeWithoutChangingQuantity；KitchenTaskConcurrencyTest#fireCourseOnce` | KIT-01 |
| KIT-04 撤菜、重做与退餐协作 | 051、052 | 撤菜与退餐按有效数量互锁；重做有来源，不改原支付事实；失败留可追踪状态 | `KitchenTaskConcurrencyTest#serializeVoidAndServe；KitchenTaskConcurrencyTest#retainRemakeOrigin` | KIT-02、RET-01 |

厨房大屏的服务端队列按工位/权限过滤。KIT-01可先测受理事件，但只有PAY-01或CORE-02联通后才算营业验收；KIT-04与RET-01联合验收。齐套、漏菜、拥堵另经GAP-07/08。

### 2.8 MOD-05 收银与结账管理

**文件/类范围：** 3张表；DiningCheckoutService/Impl、SettlementPolicy、FoodChargeRecoveryJob及CoreSubOrderPaidConsumer。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| PAY-01 预结冻结和单支付根收款 | 056、057、058、059、060 | 冻结源批次及应收版本；只支持获准单支付根；支付未知原键查询；终结校验核心已收清 | `DiningSettlementFreezeTest#serializeAddDishAndFreeze；DiningPrepayFlowTest#finalizeOnlyAfterAuthorityPaid` | ORD-03；核心支付签名核验 |
| PAY-02 顾客自助结账与支付 | 134、135、140 | 会话限定可见结账范围；顾客支付不写现金班次；重复点击返回同一支付意图 | `DiningPrepayFlowTest#replayCustomerPay；DiningCashPaymentTest#customerPayDoesNotCreateCashEntry` | PAY-01；顾客支付身份合同 |
| PAY-03 现金收款接入 | —（内部/复用） | 复用支付接口的获准现金方式，绑定班次/实收/找零及权威支付引用；支付成功而本地断电按原键恢复 | `DiningCashPaymentTest#recoverPaidCashWithoutDoubleEntry；DiningCashPaymentTest#rejectCashWithoutOpenShift` | SHIFT-01、SHIFT-02、PAY-01 |
| PAY-04 改价折扣、免单与复杂结账 | 061 | 调整保留审批/前后应收版本；AA混合跨根走核心分配；合桌不代表合并已付资金 | `DiningSplitPaymentTest#denyUnauthorizedAdjustment；DiningSplitPaymentTest#keepOriginalPaymentAllocation` | CORE-03、PAY-01；TBL-08 |

先完成单支付根在线收款，再现金、应收调整及复杂分摊。当前先付可能一批一支付根，不能将多个根拼成一个本地支付成功；多批后付结清需核心真实支持或分别实收并保留整体未结态。

### 2.9 MOD-06 退餐与售后管理

**文件/类范围：** 2张表；DiningReturnService/Impl、FoodReturnPolicy、FoodReturnRecoveryJob。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| RET-01 撤单与整单全额售后 | 062、063、064 | 厨房撤菜与财务退款独立状态；现能力仅获准整门店订单全退，按菜部分退显式拒绝 | `DiningReturnBaselineTest#rejectUnsupportedPartialReturn；DiningReturnBaselineTest#recoverRefundUnknown` | ORD-05、PAY-01；KIT-04联调 |
| RET-02 顾客退餐申请与查询 | 136、137 | 顾客可申请和查本人售后；执行与审批留商家授权；禁止借returnNo越权 | `DiningReturnBaselineTest#denyOtherCustomerReturnRead；DiningReturnBaselineTest#denyCustomerRefundExecution` | RET-01 |
| RET-03 按菜部分退、退配料套餐与恢复 | —（内部/复用） | 锁原餐饮源行，再汇总有效在途退量；按权威分摊退原支付，不假设remaining_qty列；失败恢复不超退 | `DiningPartialReturnTest#serializeTwoReturnsOnSameLine；DiningPartialReturnTest#refundBundleComponentsWithinCap` | CORE-04、RET-01 |

先验现有整单全额能力，按菜退款等CORE-04。服务数量与资金退款分别展示；加锁原源行后用selectActiveReturnQuantityByFoodLine聚合在途与已受理数量；insertAllocationIfAbsent不负责虚构核心退款容量。错单/换菜按GAP-05/09补专用合同。

### 2.10 MOD-07 打印、通知与设备管理

**文件/类范围：** 5张共享打印候选表；PrintService/Impl归共享打印，food只持有FoodPrintDocumentAssembler和业务触发。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| PRN-01 共享设备模板路由与餐饮单据冻结 | —（内部/复用） | 先补共享维护/自检合同；FoodPrintDocumentAssembler冻结厨房/预结/收款/退菜单，敏感字段按工位权限裁剪 | `FoodPrintDocumentAssemblerTest#freezeReceiptVersion；FoodPrintDocumentAssemblerTest#hideUnauthorizedCustomerFields` | GAP-10；共享打印唯一归属 |
| PRN-02 打印任务、查询、重试与补打 | 065、066、067 | 共享PrintService建任务/attempt/outbox；回执未知不宣称出纸；补打关联原任务与操作人 | `PrintDeliveryTest#trackUnknownReceipt；PrintDeliveryTest#retainReprintOrigin` | PRN-01；共享迁移另实施 |
| PRN-03 通知、KDS订阅与设备诊断 | —（内部/复用） | 复用通知订阅/设备治理；状态过期、重连重放与诊断权限受控，查询不等于推送 | `PrintDeliveryTest#rejectUnauthorizedDeviceSubscription；PrintDeliveryTest#resumeFromLastConfirmedEvent` | GAP-11、KIT-01 |

API065/067的共享PrintService直接使用受控单据解析及本地任务/outbox，不能递归调用自身PrintPort。设备/模板/路由维护先经GAP-10；通知及诊断经GAP-11。打印状态不能改变制作、交付或收款状态。

### 2.11 MOD-08 班次与现金管理

**文件/类范围：** 4张表；CashShiftService/Impl、CashDrawerPolicy。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| SHIFT-01 开班、现金收支与支付绑定 | 068、069 | 稳定资源仲裁防同钱箱双开班；流水只追加；资金记录与支付原号唯一绑定 | `CashDrawerConcurrencyTest#openOneShiftPerDrawer；CashShiftReconcileTest#linkPaymentOnce` | GOV-01；稳定钱箱仲裁合同 |
| SHIFT-02 盘点、差异及交班 | 070、071 | 实点与应有分别存，二次盘点留历史；有未知现金支付不可普通交班；差异需审批留痕 | `CashShiftReconcileTest#detectCashVariance；CashShiftReconcileTest#denyHandoverWithUnknownCashPayment` | SHIFT-01；上线现金前完成 |
| SHIFT-03 跨班未收责任交接 | —（内部/复用） | 未收应收只移责任，不修改原营业日、原订单和资金；移交与认领需双向证据 | `CashShiftReconcileTest#transferResponsibilityWithoutChangingBusinessDay` | GAP-12、SHIFT-02 |

为每钱箱建立稳定且唯一的开班仲裁；空shift集合FOR UPDATE不能防双开班。先SHIFT-01/02再启用PAY-03；电子支付不强制写现金流水。跨班未收经GAP-12只转责任。

### 2.12 MOD-09 营业日、日结与对账管理

**文件/类范围：** 5张表；BusinessCloseService/Impl、BusinessClosePolicy、FoodBusinessDayRolloverJob、FoodClosePrecheckJob。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| CLOSE-01 营业日归属、切换与预结 | 072、073 | 跨午夜营业日与门店时区统一；聚合订单支付退款现金证据，迟到事件形成差异不篡改归属 | `BusinessCloseEvidenceTest#keepBusinessDayAcrossMidnight；BusinessCloseEvidenceTest#detectLatePayment` | PAY-01、SHIFT-02；营业日基本归属在首单前接入 |
| CLOSE-02 异常结转、签核及关账后更正 | 074、075、076 | 未解异常阻止普通签核；获准结转保存责任；已签快照不可覆盖，更正以新记录追溯 | `BusinessCloseEvidenceTest#rejectSignWithUnresolvedException；BusinessCloseEvidenceTest#appendAdjustmentAfterSign` | CLOSE-01；审批合同 |

营业日标识与首单一同落定；后续补齐预结、异常、签核与更正。签核依据核心收退款与现金证据，不能只统计fnb_settlement状态。

### 2.13 MOD-10 经营报表管理

**文件/类范围：** 2张fnb定义/运行表与1张共享报表投影；DiningReportService/Impl、FoodReportWatermarkJob，ReportFoodMetricDailyRow/Dao归shop-report。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| RPT-01 经营报表与口径投影 | 077 | 营业/菜品/工位/支付退款/班次日结等按指标定义；shop-report唯一投影，水位与延迟可见 | `DiningReportRebuildTest#reconcilePaymentRefundAndClose；DiningReportAuthorizationTest#denyForeignStoreRange` | CLOSE-01；共享报表模型 |
| RPT-02 导出、授权及重建 | 078、079、080 | 导出用共享任务并验证创建人/门店范围；重建可重入、不中断原读视图；自然日历史口径保留 | `DiningReportAuthorizationTest#denyForeignExportDownload；DiningReportRebuildTest#rebuildIdempotently` | RPT-01；报表共享迁移另实施 |

报表范围包含经营总览、营业额与实收、支付退款、菜品/套餐/配料、工位时效、桌台翻台、时段渠道、员工、折扣免单、现金班次、日结差异及专项指标，以原需求矩阵J01～J13/L06定义为准。不能用fnb_report_run充当导出任务。

### 2.14 MOD-11 专项业态管理

**文件/类范围：** 5张表；DiningEntitlement、BanquetPlan、BottleCustody、RoomCharge各Service/Impl及对应策略、FoodRoomChargeRecoveryJob。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| SPEC-01 自助餐权益与续时 | 085、086 | 人数时段限量与核销；并发续时/退餐不重复权益；收费依核心报价 | `DiningEntitlementTest#extendOnceAndEnforceQuota` | ORD-06、RET-03 |
| SPEC-02 宴会方案及到场确认 | 087、088 | 预算/菜品方案与确认版本分离；实到人数报价、订金核销及后续结账留权威引用 | `BanquetPlanTest#confirmAttendanceWithVersion；BanquetPlanTest#retainDepositReference` | CORE-03；TBL-06、CAT-06 |
| SPEC-03 酒水寄存与领用流水 | 089、090 | 存入领用保留批次/有效期/责任人；并发领用不能负库存；不代替商品库存账 | `BottleCustodyTest#denyConcurrentOverdraw` | RET-01；对应商品库存合同 |
| SPEC-04 房账挂账及恢复 | 091 | 权威应收受理后确认成功，未知按原键查；拒绝把本地标记当已结清 | `RoomChargeContractTest#recoverUnknownAuthorityResult；RoomChargeContractTest#keepOutstandingWhenRejected` | CORE-03、PAY-01；权威房账合同 |

中餐主链重点覆盖共享桌菜/加菜/包间宴会，西餐补座位/道次/套餐/小费与服务费的核心计价合同；快餐自取外带、自助、酒吧寄存、酒店房账逐场景验收。服务费税费不在food本地另算资金。

### 2.15 MOD-12 第三方接入与离线营业

**文件/类范围：** 4张表；ExternalMappingService、IntegrationExceptionService、SyncBatchService/Impl、EdgeWriterLeasePolicy、FoodSyncRecoveryJob；供应商协议在food-channel。

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| INT-01 第三方映射与异常台账 | 082、083 | provider范围业务号映射唯一；平台只读获准门店异常，处理权限另定义 | `ExternalDiningContractTest#rejectDuplicateProviderMapping；FoodStoreScopeTest#denyForeignIntegrationException` | GOV-01、ORD-03 |
| INT-02 首个第三方端到端适配 | —（内部/复用） | 选定供应商后落实鉴权回调/商品订单配送同步/取消退款/对账；重复乱序重放有确定结果 | `ExternalDiningContractTest#replaySignedCallback；ExternalDiningContractTest#reconcileCancelledExternalOrder` | GAP-13、INT-01；供应商协议及沙箱 |
| EDGE-01 离线租约、单写者及本地配额 | —（内部/复用） | 租约签发续租隔离、fencing、断网权限和可售额度；离线现金与电子支付可用范围分别验证 | `OfflineSplitBrainTest#rejectStaleFence；OfflineSplitBrainTest#denyOfflineElectronicPaymentWithoutAuthority` | GAP-14；ORD/PAY/SHIFT合同 |
| EDGE-02 同步批次、回放与冲突对账 | 084 | 锁已验证存在的租约行，幂等认领同步批次并返回acceptedThrough；乱序缺口不冒充已接收，资金冲突人工核对 | `OfflineReplayReconcileTest#recoverReplayAfterPartialAck；OfflineReplayReconcileTest#detectCashConflict` | EDGE-01、INT-01 |

第三方和离线均属于完整范围。当前3个接口只覆盖映射/异常查询/同步批次；完整合同须GAP-13/14补齐。在线双部署不是离线收款，数据库批次幂等不是租约单写者协议。

### 2.16 设计缺口补齐任务（完整范围不可遗漏）

每项先交付请求/响应、状态转换、权限/错误码、持久化/迁移归属、幂等恢复及真实验收场景；经原真源更新/生成守卫后，由表中模块继续实现。以下测试是新增合同测试目标，尚不存在，不计为已通过；同名场景优先加到已有业务测试类，避免平行测试体系。

| 补约任务 | 需求 | 要补齐的合同 | 实施去向 | 目标失败测试 |
|---|---|---|---|---|
| GAP-01 | FNB-C01 | 预约预点：草稿引用、修改截止、到店转正式单及失效恢复 | TBL-06、ORD-01 | `ReservationPreorderContractTest#convertDraftOnceOnArrival` |
| GAP-02 | FNB-C03 | 共享排队：取号/叫号/过号/取消/到店及通知权限 | TBL-06、PRN-03 | `DiningQueueContractTest#separateQueueNumberFromPickupNumber` |
| GAP-03 | FNB-B09 | 称重：标称/实称/允差确认、补差报价、库存数量精度与退款 | CAT-01、ORD-02、CORE-01、RET-03 | `WeighedDishContractTest#confirmActualWeightBeforePriceChange` |
| GAP-04 | FNB-B11 | 商品导入：预检、行级错误、结果导出与餐饮字段绑定 | CAT-01 | `DishImportContractTest#rejectInvalidRowsBeforePublication` |
| GAP-05 | FNB-D06 | 赠菜/换菜/缺货替换：顾客确认、原新源行及差额审批 | ORD-03、PAY-04、RET-03 | `DishReplacementContractTest#retainOldAndNewLineRelation` |
| GAP-06 | FNB-D09 | 预售：截单/取消规则、产能预占、定时派工、断点恢复 | ORD-03、KIT-01 | `ScheduledDiningContractTest#dispatchOnceAfterScheduledTime` |
| GAP-07 | FNB-E05, FNB-E06 | 撤回交接/漏菜反馈/齐套打包核对：专用数量事实及冲销动作 | KIT-02 | `DiningFulfillmentContractTest#requirePackingConfirmation` |
| GAP-08 | FNB-E10 | 超时和拥堵：阈值、告警订阅、暂停接单与恢复 | KIT-01、GOV-01、PRN-03 | `KitchenCongestionContractTest#resumeOnlyAfterAuthorizedRecovery` |
| GAP-09 | FNB-G06 | 错单重开和离线补录：认领来源、申请审批及销量处理 | RET-03、EDGE-02 | `DiningCorrectionContractTest#reopenWithoutDuplicateSales` |
| GAP-10 | FNB-A08, FNB-H01 | 共享设备绑定/自检、模板版本、路由维护及开班检查聚合 | PRN-01 | `PrintDeviceContractTest#rejectInvalidRouteBeforeShiftOpen` |
| GAP-11 | FNB-H06, FNB-H07 | KDS订阅/通知/语音及远程诊断：鉴权、配置下发、状态过期 | PRN-03 | `DeviceSubscriptionContractTest#expireUnauthorizedDiagnosticSession` |
| GAP-12 | FNB-I07 | 跨班未收：移交、接收、拒收与责任争议，保持原营业日 | SHIFT-03 | `CashShiftReconcileTest#requireResponsibilityAcceptance` |
| GAP-13 | FNB-K02 | 第三方供应商：完整协议矩阵、沙箱、回调验签、退款配送及对账 | INT-02 | `ExternalDiningContractTest#rejectInvalidCallbackSignature` |
| GAP-14 | FNB-K03, FNB-K04 | 离线租约/隔离、边缘持久化、可售额度、支付权限、冲突及回放 | EDGE-01、EDGE-02 | `OfflineSplitBrainTest#isolateTwoWritersDuringPartition` |

GAP-01/02在MOD-01完整验收前关闭；GAP-03/04在称重/批量导入开放前关闭；其余按对应能力启动前关闭。这里“关闭”指补约、实现、测试完成，不是删除需求。计划不以MVP为理由移除18项需求。

### 2.17 发布试点任务

| 任务 | 首次API | 交付与验收行为 | 失败测试（目标，尚未执行） | 依赖/启用条件 |
|---|---|---|---|---|
| REL-01 在线试点、双部署及发布回退演练 | —（内部/复用） | 真实订单支付厨房退餐打印班次日结报表贯通；禁用新流量但保留未结单恢复和查账；应用回退不删业务表 | `FoodDeploymentContractTest#disableNewOrdersButRecoverOutstanding；DiningPrepayFlowTest#completeOnlineBusinessDay` | 在线试点验收门槛全部通过 |
| REL-02 专项第三方及断网实机试点 | —（内部/复用） | 供应商实单/真设备断网重连/双边对账；单独灰度开关与授权边界，按门店逐项启用 | `OfflineReplayReconcileTest#reconnectPhysicalStoreWithoutDuplicateSettlement；ExternalDiningContractTest#reconcileProviderSandbox` | 相关SPEC/INT/EDGE任务通过 |

发布前登记构建版本、配置开关、迁移校验、权限种子、事件/Job消费位置、设备与门店白名单及未结单恢复清单。回退演练覆盖停新单、保留查询/收退款恢复、取消新配置发布和恢复原部署；不得以删除表或修改冻结V1回退。

## §5 验证计划与验收证据

### 5.1 本次计划交付的检查

只运行文档/设计结构检查：任务首次API归属并集141且无重复；109项需求均有主模块和实施去向；18项DESIGN_GAP有专属补约任务；局部Markdown链接有效；相关设计守卫与diff空白检查。本次不声称Java编译、真实业务SQL、资金、设备和离线试点已经通过。

### 5.2 实施时使用的命令

以下为未来实施命令，五模块和测试类创建后才能执行。必须确认测试数大于0，不能把`failIfNoSpecifiedTests=false`跳过测试当通过。

```bash
# 桌台：逐任务选择Class#method；依赖模块可无该测试，但目标模块必须实际执行
mvn -f backend-plugins/shop-industry-food/pom.xml -pl food-core -am test -Dtest='TableServiceTest#replayAreaCreation' -Dsurefire.failIfNoSpecifiedTests=false
# 餐饮全模块构建、测试（集成测试按项目实际Surefire/Failsafe配置登记）
mvn -f backend-plugins/shop-industry-food/pom.xml verify
# 对宿主/核心改动运行实际零售与架构回归；本命令只代表架构，不代替业务回归
mvn -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
# 契约或类/方法真源变化后，重建清单并校验
node scripts/gen-food-code-inventory.mjs
node --test scripts/tests/food-method-inventory.test.mjs scripts/tests/food-module-code-inventory.test.mjs scripts/tests/food-design-contract.test.mjs scripts/tests/food-schema-sql.test.mjs
node scripts/gen-food-schema.mjs --check
```

每任务验收表至少记录：任务ID、FNB编号、API、实际测试Class#method、数据库/通道环境、执行命令、通过/失败数量、关键断言、消融结果和commit。真实数据库锁/事务测试须在目标数据库执行；当前本机MariaDB与生产MySQL的差异需要目标版本复测，不把DDL方言验证等同业务锁验证。

### 5.3 模块完成与上线门槛

- **模块切片完成：** API入出参、权限、Service/Mapper事务、幂等及失败恢复、归档版本和指标审计均有证据；数据库/API/类方法清单一致。未到位合同明确禁用。
- **在线试点门槛：** M0～M4与M6通过，明确试点为先付或已验收后付；支付退款对账、现金差异、日结签核与报表一致；打印已按实际设备验收。扫码、收银、服务员、厨房、老板和平台的后端权限分别验证。
- **完整功能门槛：** 109项需求逐条验收、18项设计缺口关闭、CORE各子能力真实联通；第三方和离线不能只靠mock过关。
- **双部署门槛：** 两种模式同一业务合同套件；身份范围、超时、幂等、恢复和数据隔离等价，主数据源/迁移只有受控执行者。
- **上线观测：** 建单/支付/退款UNKNOWN、恢复积压、占用异常、额度异常、厨房超时、打印失败/未知、现金差异、日结阻塞、报表水位、第三方错误和离线冲突可按门店追踪原业务号；告警接现有体系。

## §6 本轮交付与后续回填

本轮重整原B0～B11宽泛工作包为模块功能任务；原计划涉及的构建、身份、报价、先付、后付、厨房打印、复杂资金、日结报表、第三方离线和专项范围均保留，业务主顺序明确为桌台→菜品→订单。未运行数据库迁移、未生成业务Java类、未提交git。

后续首个执行单元为FND-01（运行骨架），接FND-02/GOV当前切片，再执行TBL-01。每切片更新下表；任务表中的测试和方法是计划，不是已运行记录。

| 任务 | 实际文件/commit | 实际测试及结果 | 偏差/未决条件 |
|---|---|---|---|
| 本轮计划 | 本TDD、开发任务对照索引 | 见下方本轮检查记录 | 不含Java业务实现 |

本轮实际检查（2026-10-20）：

- 四组方法/模块/设计/DDL守卫：`node --test scripts/tests/food-method-inventory.test.mjs scripts/tests/food-module-code-inventory.test.mjs scripts/tests/food-design-contract.test.mjs scripts/tests/food-schema-sql.test.mjs`，27项通过、0失败、0跳过。
- 冻结DDL检查：`node scripts/gen-food-schema.mjs --check`，确认73张fnb表、共享prn/rpt排除；未连接或修改数据库。
- 计划对账：解析任务定义及索引，核验61个实施任务、14个补约任务、141个API唯一首次任务、109项需求及18项缺口覆盖；两份文档本地链接有效。
- 只对本轮两份计划文档做空白检查；Java业务测试、设备和资金联调仍待实施。

### CAT-01A 商品基础事实接线 · 2026-10-21

桌台基础接待之后，按MOD-02进入菜品模块。需求见[CAT01菜品基础需求](../requirements/餐饮系统-CAT01菜品基础需求细化.md)，代码合同、实施步骤和验收见[CAT01实施方案](./TDD-餐饮系统-CAT01菜品基础实施方案.md)。本轮已交付商品基础事实Port及本地/远程同语义，接下来开发API016维护、113详情与112列表；实际验证及已有全局架构失败见CAT01方案§5。

本切片只新增只读商品元数据接口，不修改原GoodsQueryPort交易快照、报价、库存或订单。fnb_dish沿goods_no唯一且无store_no，归属由商品权威判定；交易就绪仍关闭。桌台跨域剩余任务继续保留，进入菜品不代表其完成。
