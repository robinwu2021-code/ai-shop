# TDD-元器件 · 库存上传二期（别名表与大模型认列 · 定位到格 · 内存暂存 · 原件存盘 · 护栏与记录）

> 2026-09-30 · 状态：**已实现（未上线）** · v3（v1 分级报错 / 导出 / 护栏 / 记录 / 限次；v2 加认列、暂存、存盘，改掉「上传即写原样行」；v3 原件两区存放、保留原名）
> 档位：1（1 张新表 · 2 张表加列 · 10 个新端点 · 2 个端点改入参 · 4 个错误码 · 12 个配置项 · elec-core 加 ehcache 依赖）
> 依据：[PRD-元器件-库存表上传](../requirements/PRD-元器件-库存表上传.md) §三 AC1–AC24
> 前置：[独立服务与第一步](./TDD-元器件-独立服务与第一步.md)（上传主链路）· [运营端接口](./TDD-元器件-运营端接口.md)（`ElecOpsGuard`）

---

## 一句话（L1）

上传的原件以原名先落进「未入库」区；认列按 **记住的映射 → 表头别名表 → qwen → 手工** 逐级兜底；解析结果**只放 Ehcache**、
上传起最多 1 小时，缓存丢了就从原件重建；确认时加锁重算、过护栏，才把库存行、批次、问题行写进库，原件移进「已入库」区。未入库区每周清，已入库区暂不删。

![上传流程](./diagrams/elec-upload-flow.svg)

图上最要紧的是两条虚线：**缓存不是数据的唯一住处** —— 原件在盘上、映射在批次行里，
所以缓存被挤出、服务重启都只是「慢一点」，不是「数据没了」。这让我们敢把缓存容量压得很小（堆只有 768MB）。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 别名表命中不调大模型 | 新表 `elc_header_alias`（种子 = 现 `Columns.NAMES`）· `HeaderAliases` · `Columns.guess(rows, aliases)` |
| AC2 | 认不出调 qwen，来源标 AI | 端口 `gateway/ElecColumnAi` · 实现 `elec-svc/QwenColumnAi` · `ColumnResolver` · `BatchPreview.columnSource` |
| AC3 | 大模型结果内容校验 | `ColumnResolver#verify`（样本行 ≥60% 像料号 / 能读成数量） |
| AC4 | 大模型不可用 → 待指定列 | `ColumnResolver` 失败返回空；批次状态 `NEED_MAPPING`；`remap` 出口 |
| AC5 | 确认后学成本家别名 | `apply` 末尾 `HeaderAliases#learn(supplierNo, …)`；查找顺序本家 > 全局 |
| AC6 | 错误定位到格、一行多处 | `Issue(row, col, header, value, code, level)`；`plan` 逐字段收集不再 `continue` |
| AC7 | 错误 / 警告分级 | `Issue.level`；`BatchPreview.rowWarn` |
| AC8 | 导出问题行（标红、全文本） | `support/SheetWriter` · `GET …/batch/{no}/problems` |
| AC9 | 合并补传不动已上架 | 无新代码（MERGE 语义），测试钉住 + 端上入口默认 MERGE |
| AC10 | 预览返回解析后的数据 | `GET …/batch/{no}/rows?view=` · `PendingBatch.rows` 带分类 |
| AC11 | 预览期库里只有元数据 | `upload` 不再写 `elc_stock_batch_row` |
| AC12 | 放弃清内存、原件留着 | `DELETE …/batch/{no}` · 状态 `CANCELLED` · `PendingBatchCache#evict` |
| AC13 | 1 小时失效 | `PendingBatchCache` 的到期时刻 = `created_at + ttl`；`requirePending` 同一个式子 |
| AC14 | 重启后从原件重建、不重调大模型、不延寿 | `PendingBatchCache#getOrRebuild` · 自定义 `ExpiryPolicy` 按到期时刻算剩余 |
| AC15 | 一家一张待确认 | `upload` 开头把本家 `PARSED/NEED_MAPPING` 置 `SUPERSEDED` 并 `evict` |
| AC16 AC17 | 下架护栏，比重算后的数 | `apply(…, expectDelist)` · `ELEC_DELIST_CONFIRM` · `elec.upload.delist-confirm-bp` |
| AC18 | 确认串行 | `SupplierMapper#lockBySupplierNo`（`FOR UPDATE`） |
| AC19 | 原名存盘、日期/供应商分目录、库里对应、确认后移区 | `support/UploadFileStore`（路径与净化）· `elc_stock_batch.file_*` · `apply` 提交后 `moveToApplied` |
| AC19a | 解析失败也建批次、原件留在 failed | `ElecStockImportServiceImpl#upload` 落盘后解析；失败走 `BatchFailureRecorder`（`REQUIRES_NEW`）· `fail_code` |
| AC19b | 每周只清 failed，applied 暂不删 | `elec-svc/ElecUploadCleaner`（周一 3:30）· `file_purged_at` |
| AC19c | 移区失败的已上架原件补移不删 | `ElecUploadCleaner` 第 1 步 |
| AC19d | 运营下载原件 | `GET /elec/ops/supplier/{no}/batch/{batchNo}/file` · `ELEC_UPLOAD_FILE_PURGED` |
| AC20 | 每日上限，只数上传 | `StockBatchMapper#countSince` · `ELEC_UPLOAD_DAILY_LIMIT` |
| AC21 | 上传记录；原件清掉仍能导出 | `ElecStockBatchService` · 问题行在 `apply` 时写入 `elc_stock_batch_row` |
| AC22 | 运营查记录 | `GET /elec/ops/supplier/{no}/batch` · `PERM_SUPPLIER_READ` |
| AC23 | 运营提升别名为全局 | `/elec/ops/header-alias` 三条 · `PERM_BASE_MANAGE` |
| AC24 | 别人的批次 404 | `ElecStockBatchService#own` / `requirePending` 都按 supplier_no 取 |

**孤立项**：没落点的 AC 无；挂不上 AC 的设计无。PRD 二期各行本篇不碰。

---

## §1 现状与影响面

**直接复用**：

| 复用 | 在哪 |
|---|---|
| 解析、zip 炸弹 / XXE 防线、GBK 回退 | `SheetReader`（不改） |
| 阶梯价列、含税提示、表头行探测 | `Columns`（改：别名从参数传入，不再读常量） |
| 先算后做、空格子不改、全量替换 | `ElecStockImportServiceImpl#plan / #apply`（改：数据源从库换成缓存） |
| 调 cdw qwen 的写法 | `shop-channel/…/GoodsVisionGateway`：**钉死 HTTP/1.1**（sglang 不认 h2c，会丢请求体）、**关 thinking**（否则 content 是空串）、**不用 response_format**（这台部署上无效）。elec 不依赖 shop-channel，照这三条另写一个 |
| 每天一次的任务 | `ElecExpiryReminder` 的形状（`@Scheduled` + cron 配置，`-` 关掉） |
| ehcache 版本 | 根 POM 走 Boot BOM（`backend/pom.xml` 注释：别在模块里另钉版本，jakarta classifier） |

**实测依据**（2026-09-30，从生产机 `soukmind-tx` 发出）：

- 生产机 → `cdw.near3.ai:8003/v1/models`：200，0.34s。
- 表头 `序 / Item / Maker / Stk / 年份 / Pkg / 含税价 / 备注` + 3 行样本，连发三次，三次都回
  `{"MPN":1,"MFR":2,"QTY":3,"DC":4,"PACKAGE":5,"PRICE":6}`，0.73–1.04s，29 个输出 token。
  这张表头里 `Item`、`Stk` 不在现有同义词表里 —— **正是规则认不出、大模型能认出的那一类**。
- elec-svc：`-Xmx768m`；systemd `ProtectSystem=full`、`ReadWritePaths` 只有日志目录；`/data` 余 38G。

**会被改到的已在跑的功能**：

| 改动 | 对老端上的影响 |
|---|---|
| 上传不再写 `elc_stock_batch_row`，数据进缓存 | 无（端上从不读这张表）。**表的语义变成「确认时的问题行」**；生产里已有的旧批次原样行留着，不迁移 |
| 预览有效期 24 小时 → 1 小时 | 老端上开着预览超过 1 小时会拿到「已失效」—— 本来就有这个错误码与文案 |
| 认不出表头：报错 → 回 `NEED_MAPPING` 状态 | **老端上会拿到一个没有映射的预览**。端上同批改好（列映射页本来就能选列）；发布顺序见 §2.9 |
| 确认过线要 `expectDelist` | 老端上全量替换过线时被拒 —— 正是护栏要的 |
| 再传一张会作废前一张 | 老端上无感（它一次只拿一个 batchNo） |
| 解析失败也建一条 `FAILED` 批次（原件落盘） | 无：端上收到的错误码与文案不变；只是记录里多一条、每日次数把它算进去 |
| `Columns.NAMES` 常量删掉，换成表 | 行为不变：种子逐条等于原常量（测试比对） |

**明确不受影响的**：买家搜索与 `elc_part_market`；「仍有货」续期；到期提醒；询价与派单；已上架库存行的字段。

---

## §2 方案

### 2.1 认列：四级递进

```
① 记住的映射   本家上次确认过的批次，表头一字不差 → 直接用（现有逻辑，保留）
② 别名表       HeaderAliases.lookup(supplierNo)：本家 LEARNED 覆盖全局 SEED/OPS
               → Columns.guess(rows, aliases)：表头行探测、同义词、阶梯价列
③ qwen         仅当 ② 之后「缺料号 / 缺数量 / 缺厂牌 / 两列抢同一字段 / 没找到表头行」之一成立
④ 手工         ③ 之后仍缺料号或数量 → status = NEED_MAPPING，端上逐列选
```

每个字段记来源：`REMEMBERED / ALIAS / AI / MANUAL`，存 `elc_stock_batch.column_source`（JSON），回给端上。
端上对 `AI` 来源的字段加「AI 识别，请核对」角标。

**③ 的合并规则**：**大模型只填 ② 没认出的字段**，② 认出的不让它改 —— 规则认出来的是确定的，
大模型的是概率的，反过来会让一张本来认得好好的表被模型「纠正」错。例外：② 里两列抢同一字段时，以模型为准并校验。

**③ 的输入**（`ElecColumnAi.guess(List<List<String>> head10, List<FieldSpec> fields)`）：

- 前 10 行（找表头行用），每格截 40 字符，最多 40 列；**数据行最多 5 行**（PRD 待拍板 #4：样本会经公网）
- 字段清单：码 + 中文说明 + 一两个例子（`MPN 料号/型号，如 STM32F103C8T6`）
- 输出要求：`{"headerRow":0,"columns":{"MPN":1,"QTY":3}}`，只输出 JSON

**③ 的校验**（`ColumnResolver#verify`，防住：模型认错了列，而一个错的料号列会让整张表作废或错上架）：

| 字段 | 样本行（表头下 ≤20 行非空）里至少 60% 满足 | 否则 |
|---|---|---|
| MPN | `Mpn.looksLikeMpn(Mpn.norm(v))` | 丢掉 |
| QTY | `Cells.qty(v) != null` | 丢掉 |
| MFR | 不是纯数字 | 丢掉 |
| PRICE / MOQ / SPQ | `Cells.priceE6` / `Cells.moq` 能读 | 丢掉 |
| 其余 | 不校验 | — |

另外：`headerRow` 必须在 0..9；列号在范围内；同一列不许给两个字段；字段码不认识的丢掉。

**③ 的保护**：超时 8 秒；**熔断** —— 连续 3 次失败后 5 分钟内不再调，直接走 ④（防住：cdw 挂了时每次上传都白等 8 秒）；
`elec.ai.enabled=false` 时整级跳过。失败一律记 WARN 日志并回空，**不让上传失败**。

**学习**（AC5）：`apply` 成功后，对来源是 `AI` 或 `MANUAL` 的字段，把那一列的表头原文规范化后写成
`(supplier_no=本家, alias_norm, field, source=LEARNED)`。跳过：表头为空、规范化后不足 2 字符、是阶梯价表头。
**只写本家、不写全局** —— 防住：一家把「规格」当料号，全平台跟着认错。全局要运营提升（AC23）。

### 2.2 问题定位到格

```java
/** 一处问题。row 与 Excel 左边的行号一致；col 从 0 起，端上显示成字母 */
public record Issue(int row, int col, String header, String value, String code, String level) {}
```

- `plan()` 逐字段收集，**一行多处全记**（现在是遇到第一处就 `continue`）；有 ERROR 的行不进 `valid`。
- `value` 截 64 字符；`col = -1` 表示整行级问题（如 `DUPLICATE`，`arg` 里给「与第 N 行重复」）。
- 预览回包带：各问题码计数 + 前 100 条；全部走 `rows?view=PROBLEM` 分页。
- 人话在端上拼：`D12（数量）读不出：约2千`。列字母用现有 `colLetter`。

问题码：

| 码 | 级别 | 位置 |
|---|---|---|
| `MPN_MISSING` / `MPN_INVALID` | ERROR | 料号列 |
| `QTY_INVALID` | ERROR | 数量列 |
| `DUPLICATE` | ERROR | 整行（`col=-1`） |
| `MFR_MISSING` / `MFR_UNKNOWN` | WARN | 厂牌列（没映射厂牌列时 `col=-1`） |
| `QTY_ZERO` | WARN | 数量列 |
| `DC_UNPARSED` | WARN | 批号列 |

### 2.3 内存暂存（Ehcache）

**放什么**：`PendingBatch`（不可变）—— `batchNo`、`supplierNo`、`deadline`、映射、`List<ParsedRow>`（解析后的值 + 分类 INSERT/UPDATE/UNCHANGED）、
`List<Issue>`、下架行的 `lineKey` 列表、计数。**不放原始单元格**（重建与导出从原件读，省一半内存）。

**容量**（堆 768MB）：一行解析结果约 0.6KB，2 万行 ≈ 12MB。上限 `elec.upload.cache-max-batches=10` 条，最坏约 120MB。
超了按 LRU 挤出 —— **挤出无害**，下次访问从原件重建（AC14）。一家只留一张待确认（AC15），10 条 ≈ 10 家同时在看预览。

**到期**：Ehcache 3 原生 API（不走 JCache、不走 Spring Cache 注解 —— 要的是自定义到期策略）：

```java
ExpiryPolicy<String, PendingBatch> byDeadline = new ExpiryPolicy<>() {
    public Duration getExpiryForCreation(String k, PendingBatch v) {
        return Duration.between(Instant.now(), v.deadline()).isNegative() ? Duration.ZERO
                : Duration.between(Instant.now(), v.deadline());
    }
    public Duration getExpiryForAccess(String k, Supplier<? extends PendingBatch> v) { return null; } // 不因访问延长
    public Duration getExpiryForUpdate(String k, Supplier<? extends PendingBatch> o, PendingBatch v) { return null; }
};
```

- `deadline = batch.created_at + elec.upload.pending-ttl-minutes`（默认 60）。**重建出来的条目沿用同一个 deadline** ——
  防住：重建一次续一小时，内存里的数据实际能活好几个小时。
- 访问不延长（`getExpiryForAccess` 回 null）：用户要求的是「最多 1 小时」，不是「1 小时没人碰」。
- 判定是否过期**不信缓存**：`requirePending()` 先看库里批次 `status ∈ {PARSED, NEED_MAPPING}` 且 `now < deadline`，
  再去缓存取。缓存只是加速，状态以库为准。

**重建**（`PendingBatchCache#getOrRebuild`）：缓存未命中且库里判定仍有效 → `UploadFileStore.read(path)` →
`SheetReader.read` → 用**存下的** `header_row` / `column_map` / `tier_cols` 重解析 → 放回缓存。
**不重调大模型**（映射已在库里）。原件不在（两区都找不到）→ 置 `EXPIRED`、回 `ELEC_BATCH_EXPIRED`。

**清理**：确认 / 放弃 / 作废 都 `evict`（只清内存，原件见 2.4）；过期交给 Ehcache 自己丢。**没有需要人去关的后台线程**，
这是用 Ehcache 而不是自己写 `ConcurrentHashMap` + 定时器的理由（防内存泄露的那一半由它负责）。

**多实例**：elec-svc 现在单实例。将来多实例时，缓存是各实例本地的，但**重建路径让它仍然正确**
（打到另一台就从原件重建）—— 前提是上传目录是共享盘。写进 §4 风险。

### 2.4 原件存盘（原名 · 按日期与供应商分目录 · 已入库 / 未入库两区）

**目录**：

```
<elec.upload.dir>/
├── failed/                       未入库：待确认中、解析失败、放弃、作废、过期        每周清，保留 7 天
│   └── 2026-10-08/
│       └── S001/
│           └── 9月库存（华强）_B20261008xxxx.xlsx
└── applied/                      已入库：确认上架过的                              暂不删（retention 0）
    └── 2026-10-08/S001/9月库存（华强）_B20261008xxxx.xlsx   ← 确认时从 failed/ 原样移过来
```

- **上传一律先落 `failed/`**，确认上架时移到 `applied/` 的**同一相对路径**。「未入库」是默认态 ——
  防住：只有「成功」时才落盘的话，解析失败的原件根本留不下来，而那正是最需要看原件的时候。
- 待确认中的文件也在 `failed/`：它最多 1 小时后要么被移走、要么就是未入库；每周清理只删 7 天前的目录，碰不到它。
- 目录是 `日期/供应商号/`：按日期删整目录简单可靠；供应商号一层让「看某家传过什么」直接 `ls`。

**文件名** = `<原名去扩展名，净化后>_<批次号>.<扩展名>`：

| 规则 | 防住什么 |
|---|---|
| 原名取端上传来的 `name`，没有才用 multipart 的原名 | 小程序 `uploadFile` 传上来的是临时路径名（`tmp_8a3f…`），不是他选的文件名 |
| 净化：去掉路径部分（`/`、`\`）、控制字符、`..`、首尾空白与点；Windows 保留字符 `<>:"\|?*` 换成 `_`；中文保留 | 路径穿越；文件名在别的系统上打不开 |
| 净化后按 UTF-8 截到 150 字节（留出 `_批次号.xlsx` 的余量，总长不过 255 字节） | Linux 单个文件名 255 字节上限；中文一个字 3 字节，85 个字就撞线 |
| 净化后为空 → 用 `upload` | 文件名全是非法字符时 |
| 后缀带批次号 | 同一家同一天传两次同名文件不会互相覆盖；拿到文件就能反查批次 |
| 扩展名**按魔数**定（`PK` → xlsx、OLE → xls、其余 csv），不信原名的扩展名 | 把 csv 改名成 xlsx 的；`.xls` 也要落盘（解析失败的原件恰恰是它） |
| 先写 `<名>.part` 再原子 `move` | 半个文件被重建读到 |
| 日期按 `Asia/Shanghai` | 与库里 `created_at` 同一天，排查对得上 |

**库里的对应**（`elc_stock_batch`）：

| 列 | 例 | 说明 |
|---|---|---|
| `supplier_no` | `S001` | 已有 |
| `file_name` | `9月库存（华强）.xlsx` | 已有列；**改存原名**（净化前的，给人看） |
| `file_path` | `2026-10-08/S001/9月库存（华强）_B…xlsx` | 相对路径，**不含区** |
| `file_area` | `FAILED` / `APPLIED` | 在哪个区 |
| `file_size` / `file_sha256` | | 大小；sha256 给二期去重、也用来核对移动没传坏 |
| `file_purged_at` | | 清理任务删掉它的时刻；非空 = 原件已不在 |

相对路径与区分开存：移动只改 `file_area` 一列。读文件时**先按 `file_area` 找，找不到再看另一区** ——
防住：移动成功了、库没来得及改（或反过来）时，重建与下载跟着失败。

**解析失败也建批次**（AC19a）：落盘后才解析；解析抛出 `ELEC_UPLOAD_FORMAT / NO_HEADER / TOO_MANY_ROWS` 时，
用**独立事务**（`REQUIRES_NEW`）写一条 `status=FAILED`、`fail_code=<错误码键>` 的批次，再把原来的错误抛给端上。
—— 不用独立事务的话，抛出的异常会把这条记录一起回滚，等于没记。
一行有效的都没有（`rowValid=0`）不算解析失败：预览照常给他看问题行，他可以改映射；放弃或过期时它就是未入库。

**移到 `applied/`**：在确认事务**提交后**（`TransactionSynchronization#afterCommit`）做，然后单独一条 `UPDATE … SET file_area='APPLIED'`。
- 为什么不在事务里移：事务回滚时文件已经移走，还得再移回来，两个方向都可能失败。
- 移动失败：记 ERROR + 企业微信告警，**不影响上架**（库存已经提交）。文件还在 `failed/`，由下面清理任务里的「补移」兜住。

**清理任务** `ElecUploadCleaner`（elec-svc，`@Scheduled(cron = "${elec.upload.clean-cron:0 30 3 * * MON}")`，每周一 3:30）：

1. **先补移**：查 `status=APPLIED AND file_area='FAILED' AND file_purged_at IS NULL` 的批次，逐个移到 `applied/` 并改 `file_area`。
   —— 防住：确认时移动失败的那个已上架原件，在下一步被当成未入库删掉。**补移失败的批次所在的日期目录这次不删**。
2. **再删 `failed/`**：只认名字能解析成日期的目录，早于 `today - failed-retention-days`（默认 7）的整目录删；
   删之前按目录下的文件把对应批次的 `file_purged_at` 填上（一条 `UPDATE … WHERE file_area='FAILED' AND file_path LIKE '日期/%'`）。
3. **`applied/`**：`applied-retention-days` 为 0（默认）时整段跳过；将来设了天数，同样按日期目录删并填 `file_purged_at`。
4. 按目录名判断，**不看 mtime** —— mtime 会被拷贝、`touch`、备份恢复改掉。名字不像日期的目录、散落的文件一律不动，记 WARN。
5. 结束记 INFO：补移几个、删了几个目录多少字节、两区各占多少空间。

**放弃 / 作废 / 过期不删原件**（AC12 AC15）：只清缓存，原件留在 `failed/` 等每周清理。
—— 放弃之后还能在记录里看到他传过什么、运营还能下载，是这一改的用处；代价是最多多占 7 天盘。

**启动自检**：`elec.enabled=true` 时，启动即建好 `failed/`、`applied/` 并各写一个探针文件再删掉；失败**直接启动失败**，报出目录与运行用户。
—— 防住：目录没权限时服务照常起来，第一个供应商上传才炸，而那时看到的只是一个 500。
（生产上 elec-svc 以 `deploy` 身份跑，目录归属要对，见 §2.9。）

**运营下载原件**（AC19d）：`GET /elec/ops/supplier/{no}/batch/{batchNo}/file`，`PERM_SUPPLIER_READ`；
`Content-Disposition` 用 RFC 5987 的 `filename*=UTF-8''…` 带原名（中文名直接放 `filename=` 在部分浏览器里是乱码）；
`file_purged_at` 非空 → `ELEC_UPLOAD_FILE_PURGED`。**供应商端这一期不给下载**：原件本来就在他手里。

### 2.5 确认

`apply(userNo, batchNo, expectDelist)`，一个事务：

1. `requireActive` → `supplierMapper.lockBySupplierNo(no)`（`SELECT id FROM elc_supplier WHERE supplier_no=? FOR UPDATE`）
   —— 锁供应商行不锁批次行：冲突在两个**不同**批次之间（AC18）。
2. `requirePending` → `getOrRebuild` 拿 `PendingBatch`。
3. **按此刻库存重算分类与下架集**（缓存里的分类只给预览看）。
4. 护栏：`onSale = 本家 STATUS_ON 行数`；
   `needConfirm = toDelist > 0 && toDelist*10000 >= onSale*delistConfirmBp`；
   `needConfirm && !Objects.equals(expectDelist, toDelist)` → `ELEC_DELIST_CONFIRM(toDelist)`（AC16 AC17）。
5. `valid` 为空 → `ELEC_BATCH_EMPTY`（现有）。
6. 写库存行（现有逻辑）→ `market.refresh`。
7. 问题行入库：每个有问题的行一条 `elc_stock_batch_row`（`cells` 从原件取、`issues` JSON）。
   **在确认时入库而不是导出时读原件** —— 将来 `applied/` 设了保留天数、原件被清掉后，记录里的「导出问题行」仍要能用（AC21）。
8. 批次 → `APPLIED`，计数写回；`HeaderAliases#learn`。
9. **事务提交后**：`evict`；原件 `failed/` → `applied/` 并改 `file_area`（2.4）。提交前做的话，回滚时缓存已空、文件已移走，都要再补回来。

### 2.6 契约变更

**端点**（供应商面走 `ctk_`；运营面走运营令牌 + `ElecOpsGuard`）：

| 方法 路径 | 新 / 改 | 入参 | 出参 | AC |
|---|---|---|---|---|
| `POST /elec/b/stock/upload` | 改 | + `name`（可选，≤128） | `BatchPreview` | AC1–AC4 AC15 AC20 |
| `POST /elec/b/stock/batch/{no}/remap` | 改（语义） | 不变 | `BatchPreview`；`NEED_MAPPING` 在这里转 `PARSED` | AC4 |
| `GET /elec/b/stock/batch/{no}/rows` | 新 | `view=INSERT\|UPDATE\|DELIST\|UNCHANGED\|PROBLEM`、`page`、`size≤100` | `List<PreviewRow>` | AC6 AC10 |
| `POST /elec/b/stock/batch/{no}/apply` | 改 | + body `{expectDelist?}` | `BatchPreview` | AC16–AC18 |
| `DELETE /elec/b/stock/batch/{no}` | 新 | — | `BatchPreview`（状态 CANCELLED） | AC12 |
| `GET /elec/b/stock/batch` | 新 | `page`、`size≤50` | `List<BatchSummary>` | AC21 |
| `GET /elec/b/stock/batch/{no}` | 新 | — | `BatchPreview` | AC21 AC24 |
| `GET /elec/b/stock/batch/{no}/problems` | 新 | — | xlsx 字节，**不套信封** | AC8 AC21 |
| `GET /elec/ops/supplier/{no}/batch` | 新 | `page`、`size` | `List<BatchSummary>` | AC22 |
| `GET /elec/ops/supplier/{no}/batch/{batchNo}/file` | 新 | — | 原件字节，不套信封，`filename*` 带原名 | AC19d |
| `GET /elec/ops/header-alias` | 新 | `scope=GLOBAL\|LEARNED`、`keyword`、分页 | `List<HeaderAliasRow>`（LEARNED 按写法聚合，带「几家在用」） | AC23 |
| `POST /elec/ops/header-alias` | 新 | `{alias, field}` → 全局 | `HeaderAliasRow` | AC23 |
| `PUT /elec/ops/header-alias/{id}` | 新 | `{field?, status?}` | `HeaderAliasRow` | AC23 |

运营别名三条用 `PERM_BASE_MANAGE`（与厂牌别名同一个码，放在 `ElecOpsBaseController`）。

**DTO**（`SupplierDtos`）：

```java
public record Issue(int row, int col, String header, String value, String code, String level) {}

// 原 RowProblem 由 Issue 取代。加：rowWarn、columnSource、issueCounts、delistConfirm、deadline、createdAt、appliedAt
// status：NEED_MAPPING / PARSED / APPLIED / CANCELLED / SUPERSEDED / FAILED / EXPIRED（EXPIRED 读时算）
public record BatchPreview(String batchNo, String fileName, String mode, boolean taxIncluded,
        List<String> headers, int headerRow, Map<String, Integer> columns, Map<String, String> columnSource,
        int rowTotal, int rowValid, int rowInvalid, int rowWarn,
        int toInsert, int toUpdate, int toDelist, int unchanged,
        Map<String, Integer> issueCounts, List<Issue> issues, List<String> delistSample,
        boolean delistConfirm, String status, LocalDateTime deadline,
        LocalDateTime createdAt, LocalDateTime appliedAt) {}

/** 预览里的一行：解析后的值；UPDATE 带 before（只含变了的字段） */
public record PreviewRow(int row, String kind, String mpn, String mfr, String mfrCode, Long qty, String dateCode,
        String pkg, Integer moq, Integer spq, List<PriceTier> tiers, String currency, String packing,
        String cond, Integer leadDays, String region, Map<String, Object> before, List<Issue> issues) {}

public record BatchSummary(String batchNo, String fileName, String mode, String status, String failCode,
        int rowTotal, int rowValid, int rowInvalid, int rowWarn,
        int toInsert, int toUpdate, int toDelist, int unchanged, boolean aiUsed,
        LocalDateTime createdAt, LocalDateTime appliedAt) {}

public record ApplyReq(Integer expectDelist) {}
```

`RowProblem` 删掉前，端上 `ElecRowProblem` 类型与 `stock-preview` 同批改（同一个提交里前后端一起，否则 vue-tsc 红）。

**库表**：`db/elec/V3__elec_upload_v2.sql`。V1、V2 已在生产执行，一个字节都不改（`ElecAppliedMigrationsFrozenTest`）。
写之前再 `ls db/elec/`：并行会话可能已占了 V3。

```sql
-- 表头别名。supplier_no='' 表示全局；不用 NULL —— MySQL 的唯一键不管 NULL，会让同一写法插进好几条全局别名
CREATE TABLE IF NOT EXISTS elc_header_alias
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    supplier_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '空串 = 全局；否则只对这家生效',
    alias_norm  VARCHAR(64) NOT NULL COMMENT '规范化后的表头写法（大写、去空白与标点，保留 /）',
    alias_raw   VARCHAR(64) NOT NULL COMMENT '第一次见到时的原文，给运营看',
    field       VARCHAR(16) NOT NULL COMMENT 'MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ / SPQ / PACKING / CONDITION / CURRENCY / LEAD / REGION',
    source      VARCHAR(16) NOT NULL COMMENT 'SEED 种子 / OPS 运营加的 / LEARNED 供应商确认过的',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_header_alias (supplier_no, alias_norm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表表头别名';

INSERT IGNORE INTO elc_header_alias (supplier_no, alias_norm, alias_raw, field, source) VALUES
('', '型号', '型号', 'MPN', 'SEED'), ('', '料号', '料号', 'MPN', 'SEED'), ...;   -- 逐条等于现 Columns.NAMES

ALTER TABLE elc_stock_batch ADD COLUMN row_warn      INT          NOT NULL DEFAULT 0;
ALTER TABLE elc_stock_batch ADD COLUMN header_row    INT          DEFAULT NULL COMMENT '表头在第几行（从 0 起）；重建时按它解析';
ALTER TABLE elc_stock_batch ADD COLUMN column_source VARCHAR(512) DEFAULT NULL COMMENT '字段 → REMEMBERED/ALIAS/AI/MANUAL，JSON';
ALTER TABLE elc_stock_batch ADD COLUMN ai_used       TINYINT      NOT NULL DEFAULT 0 COMMENT '这次调过大模型没有';
ALTER TABLE elc_stock_batch ADD COLUMN file_path     VARCHAR(255) DEFAULT NULL COMMENT '原件相对路径 yyyy-MM-dd/供应商号/原名_批次号.xlsx，不含区';
ALTER TABLE elc_stock_batch ADD COLUMN file_area     VARCHAR(16)  DEFAULT NULL COMMENT 'FAILED 未入库区 / APPLIED 已入库区';
ALTER TABLE elc_stock_batch ADD COLUMN file_purged_at DATETIME    DEFAULT NULL COMMENT '清理任务删掉原件的时刻；非空 = 原件已不在';
ALTER TABLE elc_stock_batch ADD COLUMN fail_code     VARCHAR(48)  DEFAULT NULL COMMENT '解析失败时的错误码键（status=FAILED）';
ALTER TABLE elc_stock_batch ADD COLUMN file_sha256   CHAR(64)     DEFAULT NULL;
ALTER TABLE elc_stock_batch ADD COLUMN file_size     INT          DEFAULT NULL;
ALTER TABLE elc_stock_batch_row ADD COLUMN issues      VARCHAR(1024) DEFAULT NULL COMMENT '这一行的问题 JSON：[{c,l,col,v}]';
ALTER TABLE elc_stock_batch_row ADD COLUMN issue_level VARCHAR(8)    DEFAULT NULL COMMENT '这一行最重的级别：ERROR / WARN';
```

- 一列一条 `ALTER`：H2 与 MySQL 对一条加多列的写法不同。
- `file_name` 已是 `VARCHAR(128)`，存原名够用（净化前按字符截 128）。
- 加索引 `idx_elc_batch_file (file_area, status)`：清理任务的补移查询走它（全表扫在批次多了之后每周一次也能接受，但这条查询要在告警里反复跑）。
- `status` 列是 `VARCHAR(16)`，新取值不用改表；V1 里它的 COMMENT 只写了两个取值，**不去改 V1**，取值域以本篇与实体常量为准。
- **加列三处**：迁移 + 实体（`ElcStockBatch`、`ElcStockBatchRow`、新 `ElcHeaderAlias`）+ 重跑
  `backend/scripts/gen-test-schema.py` 生成 `elec-svc/src/test/resources/db/elec-h2/V1__elec_baseline.sql`。
- **新表进 ER 图与表清单**：`scripts/gen-elec-erd.mjs`，在当下 HEAD 的干净副本里跑。
- 种子是 `INSERT IGNORE … VALUES`（不用 `INSERT … SELECT`：表结构生成器会静默丢掉那种写法）。

**错误码**（`shop-base` `ErrorCode` 9xxxx 段，写之前再看一次末号）：

| 码 | 键 | 占位 |
|---|---|---|
| `ELEC_UPLOAD_DAILY_LIMIT(90015)` | `err.elec.upload_daily_limit` | `{0}` = 每天上限 |
| `ELEC_DELIST_CONFIRM(90016)` | `err.elec.delist_confirm` | `{0}` = 此刻将下架行数 |
| `ELEC_UPLOAD_STORE(90017)` | `err.elec.upload_store` | — 原件写盘失败（盘满、权限）。不吞：写不进盘就没法重建，预览不可靠 |
| `ELEC_UPLOAD_FILE_PURGED(90018)` | `err.elec.upload_file_purged` | — 原件已被清理（运营下载时） |

三语文案进 `shop-app/src/main/resources/i18n/messages{,_en,_ar}.properties`；
`ELEC_UPLOAD_NO_HEADER(90006)` **保留**（没有一行像表头、且大模型也给不出时仍会用到 —— 例如整张表只有一列），
但常规路径改为 `NEED_MAPPING`。导出 xlsx 的「问题」列人话进 `elec-core/…/i18n/elec/messages*.properties`
（`ElecMessagesParityTest` 管三语一致）；端上 `ROW_PROBLEM` 补 4 个警告码。

**配置项**（`ElecProperties`，env `ELEC_*`）：

| 键 | 默认 | 说明 |
|---|---|---|
| `elec.upload.dir` | `${java.io.tmpdir}/elec-upload`；生产 `ELEC_UPLOAD_DIR=/data/cache/elec-upload` | 原件目录 |
| `elec.upload.failed-retention-days` | 7 | 未入库区保留天数 |
| `elec.upload.applied-retention-days` | 0 | 已入库区保留天数；**0 = 不删**（当前取值） |
| `elec.upload.clean-cron` | `0 30 3 * * MON` | 每周一 3:30；`-` 关 |
| `elec.upload.pending-ttl-minutes` | 60 | 预览有效期（取代 `batchTtlHours`） |
| `elec.upload.cache-max-batches` | 10 | 缓存条数上限 |
| `elec.upload.daily-max` | 20 | 每家每天上传次数；0 = 不限 |
| `elec.upload.delist-confirm-bp` | 3000 | 下架确认线（万分比）；0 = 凡有下架都须确认 |
| `elec.ai.enabled` | **false** | 大模型认列。默认关：开是一次配置改动 |
| `elec.ai.base-url` | 空；生产 `ELEC_AI_BASE_URL=http://cdw.near3.ai:8003/v1` | OpenAI 兼容地址 |
| `elec.ai.model` | `qwen3.6` | served name |
| `elec.ai.timeout-seconds` | 8 | 超时；熔断阈值 3 次 / 5 分钟写死为常量 |

`batchTtlHours` 删掉（只有上传用）。配置键一律 kebab-case 进 `ElecProperties`（`@ConfigurationProperties` 认它；`@Value` 不认，别混用）。

### 2.7 问题行导出（`SheetWriter`）

手写最小 xlsx：`[Content_Types].xml`、`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、
`xl/styles.xml`（两个样式：默认、红底）、`xl/worksheets/sheet1.xml`。单元格一律 `t="inlineStr"`。

- 列 = 原表头 + 「原行号」+「问题」；行 = 问题行按原行号升序；出错的格用红底样式。
- **一律文本**：写成数字的话 Excel 一打开 `0805` 就成了 `805`，他改完回传料号已经坏了。
- **不引 POI**（同 `SheetReader`）；**不导 CSV**（小程序 `openDocument` 不认 csv）。
- 数据来源：`PARSED` 批次从缓存 + 原件；`APPLIED` 批次从 `elc_stock_batch_row`（原件可能已清）。

端上下载不走 `uni.downloadFile`（要另配 downloadFile 合法域名）：`uni.request({responseType:'arraybuffer'})`
→ `FileSystemManager.writeFile(USER_DATA_PATH)` → `uni.openDocument({fileType:'xlsx', showMenu:true})`；H5 走 Blob。
封在 `elec-app/src/api/index.ts#downloadProblems`。

**不套信封**：出参 `ResponseEntity<byte[]>`。**第 0 步先确认 elec-svc 的全局信封放行它**；测试断言 `Content-Type` 与魔数 `PK`，不只断言 200。

### 2.8 模块设计

后端（`backend/elec/`，`…` = `src/main/java/ai/neargo/shop/elec`）：

| 动作 | 路径 | 说明 | AC |
|---|---|---|---|
| 新增 | `elec-core/src/main/resources/db/elec/V3__elec_upload_v2.sql` | 2.6 | 全部 |
| 新增 | `elec-core/…/entity/ElcHeaderAlias.java` | | AC1 AC5 AC23 |
| 修改 | `elec-core/…/entity/ElcStockBatch.java`、`ElcStockBatchRow.java` | 加列；状态常量补 4 个 | |
| 修改 | `elec-core/…/mapper/ElecMappers.java` | `HeaderAliasMapper`；`SupplierMapper#lockBySupplierNo`；`StockBatchMapper#countSince`、`#supersedePending` | AC15 AC18 AC20 |
| 修改 | `elec-core/pom.xml` | + `org.ehcache:ehcache`（jakarta classifier，版本随 BOM） | AC13 |
| 修改 | `elec-core/…/config/ElecProperties.java` | 2.6 配置项 | |
| 修改 | `elec-core/…/dto/SupplierDtos.java`、`OpsDtos.java` | 2.6 DTO | |
| 修改 | `elec-core/…/support/Columns.java` | 删 `NAMES` 常量；`guess(rows, aliases)` | AC1 |
| 新增 | `elec-core/…/support/HeaderNames.java` | 表头规范化（从 `Columns#key` 抽出，别名表与学习共用一把尺） | AC1 AC5 |
| 新增 | `elec-core/…/support/UploadFileStore.java` | 2.4：路径、净化、落盘、两区查找、移区、按日期删 | AC19 AC19b |
| 新增 | `elec-core/…/service/impl/BatchFailureRecorder.java` | `REQUIRES_NEW` 写 FAILED 批次 | AC19a |
| 新增 | `elec-core/…/support/SheetWriter.java` | 2.7 | AC8 |
| 新增 | `elec-core/…/gateway/ElecColumnAi.java` | 端口：`Optional<AiGuess> guess(head, fields)` | AC2 |
| 新增 | `elec-core/…/service/impl/HeaderAliases.java` | 别名查找（本家 > 全局，进程内缓存 5 分钟）与学习 | AC1 AC5 |
| 新增 | `elec-core/…/service/impl/ColumnResolver.java` | 四级递进 + 校验 + 熔断 | AC1–AC4 |
| 新增 | `elec-core/…/service/impl/PendingBatchCache.java` | 2.3 | AC13 AC14 |
| 修改 | `elec-core/…/service/ElecStockImportService.java` + `impl/ElecStockImportServiceImpl.java` | 上传（存盘、限次、作废旧的、认列、进缓存）、remap、rows、apply、cancel | AC6 AC7 AC10–AC18 AC20 |
| 新增 | `elec-core/…/service/ElecStockBatchService.java` + `impl/…Impl.java` | 记录列表、详情、导出 | AC8 AC21 AC24 |
| 新增 | `elec-core/…/service/ElecOpsHeaderAliasService.java` + `impl/…Impl.java` | 运营别名 | AC23 |
| 修改 | `elec-core/…/api/b/ElecSupplierController.java` | 2 改 5 新 | |
| 修改 | `elec-core/…/api/ops/ElecOpsSupplierController.java` | 上传记录、下载原件 | AC19d AC22 |
| 修改 | `elec-core/…/api/ops/ElecOpsBaseController.java` | 别名三条 | AC23 |
| 新增 | `elec-svc/…/svc/QwenColumnAi.java` | 实现端口：HTTP/1.1、关 thinking、剥 ``` | AC2 |
| 新增 | `elec-svc/…/svc/ElecUploadCleaner.java` | 每周：补移 → 清 failed → （applied 按配置） | AC19b AC19c |
| 修改 | `elec-svc/src/main/resources/application.yml` | `elec.upload.*`、`elec.ai.*` 带 env 占位 | |
| 修改 | `shop-base/…/common/ErrorCode.java` + `shop-app/…/i18n/messages*.properties` | 3 码三语 | |
| 修改 | `elec-core/…/i18n/elec/messages*.properties` | 问题码人话 | AC8 |
| 重新生成 | `elec-svc/src/test/resources/db/elec-h2/V1__elec_baseline.sql` | `gen-test-schema.py` | |
| 新增 | `elec-core/src/test/…/ColumnResolverTest.java`、`UploadFileStoreTest.java`、`SheetWriterTest.java`、`HeaderAliasSeedTest.java` | 单元 | AC1–AC4 AC8 AC19 |
| 新增 | `elec-svc/src/test/…/ElecUploadFlowTest.java` + `FakeColumnAi.java` | 端到端 | AC5–AC21 AC24 |
| 修改 | `elec-svc/src/test/…/ElecOpsFlowTest.java`、`ElecEndpointAuthTest.java` | 运营两组、新端点匿名探测 | AC22 AC23 AC24 |

前端与生成物：

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `packages/shared/src/types/elec.ts` | `ElecIssue`、`ElecBatchPreview`、`ElecPreviewRow`、`ElecBatchSummary` |
| 修改 | `elec-app/src/api/index.ts` | `uploadStock(+name)`、`batchRows`、`applyBatch(+expectDelist)`、`cancelBatch`、`batches`、`batch`、`downloadProblems` |
| 修改 | `elec-app/src/shared/format.ts` | `ROW_PROBLEM` → `ISSUE`（8 码）；`issueText(issue)` 拼 `D12（数量）读不出：约2千` |
| 修改 | `elec-app/src/pages/stock-upload/index.vue` | 传 `name`；字段旁来源角标（AI 橙色「请核对」）；`NEED_MAPPING` 时顶部说明「没认出料号和数量是哪一列，请选一下」 |
| 修改 | `elec-app/src/pages/stock-preview/index.vue` | 五个页签分页拉 `rows`；更新行显示旧 → 新；问题行显示定位；「放弃」「导出问题行」；剩余时间；过线弹窗；只读态（从记录进来） |
| 新增 | `elec-app/src/pages/stock-batches/index.vue` + `pages.json` | 上传记录 |
| 修改 | `elec-app/src/pages/supplier/index.vue` | 工作台入口「上传记录」 |
| 修改 | `prototypes/elec-rfq.html` + `prototypes/registry.json` | e15 加来源角标与待指定列态；e16 改五页签；新屏「上传记录」挂锚点 |
| 重新生成 | `prototypes/index.html`、`docs/technical/design/ui-catalog.json` | `gen-proto-index.py`、`gen-ui-catalog.py` |
| 重新生成 | `docs/api/openapi*.yaml`、元器件表清单与 ER 图、`docs/technical/README.md` | 在当下 HEAD 的干净副本里跑 |

### 2.9 部署

| 步 | 动作 | 验 |
|---|---|---|
| 1 | 生产 `mkdir -p /data/cache/elec-upload/{failed,applied} && chown -R deploy:deploy /data/cache/elec-upload` | `sudo -u deploy touch` 两区各一个探针 |
| 2 | `ai-shop-elec.service` 的 `ReadWritePaths` 加 `/data/cache/elec-upload`（仓库里 `deploy/tencent/systemd/` 同步改） | `ProtectSystem=full` 本不挡 `/data`，但将来收紧成 `strict` 时不会静默断 |
| 3 | `elec.env` 加 `ELEC_UPLOAD_DIR`、`ELEC_AI_BASE_URL`、`ELEC_AI_ENABLED=true`（只核键名，不打印值） | 启动日志里「上传目录 …可写」「AI 认列 已开」 |
| 4 | V3 先在生产库副本上跑一遍 | `elc_flyway_history` 成功；种子条数 = 常量条数 |
| 5 | 本地按生产 profile 冒烟（不带测试装配） | 起得来、上传一张 `Item/Maker/Stk` 表走到 AI |
| 6 | **后端先发、端上后发**：后端对不带 `name` / `expectDelist` 的老端上兼容；`NEED_MAPPING` 老端上会显示一个空映射页，可以手工选，不会卡死 | 发完 health 200 再走开 |
| 7 | 端上发布（小程序走服务器上传） | 真机：传一张规则认得的表、一张要 AI 的表、一张有错的表，各走一遍 |

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| cdw 8003 公网明文、无鉴权 | 样本（含价格）可被链路上看见；别人也能用这个端点 | 只发 5 行；PRD 待拍板 #4；二期加 key 或隧道 |
| 8003 是 soukmind 的生产端点 | 它忙时我们变慢 | 8 秒超时 + 熔断；失败走手工，不阻断 |
| 大模型认错且校验没拦住（如把「封装」当料号，而封装值恰好像料号） | 整张表按错列上架 | AI 字段在端上强提示核对；预览是解析后的数据，一眼能看出料号列不对；确认前一行不动 |
| `applied/` 不删、只涨 | 盘慢慢满（生产根盘余 38G，与 MySQL、日志同盘） | 每周清理日志里报两区占用；按单个 1MB、每天 50 次估一年约 18G —— 超过 10G 时定 `applied-retention-days` |
| 缓存容量估算偏小 | 挤出频繁，每次访问都重建（解析 2 万行约 1 秒） | 挤出无害；日志记重建次数，频繁就调 `cache-max-batches` |
| 将来多实例 | 本地缓存不共享 | 重建路径保证正确；上传目录要换共享盘（写进部署文档） |
| H2 的 `FOR UPDATE` 与 MySQL 行为不同 | AC18 的测试在 H2 下消融不变红 | 第 0 步先验；不变红就写明，并在生产库副本上手工并发验一次 |
| 老端上遇到 `NEED_MAPPING` | 看到一个没选好列的映射页 | 页面本来就能选列；端上紧跟着发 |

---

## §5 对账三 · 实现 → 需求（测试）

跑法：`mvn -o -pl elec/elec-svc -am test`（elec-core 55 条 + elec-svc 88 条，1 条真连 cdw 默认跳过）；
`cd elec-app && npm test`（21 条）。**每一行都做过消融**：把实现改回去（或注掉那一句），对应测试变红；还原后 touch 文件。

| AC | 测试方法 | 跑过 | 消融（改了什么 → 结果） |
|---|---|---|---|
| AC1 | `ColumnResolverTest#aliasHitSkipsAi` · `HeaderAliasSeedTest#seedIsConsistent / commonHeaders` | ✅ | —（种子测试读的就是 V3 本身，改种子即红） |
| AC2 | `ColumnResolverTest#aiFillsOnlyMissingFields` · `ElecUploadFlowTest#ac5_*`（来源 AI） | ✅ | 去掉「别名认出的字段不许模型改」→ 红。**第一次消融没红**：测试里模型指的第 6 列恰好被「列已占用」那道拦住，改成没被占的第 7 列后才测到这一道 |
| AC3 | `ColumnResolverTest#aiColumnFailingContentCheckIsDropped` · `#numericMfrRejected` | ✅ | 注掉 `verify` → 红 |
| AC4 | `ColumnResolverTest#aiDownGivesNeedMapping` · `ElecUploadFlowTest#ac4_aiDownGivesNeedMapping` | ✅ | — |
| AC5 | `ElecUploadFlowTest#ac5_confirmedMappingLearnedForThisSupplierOnly` | ✅ | 学成全局（supplier_no=''）→ 红在「别家不受影响」 |
| AC6 | `StockSheetParserTest#issuesPinpointCellAndCollectAllPerRow` · `ElecUploadFlowTest#ac6_issuesPinpointCell` | ✅ | 数量判断加 `&& issues.isEmpty()`（遇错即停）→ 红 |
| AC7 | `StockSheetParserTest#warnings` · `ElecUploadFlowTest#ac6_*`（警告行在售） | ✅ | — |
| AC8 | `SheetWriterTest#allCellsTextAndErrorCellsRed / emptyErrorCellStillRed` · `ElecUploadFlowTest#ac8_exportThenMergeFix` | ✅ | 写数字格 → 红在 `0805`；空格不标红 → 红 |
| AC9 | `ElecUploadFlowTest#ac8_exportThenMergeFix`（后半） | ✅ | — |
| AC10 AC11 | `ElecUploadFlowTest#ac10_rowsViewAndNothingInDbBeforeApply` | ✅ | — |
| AC12 | `ElecUploadFlowTest#ac12_cancelEvictsButKeepsFile` | ✅ | — |
| AC13 | `PendingBatchCacheTest#expiresAtDeadlineNotOnAccess` · `ElecUploadFlowTest#ac13_*` | ✅ | 访问续期（getExpiryForAccess 回 60 分钟）→ 红 |
| AC14 | `PendingBatchCacheTest#evictedEntryRebuildsWithSameDeadline` · `ElecUploadFlowTest#ac14_*` | ✅ | — |
| AC15 | `ElecUploadFlowTest#ac15_newUploadSupersedesPending` | ✅ | 去掉 `supersedePending` → 红 |
| AC16 AC17 | `ElecUploadFlowTest#ac16ac17_delistGuardComparesRecomputed` | ✅ | 去掉护栏 → 红；改成与存下的 `to_delist` 比 → 红 |
| AC18 | `ElecUploadFlowTest#ac18_concurrentApplySerialized`（同一张连点两次，5 轮） | ✅ | 去掉 `lockBySupplierNo` → 红（H2 的 FOR UPDATE 在消融时确实变红，§4 那一行的担心不成立） |
| AC19 | `UploadFileStoreTest#pathKeepsNameUnderDateAndSupplier / sanitizesTraversalAndLongNames / moveAndLocate` · `ElecUploadFlowTest#ac19_*` | ✅ | 提交后不移区 → 红 |
| AC19a | `ElecUploadFlowTest#ac19a_parseFailureRecordedWithFile` | ✅ | `REQUIRES_NEW` 改 `REQUIRED` → 红（记录被回滚） |
| AC19b AC19c | `UploadFileStoreTest#purgeOnlyOldFailedDirsByName` · `ElecUploadFlowTest#ac19bc_housekeeping` | ✅ | 按 mtime 删 → 红；注掉补移 → 红 |
| AC19d AC22 | `ElecOpsFlowTest#ac19d_ac22_opsSeesBatchesAndDownloadsOriginal` | ✅ | 不带 `filename*` → 红 |
| AC20 | `ElecUploadFlowTest#ac20_dailyLimitCountsUploadsOnly` | ✅ | 去掉限次 → 红 |
| AC21 | `ElecUploadFlowTest#ac21_historyStatusesAndExportAfterFileCleaned` | ✅ | 导出改读原件 → 红（测试里先删了原件） |
| AC23 | `ElecOpsFlowTest#ac23_promoteLearnedAliasToGlobal` | ✅ | 提升后不刷新全局缓存 → 红 |
| AC24 | `ElecUploadFlowTest#ac24_othersBatchIs404`（详情 / 行 / 导出 / 放弃 / 确认） | ✅ | 取批次不带 supplier_no → 红 |
| 隐私 | `ColumnResolverTest#privateColumnsNeverTakenFromAi` | ✅ | 采纳备注列 → 红 |

**真连 cdw**：`QwenColumnAiTest#live`（`ELEC_AI_LIVE_URL=http://cdw.near3.ai:8003/v1`），本机跑过：877 ms，认出料号、厂牌、数量、批号。

**端到端（浏览器 + 真服务）**：2026-09-30 在本机起了 HEAD 编的 elec-svc（真 MariaDB 跑 V1–V3、真连 cdw），
主系统用一个只实现 4 个内部接口的替身（本机 MariaDB 12 上主系统全量迁移跑不通：`uca1400` 与 `general_ci` 混用，见偏差 14），
H5 用开发模式构建。走过的：大模型认出抬头下一行的表头（1.09 s）、五类预览与问题定位、导出（打开文件核对：只有问题行、错格标红）、
确认、全量替换过线弹窗并带 `expectDelist` 确认、上传记录五种状态、原件两区落盘。验证中发现并修了三处（偏差 11–13）。

## §6 对账二 · 设计 → 实现

| 提交 | 内容 | 文件 |
|---|---|---|
| dd2b61fdb | 后端全部 + 测试 + V3 + H2 基线 + 错误码与三语文案 | 58 |
| 988542046 | elec-app 三页一入口、shared 类型与 http、原型、界面清单 | 19 |
| 03cbf223f | 生成物 11 份（openapi 两份、词表四份、ui-lib、规范两份、后端分层、API详情-C端） | 11 |
| 28d734713 | 枚举登记与取值域列登记（pre-push 枚举守卫） | 2 |
| 85a5b1c1a | 生成物 1 份（中英文对照-实体与字典） | 1 |
| （本次） | 端到端验证中修的三处 + 本篇 + PRD | — |
| 16f386f05 | （元器件后端会话代补）ER 生成器登记 `elc_header_alias` 的用途 | — |

与 §2.8 逐行比：

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了 | `ElecStockBatchService` 只管列表与原件下载；**详情与导出问题行放在 `ElecStockImportService`**（待确认的数据在它手里的缓存里，分开会互相调用） |
| 同上 | `support/IssueText.java`（导出表「问题」列的人话）、`service/impl/StockSheetParser.java`（逐行解析与定位，从上传服务里拆出来，纯函数可单测）、`service/impl/UploadHousekeeper.java`（清理逻辑放 core 可测，elec-svc 的 `ElecUploadCleaner` 只按时叫它）、`config/ElecUploadConfig.java`（启动自检）、`support/HeaderNames.java` |
| 同上 | `packages/shared/src/net/http-client.ts` 加 `del` 与 `downloadBinary`；`elec-app/src/shared/file.ts#openSheet`；`scripts/check-enum-fields.mjs` 与 `enum-registry.ts` 的登记 |
| TDD 列了、实际没动 | `elec-core/…/i18n/elec/messages*.properties` 的「问题码人话」—— 放进了 `IssueText.java`（只有中文，导出给的是供应商、小程序只有中文）；这三份文案包只加了 4 个错误码（ElecMessagesParityTest 要求与主系统逐字一致） |

### 偏差说明

1. **配置键**：`ElecProperties` 的前缀是 `shop.elec`，所以是 `shop.elec.upload.*` / `shop.elec.ai.*`（不是 §2.6 写的 `elec.upload.*`）；
   清理 cron 照 `elec.expiry-remind-cron` 的写法是 `elec.upload-clean-cron`。环境变量名不变（`ELEC_UPLOAD_*` / `ELEC_AI_*`，application.yml 里映射）。
2. **`QTY_ZERO` 是错误不是警告**：`Cells.qty` 本来就把 0 当读不出（0 不算有货，不上架）。PRD 写成「警告、照常上架」与现行规则冲突 ——
   上架一行数量 0 的货，买家搜得到却没有货。保留现行规则，只把提示从「读不出」换成「数量为 0」。PRD §三 已同步。
3. **`MFR_MISSING` 只在厂牌列认出来时逐行报**：厂牌列压根没认出的话，逐行报会让每一行都带警告；列映射页上「厂牌」空着已经说明了。
4. **种子里 `PACKAGING` 只归「包装方式」**：原常量同时列在封装与包装方式下（同一列会被两个字段一起认走）；按行业惯例归包装方式。
5. **下载两个接口直接写 `HttpServletResponse`、返回 void**：`byte[]` 会被全局信封包成 `ApiResult`，走字节转换器时抛类型转换异常。共享的 `ApiResponseWrapper` 管着主系统，不去改它。
6. **移区失败只记 ERROR、不发企业微信**：每周清理第一步的补移兜住它，多一个告警通道要改 `ElecAlerts` 的两个实现与测试替身，这一期不值。
7. **`BatchPreview.problems` 保留为过渡字段**：老版本小程序读它；后端先于端上发时老版本不至于白屏。
8. **大模型样本是「至多 8 行非空」而不是「5 行数据」**：找表头要看抬头，而表头在第几行事先不知道；8 = 抬头至多 2 行 + 表头 + 5 行数据。
9. **一家只留一张待确认时，已过期的不改成作废**：记录里它该显示「已过期」。
10. **`elc_stock` 上没有 `(supplier_no, line_key)` 唯一键** —— AC18 原先设想的是「两张不同的预览并发确认」，一家一张之后那种情况不存在了；
    真实的并发是**同一张被连点两次**，而不加锁时库存行会被静默插两遍、库不报错。测试按这个场景写。
11. **（验证中修）空着的错格也标红**：「没有料号」那一格恰恰是空的，原实现跳过空格就没标上。
12. **（验证中修）备注 / 联系方式类的列，大模型认了也不采纳、也不学成别名**（`HeaderNames.isPrivate`）：真连 qwen 时它把「备注」认成了货况并被学进了别名，
    而原型 e15 要求备注默认不导入（常写公司名和微信）。供应商手工选仍可以，但不学。提示词里也写明了。
13. **（验证中修）已放弃 / 作废 / 过期的批次在确认页不再给「有问题」页签与导出**：它们的问题行没有入库，页签有数、列表却是空的，导出点了报错。改为一句「这次没有上架，逐行的数据没有保留」；「数量为 0」的人话不再重复原值。
14. **验证环境**：本机数据库是 **MariaDB 12.2**，生产是 MySQL 9.7 —— V3 在本机跑通不等于在生产跑通，上线前仍要在生产库副本上跑一遍（§2.9 第 4 步）。
    本机主系统全量迁移在 MariaDB 12 上因 `uca1400` / `general_ci` 混用起不来，与本篇无关，端到端验证因此用了主系统替身。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-30 | v1 草稿（分级报错、导出、护栏、记录、限次） |
| 2026-09-30 | v2 草稿：加入别名表 + qwen 认列、定位到格、Ehcache 暂存、原件存盘；上传不再写原样行 |
| 2026-09-30 | v3：原件保留原名、`日期/供应商号/` 目录、库里对应；分 `failed/` 与 `applied/` 两区，未入库每周清、已入库暂不删；放弃不删原件；解析失败也建批次；运营下载原件 |
| 2026-09-30 | PRD §四 七条按建议定下；**已实现**（后端 dd2b61fdb、前端 988542046 及其后几条）。闸门：elec-core / elec-svc 全量、elec-app vitest、三端 vue-tsc、Controller 内聚、`check-generated-docs`（26 个生成器）、packages/shared 守卫（在 HEAD 副本里比对 known-guard-failures，无新增）。**未上线**：要按 §2.9 部署，由用户决定何时发 |
