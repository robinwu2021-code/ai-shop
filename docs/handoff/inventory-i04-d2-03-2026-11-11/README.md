# I04 库存对接交接：零售补货数量闭环

日期：2026-11-11。提交范围：三行业简单库存统一抽象、餐饮补货及恢复、零售来源认领和数量闭环（I04-A/B/C/D1、D2-01/02/03），以及这些能力的代码、迁移、限定合同、测试和幂等规范。下一批从 **D2-04 正式零售入口与能力准入** 开始。其他业务继续暂停。

本交接随代码提交；提交号以 Git 中本文件所属提交为准。推送分支为 `codex/food-next01`，远端为现有 `origin`。这是源码交付，未部署本批迁移、未翻转业务库存 writer，也不代表真实商家/App/166 营业验收通过。

## 1. 已确认的业务口径

三行业不依赖进销存也能保留简单库存；本地指服务端原有业务记录，不是 App 离线缓存或内嵌进销存引擎。

- **LOCAL**：沿原本地库存执行，不要求进销存在线。
- **REPLENISH**：源仓正式出库已经扣源仓；本地按实际收货加库存，订单、赠品、履约及售后仍沿本地原账。销售不能再扣源仓一次。
- **SHARED_SYNC**：共享同一物理池时，必须有真实远端同步预留/扣减合同；本批未开放。
- **DELEGATED**：只有真实独占授权额度才能支持本地额度执行；普通线上额度不是授权。本批未开放。

餐饮按配方/物料/成品消费；零售按实物 SKU 和原主体/门店池消费；美业区分实物、耗材、服务次数和资源容量。统一抽象不改变行业账本及消耗阶段。镜像同步是独立开关，不是本地余额的另一个 writer。

主设计：[统一 TDD](../../technical/design/TDD-三行业简单库存与进销存统一对接方案.md)，需求：[进销存需求 §十四](../../requirements/进销存-需求.md)，关键决策：[ADR-057](../../technical/ADR/ADR-057-补货来源统一认领与零售库存写入互斥.md)，幂等唯一正文：[幂等设计规范](../../technical/reference/幂等设计规范.md)。

## 2. 完成范围和代码入口

以下路径均相对于仓库根目录；完整提交文件清单见[files.txt](./files.txt)。

| 能力 | 状态和主要落点 |
|---|---|
| 三行业只读库存、策略、预检 | `shop-base/.../spi/product/LocalStockControlPort`、`LocalStockFactsPort`；`shop-core/.../product/service/LocalStockControlService`、`policy/LocalStockStrategyPolicy`；公开入口 `POST /biz/stock-control/preflight`，只读预检不授权切账 |
| 可信源事实 | `inventory-api/.../replenishment/ReplenishmentContracts`；`inventory-domain/.../ReplenishmentQueryService`；`inventory-svc/.../ReplenishmentController`；经 `InventoryClient` 和宿主 `InventoryReplenishmentSourcePortImpl` 调用，源事实不能由 App 自填 |
| 餐饮补货和恢复 | `FoodStockReplenishmentPort`、food-core `FoodStockReplenishmentPortImpl`、food-web `RemoteFoodStockReplenishmentPort`、food-server `InternalFoodStockReplenishmentController`；宿主 `StockReplenishmentServiceImpl` 与 `BizStockReplenishmentController` |
| 餐饮 App | `b-app/src/pages/stock-replenishments/index.vue`、`api/replenishment*.ts`、`shared/replenishment-command.ts`；启停、待核对列表、原号查询/恢复/封存，保留未知结果的原键和参数；此页面不是已完成的零售页面 |
| 零售目标池 | `RetailReplenishmentTargetPortImpl` + `StoreStockReader.lockPool`；冻结原实体/门店、SKU、市场、单位和真实行身份，不按当前状态重绑 |
| MAIN 来源认领和连接 | `StockReplenishmentAdmissionServiceImpl`；MAIN 全局来源唯一，来源占用先持久化，再调用 FOOD；远端未知保留意图，不冒充执行成功 |
| 零售数量动作 | `RetailStockReplenishmentServiceImpl`：累计实收、差异关闭、累计退仓、实际源验收、正式有符号调整、真实人工凭据关联及只读核对；**目前仅内部 Java 服务，无公开零售 HTTP/App** |
| 原子记账及旧 writer 围栏 | `StockReplenishmentCommands` 共用既有永久回执；`RetailStockReplenishmentLedger` 条件增减 `prd_sku` / `prd_store_stock`；`StockReplenishmentWriterGuard`、`StockSyncServiceImpl`、`MerchantGoodsServiceImpl` 阻止接管后旧绝对值覆盖和未支持的改池 |

`shop-base/shop-core/shop-app` 均在 `backend/`；FOOD 在 `backend-plugins/shop-industry-food/`；独立进销存在 `backend/inventory/`。独立源客户端配置从库存执行引擎配置中分离：只配置远端传输不启动内嵌引擎，也不切业务 writer。

限定合同：[LOCAL 预检](../../api/local-stock-control.openapi.json)、[补货和恢复](../../api/stock-replenishment.openapi.json)。后者保留 24 条既有公开/源/FOOD 内部路径；`x-retail-local-effects` 只描述内部零售服务，不能当成已发布零售端点。

## 3. 数据、事务与恢复

### 3.1 迁移

| 所属账本 | 文件 | 用途 |
|---|---|---|
| FOOD | `food-core/src/main/resources/db/industry/food/V38__food_stock_replenishment.sql` | 补货原件、源仓退回验收事实 |
| FOOD | `food-core/src/main/resources/db/industry/food/V39__food_stock_replenishment_recovery.sql` | 启停代次/水位、人工凭据、旧原请求恢复 |
| MAIN | `shop-app/src/main/resources/db/migration/V659__retail_stock_replenishment_admission.sql` | 来源原件、连接、成功命令回执、实体 writer 围栏 |
| MAIN | `shop-app/src/main/resources/db/migration/V660__retail_stock_replenishment_effects.sql` | 关闭原因、原件整数数量约束、真实变动流水、人工关联和源退仓验收 |

本地测试执行了实际 MySQL9 迁移；目标环境尚未执行本批发布。本次提交不修改历史冻结迁移，也不新增库存余额表。共享 `schema-test.sql` 只提交上述 MAIN 库存夹具，其他会话的账号/审批/打印夹具不在本提交中。

### 3.2 核心不变量

1. MAIN 来源唯一键为租户、源系统、源 owner、源单、源行，不含行业、门店或接入代次。关闭再开启、换请求键都不能重复认领同一物理来源；FOOD 历史查询包含归档原件。查不到历史组件或历史查询失败不能当作“从未入账”。
2. 永久请求身份为作用域、动作和原始请求键；同键异参或操作人变化拒绝，完成后恢复原成功快照。新 MAIN 原键使用 NO PAD 二进制排序规则，不 trim、不截断。不是新增通用幂等引擎。
3. 数量事务按实体围栏→原件→商品→SKU→门店池加锁；同事务写原余额、流水、累计量和成功回执，失败全部回滚。源 RPC 必须在该本地锁事务外执行。
4. 实收 18 再累计收 20，只增加 2；短收关闭记录剩余差异，不伪造实收。退仓只按累计差额减原池，不能低于锁定量。
5. 源 RETURN 必须已正式过账，来源/物品/单位正确、`sourceRef` 对应 SRP 原件，按冻结 factor 精确换成本地正整数；验收量不超已发出量，源行永久唯一，验收不再扣本地。
6. 正式 ADJUST 写真实 delta/before/after/reserved 与成功凭证。人工收货关联只接受同范围/同目标的真实 ADJUST_IN 及其成功回执；一条流水只能全量归一原件，关联不再加库存。旧绝对值和当前库存不能反推为人工凭证。
7. 对账只比较本原件的自动实收、人工关联、退仓和源验收。期初、销售及其他调整仍会改变同一本库存，不能用“本单实收减退仓”推导整本余额；无源事实报告未核实，不自动修账。
8. REPLENISH 关闭连接只停止新来源准入，原件可继续收尾，writer 不自动回到 LEGACY。现有商品价格/文案编辑保留实际余额；旧绝对值改数、已有 SKU 单位/新增市场/退休和无迁移切池拒绝。销售、赠品、取消、履约和顾客售后沿原路径，顾客退回与退源仓分开。

MAIN 先认领再调用 FOOD 不是跨库原子事务。MAIN 的 EXTERNAL 意图不能代替 FOOD 成功回执；结果未知时使用同原号恢复，不能自动解绑或改派来源。

## 4. 验证与复现

D2-03 已记录：45 模块受影响编译通过；38 项不同测试方法全通过，零跳过，其中 30 项真实 MySQL9（原准入 18、数量效果 12），另有所有权围栏 3 和宿主编排 5。数量效果覆盖并发同键、成功回执失败整笔回滚、原池隔离、实际订单/赠品/取消/V2履约和顾客可售/不可售退回。一次独立只读复核无 P1/P2 或延期 minor。

此前 A/B/C/D1 证据各在统一 TDD §14—17，不能将多次运行数字累加成新增功能。真实源/FOOD 历史、商家资料等测试替身与真实本地账本的边界已在 TDD 写明。

本次为拆分共享工作区后的提交快照重新核对依赖；结果见[verification.md](./verification.md)。原始临时日志路径用于本机追溯，不要求接手人拥有 `/tmp` 旧文件；可随当前代码重新执行受影响用例。

编译环境：JDK21、Maven（项目私有父 POM 和 common 依赖须已配置）、离线缓存；独立库存先构建，再构建宿主/FOOD：

```sh
mvn -o -f backend/inventory/pom.xml -Dmaven.test.skip=true -Dcheckstyle.skip=true install
mvn -o -f backend/pom.xml -Pfood-embedded,food-verify -pl shop-app,:food-server -am -Dcheckstyle.skip=true compile
```

MySQL 原生测试使用 `127.0.0.1:3308` 的 MySQL9，设置 `I04B_NATIVE_MYSQL=true` 和 `R01_MYSQL_URL/R01_MYSQL_USER/R01_MYSQL_PASSWORD`；URL 指向本机 `information_schema`，用例创建并清理随机隔离库。仅从本机私有配置或环境注入凭据，不写入仓库。运行原生用例前确认不是共享业务数据库。

核心测试类：`RetailStockReplenishmentAdmissionNativeMysqlTest`、`RetailStockReplenishmentEffectsNativeMysqlTest`（共享 `RetailStockReplenishmentNativeFixture`）、`FoodStockReplenishmentNativeMysqlTest`、`RetailReplenishmentTargetNativeMysqlTest`；API/传输另见 `StockReplenishmentApiTest`、`FoodReplenishmentAdmissionContractTest`、`FoodStockReplenishmentSignedHttpTest`、`InventoryReplenishmentSourceHttpTest`。可用既有 Maven/JUnit 选择器运行，FOOD 原生类需 `food-embedded` profile。

餐饮 App 定向复现：

```sh
cd b-app
../node_modules/.bin/vitest run --config vitest.config.mts tests/replenishment-command.test.ts tests/replenishment-http.test.ts tests/replenishment-page.test.ts
../node_modules/.bin/vue-tsc --noEmit
```

## 5. 发布前置与回退边界

本次只推送源码。后续部署须独立核对 MAIN/FOOD 制品、迁移、主库认领生产者与内部签名凭据，不能直接以本提交开营业开关。

1. 先使 FOOD 拒绝陌生旧 HTTP 注册，并部署新完整 body 签名合同；再升级所有会调用 FOOD 的宿主生产者，保证先 MAIN 持久认领再签名调用。
2. 必须升级或退出旧嵌入式 FOOD 宿主和未接实体围栏的 StockSync 节点，排空在途旧写回，不能与新零售准入混跑。
3. 核对 V659/V660 与 FOOD V38/V39、实际选中 PLATFORM 执行器、历史 FOOD 来源可查询、水位和目标池后，才按后续 D2-04/H 流程显式准入指定商家/门店。
4. `source-system` 是稳定来源身份，配置键为 `shop.inventory.replenishment.source-system`；远端连接沿现有 `shop.inventory.service.*`。不得为重试随意换源系统号。凭据只走部署配置。
5. 关闭新准入不删除原件、流水或回执，不丢弃在途业务；接管后不能直接回滚到会绝对覆盖库存的旧版本。需保留认识新状态的程序处理收尾；跨权威或跨池迁移另做受控方案。

## 6. 后续任务与接手起点

| 顺序 | 任务与验收出口 |
|---|---|
| D2-04（下一批） | 正式零售 ai-shop 入口与权限/门店范围；可信源查询、原请求恢复编排；仅在实际能力就绪时发布 REPLENISH；新连接/来源认领/命令历史纳入 StoreRelink 改挂预检，不能因空目录而放过历史 |
| D2-05 | 零售 App 登记/实收/差异/退仓/源验收/正式调整关联/核对，未知结果保留原参数原键，作用域切换不重发旧操作；不复用餐饮页面冒充零售闭环 |
| D2-06 | 本地正式接口与 App 闭环验收，覆盖启停/代次/旧原件/权限/售后/并发恢复；不重复构造第二套幂等机制 |
| I04-E/F/G | 美业策略适配、真实共享同步预留、独占额度合同与生命周期；按各自依赖分批执行，当前未开放 |
| I04-H | 选择或新建测试商家/门店，指定版本/迁移/凭据与源仓，验证真实 App→ai-shop→独立进销存完整链路；用户已授权自动选择或新建样本，仍须保留隔离和可追溯记录 |
| 后续扩展 | 完整盘点/调拨业务、受控跨池/跨权威迁移；本批正式增减调整不等于完整盘点流程 |

无需重新实施既有商品发货、会员积分等业务，也不恢复已暂停的其他清单项。下一开发者先读根 `MEMORY.md`、本交接、统一 TDD §17.4 和 §17.10—17.11，再继续 D2-04。
