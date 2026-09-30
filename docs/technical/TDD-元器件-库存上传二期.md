# TDD-元器件 · 库存上传二期（分级报错 · 问题行导出 · 下架护栏 · 上传记录 · 限次）

> 2026-09-30 · 状态：**草稿**
> 档位：1（新增 4 个端点 · 改 2 个端点的入参 · 2 张表加 3 列 · 2 个错误码 · 2 个配置项）
> 依据：[PRD-元器件-库存表上传](../requirements/PRD-元器件-库存表上传.md) §三 AC1–AC11
> 前置：[独立服务与第一步](./TDD-元器件-独立服务与第一步.md)（上传主链路）· [运营端接口](./TDD-元器件-运营端接口.md)（`ElecOpsGuard`）

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 错误 / 警告分级；警告行照常上架 | `ElecStockImportServiceImpl#plan` 加 `warn(...)` · `RowProblem` 加 `level` · `BatchPreview` 加 `rowWarn` · `elc_stock_batch.row_warn` |
| AC2 | 导出问题行为 xlsx | 新 `support/SheetWriter` · `GET /elec/b/stock/batch/{no}/problems` · 问题落在 `elc_stock_batch_row.issue_code/issue_level/issue_arg` |
| AC3 | 改好后按合并补传，已上架的不动 | 无新代码：MERGE 本来就只改表里有的行。落点是**测试**钉住它 + 端上「补传」入口默认 MERGE |
| AC4 | 下架过线须带 `expectDelist` | `ElecStockImportService#apply(…, Integer expectDelist)` · `BatchPreview.delistConfirm` · `ELEC_DELIST_CONFIRM` · `elec.delist-confirm-bp` |
| AC5 | 确认的是此刻的下架数 | 同 AC4：`expectDelist` 与**重算后**的数比，不与存下来的 `to_delist` 比 |
| AC6 | 同一家的确认串行 | `apply` 开头 `SupplierMapper#lockBySupplierNo`（`SELECT … FOR UPDATE`） |
| AC7 | 每天上传上限 | `upload` 开头数今天的批次 · `ELEC_UPLOAD_DAILY_LIMIT` · `elec.upload-daily-max` |
| AC8 | 上传记录列表，含已过期、原文件名 | `GET /elec/b/stock/batch` · 新 `ElecStockBatchService` · 状态 EXPIRED **读时算** · 上传加参 `name` |
| AC9 | 记录详情 + 再导出 | `GET /elec/b/stock/batch/{no}` · 问题行从 `elc_stock_batch_row` 读，不重算 |
| AC10 | 运营查某家的上传记录 | `GET /elec/ops/supplier/{no}/batch` · `PERM_SUPPLIER_READ` |
| AC11 | 别人的批次 404 | `ElecStockBatchService#own` 按 `supplier_no` 取，取不到 404 |

**孤立项**：

- 没落点的 AC：无。
- 挂不上 AC 的设计：无。PRD §二 里二期的行（1.5 起）本篇一行都不碰。

---

## §1 现状与影响面

**已有、直接复用**：

| 复用 | 在哪 |
|---|---|
| 解析、猜列、先算后做、空格子不改 | `ElecStockImportServiceImpl`（`plan` / `apply`） |
| 原样行 | `elc_stock_batch_row.cells`：导出问题行就是把这些行原样写回去，**不需要原文件** |
| 厂牌别名表 | `ElecPartCatalog#aliases()`（`MFR_UNKNOWN` 用它判） |
| 年份解析 | `Cells#dcYear`（`DC_UNPARSED` = 批号非空而它回 null） |
| 供应商身份 | `ElecSupplierAccess#requireActive` —— supplier_no 只从这里来 |
| 运营鉴权 | `ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ)` |

**会被改到的已在跑的功能**：

- `POST /elec/b/stock/upload`：多一个可选参 `name`；多一道每日次数判定。**不带 `name` 的老端上照常工作**（文件名退回 multipart 的原名）。
- `POST /elec/b/stock/batch/{no}/apply`：多一个可选 body `{expectDelist}`。
  **行为变化**：全量替换下架过线时，老端上（不带 body）会被拒 —— 这正是护栏要的；端上同批改好（§2 模块设计）。
  不过线的确认与现在完全一样。
- `plan()`：多记警告；`problem()` 的 50 条上限只作用于**预览回包**，落库的问题行不设上限（导出要全量）。
- `remap()`：问题行要随新映射重写（先清后写，见 §2.3）。

**明确不受影响的**：买家搜索与 `elc_part_market` 投影；「仍有货」续期；到期提醒；询价与派单；
已上架的库存行（本篇不改 `elc_stock` 一个字段）。

---

## §2 方案

### 2.1 契约变更

**端点**（供应商面 4 条走 `ctk_`，运营 1 条走运营令牌）：

| 方法 路径 | 入参 | 出参 | AC |
|---|---|---|---|
| `POST /elec/b/stock/upload` **（改）** | + `name`（可选，≤128） | `BatchPreview`（加字段，见下） | AC7 AC8 |
| `POST /elec/b/stock/batch/{no}/apply` **（改）** | + body `{expectDelist?: int}`（可空） | `BatchPreview` | AC4 AC5 AC6 |
| `GET /elec/b/stock/batch` **（新）** | `page`、`size`（≤50） | `List<BatchSummary>` | AC8 |
| `GET /elec/b/stock/batch/{no}` **（新）** | — | `BatchPreview` | AC9 AC11 |
| `GET /elec/b/stock/batch/{no}/problems` **（新）** | — | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 字节，**不套信封** | AC2 AC9 AC11 |
| `GET /elec/ops/supplier/{no}/batch` **（新）** | `page`、`size` | `List<BatchSummary>` | AC10 |

**DTO**（`SupplierDtos`）：

```java
// 加 level：ERROR / WARN；加 arg：给人话里的占位（如认不出的厂牌原文），可空
public record RowProblem(int row, String reason, String mpn, String level, String arg) {}

// 加 rowWarn、delistConfirm（true = 确认时必须带 expectDelist）、createdAt、appliedAt
public record BatchPreview(..., int rowWarn, boolean delistConfirm,
                           LocalDateTime createdAt, LocalDateTime appliedAt) {}

// 列表一行。status 多一个 EXPIRED（读时算，库里仍是 PARSED）
public record BatchSummary(String batchNo, String fileName, String mode, String status,
                           int rowTotal, int rowValid, int rowInvalid, int rowWarn,
                           int toInsert, int toUpdate, int toDelist, int unchanged,
                           LocalDateTime createdAt, LocalDateTime appliedAt) {}

public record ApplyReq(Integer expectDelist) {}
```

`RowProblem.reason` 取值：原 4 个（ERROR）+ `MFR_MISSING` / `MFR_UNKNOWN` / `QTY_ZERO` / `DC_UNPARSED`（WARN）。

**库表**：新迁移 `db/elec/V3__elec_batch_issues.sql`。**V1、V2 已在生产执行过，一个字节都不改**
（`ElecAppliedMigrationsFrozenTest` 钉着）。写之前再 `ls db/elec/` 一次 —— 并行会话可能已经占了 V3。

```sql
ALTER TABLE elc_stock_batch     ADD COLUMN row_warn    INT          NOT NULL DEFAULT 0 COMMENT '警告行数：照常上架，但列给供应商看';
ALTER TABLE elc_stock_batch_row ADD COLUMN issue_code  VARCHAR(32)  DEFAULT NULL COMMENT '这一行的问题；空 = 没问题';
ALTER TABLE elc_stock_batch_row ADD COLUMN issue_level VARCHAR(8)   DEFAULT NULL COMMENT 'ERROR 不上架 / WARN 照常上架';
ALTER TABLE elc_stock_batch_row ADD COLUMN issue_arg   VARCHAR(64)  DEFAULT NULL COMMENT '人话里的占位，如认不出的厂牌原文';
```

- 一列一条 `ALTER`：H2 与 MySQL 对「一条 ALTER 加多列」的写法不同。
- 不加索引：问题行按 `(batch_no, row_idx)` 现有索引扫一个批次，最多 2 万行。
- 三处同步：迁移 + 实体（`ElcStockBatch#rowWarn`、`ElcStockBatchRow#issue*`）+ 重跑
  `backend/scripts/gen-test-schema.py` 生成 `elec-svc/src/test/resources/db/elec-h2/V1__elec_baseline.sql`。

**错误码**（`shop-base` `ErrorCode`，9xxxx 段；写之前再看一次末号）：

| 码 | 键 | 占位 |
|---|---|---|
| `ELEC_UPLOAD_DAILY_LIMIT(90015)` | `err.elec.upload_daily_limit` | `{0}` = 每天上限 |
| `ELEC_DELIST_CONFIRM(90016)` | `err.elec.delist_confirm` | `{0}` = 此刻将下架的行数 |

**i18n**：上面两条 × `shop-app/src/main/resources/i18n/messages{,_en,_ar}.properties`；
端上 `elec-app/src/shared/format.ts` 的 `ROW_PROBLEM` 补 4 个警告码的人话。
导出 xlsx 里的「问题」列文案在后端（`SheetWriter` 调用方按 `issue_code` 取），走 `i18n/elec/messages*.properties`，
`ElecMessagesParityTest` 管三语键集一致。

**配置项**（`ElecProperties`，env `SHOP_ELEC_*`）：

| 键 | 默认 | 说明 |
|---|---|---|
| `elec.upload-daily-max` | 20 | 每家每天（自然日，服务器时区）上传次数；0 = 不限 |
| `elec.delist-confirm-bp` | 3000 | 将下架 ÷ 当前在售 ≥ 此万分比时须带 `expectDelist`；0 = 凡有下架都须带 |

### 2.2 模块设计

| 动作 | 路径 | 说明 | AC |
|---|---|---|---|
| 新增 | `elec-core/…/db/elec/V3__elec_batch_issues.sql` | 见 2.1 | AC1 AC2 |
| 修改 | `elec-core/…/entity/ElcStockBatch.java`、`ElcStockBatchRow.java` | 加列对应字段 | AC1 AC2 |
| 修改 | `elec-core/…/mapper/ElecMappers.java` | `SupplierMapper#lockBySupplierNo`；`StockBatchRowMapper#clearIssues(batchNo)`、`#setIssue(…)` 批量；`StockBatchMapper#countSince(supplierNo, from)` | AC2 AC6 AC7 |
| 修改 | `elec-core/…/config/ElecProperties.java` | 两个配置项 | AC4 AC7 |
| 修改 | `elec-core/…/dto/SupplierDtos.java` | `RowProblem`、`BatchPreview` 加字段；新 `BatchSummary`、`ApplyReq` | 全部 |
| 修改 | `elec-core/…/service/ElecStockImportService.java` + `impl/ElecStockImportServiceImpl.java` | 警告、问题行落库、护栏、加锁、限次、`name` | AC1 AC4–AC7 |
| 新增 | `elec-core/…/service/ElecStockBatchService.java` + `impl/ElecStockBatchServiceImpl.java` | 列表、详情、导出；EXPIRED 读时算；按 supplier_no 取 | AC2 AC8–AC11 |
| 新增 | `elec-core/…/support/SheetWriter.java` | 最小 xlsx 写出（见 2.4） | AC2 |
| 修改 | `elec-core/…/api/b/ElecSupplierController.java` | 改 2 条、加 3 条 | AC2 AC4 AC7–AC9 |
| 修改 | `elec-core/…/api/ops/ElecOpsSupplierController.java` | 加 1 条 | AC10 |
| 修改 | `shop-base/…/common/ErrorCode.java` + `shop-app/…/i18n/messages*.properties` | 两个码三语 | AC4 AC7 |
| 修改 | `elec-core/…/i18n/elec/messages*.properties` | 8 个问题码的人话（导出用） | AC2 |
| 重新生成 | `elec-svc/src/test/resources/db/elec-h2/V1__elec_baseline.sql` | `gen-test-schema.py` | — |
| 修改 | `elec-svc/src/test/…/ElecFlowTest.java`（或新 `ElecUploadFlowTest.java`） | AC1–AC9 AC11 | — |
| 修改 | `elec-svc/src/test/…/ElecOpsFlowTest.java` | AC10 | — |
| 修改 | `elec-svc/src/test/…/ElecEndpointAuthTest.java` | 新端点的匿名 / 非供应商探测 | AC11 |
| 修改 | `packages/shared/src/types/elec.ts` | `ElecRowProblem`、`ElecBatchPreview`、`ElecBatchSummary` | — |
| 修改 | `elec-app/src/api/index.ts` | `uploadStock` 带 `name`；`applyBatch` 带 `expectDelist`；`batches` / `batch` / `downloadProblems` | — |
| 修改 | `elec-app/src/shared/format.ts` | `ROW_PROBLEM` 补 4 个 | AC1 |
| 修改 | `elec-app/src/pages/stock-upload/index.vue` | 传 `name` | AC8 |
| 修改 | `elec-app/src/pages/stock-preview/index.vue` | 第三个页签「提醒 N」；「导出问题行」；`delistConfirm` 时弹窗写明「将下架 N 行」再确认 | AC1 AC2 AC4 |
| 新增 | `elec-app/src/pages/stock-batches/index.vue` + `pages.json` | 上传记录；点一条进 `stock-preview?batchNo=`（只读态）；有问题行的给「导出」「补传」 | AC8 AC9 |
| 修改 | `elec-app/src/pages/supplier/index.vue` | 工作台加「上传记录」入口 | AC8 |
| 修改 | `prototypes/elec-rfq.html` + `prototypes/registry.json` | 新一屏「上传记录」挂锚点；e16 补「提醒」页签与确认弹窗 | — |
| 重新生成 | `docs/technical/design/ui-catalog.json`、`prototypes/index.html` | `gen-proto-index.py`、`gen-ui-catalog.py` | — |
| 重新生成 | `docs/api/openapi*.yaml`、元器件表清单 | 在当下 HEAD 的干净副本里跑 | — |

### 2.3 关键规则（每条写「防住什么」）

**问题行落库，不在读时重算。** 详情与导出读 `issue_*`，不再跑一遍 `plan()`。
—— 防住：已上架批次的问题行「变了」。`MFR_UNKNOWN` 取决于别名表，运营今天补了一个别名，
重算就会让三天前那张表的警告凭空少一条，而供应商拿去对的是当时导出的那份。

**`remap` 先清后写。** 换了映射，所有行的问题都可能变：`clearIssues(batchNo)` 再按新结果批量 `setIssue`，同一事务。
—— 防住：旧映射下的「没有料号」留在新映射的导出里。
注意 `updateById` 跳 null，「清空」必须是显式的 `UPDATE … SET issue_code = NULL …`，不能靠实体置 null。

**下架护栏比的是重算后的数。** `apply` 里先加锁、再 `plan()`，然后：
```
needConfirm = toDelist > 0 && toDelist * 10000 >= onSale * delistConfirmBp
if needConfirm && !Objects.equals(expectDelist, toDelist) → ELEC_DELIST_CONFIRM(toDelist)
```
—— 防住两件事：半张表把库存清掉（AC4）；预览之后库存变了，他确认的其实是一个已经不存在的后果（AC5）。
`onSale` = 该家此刻 `STATUS_ON` 行数；为 0 时不会有下架，自然不触发。
预览时同一个公式算出 `delistConfirm` 给端上决定要不要弹窗 —— **端上只是提前告诉他，判定在后端**。

**加锁锁供应商行，不锁批次行。** `SELECT id FROM elc_supplier WHERE supplier_no = ? FOR UPDATE`，放在 `apply` 事务第一句。
—— 防住：两张预览同时确认时各自以同一份「此刻库存」算计划，交错写出一份两边都不认的库存（AC6）。
锁批次行没用：冲突在两个**不同**批次之间。第二个请求等第一个提交后重算，结果等于依次执行。

**限次只数 `upload`。** `countSince(supplierNo, 今天 00:00) >= uploadDailyMax` → 拒。数的是 `elc_stock_batch` 行，
`remap` / `apply` 不新建批次，自然不计。
—— 防住：有人拿上传当接口刷（每次都解析 2 万行、写 2 万条原样行）。不防：正常人反复试映射 —— 那走 `remap`，不计次。
与并发上传之间有一个「同时第 20、21 次都通过」的缝，不加锁：多一次无害，锁住上传反而拖慢正常用户。

**EXPIRED 读时算。** `PARSED && created_at + batchTtlHours < now` → 回 `EXPIRED`，库里不改。
—— 防住：为一个显示状态加定时任务；也保证与 `parsedBatch()` 的过期判定是同一个式子（抽成一个方法共用）。

**文件名以端上为准。** `name` 非空用它，否则用 `MultipartFile#getOriginalFilename`。
—— 防住：记录里全是 `tmp_8a3f….xlsx`。小程序 `chooseMessageFile` 能拿到原名，但 `uploadFile` 传上来的是临时路径名
（`stock-preview` 页头注释已经记着这件事，只是之前没存进批次）。

### 2.4 问题行导出

`SheetWriter.xlsx(List<String> header, List<List<String>> rows) → byte[]`：手写最小 xlsx
（`[Content_Types].xml`、`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/worksheets/sheet1.xml`），
单元格一律 `t="inlineStr"`。

- **不引 POI**：与 `SheetReader` 同一个理由（jar 体积、GraalVM）。
- **一律写文本、不写数字**：料号 `0805`、`1E5` 写成数字单元格，Excel 打开就被改掉，他改完回传时料号已经坏了 ——
  导出的目的是「改完直接回传」，这一条是它成立的前提。
- **为什么不导 CSV**：小程序 `uni.openDocument` 不认 csv；而供应商就在小程序里，打开后转发给自己电脑是他的实际路径。
- 行内容 = 表头行 + 「问题」列；其后每个问题行：原样 `cells` + 人话。按 `row_idx` 升序。
- 最多 2 万行（上传上限），不另设上限。

**端上下载**：不走 `uni.downloadFile` —— 它要另配「downloadFile 合法域名」。改为
`uni.request({ responseType: 'arraybuffer' })`（request 域名已配）→ `FileSystemManager.writeFile` 到 `USER_DATA_PATH`
→ `uni.openDocument({ fileType: 'xlsx', showMenu: true })`，`showMenu` 让他能直接「发送给朋友 / 用其他应用打开」。
H5 走 Blob + `<a download>`。封在 `elec-app/src/api/index.ts#downloadProblems` 里，页面不感知平台差异。

**响应不套信封**：出参是 `ResponseEntity<byte[]>`。实现时**先确认 elec-svc 的全局信封对 `ResponseEntity<byte[]>` 放行**
（主系统吃过「内部端点被信封裹住、字段全 null、浏览器里看不出」的亏）；测试断言 `Content-Type` 与 zip 魔数 `PK`，不只断言 200。

---

## §5 对账三 · 实现 → 需求（测试）

测试放 `elec-svc`（H2 真迁移 + `FakeMainSystem`），与现有 `ElecFlowTest` 同一套装配。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `ElecUploadFlowTest#ac1_warningsListedButStillListed` | | 把 `warn()` 改成 `problem()` → 红在「警告行在售」 |
| AC2 | `ElecUploadFlowTest#ac2_exportHasOnlyProblemRowsAsText` | | `SheetWriter` 写数字单元格 → 红在「0805 仍是 0805」 |
| AC3 | `ElecUploadFlowTest#ac3_fixedRowsMergeWithoutTouchingOthers` | | 补传改用 REPLACE → 红在「原有行仍在售」 |
| AC4 | `ElecUploadFlowTest#ac4_delistOverLineNeedsExpectDelist` | | 注掉护栏 → 红 |
| AC5 | `ElecUploadFlowTest#ac5_expectDelistComparedWithRecomputedCount` | | 改成与 `b.getToDelist()` 比 → 红 |
| AC6 | `ElecUploadFlowTest#ac6_concurrentApplySerialized` | | 注掉 `lockBySupplierNo` → 红（两线程 + `CountDownLatch`，重复 20 次） |
| AC7 | `ElecUploadFlowTest#ac7_dailyLimitCountsUploadsOnly` | | 把 remap 也计次 → 红 |
| AC8 | `ElecUploadFlowTest#ac8_historyShowsExpiredAndClientName` | | 不存 `name` → 红 |
| AC9 | `ElecUploadFlowTest#ac9_detailReadsStoredIssues` | | 详情改成重算 + 测试里中途补一个别名 → 红 |
| AC10 | `ElecOpsFlowTest#ac10_opsSeesSupplierBatches` | | 去掉 `ElecOpsGuard.require` → 红在 403 那一半 |
| AC11 | `ElecUploadFlowTest#ac11_othersBatchIs404` | | `own()` 去掉 supplier_no 条件 → 红 |

AC6 的消融要**真的跑到交错**：H2 的 `FOR UPDATE` 行为与 MySQL 一致性要先单独确认一次；
若 H2 下不加锁也碰巧串行（红不了），在本节写明，并在生产库副本上补一次手工验证。

---

## §6 对账二 · 设计 → 实现（实现完再填）

```
（待填 git diff --stat）
```

### 偏差说明

（待填）

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-30 | 草稿；PRD §四 三条待拍板 |
