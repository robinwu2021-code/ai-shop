# I04 库存对接交接：零售补货正式入口与 App（D2-04/05/06）

日期：2026-11-11。分支 `codex/food-next01`，承接 [D2-03 交接](../inventory-i04-d2-03-2026-11-11/README.md)。本批为源码交付：未推送、未部署迁移（本批无新迁移）、未签发凭据、未在真机或 166 营业验收。

## 1. 本批完成

| 能力 | 落点 |
|---|---|
| 零售正式入口 | `shop-core/.../product/api/biz/BizRetailStockReplenishmentController`，路径 `/biz/stock-control/retail-replenishment`（context、connection、列表/登记、adjustments、`{no}`、receive、close、return-dispatch、return-ack、manual-receipt、reconciliation），权限 `biz:stock`，范围由 `LocalStockAccessPolicy` 解析 |
| 来源编排 | `RetailStockReplenishmentService` 新增重载：先按原键恢复已完成回执，再在本地事务外调可信 `StockReplenishmentSourcePort`，最后进入 D2-01/03 原事务；`StockReplenishmentAdmissionService.recoverConfiguration` |
| REPLENISH 预检 | 仅 RETAIL+SKU，且该门店零售接入实际开启；FOOD/美业/共享/独占仍 `STRATEGY_NOT_ENABLED` |
| 改挂预检 | `StoreRelinkHostFactsPortImpl` 计入补货原件、连接、回执、流水、人工关联、源验收与 writer 围栏 |
| 商家 App | `b-app/src/pages/retail-replenishments/index.vue`、`api/retail-replenishment*.ts`、`shared/retail-replenishment-command.ts`、三语 `retailReplenishment` 词条、首页入口 |
| 合同 | [补货 OpenAPI](../../api/stock-replenishment.openapi.json) 新增 12 个零售操作；`BizEndpointPermTest` 登记全部 `/biz/stock-control/*`；UI 清单 336、进销存界面清单 16 屏 |

设计与证据：[统一 TDD §17.12—17.13](../../technical/design/TDD-三行业简单库存与进销存统一对接方案.md)。

## 2. 关键口径

1. 线上计数一律整数；请求体不接受 target/owner，调整的目标池由服务端按实际池观察，同键重放沿原 Target。
2. 零售命令与永久回执原子提交，没有“已认领未执行”中间态。App 对未知结果按原实体/门店/账号、原键、原 body 重发；明确 4xx 才移除待核对项；换店/换账号不重发。不另做封存或状态查询。
3. FOOD 与 RETAIL 入口分离：FOOD 门店无法开启零售接入（不留 writer），零售页面不调用餐饮路径。
4. 关闭接入只停新增来源，旧原件可继续收尾；未装源客户端时核对报 `SOURCE_UNVERIFIED`。

## 3. 验证复现

```sh
mvn -o -f backend/inventory/pom.xml -Dmaven.test.skip=true -Dcheckstyle.skip=true install
mvn -o -f backend/pom.xml -pl shop-core -am -Dcheckstyle.skip=true -Dsurefire.failIfNoSpecifiedTests=false test \
  -Dtest='RetailStockReplenishmentApiTest,StockReplenishmentApiTest,LocalStockControlServiceTest,LocalStockPreflightApiTest,StoreRelinkHostFactsPortImplTest,StockReplenishmentServiceTest'
I04B_NATIVE_MYSQL=true R01_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/information_schema' R01_MYSQL_USER=… R01_MYSQL_PASSWORD=… \
  mvn -o -f backend/pom.xml -pl shop-app -am -Dcheckstyle.skip=true -Dsurefire.failIfNoSpecifiedTests=false test \
  -Dtest='RetailStockReplenishmentEntryNativeMysqlTest,RetailStockReplenishmentEffectsNativeMysqlTest,RetailStockReplenishmentAdmissionNativeMysqlTest'
cd b-app && ../node_modules/.bin/vitest run --config vitest.config.mts tests/retail-replenishment-command.test.ts tests/retail-replenishment-page.test.ts && ../node_modules/.bin/vue-tsc --noEmit
```

本机结果：shop-core 66/66；MySQL9 原生 34/34（新增 4）；App 10 新增 + 17 餐饮 = 27/27；vue-tsc 0。单模块 `-pl shop-core` 不带 `-am` 会读到 `~/.m2` 旧 shop-base，必须走 reactor。

## 4. 闸门状态（未放宽任何基线）

- pre-push 通过到“后端无 App 入口”为止；该闸基线外 27 条与 D2-03 记录逐条相同，无库存项。
- controller-cohesion、sql-portability、generated-docs 三项在交接提交 8363a3084 上同样失败（餐饮补货控制器/路径、独立库存迁移排序规则），本批未新增条目。
- `BizEndpointPermTest` 仅余 5 条其他域既有缺口；`ArchitectureTest` 既有违规不含本批类。
- `food-embedded-test` 的 `FoodChannel*` 在 `-pl shop-app -am` 下缺 commerce channel 模块而编译失败，属既有问题；本批改动的 `LocalStockPreflightNativeMysqlTest` 无编译错误。

## 5. 下一步（2026-11-12 重排）

2026-11-12 补充事实：商户 App 的正式实现是 Flutter 仓库 `ai-shop-bapp`（本仓库 uni-app `b-app` 已归档，只作参考实现）；零售补货已移植到 bapp 分支 `codex/retail-replenishment`（本地提交 cc7cf0b，未推送）。移植时发现 `/biz/stock-control/*` 未登记 `ShopStoreMemberRoutes`，新模型门店会在控制器前被 70006 拒，已在本分支修复（1c1adfad7）。App 实际对接的后端分支是 `codex/ai-shop-codex`，本分支缺它的 `/biz/merchant/store-setup` 等登录后入口，二者需汇合。

| 顺序 | 任务 | 完成条件 | 状态 |
|---|---|---|---|
| R1 | 模拟器闭环验收 | `codex/ai-shop-codex`+本分支本地合并包、真实独立 inventory-svc（已过账调拨出库/退货入库）、MySQL9；`pos_phone` 上用 Flutter App 走开启→登记→实收→调整关联→退仓→验收→核对，记录截图与库存流水 | 进行中 |
| R2 | 后端分支汇合 | 本分支正式合入 `codex/ai-shop-codex`；冲突 2 处（`schema-test.sql` 需合并两边夹具、`ui-catalog.json` 重新生成）；合并后重跑零售原生/接口测试 | 待做 |
| R3 | 门店路由闸门自动化证据 | 修好 `StoreScopedDailyRoutesFlowTest` 上下文（缺 `commerceMigrationInitializer`），让新增 `stockControlRoutesPassAdmission` 真跑 | 待做 |
| R4 | Flutter 分支收口 | bapp 分支推送到其 origin；在 bapp 功能矩阵/接口对接记录登记零售补货；入口只对零售门店、`biz:stock` 可见的 widget 测试 | 待做（R1 后） |
| R5 | uni-app 页面处置 | 决定 `b-app/pages/retail-replenishments` 保留为参考还是随归档冻结；UI 清单口径同步 | 待定 |
| R6 | App 端策略选择接 REPLENISH 预检 | 零售门店在 App 里看到“可补货”能力由预检决定，而非开关 | 低优先 |
| I04-H | 目标环境验收 | 166 执行 V659/V660、配置 `shop.inventory.replenishment.source-system` 与源客户端凭据，选定测试商家走真实 App→ai-shop→独立进销存 | 待 R1–R4 |
| 既有欠账 | 餐饮补货复数路径/胖控制器、`generated-docs` 闸门、其他域 5 条权限缺口 | 各自负责人处理，不在零售批次内改名已发布接口 | 记录 |
| 后置 | I04-F SHARED_SYNC、I04-G DELEGATED、I04-E 美业、完整盘点与跨池迁移 | 按总计划 | 暂停 |
