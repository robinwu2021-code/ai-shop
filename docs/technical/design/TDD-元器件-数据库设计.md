# TDD-元器件 · 数据库设计

> 2026-09-29 · 状态：**设计 · 待评审**（尚无迁移、尚无代码）
> 档位：2（新域 · 新表族 · 独立库）。本文只回答**库怎么建**；模块装配与端点另立 TDD，
> 独立化路线另立 ADR。
> 原型：[prototypes/elec-rfq.html](../../../prototypes/elec-rfq.html)（发布版 [元器件询价](https://claude.ai/artifact/CRXD4btowJTHVCkyi3WykG)）（小程序 · 买家 + 供应商）
>
> 已拍板（2026-09-29 用户）：**独立的库 · 独立的供应商主体 · 自建料号库 · 前端先做小程序**。
> 参照实现：`shop-inventory`（独立数据源 + 独立 Flyway 历史，已在生产）·
> `job`（第一个独立进程的模块）· [ADR-021](../ADR/ADR-021-支付域独立为服务与独立库.md)（分阶段独立）。

---

## L1 定位

电子元器件是**一个将来要整个搬走的业务**：供应商上传库存 → 买家按料号查库存与参考价 →
在线询价 → 平台报价 → 线下成交跟进。

所以它的库从第一天起就是**另一个库** `ai_shop_elec`：与 `ai_shop` 零张共享表、零外键、零跨库 join。
搬走那天，库原样带走，不需要从主库里「拆」任何东西 —— 这是 ADR-021 里最难的一步（切库），
新模块直接跳过。

整套设计围着一条约束转：**短期内买家看不到供应商，将来可以**。
这条约束**落在表结构上，而不是落在前端藏字段上**（见 L2 的「两个面」）。

---

## L2 结构

### 2.1 五组表、24 张

| 组 | 表 | 一句话 |
|---|---|---|
| **A 料号库** | `elc_manufacturer` · `elc_mfr_alias` · `elc_category` · `elc_part` · `elc_part_alias` | 料号主数据。**平台的资产**，不属于任何供应商 |
| **B 供应商** | `elc_supplier` · `elc_supplier_member` · `elc_supplier_score` | 独立主体，不复用 `mch_entity` |
| **C 库存** | `elc_stock_batch` · `elc_stock_batch_row` · `elc_stock` · **`elc_part_market`** | 上传 → 预演 → 应用；最后一张是买家唯一能读的库存面 |
| **D 询价** | `elc_rfq` · `elc_rfq_line` · `elc_rfq_dispatch` · `elc_quote` · `elc_quote_revision` · **`elc_offer`** · `elc_markup_rule` · `elc_deal` | 买家单 → 派给谁 → 供应商报 → 平台换算 → 买家看 → 跟进成交 |
| **E 运营面** | `elc_demand_daily` · `elc_setting` · `elc_outbox` · `elc_audit_log` | 需求热度（喂给供应商看板）· 配置 · 通知可靠投递 · 审计 |

### 2.2 两个面：买家面与供应商面

```
          买家面（/mp/elec/**）                  供应商面（/biz/elec/**）            平台面（/ops/elec/**）
   ┌──────────────────────────────┐      ┌───────────────────────────────┐      ┌────────────┐
   │ elc_part  elc_part_market    │      │ elc_supplier*  elc_stock*     │      │   全部     │
   │ elc_rfq   elc_rfq_line       │      │ elc_rfq_dispatch → 读 rfq_line │      │            │
   │ elc_offer（不含 quote_no）    │      │ elc_quote  elc_quote_revision │      │            │
   └──────────────────────────────┘      └───────────────────────────────┘      └────────────┘
         ✗ 不读 elc_stock / elc_quote / elc_supplier*           ✗ 不读 elc_rfq 的买家列 / elc_offer / elc_deal
```

**买家面只读两张「投影表」**：库存看 `elc_part_market`（按料号聚合、数量分档、无供应商维度），
报价看 `elc_offer`（平台换算后的价、不透明编号）。两张表**物理上没有供应商列**。

这比「VO 里不放 supplier 字段」强一档：VO 守住的是序列化，投影表守住的是**查询** ——
买家侧的 Mapper 连 `elc_stock` 都不 import，写错一个 join 也拿不到供应商。
由 ArchUnit 钉死：`..elec.mp..` 不得依赖 `ElcStock*` / `ElcQuote*` / `ElcSupplier*`。

**双向匿名**：供应商面只通过 `elc_rfq_dispatch` 看到被派到的那一行（料号、数量、批号要求、截止时间），
看不到 `elc_rfq` 的买家、公司、联系方式、收货地区的细节 —— 否则当天就被私下成交绕开。

### 2.3 一条询价的数据流

```
买家提交 elc_rfq(+lines)
  └─ 派单 → elc_rfq_dispatch（每行 × 若干供应商；AUTO_MATCH 按 elc_stock 命中 / OPS 手工）
        └─ 供应商报价 → elc_quote（改价写 elc_quote_revision）
              └─ 平台换算 → elc_offer（按 elc_markup_rule 加价、按曝光档位定标签）
                    └─ 买家接受 → elc_deal（锁价快照 · 运营线下跟进）
```

### 2.4 曝光档位：将来公开只改配置

| 档 | 买家看到 | 生效方式 |
|---|---|---|
| **L0 聚合**（一期） | 「平台报价 A / B / C」，库存按料号聚合 | — |
| L1 匿名代号 | `S-3F7K · 深圳 · 认证★★★ · 响应率 92%` | `elc_offer.label` 取 `elc_supplier.mask_code` |
| L2 公开 | 公司简称、资质 | `elc_offer.label` 取 `short_name`；`elc_rfq_dispatch.via` 可为 `BUYER_PICK` |

实际档位 = `min(elc_setting['disclosure.cap'], elc_supplier.disclosure_consent)`，**在生成 offer 那一刻快照**
进 `elc_offer.disclosure`。之后平台调档不追溯已发出的报价 —— 否则一次调档就把历史报价「解密」了。

---

## L3 详情

### 3.0 全库通则（逐表不再重复）

沿用 `shop-inventory` V1 头部的三条，外加本域四条：

| # | 规则 | 为什么 |
|---|---|---|
| ① | **无 tenant_no** | 独立域；隔离维度就是 `supplier_no` / `buyer_ref` |
| ② | **无 deleted 软删**：主数据 `status=ARCHIVED/MERGED`，单据 `status=CANCELLED/VOIDED`，流水不能删 | 「消失」有三种语义，压成一个布尔后没人答得清 |
| ③ | **无外键**，业务键 `VARCHAR(32)` 关联 | 与 inventory 一致；完整性在聚合根 + 唯一键 |
| ④ | **单价用 `*_e6 BIGINT`（百万分之一元）**，不用 `_minor`（分） | 0402 电阻单价 ¥0.0015，分做不出来；`DECIMAL` 在 Java/H2/JSON 三处各有舍入口径 |
| ⑤ | **含税口径是列，不是约定**：每个价都配 `tax_included TINYINT` | 元器件报价含 13% 税与不含税并存，差一成三，比加价率还大 |
| ⑥ | **跨库引用只存外部键**：`account_ref`（主系统 `usr_no`）、`external_ref`（可选 `mch_entity_no`）、`file_ref`（COS key） | 独立那天换成自己的账号，改的是值不是结构 |
| ⑦ | 建表收尾 `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='…';` **单行**、**不写 COLLATE**，跟随库默认 | 生产主库已是 MySQL 9.7，`uca1400` 不可用；见 `check-sql-portability.mjs`。收尾断行会让生成器把列算到下一张表头上 |

审计列：`created_at / created_by` 每张都有；只追加的表（`elc_quote_revision`、`elc_audit_log`、`elc_demand_daily`、
`elc_stock_batch_row`）**没有 `updated_*`** —— 没有这两列就没有「改一行」这个动作。

**料号规范化 `mpn_norm`**（所有匹配与检索都走它，库里只存一份规则的结果）：
大写 → 去空白 → 去 `-` `_` → 保留 `/ . # + ,`（`1.5KE6.8CA`、`74HC595D/T3` 里它们有意义）。
规则变更要**全量重算**，所以规则版本号记在 `elc_setting['mpn.norm.version']`。

---

### A. 料号库

#### `elc_manufacturer` 厂牌

| 列 | 类型 | 说明 |
|---|---|---|
| mfr_code | VARCHAR(32) UK | 本域生成，如 `TI` `ST` `YAGEO` |
| name_en / name_cn | VARCHAR(128) | `Texas Instruments` / `德州仪器` |
| country | CHAR(2) | 原厂所在地 |
| website | VARCHAR(255) | |
| status | VARCHAR(16) | `ACTIVE` / `MERGED` |
| merged_into | VARCHAR(32) | 合并到哪个 mfr_code（被收购：Linear → ADI） |

#### `elc_mfr_alias` 厂牌别名 —— 搜索命中率靠它

| 列 | 类型 | 说明 |
|---|---|---|
| alias_norm | VARCHAR(128) **UK** | 规范化后的别名：`TEXASINSTRUMENTS` `TI` `德州仪器` `德州` |
| mfr_code | VARCHAR(32) | |
| source | VARCHAR(16) | `SEED` 初始 / `UPLOAD` 上传时学到的 / `OPS` 运营加 |

UK 在 alias 上而不是 (alias, mfr)：一个写法只能指一家。有歧义的写法（如 `ON`）**不进别名表**，匹配时落到待确认。

#### `elc_category` 类目（三级）

`cat_code` UK · `parent_code` · `name` · `level` 1–3 · `sort`。一级约十个：被动、分立、IC、连接器、光电、传感、电源模块、机电、开发板、其它。
**不复用平台 `prd_category`**：两个库、两套语义，独立时对方库里不会有它。

#### `elc_part` 料号 —— 本库最大的表

| 列 | 类型 | 说明 |
|---|---|---|
| part_no | VARCHAR(32) UK | 本域业务键 |
| mpn | VARCHAR(64) | 原样展示：`STM32F103C8T6` |
| mpn_norm | VARCHAR(64) | 见通则；**前缀检索索引** |
| mfr_code | VARCHAR(32) | 厂牌不明时为 `UNKNOWN`（仍可被搜到，只是不参与跨厂牌去重） |
| cat_code | VARCHAR(32) | |
| package | VARCHAR(32) | `LQFP-48` `0402` `SOT-23` |
| description | VARCHAR(255) | |
| datasheet_ref | VARCHAR(255) | COS key 或外链 |
| lifecycle | VARCHAR(16) | `ACTIVE` / `NRND` / `EOL` / `UNKNOWN` |
| rohs | TINYINT | NULL = 未知 |
| params | JSON | 参数键值；一期不建参数表，不做参数筛选 |
| source | VARCHAR(16) | `UPLOAD` 由库存上传长出 / `OPS` / `IMPORT` 外部数据源 |
| status | VARCHAR(16) | `ACTIVE` / `PENDING`（新长出、待运营过目）/ `MERGED` |
| merged_into | VARCHAR(32) | |

- `UNIQUE (mfr_code, mpn_norm)`：同一个料号串可以属于两家厂牌（真实存在），所以不能只在 mpn_norm 上唯一。
- `KEY (mpn_norm)`：`WHERE mpn_norm LIKE 'STM32F103%'` 走 B-tree 前缀。**一期不做包含式模糊**（`%C8T6%`），
  千万级之前 MySQL 前缀足够；要做包含式再上 ngram/ES，不在一期。
- **冷启动**：一期料号库由上传长出（`source=UPLOAD, status=PENDING`），PENDING 的料号**买家照样搜得到** ——
  等运营审完才上架会让供应商上传后「搜不到自己的货」，第一次体验就坏了。运营审的是合并与厂牌，不是可见性。

#### `elc_part_alias` 料号变体

`alias_norm` · `part_no` · `kind`（`PACKING` 卷带/托盘后缀如 `TR` `-REEL` / `OLD` 旧型号 / `CROSS` 国产替代，**替代一期只存不展示**）。
`UNIQUE (alias_norm, part_no)`。

---

### B. 供应商

#### `elc_supplier` 供应商主体

| 列 | 类型 | 说明 |
|---|---|---|
| supplier_no | VARCHAR(32) UK | |
| company_name | VARCHAR(128) | 营业执照全称 · **仅供应商面与平台面** |
| short_name | VARCHAR(32) | L2 才对买家出现 |
| uscc | VARCHAR(18) UK | 统一社会信用代码；一照一个供应商 |
| license_ref | VARCHAR(255) | 执照图 COS key |
| kind | VARCHAR(16) | `AGENT` 代理 / `TRADER` 贸易 / `FACTORY` 工厂余料 / `OTHER` |
| region_code | VARCHAR(12) | 所在区县；买家侧只到城市（L1） |
| contact_name | VARCHAR(32) | |
| contact_phone_enc | VARCHAR(255) | 密文 |
| contact_phone_hash | CHAR(64) | 查重用 |
| **mask_code** | VARCHAR(8) UK | 匿名代号 `S-3F7K`，入驻即生成、**永不复用**（复用 = 新供应商继承老供应商的信用） |
| **disclosure_consent** | VARCHAR(4) | 供应商愿意的最高档 `L0` / `L1` / `L2`，默认 `L0` |
| cert_level | TINYINT | 0 未认证 / 1 执照 / 2 实地 / 3 代理证 |
| external_ref | VARCHAR(64) | 可选：同一主体也是平台商家时 = `mch_entity.entity_no`。**只记录，不联动** |
| status | VARCHAR(16) | `PENDING` / `ACTIVE` / `SUSPENDED` / `REJECTED` |
| reject_reason | VARCHAR(255) | |
| approved_at / approved_by | | |

#### `elc_supplier_member` 谁能代表这家供应商

| 列 | 说明 |
|---|---|
| supplier_no | |
| account_ref | 主系统 `usr_no`（小程序登录的那个人）；独立后换成本域账号 |
| role | `OWNER` / `STAFF` |
| status | `ACTIVE` / `REMOVED` |

`UNIQUE (account_ref)`：**一个人一期只代表一家**。放开多家会让「切换供应商」进入每一屏，而一期没有这个需求。

#### `elc_supplier_score` 信用（按日重算，供派单排序与 L1 展示）

`supplier_no` PK · `dispatch_30d` · `quote_30d` · `quote_rate_bp`（响应率，万分比）· `win_30d` · `avg_response_min` ·
`breach_cnt`（接受后不履约，累计不衰减，沿用 [ADR-003](../ADR/ADR-003-报价不审核而用锁价公示信用防加价.md) 第三层）·
`stock_fresh_days`（库存平均多少天没确认过）· `calc_at`。

---

### C. 库存

#### `elc_stock_batch` 一次上传

| 列 | 说明 |
|---|---|
| batch_no | UK |
| supplier_no | |
| source | `EXCEL` / `PASTE` 粘贴 / `MANUAL` 单条 / `API`（二期） |
| file_ref / file_name | 小程序从聊天记录选的 Excel（`wx.chooseMessageFile`） |
| **mode** | `MERGE` 增量（只改出现的行）/ `REPLACE` 全量（本次没出现的行下架） |
| column_map | JSON：`{"A":"mpn","B":"mfr","C":"qty","D":"dc",…}`；**按供应商记住**，下次默认沿用 |
| row_total / matched / pending / invalid | 预演统计 |
| to_insert / to_update / to_delist / unchanged | 预演结果：应用了会发生什么 |
| status | `PARSED` 已预演 / `APPLIED` / `DISCARDED` / `EXPIRED`（预演 24 小时不应用作废） |
| applied_at / applied_by | |

**先算后做**：解析与匹配只写 `batch` + `batch_row`，**不碰 `elc_stock`**；供应商看了预演点「确认上架」才应用。
`REPLACE` 模式下 `to_delist` 必须在预演里显示出来 —— 一张少了半截的表会让他一半库存下架，这一步要他亲眼看到数字。

#### `elc_stock_batch_row` 预演明细（只追加，90 天清理）

`batch_no` · `row_idx` · `raw` JSON（原样一行）· 解析出的 `mpn_norm / mfr_code / qty / dc / price_tiers …` ·
`match` `MATCHED` / `NEW_PART` / `AMBIGUOUS`（厂牌有歧义）/ `INVALID` · `part_no` · `error_code` ·
`action` `INSERT` / `UPDATE` / `UNCHANGED` / `SKIP`。

**空格子 = 不改，不是清空**（沿用批量导入的既定语义）：增量模式下某行价格列为空，应用时保留原价。

#### `elc_stock` 库存行（**供应商面**；买家永远不直接读）

| 列 | 类型 | 说明 |
|---|---|---|
| stock_no | VARCHAR(32) UK | |
| supplier_no | VARCHAR(32) | |
| **line_key** | VARCHAR(96) | 认行的键：供应商自带编号优先，否则 `mpn_norm|mfr_code|dc|condition`。`UNIQUE (supplier_no, line_key)` |
| part_no | VARCHAR(32) | 匹配前可为空 |
| mpn_raw / mfr_raw | VARCHAR(64) | 原样保留，匹配规则变了能重跑 |
| mpn_norm | VARCHAR(64) | 冗余，便于按料号找库存（派单） |
| qty | BIGINT | 可售数量 |
| date_code | VARCHAR(16) | 原样：`2338` `23+` `24/25` |
| dc_year | SMALLINT | 解析出的年份，**买家只看得到这个**（精确批号能认出是谁家的货） |
| package | VARCHAR(32) | 供应商写的封装，可能与料号库不同 |
| moq / spq | INT | 起订量 / 最小包装 |
| lead_days | SMALLINT | 0 = 现货 |
| condition | VARCHAR(16) | `ORIGINAL` 原装原包 / `BULK` 原装散新 / `PULLED` 拆机 / `OTHER` |
| region_code | VARCHAR(12) | 货在哪 |
| price_tiers | JSON | `[{"minQty":1,"e6":1850000},{"minQty":1000,"e6":1620000}]` |
| currency | CHAR(3) | `CNY` / `USD` / `HKD` |
| tax_included | TINYINT | |
| valid_until | DATE | 到期自动 `EXPIRED`，默认 `上传日 + elc_setting['stock.ttl.days']`（30） |
| confirmed_at | DATETIME | 最近一次「仍有货」 |
| status | VARCHAR(16) | `ON` / `OFF`（供应商下架）/ `EXPIRED` / `DELISTED`（全量替换时下架） |
| batch_no | VARCHAR(32) | 最后一次被哪批改过 |

索引：`(part_no, status)` 派单查询；`(supplier_no, status)` 我的库存；`(valid_until, status)` 过期任务。

#### `elc_part_market` 买家面的库存投影 ★

每个有货料号一行，**由 `elc_stock` 变更触发重算**（同事务写 `elc_outbox`，异步重算该 part）。

| 列 | 说明 |
|---|---|
| part_no | PK |
| mpn_norm | 冗余，搜索直接打这张表 |
| qty_band | `B100` `B1K` `B10K` `B100K` `B1M`：显示成「1k+」。**不存精确总量** |
| source_band | `ONE` / `FEW`（2–4）/ `MANY`（5+）：显示成「多家有货」；不给精确家数 |
| price_from_e6 | 按加价规则换算后的最低参考单价 |
| price_from_qty | 这个价从多少起 |
| price_currency / price_tax_included | 统一换算成 CNY 含税展示 |
| spot | 是否有现货（lead_days=0 的行存在） |
| lead_days_min | |
| dc_year_max | 最新批次年份 |
| condition_set | `ORIGINAL,BULK` |
| refreshed_at | |

**为什么是一张物化的表而不是一个查询**：
① 防泄露由结构保证（上一节）；② 搜索是最热的路径，聚合不该在请求里算；
③ 分档是**有损**的 —— 表里就没有精确值，将来谁写了个「显示精确库存」的需求，也得先来改这张表的定义，而那时会被看见。

---

### D. 询价

#### `elc_rfq` 询价单（买家）

| 列 | 说明 |
|---|---|
| rfq_no | UK |
| buyer_ref | 主系统 `usr_no` |
| company | 买家填的公司名（可空）—— **供应商面不可见** |
| contact_name / contact_phone_enc | 同上 |
| source | `SINGLE` 单料号 / `BOM` |
| bom_file_ref | BOM 原件 |
| deliver_region | 收货城市（供应商面只给到省，用于判断运费与时效） |
| need_invoice | `NONE` / `VAT_NORMAL` / `VAT_SPECIAL` 专票 |
| deadline_at | 希望多久内报价（默认 24 小时） |
| status | `DRAFT` / `SUBMITTED` / `QUOTING` / `QUOTED` / `CLOSED` / `EXPIRED` / `CANCELLED` |
| line_cnt / quoted_line_cnt | 冗余计数，列表页用 |
| submitted_at / closed_at / close_reason | |

#### `elc_rfq_line` 询价行

`rfq_no` · `line_no` · `mpn_raw` · `mfr_raw` · `part_no`（匹配上才有）· `qty` · `target_e6`（目标价，可空）·
`dc_req`（`ANY` / `Y1` 一年内 / `Y2` 两年内）· `condition_req` · `status`
（`OPEN` / `QUOTED` / `ACCEPTED` / `NO_SOURCE` 无货源 / `CLOSED`）· `accepted_offer_no`。
`UNIQUE (rfq_no, line_no)`。

#### `elc_rfq_dispatch` 派单 —— **供应商能看到询价的唯一通道**

| 列 | 说明 |
|---|---|
| dispatch_no | UK；供应商面用它当询价的编号，**不暴露 rfq_no**（rfq_no 在买家手里，两边拿到同一个号就能对上） |
| rfq_no / line_no | |
| supplier_no | |
| via | `AUTO_MATCH` 库存命中 / `OPS` 运营指派 / `BUYER_PICK`（L2 才开） |
| status | `SENT` / `VIEWED` / `QUOTED` / `DECLINED` / `EXPIRED` |
| sent_at / viewed_at / responded_at | 响应率与响应时长由它算 |
| decline_reason | `NO_STOCK` / `PRICE` / `OTHER` |

`UNIQUE (rfq_no, line_no, supplier_no)`。每行派几家由 `elc_setting['dispatch.max.per.line']`（默认 5）控制 ——
派太多，供应商报价被采纳的概率太低，响应率会整体塌掉。

#### `elc_quote` 供应商报价

`quote_no` UK · `dispatch_no` UK（一派一报，改价走 revision）· `supplier_no` · `unit_e6` · `currency` · `tax_included` ·
`qty_available` · `date_code` · `lead_days` · `condition` · `valid_until` · `remark`（**只给平台看**，供应商常在这里写公司名和微信）·
`status` `ACTIVE` / `WITHDRAWN` / `ACCEPTED` / `EXPIRED` · `version`。

#### `elc_quote_revision` 改价历史（只追加）

`quote_no` · `version` · `unit_e6` · `qty_available` · `lead_days` · `changed_at` · `changed_by`。

#### `elc_offer` 买家看到的报价 ★

| 列 | 说明 |
|---|---|
| **offer_no** | UK；**随机不透明**，与 quote_no 无可推算关系 |
| rfq_no / line_no | |
| quote_no | 内部回指 —— **买家面的 VO 不映射此列**（ArchUnit + 响应体守卫双重） |
| unit_e6 / currency / tax_included | 换算后的价（统一 CNY；含税与否跟买家单的 need_invoice 走） |
| markup_rule_no / markup_e6 | 用了哪条规则、加了多少：运营复盘用 |
| qty_available | 原样 |
| dc_year | 只给年份 |
| lead_days / condition | |
| valid_until | ≤ quote.valid_until |
| **disclosure** | 生成时快照的档位 |
| **label** | 生成时快照的展示名：L0 `报价 A`、L1 `S-3F7K`、L2 简称 |
| status | `PUBLISHED` / `ACCEPTED` / `EXPIRED` / `WITHDRAWN` |
| published_by | `AUTO` 或运营账号 |

**为什么 offer 与 quote 分两张**：同一个报价，买家看到的价、批号精度、名字都不是供应商填的那个。
放一张表里靠「查询时换算」，就总有一个接口会忘了换算；分开以后，买家面**根本没有**原价可读。

#### `elc_markup_rule` 加价规则

`rule_no` · `scope` `GLOBAL` / `CATEGORY` / `MFR` / `SUPPLIER` · `scope_ref` · `rate_bp`（万分比）·
`min_add_e6`（最低加多少，防止小单价按比例加出 0）· `priority` · `status`。命中取 priority 最高的一条，**不叠加**。

#### `elc_deal` 成交跟进

`deal_no` · `rfq_no` · `line_no` · `offer_no` · `locked_e6`（接受瞬间的锁价快照）· `qty` ·
`status` `PENDING` 待联系 / `CONTRACTED` 已签 / `DELIVERED` / `DONE` / `FAILED` · `ops_owner` · `fail_reason` · `supplier_breach`（是否记供应商毁约）。

一期不在线交易，**这张表就是线索台账**：运营按它打电话、签合同、登结果。二期要做在线交易时，它会被订单取代而不是扩列。

---

### E. 运营面

| 表 | 要点 |
|---|---|
| `elc_demand_daily` | `(stat_date, mpn_norm)` PK · `search_cnt` · `rfq_line_cnt` · `rfq_qty_sum`。**喂给供应商看板**：「你库里的 STM32F103C8T6 这周被搜 312 次、询价 9 次」—— 这是劝他上传与保持新鲜的最直接的理由。只存聚合，不存谁搜的 |
| `elc_setting` | `k` PK · `v` · `note`。一期的键：`disclosure.cap`=L0 · `stock.ttl.days`=30 · `dispatch.max.per.line`=5 · `rfq.deadline.hours`=24 · `mpn.norm.version`=1 · `qty.bands` |
| `elc_outbox` | 独立库没有 `sys_outbox`，照它的结构另建一张：投影重算、派单通知、报价到达通知都走它 |
| `elc_audit_log` | 谁在什么时候把哪个供应商审过/停掉、谁改了加价规则、谁手工发了 offer |

---

## L4 边界

### 4.1 没做的（有意）

| 不做 | 原因 | 何时做 |
|---|---|---|
| 参数表 / 参数筛选 | 参数来源一期只有上传，质量撑不起筛选 | 接入外部数据源后 |
| 包含式模糊搜索 | MySQL 前缀够用；ngram 索引对料号串的误召回高 | 料号 > 千万或有数据支撑时 |
| 国产替代展示 | `elc_part_alias.kind=CROSS` 存着，不给买家看 —— 替代关系错一条就是质量事故 | 有人工审过的替代库之后 |
| 在线交易 | 开票、账期、合同、质检；小程序微信支付已因 B2B 类目受限过一次 | 二期单立 ADR |
| 多币种实时汇率 | 投影统一 CNY，汇率取 `elc_setting` 手工值 | 有外币报价量之后 |

### 4.2 待拍板

1. **加价方式**：按比例（`rate_bp`）还是固定加价？表两种都支持，默认值要定。建议 8%，最低加 ¥0.0005
2. **库存有效期**：30 天是否合适？现货贸易商库存周转快，可能要 14 天
3. **买家要不要实名/公司认证才能询价**：不认证询价量大、质量低；认证了门槛高。表结构不受影响（`elc_rfq.company` 已有）
4. **匿名代号长度**：`S-` + 4 位（36⁴ ≈ 168 万）够用；要不要按地区前缀（`SZ-3F7K`）—— 前缀本身就泄露了一点位置

### 4.3 取舍记录

- **供应商不复用 `mch_entity`**（2026-09-29 用户拍板）：人群不同，带进门店/经营范围/进件三套无关概念；独立时还要拆。
  留 `external_ref` 做对照，不联动
- **账号先借主系统**：`account_ref = usr_no`，小程序同一个登录态。独立那天换 `ElecIdentityPort` 的实现，`account_ref` 的值做一次映射迁移
- **价格精度 e6 而非 DECIMAL**：见通则 ④
- **投影表而非视图**：见 C 节 `elc_part_market`

### 4.4 与主系统的全部接触面

| 方向 | 什么 | 形式 |
|---|---|---|
| elec → 主系统 | 当前登录的人（usr_no） | `ElecIdentityPort` |
| elec → 主系统 | 短信 / 订阅消息 | `ElecNotifyPort` |
| elec → 主系统 | 文件上传（Excel、执照、datasheet） | `ElecMediaPort`（COS） |
| 主系统 → elec | 无 | — |

**主库里不出现任何 `elc_` 表，elec 库里不出现任何主库表名** —— 这两条在建模块时加成闸门。
