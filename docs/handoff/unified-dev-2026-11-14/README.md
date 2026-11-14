# 统一开发分支交接清单（2026-11-14）

分支：`integration/unified-20261114`（仅本地，**未推送**；推送待确认）
工作区：`/Users/workingowl/work/ai-shop-unified`

> 把主线、运营线、零售 / 餐饮线合到一条开发分支上；本文列出合了什么、改了什么、怎么验证的、还剩什么。

## 1. 合并范围

| 顺序 | 来源 | 内容 | 合并提交 |
|---|---|---|---|
| 1 | `origin/main` | 主线（迁移到 V666） | 由 2 一并带入 |
| 2 | `origin/codex/ai-shop-codex` | 运营线：统一准入管理、OTP / 图形验证码（V667） | `ba3f04048` |
| 3 | `origin/codex/food-next01` | 餐饮 / 零售线：服务执行、工单认领、零售补货、餐饮本地库存（V649–V660 → 已顺延） | 由 4 一并带入 |
| 4 | `origin/fix/food-p0-schema-scope`（PR #14） | 餐饮 P0 schema 范围修复 | `7c5bc8589` |
| 5 | `origin/feat/food-pos-gaps`（含 PR #16 / #17 / #18） | 柜台后付结账、门店基本信息与时间约定、POS 服务端缺口第二批 | `bd96dd632` |
| 6 | — | 迁移顺延与合并后引用修正 | `037866b05` |

未纳入：`feat/store-push-sse`（不在本次范围）。7 条来源分支 `git merge-base --is-ancestor` 均为真（2026-11-14 fetch 后复核）。

### 冲突处理

| 文件 | 处理 |
|---|---|
| `MerchantGoodsServiceImpl` 初始库存 | 两边规则都保留：仅新 SKU 写初始库存（主线），补货保存不写库存（零售） |
| `FoodStoreRelinkFactsPortImpl` | 取 PR #14 版本（无 `@Component`，由 `FoodPersistenceConfiguration` 显式导入） |
| `FoodSchemaValidator` / `FoodPrimaryDatabaseTest` | 保留常量写法 `EXPECTED_VERSION`，值改为 `"40"`（餐饮迁移 V38–V40 线性，无冲突） |
| `h2-fixture-policy.json` | 两边条目取并集（主线 V660 支付服务申请 + 餐饮 V668/V669 服务执行） |
| `schema-test.sql` | 改用生成器 `gen-test-schema.py` 的输出，不再手工拼接 |
| `test-schema-constraints.test.mjs` | 取主线（按 capability 查找） |
| `ui-catalog.json`、后端分层清单、数据库表清单 | 取一侧后重新生成 |
| `NativeResourceWorkflowIT`（非文本冲突） | 主线把 `StoreOwnershipWritePortImpl` 移到 `merchant.port`，跟着改 import |

## 2. 迁移编号顺延（用户决定：接到 V667 之后顺延）

主线 / 运营线的编号不动；餐饮线平台迁移按原顺序整体顺延：

| 原编号 | 新编号 | 迁移 |
|---|---|---|
| V649 | **V668** | `service_execution_start_ack` |
| V651 | **V669** | `service_execution_completion` |
| V654 | **V670** | `service_work_order_task_claim` |
| V657 | **V671** | `service_work_order_claim_evidence` |
| V659 | **V672** | `retail_stock_replenishment_admission` |
| V660 | **V673** | `retail_stock_replenishment_effects` |
| V665 | **V674** | `postpay_receipt` |

内容一字未改（校验和不变）。同步修改：`ServiceExecutionSchemaVerifier`（历史版本 668/669、描述版本断言）、两份 schema 描述及生成器、`h2-fixture-policy.json` 哈希、脚本测试、`TDD-餐饮柜台后付结账.md` 与 `TDD-三行业简单库存与进销存统一对接方案.md` 的编号。历史交接文档（`docs/handoff/*`、`artifacts/*`）保留原编号，不改。

### 已跑过餐饮线迁移的库怎么升级（166 等）

1. root 执行 [`upgrade-food-history.sql`](./upgrade-food-history.sql)：只把 `flyway_schema_history` 里 7 行的版本号与脚本名改成新号。
2. 应用带 `--spring.flyway.out-of-order=true` 启动一次，补跑主线 V648–V667 中缺的 17 个迁移；之后去掉该参数。
3. 166 应用账号无 DDL 权限：第 2 步要以 root 作为 Flyway 账号（`--spring.flyway.user=root`），或由 root 先手工执行这 17 个迁移。

全新库直接按顺序跑即可（见 §4 验证）。

## 3. 已完成的功能（本轮合入）

| 来源 | 项 | 文档 |
|---|---|---|
| PR #16 | 柜台后付结账：整桌一张结算单 → 现金 / 外部刷卡记账 → 收齐确认 → 核心后付单 PAID（人工实收）→ 关单 → 放台；日结、资金对账排除后付单 | `TDD-餐饮柜台后付结账.md`、ADR-059 |
| PR #17 | 门店基本信息 `GET /biz/x/food/stores/{s}/profile`（时区、币种、当前营业日）；时间字段约定（B5/B6） | `TDD-餐饮门店基本信息与时间约定.md` |
| PR #18 | 现金交班单次提交与读回（B14/B15）；离线回放保留期 ≥ 7 天（B16）；厨房任务带购物车行号、按批次查（B17）；已结账记账禁止作废 / 更正；后付实收触发报表重算 | `TDD-餐饮POS服务端缺口第二批.md` |
| PR #14 | 餐饮 P0 schema 范围修复 | — |
| 运营线 | 统一准入管理、OTP / 图形验证码 | 运营线自带文档 |
| 餐饮 / 零售线 | 服务执行开工 / 完工、工单认领与凭证、零售补货准入与效果、餐饮本地库存 | `TDD-三行业简单库存与进销存统一对接方案.md` 等 |

本机已验证（8091，合并前的餐饮分支）：POS 真机柜台结账；两人日结签核（店主预览、复核店长签核成功，同一人签 10403）。

## 4. 合并后验证（2026-11-14）

### 编译与测试

| 项 | 结果 |
|---|---|
| 后端主代码编译（`-P food-embedded`，含 shop-app 全部依赖） | 通过 |
| 默认 profile 测试代码编译 | 通过（修了 `NativeResourceWorkflowIT` 的 import 后） |
| 服务执行相关测试（`ServiceExecution*`、`PayServiceApplication*`） | 全过 |
| `BackendI18nParityTest` | 全过 |
| `ArchitectureTest` | 3 条规则红，违例全部来自单条线、合并未新增（见 §5「基线红灯」） |
| `BizEndpointPermTest` | 1 条红（2 个端点未定权限），同上 |
| 脚本测试 `scripts/tests/*.mjs` | 失败项全部在运营线或餐饮线基线中已存在，合并新增 0 |
| 餐饮插件全量测试（后端 reactor 内 `-P food-embedded`） | food-api 56、food-core 766（跳过 60 个需一次性 MySQL 的）、food-web 268，失败 0 |
| `gen-ui-catalog.py --check` | 最新（333 个界面） |

### 本机部署（`~/.local/var/ai-shop-uni/`）

| 场景 | 库 | 结果 |
|---|---|---|
| 全新安装 | `ai_shop_uni` | 平台迁移 V1→V674 全部成功（586 个）；支付 V16、餐饮 V40 成功 |
| 从餐饮线升级 | `ai_shop_uni_up`（复制 8091 的 `ai_shop_cs`） | 跑 `upgrade-food-history.sql` 后以 out-of-order 补跑主线 17 个迁移成功，应用启动成功（8095） |

8095 上的业务验证（2026-11-14，测试号 13800000000）：

| 链路 | 结果 |
|---|---|
| 开台 → 加菜 → 报价（含 5% 服务费）→ 员工后付下单 → 柜台结算单 → 现金记账 → 确认（找零 7820）→ 重放确认 | 通过（H02、H03 两桌） |
| 厨房任务推到已上菜 → 放台 | 通过 |
| `GET …/profile` 门店信息（时区、币种、营业日起止） | 通过 |
| 厨房任务按 `batchNo` 查，带 `sourceLineRef` L1 / L2 | 通过 |
| 已被结算单使用的外部记账作废 | 10409（符合预期） |
| 现金交班提交（差额 0、非销售支出 500、挂 1 笔记账）；同一记账挂第二个班 | 成功；第二次 10409 |
| 开营业日 11-14 → 日结预览 | 预览 `issues` 为空；签核因营业日未结束（20:00 UTC）被拒，符合规则。两人签核已在合并前（8091，11-13）实测通过 |

本机启停：`~/.local/var/ai-shop-uni/start.sh start|stop|status|log`；`e2e.py <桌名>`、`release.py <台账> <批次>`、`sign.sh <日期>` 可复跑。

## 5. 待完成

### 需要产品 / 用户决定

- 结账时是否视为已上齐并直接放台（现规则 R4：没上齐只关单不放台）
- 散客下单方案（员工下单现在必须带 buyerNo 或 partyNo 之一）
- POS 内日结签核流程
- 刷卡方式（目前只有外部刷卡机 + 外部记账）
- 员工身份怎么校验（POS 端）
- 离线让价的处理
- 是否需要「待清台」状态
- 零售线后续由谁负责
- 共享库 `inventory_test` 里误写的 16 行（8 条 `inv_item` / 8 条 `inv_item_ref`）是否删除
- 统一分支推送与合入方式（本分支仅本地）

### 需要运维 / 平台

- root 执行迁移（V668–V674 及主线缺的迁移；餐饮 V40）；已有库先跑 `upgrade-food-history.sql`
- 正式环境 HTTPS 与 POS 终端令牌
- 部署配置按 `TDD-餐饮柜台后付结账.md`「上线清单」逐项核对（餐饮开关、统一 outbox、库存权威、JVM UTC）

### 可后续排期

- 交班时回查外部记账（需要核心开按记账号读的端口）
- 开台时锁定服务费率
- 员工下单支持先付
- 让价、赠送
- 官方并台接口

### 基线就有的红灯（合并未新增，但需要有人处理）

| 项 | 来源 | 说明 |
|---|---|---|
| `ArchitectureTest.implsMustLiveInDedicatedPackage` | 餐饮线 3（`ServiceWorkOrderUseFactsService`、`TaskRuntimeAuthorityConfiguration$RemoteTaskRuntimeAuthorityPort`、`LocalStockControlServiceImpl`）+ 运营线 1（`MarketPhonePolicyServiceImpl`） | Port 实现不在 `.port` 包 |
| `ArchitectureTest.noFullyQualifiedReferences` | 两条线共有（`BizEntitlementUseController`、`EntitlementServiceUseCoordinator*`） | 全限定名改 import |
| `ArchitectureTest.serviceMustBeInterface` | 运营线（`AdmissionManagementService`） | 具体类以 Service 结尾 |
| `BizEndpointPermTest.everyBizEndpointHasADecision` | `/biz/entitlement-uses/work-order-money-previews`、`/biz/pay/v2/collections` | 端点未定权限 |
| `b-app/scripts/gen-openapi.mjs` | 餐饮 / 零售线 | 端点 `mRpState` 路径资源段 `commands` 违反命名空间约定；生成文档闸门因此跑不完 |
| `-P food-embedded` 测试编译 | 餐饮线 | `food-embedded-test` 下 `FoodChannelVertical*` 依赖 commerce 模块，单开 food-embedded 编不过；加 commerce 相关 profile 后 `FoodInventoryDirectSyncNativeMysqlTest` 又缺 food-channel |
| pre-push 第 6 道「app-backend orphan」 | 基线 | 推送时会挡，需逐次批准跳过 |
| 本机从零重建工具 | `~/work/tools/ai-shop-mysql-rebuild` | 分段启动时支付库 Flyway 在平台表不全时先跑，留下失败的 V3 记录；删掉失败行再启动即可 |
