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

## 5. 下一步

| 任务 | 说明 |
|---|---|
| I04-H | 选择/新建受控零售测试商家与门店，部署本分支、配置 `shop.inventory.replenishment.source-system` 与源客户端凭据，在 Android 模拟器/真机走 App→ai-shop→独立进销存全链；记录真实流水/余额 |
| 小项 | 首页按门店经营类型隐藏不适用的补货入口；App 端策略选择接 REPLENISH 预检 |
| 既有欠账 | 餐饮补货已发布路径的复数段/胖控制器、`/biz` 其他域 5 条权限缺口，按各自负责人处理 |
| 后置 | SHARED_SYNC（I04-F）、DELEGATED（I04-G）、美业 I04-E、完整盘点与跨池迁移 |
