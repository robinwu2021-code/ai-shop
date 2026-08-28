# TDD 打印域 · 方案与规格（合册）

状态：**草稿 · 待确认** · 创建 2026-08-28 · 第一梯队 #4
定位：基座能力 I1–I4 的成册。**打印是设备通道不是餐饮功能**：零售出小票、快递打面单、
美业出服务单 —— 本质是「把一条结构化消息送到一台设备」，与 notify 送微信同构，故居 `shop-channel/print`。
上游：核心能力清单 I1–I4 · 行业包功能清单（餐饮 5 + 美业 5 个打印场景）· SPI `PrintPayloadProvider`/`CorePrintApi`

---

# 一 · 决策记录

| # | 决策 | 一行理由 |
|---|---|---|
| 1 | 通道/设备/模板/路由/任务/重试全在基座；**内容在行业**（`PrintPayloadProvider`） | 行业自带打印=第二个行业再抄一遍设备管理与补打 |
| 2 | 复用 outbox 语义：**打印不可丢**，at-least-once + 幂等键 | 后厨票重复是最贵的重复（真会多做一份菜）→ 幂等在提交侧 |
| 3 | 模板**存库不写代码**；平台默认模板 + 门店覆盖 | 零硬编码；改模板不发版 |
| 4 | 行业差异全落 `prn_route` **数据行**；`match_expr` 是**受限表达式不是脚本**（§三） | 代码零行业分支；表达式可脚本化=注入面+不可审计 |
| 5 | vendor 适配器 SPI（飞鹅/易联云/365…），**只装适配不装业务判断** | channel 域既有规矩 |
| 6 | 触发一律**订阅事件**，交易/台账域不直接调打印；提交在**事务外** | 打印失败不回滚订单（既有裁决） |
| 7 | 重试有上限，超限置 FAILED **可见可补打**；补打是新任务引用原任务 | 恒红即噪声；静默丢最不可接受 |
| 8 | **真机验收不可替代**：分单命中/中文/切纸/断网重试，假打印机的绿测不算数 | 既有承诺，写进验收清单 |
| 9 | 路由零命中 → 按场景配置「告警 or 落默认机」，**绝不静默丢** | 一张没打出来的后厨票=一桌菜没人做 |
| 10 | 模板占位符为 `{{path}}` 取值 + 少量格式化管道；ESC/POS 指令在 vendor 层 | 模板作者是运营不是工程师 |

# 二 · 表（终版 DDL，前缀 `prn_`）

```sql
CREATE TABLE prn_printer (
  printer_no VARCHAR(32) NOT NULL,
  store_no   VARCHAR(32) NOT NULL,
  name       VARCHAR(64) NOT NULL,             -- 「后厨-热菜」「前台小票机」
  vendor     VARCHAR(24) NOT NULL,             -- FEIE / YLY / P365（适配器注册表校验）
  device_sn  VARCHAR(64) NOT NULL,
  secret     VARCHAR(128) NOT NULL,            -- 加密存储（复用媒体域凭证手法）
  paper      VARCHAR(8) NOT NULL DEFAULT '58', -- 58/80mm
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  last_seen_at DATETIME NULL,                  -- vendor 心跳回写，离线告警据此
  UNIQUE KEY uk_printer (printer_no), UNIQUE KEY uk_printer_sn (vendor, device_sn),
  KEY idx_printer_store (store_no, status)
);
CREATE TABLE prn_template (
  template_no VARCHAR(32) NOT NULL,
  scene    VARCHAR(24) NOT NULL,               -- 取值域 PrintScenes（§四）
  store_no VARCHAR(32) NULL,                   -- NULL=平台默认；门店行覆盖之
  name     VARCHAR(64) NOT NULL,
  content  TEXT NOT NULL,                      -- {{}} 模板文本（§五）
  UNIQUE KEY uk_template (template_no), UNIQUE KEY uk_tpl_scene (scene, store_no)
);
CREATE TABLE prn_route (
  route_no   VARCHAR(32) NOT NULL,
  store_no   VARCHAR(32) NOT NULL,
  scene      VARCHAR(24) NOT NULL,
  match_expr VARCHAR(255) NULL,                -- 受限表达式（§三）；NULL=场景兜底路由
  printer_no VARCHAR(32) NOT NULL,
  template_no VARCHAR(32) NULL,                -- NULL=按 scene 取默认模板
  copies     INT NOT NULL DEFAULT 1,
  priority   INT NOT NULL DEFAULT 0,           -- 具体在前、兜底在后（与 perm-map 同规矩）
  enabled    TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_route (route_no), KEY idx_route_lookup (store_no, scene, enabled, priority)
);
CREATE TABLE prn_job (
  job_no     VARCHAR(32) NOT NULL,
  store_no   VARCHAR(32) NOT NULL,
  scene      VARCHAR(24) NOT NULL,
  printer_no VARCHAR(32) NOT NULL,
  template_no VARCHAR(32) NOT NULL,
  payload    JSON NOT NULL,                    -- 渲染数据快照（行业 render 的产物）
  biz_ref    VARCHAR(48) NULL,                 -- CHECK/chk-A3 · ORDER/O-1（补打与追溯）
  status     VARCHAR(16) NOT NULL,             -- PENDING→SENT→DONE | FAILED（超限，可补打）
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(255) NULL,
  reprint_of VARCHAR(32) NULL,                 -- 补打指向原任务
  idem_key   VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_job (job_no), UNIQUE KEY uk_job_idem (idem_key),
  KEY idx_job_retry (status, retry_count), KEY idx_job_biz (biz_ref)
);
```

# 三 · `match_expr` 受限表达式（定稿）

语法：`字段 op 值 { AND 字段 op 值 }`；op ∈ `= != in`；值为字面量或集合。
**字段白名单**（超出即建路由时报错）：`stationNo · channel · lineKind · fulfillment · sceneArg.*`。
示例：`stationNo = ST-hot`（热菜分单）· `channel in (TAKEOUT, DELIVERY)`（外带小票）。
实现为解析后的结构化条件（存原文+编译缓存），**不 eval、不接任何脚本引擎**。

# 四 · 场景取值域 `PrintScenes`（含已登记的行业场景）

```
基座：ORDER_PAID(零售小票) · PICKING(拣货分单) · CASHIER_RECEIPT
餐饮：KITCHEN · KITCHEN_VOID(退菜撤单条) · URGE(催菜) · PRE_BILL(预结,须与小票视觉可区分) · CHECKOUT
美业：APPT_CONFIRM · SERVICE_START(含过敏提醒) · CARD_BALANCE(余次条,数字必取基座) · HANDOVER(交班)
```
行业新增场景 = 常量 + 默认模板迁移行，**不改基座代码**。

# 五 · 模板语法（给运营写的）

`{{order.lines[].title}}` 路径取值 · 管道 `{{amount|fen2yuan}}` `{{at|time}}` ·
区块 `{{#lines}}…{{/lines}}` · 对齐/加粗用轻标记（`|c|` 居中、`**`）由 vendor 层译成 ESC/POS。
**模板渲染失败 → 落默认模板并告警**，不失败任务（决策 9 的模板版）。

# 六 · 状态机与链路

```
submit(scene, ctx) [幂等键]
  → 行业 PrintPayloadProvider.render(ctx) → List<PrintDoc>
  → 路由匹配（priority 序，首中即用；零命中→按场景配置 告警/默认机）
  → prn_job(PENDING) ×copies —— 事务外，失败不回滚业务
  → 投递 Job：PENDING→SENT→DONE ｜ 失败退避重试 ×N → FAILED（B 端可见，可补打）
```

# 七 · Service 与 API

```java
PrinterService  upsert/list/testPrint(printerNo)        // 测试页：接线验收第一步
TemplateService upsert/preview(templateNo, samplePayload)
RouteService    upsert/list/dryRun(scene, ctx) → 命中报告   // 上线前演练路由
PrintJobService submit(scene, ctx, idemKey) / reprint(jobNo) / page(query)
```
```
GET/POST /biz/printers · POST /biz/printers/{no}:test
GET/PUT  /biz/print-templates · POST /biz/print-templates/{no}:preview
GET/PUT  /biz/print-routes · POST /biz/print-routes:dry-run
GET      /biz/print-jobs?status=FAILED · POST /biz/print-jobs/{no}:reprint
```

# 八 · 不变量与测试

P1 提交幂等（同 idem_key 只产生一批任务）｜ P2 重试上限→FAILED 可见可补 ｜
P3 路由零命中绝不静默丢 ｜ P4 模板缺失/渲染失败→默认模板+告警 ｜ P5 补打引用原任务、原文快照重打。
自动化只能测到「任务生成+路由命中+重试」；**真机验收清单**：中文与宽字符 · 58/80 切纸 ·
分单命中（热菜/凉菜/水吧各一）· 断网 5 分钟恢复后不丢不重 · 同单重提交只出一套票。

# 九 · 施工
四表迁移 → vendor 适配器（先接一家真机）→ 路由/模板/任务服务 → 事件订阅接线（OrderPaid + 行业台账事件）
→ B 端四组端点+权限登记 → 默认模板种子（各场景平台行）→ 真机验收。
