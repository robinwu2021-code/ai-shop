# TDD-经营范围改门店级

状态：已实现（可见性会话确认 reach 系统已稳定、本会话实现、对方 review）
关联需求：`docs/requirements/PRD-位置与经营范围.md`；本文修正其中「经营范围是主体级」的定位错误
创建：2026-10-08

## §0 对账一 · 需求 → 设计

缺陷原文（店主，2026-10-08）：「修改一个门店的经营范围，其他门店也改了。经营范围在门店，不在主体。要修改之前的定位错误。」

实证：`mch_service_area` 只有 `entity_no`、无 `store_no`，经营范围存在主体上、全主体门店共用一份。
店主有跨城多店（虹选鲜果-深圳龙华 / 虹选粮油-山西运城），共享一份足迹不成立。

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 经营范围按门店存：改 A 店的范围，B 店不变 | `mch_service_area` 加 `store_no` + 保存入口按当前门店写 |
| AC2 | 可见性按门店各自的范围算：A 店只对 A 店范围内的社区可见 | `StoreReachLoader.load/preview` 的 `areasOf` 改成按 `(entity, store)` |
| AC3 | 存量不丢可见性：迁移当天每家店照旧覆盖原来那份范围 | 迁移把主体级行复制到该主体每家 ACTIVE 门店 |
| AC4 | 前端不再把经营范围说成「主体级/全店共用」；它跟随当前门店 | `store-scope/index.vue` 文案与保存口径 |

**孤立项**：无。四条 AC 都有落点。

## §1 现状与影响面

### 地基（可见性会话 2026-10-07 刚重构完，已提交、现无人编辑）

- 判定：`ai.neargo.shop.merchant.reach.ReachRule.covers(reach, ref)` —— **纯函数**，不碰 Mapper。
- 读配置：`StoreReachLoader.load(MchEntity, storeNo)` —— **唯一读点**。
  - `areas = areasOf(List.of(entityNo))` ← **按主体读，这就是要改的那一行**。
  - channels 按 store；subsets（SUBSET）按 store。
- 社区池已删，可见性查询时现算（见 `visibility-computed-no-pool`）。

所以改动很集中：`StoreReachLoader` 的 `areasOf` 从「按 entity」换成「按 (entity, store)」，
`ReachRule` 一行不用动。

### 线上数据家底（2026-10-08 查，决定迁移怎么做）

| 量 | 值 |
|---|---|
| `mch_service_area` 行（deleted=0） | **2**（全平台就两行） |
| 有范围的主体 | 2 |
| 多门店主体 | **1**（虹选科技，4 店，共 1 行范围） |
| `mch_channel_area`（SUBSET）行 | **0**（SUBSET 机制实际没人用） |

数据极小、SUBSET 是死的 —— 迁移安全，且佐证「门店级 areas」就该是主模型。

### 这修正了哪份设计

`docs/technical/design/可见性按门店算-方案.md` §3 当初的结论是「不需要门店服务范围概念，
用 `mch_channel_area` SUBSET 收窄即可」。本 TDD **推翻那条**：SUBSET 零使用、且跨城多店
用「主体总足迹 + 每店收窄」反直觉。经营范围本身改成门店级。

## §2 方案

### 契约变更
- 库表：`mch_service_area` 加 `store_no`（可空兼容期；迁移号取提交时的下一个，当场查撞号）。
- 端点：无新增（保存仍走 `mSaveStore`，但语义改为按当前门店；读 `mStore`/profile 带当前门店的范围）。
- 权限码 / i18n / 配置：前端文案要改（去掉「主体级」），可能动 i18n 词条。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 迁移 | `V3xx__service_area_store_no.sql` | 加列 + 回填（主体级行复制到每家 ACTIVE 门店，带上 store_no） |
| 实体 | `MchServiceArea.java` | 加 `storeNo` 字段 |
| schema 测试 | `schema-test.sql` | 同步加列（见 `migration-needs-entity-field`） |
| 改 | `StoreReachLoader.areasOf` / `load` / `preview` | 按 `(entity, store)` 读范围 |
| 改 | 保存入口（merchant 域 save service areas 的那处） | 按当前门店写、删也按门店 |
| 改 | `b-app store-scope/index.vue` | 文案去「主体级」；范围跟随当前门店（切店要重拉这张卡） |

### §3 决定（与可见性会话对齐后）
- **SUBSET 保留（选项甲）**：`mch_channel_area` 一行不动，语义变成「同一家店里某一路在**这家店自己的范围**里再收窄」——
  因为读的已是门店级 areas，取交基准自动从「主体 INCLUDE」变成「门店 INCLUDE」。线上 0 行，要不要砍另议。
- **主体口径 = 名下各 ACTIVE 门店足迹的并集**（`StoreReachLoader.loadEach`），**路一律按「全部」、不套 SUBSET**，
  与改造前主体口径逐字一致。**不能把各店的 INCLUDE/EXCLUDE 混成一份再判**：A 店排除的楼会把 B 店纳入的同一栋减掉。
- **新开门店照抄默认店的范围**（与「新店继承主体经营类目」同一取舍）：不抄则只做自提的新店对谁都不可见。
- 有门店号的调用方（自提点候选、B 端预览）改用**这家店**的范围；没门店号的（商家详情、积分、运营看板、销售地区）走并集。

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1 + AC2 | `ServiceAreaFlowTest#editingOneStoreRangeLeavesOtherStoresAlone` | ✅ | 去掉 `replaceAreas` 的 `store_no` 过滤 → 红，报「改 A 店，B 店的范围不该跟着变」✅ |
| 主体并集 | `ServiceAreaFlowTest#entityReachIsUnionOfStoresNotAMixedList` | ✅ | 主体口径改成只看默认店 → 红 ✅ |
| 新店继承 | `ServiceAreaFlowTest#newStoreInheritsDefaultStoreRange` | ✅ | 注掉 `inheritServiceAreas` → 红 ✅ |
| AC3 迁移 | **真库副本实跑**（`zz_tmp_*` 复制 mch_service_area/mch_store/mch_channel_area，跑改名后的 V381） | ✅ 见下 | — |
| AC4 | `b-app store-scope` 读写都走 `X-Store-No`；vue-tsc 0 | ✅ | — |

**AC3 真库副本实跑**（2026-10-08，生产库 3 行范围）：第一版 V381 **在真库上撞 1062** ——
先插副本、后换唯一键，副本与原行 `(entity_no, level, ref_code)` 相同。H2 用例永远看不见：
测试库是 `schema-test.sql` 重放的**终态结构**，根本不跑这段数据迁移。改为先换键再复制后实跑通过：
虹选科技那 1 行复制到 4 家店（含停用的福田店）；合成的一条 SUBSET（粮油店引用默认店那行）被改指到粮油店自己那份；
新唯一键 `(entity_no, store_no, level, ref_code)` 生效；另 2 行属于证照合并时删掉的主体（`deleted=1`、0 家门店），保持空 store_no、不参与任何可见性。

既有用例：16 个相关类全绿。7 个类的夹具在造「有范围、没门店」这种线上不存在的状态（激活时 ensureDefaultStore 必建默认店），
已改为把范围挂到默认店上；`StoreScopedVisibilityFlowTest#buyerInCommunityBOnlySeesGoodsFromStoreB` 原先就是旧模型
（主体框两块 + SUBSET 各收一块），改为两家店各框各的；`visibilityMatchesReachableExactly` 与另一条用例共用手机号、
落在同一主体上，范围主体级时被后一次保存覆盖掩盖，现给它独占号。

## §6 对账二 · 设计 → 实现

与 §2 模块设计相比多动了这些（均为门店级之后必须跟上的读写点，设计时没列全）：

| 文件 | 为什么 |
|---|---|
| `MerchantMappers.ServiceAreaMapper` | `hardDelete(entity, level, ref)` 换成 `hardDeleteById` —— 按那三个键删会把**所有门店**的那一条一起删 |
| `MerchantGovernServiceImpl` | 运营驳回范围按 id 删；运营覆盖明细按 (方向,层级,编码) 去重（否则四家店的同一条列四遍） |
| `MerchantSettlementRefPortImpl` | 聚落合并的去重维度跟唯一键走：主体 → 主体+门店 |
| `StoreFulfillmentServiceImpl` | SUBSET 只能从这家店自己的范围里挑 |
| `StoreAdminServiceImpl` | 新店照抄默认店范围 |
| `MerchantQueryPort` / `BizMerchantController` | 预览加门店重载；改前改后两个数都按当前门店算 |
| `BizPickupPointController` | 自提点候选、自建点兜底社区按本店范围 |
| `MerchantPortImpl.saleScope` | 买家页「可售地区」= ACTIVE 门店范围去重并集；不限 = 任一店不限 |
| `ReachRule` | 只改 `StoreReach` 文档（includes/excludes 是门店的）；判定一行未动 |
