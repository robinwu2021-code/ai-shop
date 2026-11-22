# 交接 · 商户 App 发票打印（模拟打印 + PDF 展示）· 2026-11-22

给 `ai-shop-bapp`（Flutter 商户 App）。目标：在 App 上为销售发票增加「打印」——**先用 mock 方案**模拟打印，生成并展示 PDF 格式的发票打印件；接口按 ai-shop 后端真实合同整理，mock 与真实接口同形，切真接口只换数据源。

- 后端依据：ai-shop `codex/food-next01` 分支，[TDD-发票模块 §9 / §21 / §22](../../technical/TDD-发票模块.md)；B 端 H5 已有同功能页 `b-app/src/pages/sales-invoice-print-sim/index.vue`，交互可直接参照。
- 样例 PDF：本目录 `pdf/`，由后端**真实渲染器**生成（`ReceiptSoftwareAcceptanceTest`），版面、条码、QR 与线上一致，页眉印「模拟打印，非真实出票」，页脚印「PDF副本」。mock 直接用它们。

## 1. 三种打印件

| 场景码 `scene` | 名称 | 何时有 | 纸宽 `variantCode` | 样例 PDF |
|---|---|---|---|---|
| `INVOICE_PROOF` | 电子发票证明联 | 已开立、`printMark=true`（一般消费者、未存载具、未捐赠） | 仅 `STANDARD_58`（法定 5.7 cm） | `pdf/INVOICE_PROOF-58.pdf` |
| `INVOICE_DETAIL` | 交易明细 | 已开立即有（存载具 / 捐赠时它是顾客唯一的纸） | `STANDARD_58` / `STANDARD_80` | `pdf/INVOICE_DETAIL-58.pdf` · `-80.pdf` |
| `INVOICE_ALLOWANCE` | 折让证明单 | 每张未作废的折让 | `STANDARD_58` / `STANDARD_80` | `pdf/INVOICE_ALLOWANCE-58.pdf` · `-80.pdf` |

作废的发票**没有**证明联与明细可打印。PDF 版式 `pdfProfile`：`ORIGINAL_ROLL`（原卷宽）/ `A4_SHEET`（A4 纸上居中）；样例只有原卷宽，mock 选 A4 时可沿用原卷宽样例。

## 2. 流程（真实接口）

```
发票详情 ─GET /biz/invoices/{invoiceNo}/print-facts─▶ 本张票可打的打印件（含 factRef）
        ─POST /biz/print/simulations（pdfEnabled=true）─▶ operation.result = {simulationNo, exportNos[0]}
        ─GET /biz/print/exports/{exportNo}（轮询，约 500ms × 20）─▶ status=READY
        ─POST /biz/print/exports/{exportNo}/download-grants─▶ {url}（60 秒有效）
        ─GET {API_BASE}{url}─▶ application/pdf 字节 ─▶ App 内 PDF 查看器展示
```

「模拟」= 不出纸、不建正式打印任务；不选具体发票时 `source.type=SAMPLE` 用场景样例数据。

### 通用约定

- 统一响应包：`{"code": 0, "msg": "success", "data": …}`；`code≠0` 为失败，`msg` 是可直接展示的文案。打印中心失败另带 `data.key`（如 `print.forbidden`、`print.exportNotReady`），按 key 出本地化文案。
- 请求头：`Authorization: Bearer <token>`、`X-Store-No: <当前门店>`（B 端所有接口）；`POST /biz/print/simulations` **必须**带 `Idempotency-Key: <uuid>`（同一意图重试用同一个 key）。
- 金额字段 `*Minor` 是最小货币单位（TWD 按分，展示 ÷100）；时间是 ISO 8601 字符串。
- 权限：页面要 `biz:print:simulate` 与 `biz:print:export`；用真实发票还要 `biz:invoice:read`。

### 2.1 `GET /biz/invoices/{invoiceNo}/print-facts`（`biz:invoice:read`）

```json
{"code":0,"msg":"success","data":[
  {"scene":"INVOICE_PROOF","docNo":"IVC26112243X8H9BS1C",
   "factRef":{"tenantNo":"MAIN","entityNo":"M0001","storeNo":"ST-M0001","sourceType":"INVOICE",
              "factNo":"IVC26112243X8H9BS1C","bizRevision":"1","scene":"INVOICE_PROOF","snapshotHash":"9f2c…"}},
  {"scene":"INVOICE_DETAIL","docNo":"IVC26112243X8H9BS1C","factRef":{…,"scene":"INVOICE_DETAIL"}},
  {"scene":"INVOICE_ALLOWANCE","docNo":"ALW261122…","factRef":{…,"sourceType":"INVOICE_ALLOWANCE"}}
]}
```

`factRef` 原样回传给模拟接口，App 不解析。别店的发票回 `code=26005`（发票不存在）。

### 2.2 `POST /biz/print/simulations`（`biz:print:simulate`，头 `Idempotency-Key`）

请求：

```json
{"storeNo":"ST-M0001","scene":"INVOICE_PROOF","variantCode":"STANDARD_58","fixtureCode":"SUCCESS",
 "source":{"type":"FACT","no":null,"factRef":{…2.1 的 factRef…},"expectedRevision":null},
 "templateSelection":null,
 "outputOptions":{"pdfEnabled":true,"pdfProfile":"ORIGINAL_ROLL"}}
```

用样例数据时 `source = {"type":"SAMPLE","no":null,"factRef":null,"expectedRevision":null}`。`storeNo` 必须等于 `X-Store-No`。

响应（`status=SUCCEEDED` 时 `result.canonicalJson` 是一段 JSON 字符串，需再解析一次）：

```json
{"code":0,"msg":"success","data":{
  "operationNo":"PO…","type":"CREATE_SIMULATION","status":"SUCCEEDED","targetType":"SIMULATION","targetNo":"SM…",
  "result":{"canonicalJson":"{\"exportNos\":[\"EX…\"],\"simulationNo\":\"SM…\"}","sha256":"…"},"error":null}}
```

`status=FAILED` 时看 `error.key`。

### 2.3 `GET /biz/print/exports/{exportNo}`（`biz:print:export`）

```json
{"code":0,"msg":"success","data":{"exportNo":"EX…","sourceType":"SIMULATION","sourceNo":"SM…",
  "status":"READY","fileSize":13071,"pageCount":1,"expiresAt":"2026-11-29T11:00:00Z","revision":1}}
```

`status`：`PENDING` → 继续轮询；`READY` → 下一步；`FAILED` → 提示生成失败。实测提交后通常 1–2 秒内就绪。

### 2.4 `POST /biz/print/exports/{exportNo}/download-grants`（`biz:print:export`）

```json
{"code":0,"msg":"success","data":{
  "url":"/private/print-artifacts?key=MAIN%2FM0001%2FST-M0001%2Fprint%2F202611%2FEX….pdf&expires=1795330731&signature=…",
  "expiresAt":"2026-11-22T11:01:00Z","fileName":"EX….pdf","contentType":"application/pdf"}}
```

`url` 以 `/` 开头时拼 `API_BASE`。**60 秒有效**，每次打开 PDF 都重新申请。该链接返回 `Content-Disposition: attachment` 与 `X-Frame-Options: DENY`：App 用原生 HTTP 取字节再交给 PDF 查看器即可（不要塞进 WebView 内嵌，Android WebView 也不渲染 PDF）。

## 3. mock 方案

与真实接口同形，切换只换数据源（照项目现有 `lib/dev/mock/` 的做法）：

| 接口 | mock 行为 |
|---|---|
| `print-facts` | 证明联 + 交易明细（`printMark=false` 的票只给明细；作废的票给空数组）；有折让时每张加一条 `INVOICE_ALLOWANCE`；`factRef` 随便造，原样回传即可 |
| `simulations` | 直接回 `SUCCEEDED`，`exportNos` 造一个 `EX-MOCK-<scene>-<width>` |
| `exports/{no}` | 首次 `PENDING`、第二次 `READY`（顺带验证轮询）；`pageCount=1` |
| `download-grants` | `url` 指向打包进 App 的资源：`<scene>-<width>.pdf`（`A4_SHEET` 也用原卷宽样例） |

mock 下 PDF 从 App 资源读取字节，展示路径与真实下载后完全一致。

## 4. 页面建议（参照 H5 同功能页）

- 入口：销售发票详情「打印 / 预览 PDF」（用本张票的 `print-facts`）；发票列表「发票打印模拟」（用样例）。
- 选项：打印件（按 `print-facts` 返回列出）、纸宽（证明联只给 58）、PDF 版式（原卷宽 / A4）；切换任一选项清掉上一份 PDF。
- 「生成 PDF」→ 依次调 2.2 → 2.3 轮询 → 2.4 → 取字节 → 页内展示；「重新打开」重新申请授权。
- 文案明确「模拟打印：不出纸，也不建正式打印任务」。

## 5. 已验证（后端）

| 项 | 结果 |
|---|---|
| 三种打印件样例模拟 → PDF READY、不建正式任务 | `PrintDatabaseAcceptanceTest#invoiceDocumentsSimulateToReadyPdfWithoutProductionJobs` ✅ |
| `print-facts`：应印才有证明联、作废为空、别店 26005 | `InvoiceServiceMysqlTest#ac43…` ✅ |
| H5 实测：证明联 58 原卷宽、明细 80 A4 生成并内嵌显示 | 2026-11-22 浏览器实测 ✅ |
| 鲸跃生产：开立 S0、作废 C0、查单一致（与打印无关，说明开票链路可用） | TDD-发票模块 §22.3 ✅ |
| 按最新代码（供应商层改造后，HEAD 02cac2dc8）复测：后端 191 条测试、样例 PDF 重生成**逐字节一致**、H5 三种打印件与出错态 | TDD-发票模块 §21.7 ✅ |

接口与 mock 方案在复测后**没有变化**，本文与 `pdf/` 不需要更新。H5 页把端差异移到了 `b-app/src/ports/pdf-view.ts`，App 端「取字节 → 系统查看器」的做法可直接参照。
