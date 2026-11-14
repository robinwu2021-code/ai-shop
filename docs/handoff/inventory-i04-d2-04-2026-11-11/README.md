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
| R1 | 模拟器闭环验收（2026-11-12 本地完成，见第 6 节） | `codex/ai-shop-codex`+本分支本地合并包、真实独立 inventory-svc（已过账调拨出库/退货入库）、MySQL9；`pos_phone` 上用 Flutter App 走开启→登记→实收→调整关联→退仓→验收→核对，记录截图与库存流水 | 本地完成 |
| R2 | 后端分支汇合 | 本分支正式合入 `codex/ai-shop-codex`；冲突 2 处（`schema-test.sql` 需合并两边夹具、`ui-catalog.json` 重新生成）；合并后重跑零售原生/接口测试 | 待做 |
| R3 | 门店路由闸门自动化证据 | 修好 `StoreScopedDailyRoutesFlowTest` 上下文（缺 `commerceMigrationInitializer`），让新增 `stockControlRoutesPassAdmission` 真跑 | 待做 |
| R4 | Flutter 分支收口 | bapp 分支推送到其 origin；在 bapp 功能矩阵/接口对接记录登记零售补货；入口只对零售门店、`biz:stock` 可见的 widget 测试 | 待做（R1 后） |
| R5 | uni-app 页面处置 | 决定 `b-app/pages/retail-replenishments` 保留为参考还是随归档冻结；UI 清单口径同步 | 待定 |
| R6 | App 端策略选择接 REPLENISH 预检 | 零售门店在 App 里看到“可补货”能力由预检决定，而非开关 | 低优先 |
| I04-H | 目标环境验收 | 166 执行 V659/V660、配置 `shop.inventory.replenishment.source-system` 与源客户端凭据，选定测试商家走真实 App→ai-shop→独立进销存 | 待 R1–R4 |
| 既有欠账 | 餐饮补货复数路径/胖控制器、`generated-docs` 闸门、其他域 5 条权限缺口 | 各自负责人处理，不在零售批次内改名已发布接口 | 记录 |
| 后置 | I04-F SHARED_SYNC、I04-G DELEGATED、I04-E 美业、完整盘点与跨池迁移 | 按总计划 | 暂停 |

## 6. R1 模拟器闭环验收记录（2026-11-12）

环境全部为一次性本机环境：`codex/ai-shop-codex`+本分支的**临时本地合并包**（不提交、不推送），MySQL 9.7.4 临时实例（127.0.0.1:3308，独立数据目录），当前源码构建的独立 inventory-svc（18086），Flutter 商户 App 零售构建（bapp 分支 `codex/retail-replenishment`，cc7cf0b）装在新建的零售专用模拟器 `retail_phone`（emulator-5590），没有使用其他会话正在用的 `pos_phone`。合并包为了能启动，只在临时目录内做了三处与零售无关的调整（见下方“发现”），任何分支、共享库、166 均未改动。

| 步骤（App 实际操作） | 结果 |
|---|---|
| 新账号登录 → 新模型三步建店（零售·便利店，FREE） | 门店 `ST261112K9ND8228KM`，零售首页出现「進銷存」，中心里「零售補貨」不受独立进销存“未開放”影响（[截图](./emulator/01-stock-hub.png)） |
| 开启补货接入 | 后端向真实 inventory-svc 取来源水位（6），writer 进入 REPLENISH |
| 登记开启前过账的调拨单 | 正确拒绝（10409：序号 ≤ 水位，按设计只能走人工凭据）；App 将明确拒绝移出待确认 |
| 登记开启后过账的调拨单 `OUT…22943`（2 箱×10） | 交接单 `SRP261112GHWCBWGTMB` 待收 0/20、主体库存池；库存仍 17（[截图](./emulator/02-registered.png)） |
| 累计实收 18 | 17→35，RECEIVE +18 |
| 正式调整 +2 → 关联到交接单 | 35→37（ADJUST_IN +2）；关联后累计实收 20「已收齊」，库存仍 37（不重复入账） |
| 累计退仓 10 | 37→27，RETURN −10 |
| 源仓过账 RETURN 入库（1 箱，sourceRef=SRP 号）→ App 核对验收 | 换算为 10 件，已验收 10；库存仍 27（验收不再扣本地） |
| 核对本单 | 「本單與本地記錄一致」（用真实源单核对，无差异）（[截图](./emulator/03-reconciled.png)） |

最终流水：RECEIVE +18（17→35）、ADJUST_IN +2（35→37）、RETURN −10（37→27），锁定量全程 0；7 类命令各一条永久回执（CONFIG/REGISTER/RECEIVE/ADJUST/MANUAL/RETURN/RETURN_ACK）。

### 发现（均未在分支上修改，待决定）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| F1 | 两分支迁移撞号：`food-next01` 的工单/服务业迁移 V649、V651、V657 与 `ai-shop-codex` 的同号迁移冲突 | R2 汇合时 Flyway 拒绝启动 | 由服务业负责人决定重新编号；验收时仅在临时目录改为 V649_1/V651_1/V657_1 |
| F2 | 餐饮 `FoodSchemaValidator` 仍要求迁移版本 37，但本分支已有餐饮 V38/V39 | **餐饮组件执行完 V38/V39 后启动失败**（内嵌与 food-server） | 餐饮负责人确认后改为 39；验收时仅在临时目录修改 |
| F3 | 零售登记新来源须能查询餐饮来源历史（设计内），未部署餐饮组件时返回 10505 | 零售上线的部署前置条件 | 写入发布前置；或决定零售单独部署时的历史查询方式 |
| F4 | `LocalStockAccessPolicy` 经 `StoreDirectoryPort.cards` 只认主体 ACTIVE 的门店，待补证照门店的零售/餐饮补货与预检均 10404 | 新开店到证照审核通过前不能补货 | 产品决定口径；验收时临时库内把主体置为 ACTIVE |
| F5 | Flutter 页面：关单/退仓/调整共用接入卡片里的“操作原因”框，且校验提示在页顶、滚动后看不到 | 在下方操作时看似“点了没反应” | **已修复**（bapp ddee4f7）：关单/退仓、调整各有原因框，调整另有 SKU/单位；失败就近显示在被点的卡片；页面测试覆盖。未重跑模拟器 |
| F6 | Flutter 页面：未确认的原请求只存在页面内存，App 被系统结束后丢失 | 与“未知结果保留原键原参数”不完全一致（服务端原子回执仍保证不重复入账） | **已修复**（bapp ddee4f7）：待确认命令按主体+门店+账号存设备，下次进入恢复并按原键原 body 重发；成功或明确拒绝即清除；不跨店/账号恢复；页面测试覆盖。未重跑模拟器 |
| F7 | 临时环境需要 `commerce.enabled`、`commerce.store-creation.enabled`、db 令牌存储、发布的零售目录与权益码映射才能走新模型建店 | 仅本地验收配置 | 记录在本节，供 I04-H 复用 |
