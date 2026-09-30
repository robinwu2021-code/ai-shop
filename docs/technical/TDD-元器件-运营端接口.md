# TDD-元器件 · 运营端接口

> 2026-09-30 · 状态：**已实现**（后端；ops-web 页面与菜单登记另起一批）
> 档位：1（新端点 17 个 · 权限码 4 个 · `elc_supplier` 加 2 列 · 错误码 2 个）
> 依据：[TDD-元器件-前端独立与通知矩阵](./TDD-元器件-前端独立与通知矩阵.md) §六（菜单与每页做什么）
> 不含：ops-web 的页面、菜单迁移与权限种子 —— 那是接前端时的一批（§1「明确不在这一批」）

---

## §0 对账一 · 需求 → 设计

需求原文取自依据文档 §6.2 的四行表格与 §6.3，逐条拆成 AC：

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 供应商列表：公司名、类型、城市、电话、在售数、7 天内到期数、最近上传、状态；可按关键字找 | `GET /elec/ops/supplier` · `ElecOpsSupplierService#list` · `StockMapper#statsBySupplier` · `StockBatchMapper#lastAppliedBySupplier` |
| AC2 | 供应商详情：资料、库存统计、派单响应情况；看他的库存 | `GET /elec/ops/supplier/{no}` · `GET /elec/ops/supplier/{no}/stock` · `DispatchMapper#statsOf` |
| AC3 | 暂停 / 恢复：**暂停后他的货不再给买家看**；暂停要写理由 | `POST …/suspend`（必填 reason）· `POST …/resume` · 两者都 `market.refresh(他的全部料号)` · `elc_supplier.suspend_reason / suspended_at` |
| AC4 | 运营可改供应商资料 | `PUT /elec/ops/supplier/{no}`（复用 `RegisterReq` 与同一套校验） |
| AC5 | 料号搜索与买家同一套（开头 / 中段 / 厂牌），**但不计入买家需求统计** | `GET /elec/ops/part` · `ElecPartService#matchPartNos`（从 `search` 抽出的候选查询，不写 `elc_search_daily`） |
| AC6 | 某料号 → 谁有货：公司、电话、数量、批号、**阶梯价、起订量**、货况、包装、交期、货源地 | `GET /elec/ops/part/{partNo}` · `StockMapper#sourcesOf` 补列 |
| AC7 | 库存行查询：按料号 / 供应商 / 到期状态筛 | `GET /elec/ops/stock` |
| AC8 | 厂牌列表、加厂牌、改名；**加厂牌时把自己的代码与名字登成别名**（否则新厂牌永远认不出） | `GET/POST /elec/ops/mfr` · `PUT /elec/ops/mfr/{code}` · `ElecMfrService` |
| AC9 | 别名列表、加别名；**加了之后既有库存按它改认**（不只「下次上传」） | `GET/POST /elec/ops/mfr/{code}/alias` · `ElecMfrService#addAlias` 逐行改认 |
| AC10 | 「认不出的厂牌」按出现次数排、给建议，点一下补成别名，补完从列表消失 | `GET /elec/ops/mfr/unknown` · 建议算法 `MfrSuggest` |
| AC11 | 询价列表：派了几家、几家响应、几家报了价；详情逐行看**供应商报了什么（真名）** | `OpsRfqView` 加三个计数 · `OpsLineView.offers` |
| AC12 | 手工指派给某几家供应商 | `POST /elec/ops/rfq/{rfqNo}/line/{lineNo}/dispatch`（接已有的 `dispatchTo`） |
| AC13 | 报价记录：按供应商 / 状态查全部供应商报价 | `GET /elec/ops/quote` |
| AC14 | 权限：五个码各管各的 | `ElecInternal` 登记 6 个码 · `InternalElecEndpoint` 按清单过滤 · 每个端点 `ElecOpsGuard.require` |

**孤立项**：

- **与依据文档的两处偏差**（已更正依据，见文末「偏差说明」）：
  ① §6.3 说「认不出的厂牌」数据来自 `elc_part.mfr_name_raw`，改为来自 `elc_stock.mfr_raw`；
  ② §6.3 说补了别名「下次上传就认得出」，改为**当场改认既有库存**。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面

**已有、直接复用**：

| 复用什么 | 在哪 |
|---|---|
| 运营令牌认证、权限判定 | `ElecOpsGuard.require` / `InternalElecEndpoint#elecPerms` |
| 投影重算 | `ElecMarketService#refresh(partNos)` —— 它已经只算 `ACTIVE` 供应商（`ElecMarketServiceImpl:90`），暂停时**只要调它**就能让买家看不到 |
| 谁有货 | `StockMapper#sourcesOf`（运营询价详情在用） |
| 料号认厂牌 | `ElecPartCatalog.Session#resolve / flush`（上传在用）—— 改认既有库存走同一条路，不另写一套 |
| 手工派单 | `ElecDispatchService#dispatchTo`（已实现、未接端点） |
| 资料校验 | `ElecSupplierServiceImpl#update` 的那套（公司名 ≥2、手机号 11 位、类型取值域） |

**会被改到的已在跑的功能**：

- `ElecPartServiceImpl#search`：候选查询抽成 `matchPartNos`，`search` 改调它。行为不变，由 `ElecFlowTest` 的搜索用例兜着。
- `ElecSupplierServiceImpl`：库存行 → `StockView` 的转换与阶梯价解析抽到 `ElecStockViews`，供运营端复用。
- `ElecRfqServiceImpl#opsView`：`OpsRfqView` / `OpsLineView` 各加字段（record 变构造参数，只有它一处在构造）。
- `InternalElecEndpoint`：两个码写死的 `Stream.of(…)` 改成 `ElecInternal.OPS_PERMS`。**主系统改动只有这一行**。

**为什么「认不出的厂牌」要按库存行算**：UNKNOWN 名下的一个料号会汇集多家供应商的库存
（`resolve` 在认不出厂牌时返回同串唯一的那个料号），而 `elc_part.mfr_name_raw` 只记了**第一次**那家写的原文。
按料号算，第二家写的「TEXAS INSTRUMENT」永远不会出现在列表里。

**为什么补别名要逐行改认、不能整条料号换厂牌**：同一个 UNKNOWN 料号下的几行，原文可能分属两家厂牌；
而且 `(mfr_code, mpn_norm)` 是唯一键，目标厂牌下可能已有这个料号串 —— 整条换会撞键。
逐行走 `resolve`：认得出就挂到（已有或新建的）那个料号上；UNKNOWN 料号的行搬空了就标 `MERGED`。

**明确不受影响的**：买家与供应商两端全部接口；上传流程；`elc_part_market` 的结构。

**明确不在这一批**：ops-web 页面与 TS 类型；主系统的菜单迁移、权限种子、perm-map、端点矩阵
（依据文档 §6.4 的「五处登记」）—— 在那之前只有带 `elec:*` 或 `*` 的运营能用这些端点。

---

## §2 方案

### 契约变更

**端点**（17 个新增，全部 `/elec/ops/**`，运营令牌）：

| # | 方法 路径 | 权限码 | 入参 → 出参 |
|---|---|---|---|
| 1 | `GET /elec/ops/supplier` | `elec:supplier:read` | `keyword?` `status?` `page` `size` → `List<OpsSupplierRow>` |
| 2 | `GET /elec/ops/supplier/{supplierNo}` | 同上 | → `OpsSupplierDetail` |
| 3 | `GET /elec/ops/supplier/{supplierNo}/stock` | 同上 | `keyword?` `filter=ALL/EXPIRING/EXPIRED` `page` `size` → `List<StockView>` |
| 4 | `PUT /elec/ops/supplier/{supplierNo}` | `elec:supplier:manage` | `RegisterReq` → `OpsSupplierDetail` |
| 5 | `POST /elec/ops/supplier/{supplierNo}/suspend` | 同上 | `{reason}`（≥2 字）→ `OpsSupplierDetail` |
| 6 | `POST /elec/ops/supplier/{supplierNo}/resume` | 同上 | → `OpsSupplierDetail` |
| 7 | `GET /elec/ops/part` | `elec:part:read` | `q` → `List<OpsPartRow>`（最多 50） |
| 8 | `GET /elec/ops/part/{partNo}` | 同上 | → `OpsPartDetail` |
| 9 | `GET /elec/ops/stock` | 同上 | `q?` `supplierNo?` `filter` `page` `size` → `List<OpsStockRow>` |
| 10 | `GET /elec/ops/mfr` | `elec:base:manage` | `q?` → `List<MfrRow>` |
| 11 | `POST /elec/ops/mfr` | 同上 | `MfrReq` → `MfrRow` |
| 12 | `PUT /elec/ops/mfr/{mfrCode}` | 同上 | `MfrReq`（只改名字）→ `MfrRow` |
| 13 | `GET /elec/ops/mfr/{mfrCode}/alias` | 同上 | → `List<AliasRow>` |
| 14 | `POST /elec/ops/mfr/{mfrCode}/alias` | 同上 | `{alias}` → `AliasResult` |
| 15 | `GET /elec/ops/mfr/unknown` | 同上 | `limit?` → `List<UnknownMfrRow>` |
| 16 | `POST /elec/ops/rfq/{rfqNo}/line/{lineNo}/dispatch` | `elec:rfq:quote` | `{supplierNos}` → `OpsRfqView` |
| 17 | `GET /elec/ops/quote` | `elec:rfq:read` | `supplierNo?` `status?` `page` `size` → `List<OpsQuoteRow>` |

路径 `/elec/ops/mfr/unknown` 与 `/elec/ops/mfr/{mfrCode}` 不冲突：前者只有 GET、后者只有 PUT；
且厂牌代码 `UNKNOWN` 本身是种子里的占位厂牌，不许改名（见下）。

**已有端点的出参加字段**（只加不改）：
`OpsRfqView` + `dispatchCnt` `respondedCnt` `offerCnt`；`OpsLineView` + `offers: List<OpsOffer>`（列表页为空，详情才带）；
`OpsSource` + `stockNo` `moq` `spq` `tiers` `validUntil`。

**库表**：`elc_supplier` 加 `suspend_reason VARCHAR(255)`、`suspended_at DATETIME`。
改在 `V1__elec_baseline.sql` 里 —— **该迁移尚未在任何库应用过**（元器件还没部署），加列不产生冻结问题。
三处同步：迁移 · `ElcSupplier` 实体 · H2 schema（生成）。
> ⚠️ **这句只在写作时（2026-09-30 07:xx）成立**：生产在 08:21 执行了 V1，从那一刻起 V1 **一个字都不能改**（连注释都算进校验和）。之后的新列一律另起 `V2`、`V3`…，见 `ElecAppliedMigrationsFrozenTest`。


**权限码**（`ElecInternal`）：新增 `elec:supplier:read` `elec:supplier:manage` `elec:part:read` `elec:base:manage`；
六个码收成 `OPS_PERMS` 一张表，主系统按它过滤 —— 新增码只改这一处。

**错误码**：
`ELEC_MFR_EXISTS(90013)` 厂牌代码已存在；
`ELEC_ALIAS_TAKEN(90014)` 这个写法已经指向另一家厂牌 `{0}`（不静默改指向：改了等于把已认到旧厂牌的料号全部认错）。
文案六处：`elec/messages{,_en,_ar}` + `shop-app/messages{,_en,_ar}`。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `elec-api/…/ElecInternal.java` | 4 个码 + `OPS_PERMS` |
| 修改 | `shop-app/…/InternalElecEndpoint.java` | 按 `OPS_PERMS` 过滤 |
| 修改 | `shop-base/…/ErrorCode.java` | 90013 / 90014 |
| 修改 | `elec-core/…/i18n/elec/messages*.properties` · `shop-app/…/i18n/messages*.properties` | 两条 × 三语 × 两处 |
| 修改 | `elec-core/…/db/elec/V1__elec_baseline.sql` | `elc_supplier` 两列 |
| 修改 | `elec-core/…/entity/ElcSupplier.java` | 两个字段 |
| 新增 | `elec-core/…/dto/OpsDtos.java` | 供应商 / 料号 / 库存行的运营视图 |
| 新增 | `elec-core/…/dto/MfrDtos.java` | 厂牌 / 别名 / 认不出的厂牌 |
| 修改 | `elec-core/…/dto/RfqDtos.java` | `OpsOffer` `OpsQuoteRow` `DispatchReq`；两个视图加字段 |
| 修改 | `elec-core/…/mapper/ElecMappers.java` | `StockMapper#statsBySupplier` `#unknownMfrRows` `#sourcesOf` 补列；`StockBatchMapper#lastAppliedBySupplier`；`DispatchMapper#statsOf` `#countsByRfq`；`PartMapper#countsByMfr` |
| 新增 | `elec-core/…/service/ElecOpsSupplierService.java` + `impl/ElecOpsSupplierServiceImpl.java` | AC1–AC4 |
| 新增 | `elec-core/…/service/ElecOpsPartService.java` + `impl/ElecOpsPartServiceImpl.java` | AC5–AC7 |
| 新增 | `elec-core/…/service/ElecMfrService.java` + `impl/ElecMfrServiceImpl.java` | AC8–AC10 |
| 新增 | `elec-core/…/support/MfrSuggest.java` | 认不出的写法 → 建议厂牌（纯函数，好测） |
| 新增 | `elec-core/…/service/impl/ElecStockViews.java` | 从 `ElecSupplierServiceImpl` 抽出的行 → 视图转换 |
| 修改 | `elec-core/…/service/ElecPartService.java` + impl | 抽出 `matchPartNos` |
| 修改 | `elec-core/…/service/impl/ElecSupplierServiceImpl.java` | 改用 `ElecStockViews` |
| 修改 | `elec-core/…/service/ElecRfqService.java` + impl | `opsView` 加计数与 offers；`opsQuotes` |
| 修改 | `elec-core/…/service/ElecDispatchService.java` + impl | `opsOffersOf`（真名、原价）· `opsQuotes` |
| 新增 | `elec-core/…/api/ops/ElecOpsSupplierController.java` | 端点 1–6 |
| 新增 | `elec-core/…/api/ops/ElecOpsPartController.java` | 端点 7–9 |
| 新增 | `elec-core/…/api/ops/ElecOpsBaseController.java` | 端点 10–15 |
| 修改 | `elec-core/…/api/ops/ElecOpsRfqController.java` | 端点 16–17 |
| 新增 | `elec-core/src/test/…/support/MfrSuggestTest.java` | 建议算法 |
| 新增 | `elec-svc/src/test/…/ElecOpsFlowTest.java` | AC1–AC14 端到端 |
| 生成 | H2 schema · `db-elec.svg` · `数据库-元器件.md` · glossary 等 | 生成器 |

### 关键接口

```java
// 暂停：写状态 → 重算他全部料号的投影。投影只算 ACTIVE 供应商，所以重算即隐藏
OpsSupplierDetail suspend(String staffNo, String supplierNo, String reason);

// 加别名：写别名 → 逐行改认 UNKNOWN 名下原文规范化后等于它的在售库存 → 重算受影响料号的投影
// 同一别名已指向同一厂牌 = 幂等（仍会跑一次改认，给「认不出的厂牌」页的重复点击留条活路）
AliasResult addAlias(String staffNo, String mfrCode, String alias);

record AliasResult(String aliasNorm, String mfrCode, int movedRows, int touchedParts) {}

// 认不出的厂牌：按规范化后的写法聚合（大小写、空格、Co.,Ltd 后缀不同算同一个）
record UnknownMfrRow(String aliasNorm, String sample, int rowCnt, int supplierCnt, int partCnt,
                     String suggestCode, String suggestName) {}
```

**建议算法**（`MfrSuggest`）：在别名表里找与这个写法**互为前缀**的别名，取最长的那条。
较短一方至少 4 个字符（含中文时至少 2 个）—— 否则 `ST` 会把 `STARCHIP` 建议成意法。
只是建议：运营点了才写。

---

## §5 对账三 · 实现 → 需求（测试）

`ElecOpsFlowTest`（elec-svc，12 条）· `MfrSuggestTest`（elec-core，4 条）· `InternalElecEndpointTest`（shop-app）。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 AC2 | `ElecOpsFlowTest#ac1ac2_supplierListAndDetail` | ✅ | — |
| AC3 | `ElecOpsFlowTest#ac3_suspendHidesStockFromBuyers` | ✅ | 注掉暂停里的 `market.refresh` → 红在「暂停后买家面上没有他的货」✅ |
| AC4 | `ElecOpsFlowTest#ac4_opsUpdatesProfile` | ✅ | — |
| AC5 | `ElecOpsFlowTest#ac5_opsSearchDoesNotLogDemand`（带对照：买家搜一次计数变 1，证明量具是活的） | ✅ | — |
| AC6 | `ElecOpsFlowTest#ac6_partDetailShowsTiersAndMoq` | ✅ | — |
| AC7 | `ElecOpsFlowTest#ac7_stockQuery` | ✅ | — |
| AC8 | `ElecOpsFlowTest#ac8_createMfrRegistersOwnNames` | ✅ | 改认时「计数照报、库存行不动」→ 红在「建完当场认过去」✅ |
| AC9 AC10 | `ElecOpsFlowTest#ac9ac10_unknownMfrBecomesAlias` · `#ac9_aliasTaken` · `MfrSuggestTest` 4 条 | ✅ | 同上 → 红在「补完就从列表消失」✅；注掉删分段键 → 红在「买家搜中段：没有空重影」✅ |
| AC11 AC12 | `ElecOpsFlowTest#ac11ac12_dispatchQuoteAndOpsView` | ✅ | 见偏差 3：它第一次跑就红了，红的是上一批的真缺陷 |
| AC13 | `ElecOpsFlowTest#ac13_quoteRecords` | ✅ | — |
| AC14 | `ElecOpsFlowTest#ac14_permsAreSeparate` · `InternalElecEndpointTest#operatorTokenCarriesOnlyElecPerms` | ✅ | 主系统过滤改回只认两个码 → 红在第 81 行（精确相等）✅ |

跑的输出（`mvn -o -pl elec/elec-svc -am test`，全量）：

```
Tests run: 3,  Failures: 0 -- in ai.neargo.shop.elec.support.AlertTextTest
Tests run: 4,  Failures: 0 -- in ai.neargo.shop.elec.support.MfrSuggestTest
Tests run: 15, Failures: 0 -- in ai.neargo.shop.elec.support.ParsingTest
Tests run: 12, Failures: 0 -- in ai.neargo.shop.elec.svc.ElecOpsFlowTest
Tests run: 24, Failures: 0 -- in ai.neargo.shop.elec.svc.ElecFlowTest
Tests run: 1,  Failures: 0 -- in ai.neargo.shop.elec.svc.ElecMessagesParityTest
Tests run: 12, Failures: 0 -- in ai.neargo.shop.elec.svc.ElecQuoteFlowTest
Tests run: 1,  Failures: 0 -- in ai.neargo.shop.elec.svc.ElecEndpointAuthTest
```

`mvn -o -pl shop-app -am test -Dtest='InternalElecEndpointTest,BackendI18nParityTest'`：6 + 6 条，0 红。

新端点的鉴权由 `ElecEndpointAuthTest` 自动覆盖（它扫全部 `/elec/**` 映射，没有名单要改）。

## §6 对账二 · 设计 → 实现

与 §2 模块设计逐行比，下列是差异：

| 差异 | 说明 |
|---|---|
| `ElecPartServiceImpl#search` **没有改** | 设计写「候选查询抽成 `matchPartNos`，`search` 改调它」。实际只新增了 `matchParts`，与 `search` 共用私有的 `candidates()`；`search` 一行没动。买家搜索还有可见性排序、近似退位、记需求三件事，硬并成一个方法反而要加开关 |
| `ElecOpsPartService#search` 不经 `hitsOf` | 那是买家的查询（只 join 投影）；运营这边要料号状态与精确统计，直接读料号表 + `statsByPart` |
| 新增 `ElcMfrAlias.SOURCE_*` / `ElcManufacturer.STATUS_*` / `ElcQuote.STATUS_EXPIRED` 常量 | 设计没列。原先这几个取值在代码里没有常量，新代码要用 |
| `ElecMarketServiceImpl` 改了一行注释 | 原注释「第一步没有暂停入口（运营直接改库）」从这一批起不成立 |
| `ElecMappers#mine` 包上 `<script>` | 设计没有。见偏差 3 |
| `PartMapper#countsByMfr` / `MfrAliasMapper#countsByMfr` / `CodeCount` | 设计里写的是 `PartMapper#countsByMfr`，别名计数与通用行类是实现时补的 |

`ElecPartCatalog`、`ElecOpsGuard` 以外，设计列了而没动的文件：无。

## 偏差说明

1. **「认不出的厂牌」的数据来源**：依据文档 §6.3 写的是 `elc_part.mfr_name_raw`，改为 `elc_stock.mfr_raw`。
   理由见 §1：料号只记第一家的原文，后来者的写法按料号数不出来。依据文档已同步更正。
2. **补别名的生效时机**：依据文档写「下次上传就认得出」，改为**当场改认既有库存**。
   不改认的话，列表是 `mfr_code='UNKNOWN'` 算出来的，补完那一行永远不会从列表消失 —— 运营会以为没点上。
3. **上一批的真缺陷（已修）**：供应商「我收到的求购」那条 SQL（`DispatchMapper#mine`）挂了
   `@Lang(XMLLanguageDriver)` 却没包 `<script>` —— MyBatis 只在文本以 `<script>` 开头时才解析 `<if>`，
   于是 `<if>` 原样发给了数据库。供应商的求购列表、详情、报价后的返回三处都是 500（报价本身已写库）。
   上一批 12 条询价测试没有一条走到这条 SQL；`ElecEndpointAuthTest` 打到了路径，但在进 SQL 之前就 401 了。
   这一批的手工指派测试要走「派单 → 供应商列表里看到 → 报价」，第一次跑就红在这里。
   修完后扫了全仓库 56 条文本块 SQL，含动态标签却没包 `<script>` 的只此一处。
4. **合并料号时删分段键**：设计没写。补别名会产生 MERGED 料号，而买家搜索的中段命中那一路
   （`PartKeyMapper#containCandidates`）不按状态过滤 —— 不删的话买家会搜出一条空的「厂牌不明」重影。
   以前没有代码写 MERGED，这个洞一直没暴露。
