# 进销存 · API 与四层对齐

> 状态：**草稿 · 待评审** · 2026-08-26
> 四层：**界面 → 端点 → 领域对象 → 表**。上一层的每个动作都要能落到下一层，落不下去的就是缺口。
> 上游：[B 端九屏原型](https://claude.ai/code/artifact/f3904519-ea36-4bc0-b231-d113b696f7e0) ·
> [三端落点与功能矩阵](./进销存-三端落点与功能矩阵.md) ·
> [领域对象详解](./进销存-领域对象详解.md) · [数据库表结构](./进销存-数据库表结构.md)

---

## 一、先说三条贯穿全篇的规矩

| # | 规矩 | 后果 |
|---|---|---|
| 1 | **没有「改库存」这个端点** | 界面上的「改数」底下是一次**单件盘点**（开单 → 录实盘 → 过账）。所有余额变动都必须经过单据，这条在 API 面上就是「没有裸的 PUT balance」 |
| 2 | **销售出库没有创建端点** | `POST /biz/inventory/outbounds` 必须**拒绝 `purpose=SALE`**。它只能由预留 `commit` 在服务端产生 —— 否则商家能凭空造一笔销售出库，而它会进销量榜 |
| 3 | **流水只有读，没有写** | `inv_ledger` 是单据过账的产物。给它写端点等于给「绕过领域改账」开了门 |

**另一条与部署形态有关的**：B 端路径**不因进销存独立成服务而改变**。
前端仍调 `/biz/inventory/**`，由 portal 层转发到领域（进程内 Port 或 HTTP）。
**契约与部署形态解耦** —— 拆服务那天前端一行不改。

---

## 二、四层对齐总表

| 屏 / 动作 | 端点 | 聚合根 | 表 |
|---|---|---|---|
| s01 库存总览 | `GET /biz/inventory/summary`<br>`GET /biz/inventory/balances` | StockBalance | `inv_stock_balance` ＋ `inv_item` |
| s01 门店切换 | 复用 `GET /biz/stores` ＋ 服务端映射 | Location | `inv_location.external_ref` |
| s01 「改数」 | 见 s03（单件盘点） | StockCount | —— |
| s02 明细头 | `GET /biz/inventory/items/{itemId}` | Item ＋ StockBalance | `inv_item` `inv_item_ref` `inv_stock_balance` |
| s02 变动明细 | `GET /biz/inventory/ledger` | StockLedgerEntry | `inv_ledger` |
| s03 开盘点 | `POST /biz/inventory/counts` | StockCount | `inv_stock_count(_line)` |
| s03 录实盘 | `PUT /biz/inventory/counts/{no}/lines` | StockCount | `inv_stock_count_line` |
| s03 过账 | `POST /biz/inventory/counts/{no}/post` | StockCount → In/Outbound | ＋`inv_*bound_*` ＋`inv_ledger` ＋余额 |
| s04 进货草稿 | `POST/PUT /biz/inventory/inbounds` | InboundOrder | `inv_inbound_order(_line)` |
| s04 过账入库 | `POST /biz/inventory/inbounds/{no}/post` | InboundOrder | ＋`inv_ledger` ＋余额 ＋`inv_item.default_cost` |
| s05 单据中心 | `GET /biz/inventory/documents` | Inbound / Outbound / Count / Transfer | 四张头表 union（**仅列表**，汇总走流水） |
| s06 报损 | `POST /biz/inventory/outbounds` ＋ `/post` | OutboundOrder | `inv_outbound_order(_line)` |
| s07 调拨发出 | `POST /biz/inventory/transfers/{no}/ship` | TransferOrder → Outbound | ＋ TRANSIT 库位余额 |
| s07 确认收货 | `POST /biz/inventory/transfers/{no}/receive` | TransferOrder → Inbound | TRANSIT → 目标库位 |
| s08 月报 | `GET /biz/inventory/report/monthly` | —— | `inv_ledger` 聚合（D3 后走 `inv_daily_snapshot`） |
| s08 榜单 | `GET /biz/inventory/report/ranking` | —— | 同上 |
| s08 导出 | `GET /biz/inventory/export` | —— | 同上 ＋ **平台 `sys_audit_log`**（见 §五缺口 3） |
| s09 库位 | `GET/POST/PUT /biz/inventory/locations` | Location | `inv_location` |
| Ops 健康度 | `GET /ops/inventory/health` | StockBalance | `inv_stock_balance` |
| Ops 台账 | `GET /ops/inventory/ledger` | StockLedgerEntry | `inv_ledger` |
| Ops 对差 | `GET /ops/inventory/recon` | —— | `inv_stock_balance` ×平台 `prd_sku.stock` |
| 交易域下单 | `ReservationPort.reserve` | Reservation | `inv_reservation(_line)` ＋ `reserved` |
| 交易域支付 | `ReservationPort.commit` | Reservation → Outbound | ＋`inv_outbound_order` ＋`inv_ledger` ＋`on_hand` |
| 交易域退货 | `POST /biz/inventory/inbounds`（`source_type=RETURN`，系统调） | InboundOrder | 同 s04 |

---

## 三、端点清单

### 3.1 B 端 `/biz/inventory/**`

| 方法 | 路径 | 说明 | 幂等 | 权限 | 期 |
|---|---|---|---|---|---|
| GET | `/biz/inventory/summary` | 三个汇总数 | — | `biz:stock` | D2 |
| GET | `/biz/inventory/balances` | 余额列表（`filter=todo\|all\|reserved`） | — | `biz:stock` | D2 |
| GET | `/biz/inventory/items/{itemId}` | 物料 ＋ 各库位余额 | — | `biz:stock` | D2 |
| GET | `/biz/inventory/ledger` | 变动明细（**游标分页**） | — | `biz:stock` | **D1** |
| POST | `/biz/inventory/counts` | 开盘点单（**锁账面数**） | 客户端 `requestId` | `biz:stock` | **D1** |
| GET | `/biz/inventory/counts/{countNo}` | 盘点单详情 | — | `biz:stock` | D1 |
| PUT | `/biz/inventory/counts/{countNo}/lines` | 录实盘 | 全量覆盖 | `biz:stock` | D1 |
| POST | `/biz/inventory/counts/{countNo}/post` | 过账 | 状态早退 | `biz:stock` | **D1** |
| POST | `/biz/inventory/inbounds` | 建入库草稿 | `requestId` | `biz:stock` ＋ `inv_purchase` | D3 |
| PUT | `/biz/inventory/inbounds/{no}` | 改草稿（**仅 DRAFT**） | 全量覆盖 | 同上 | D3 |
| POST | `/biz/inventory/inbounds/{no}/post` | 过账入库 | 状态早退 | 同上 | D3 |
| POST | `/biz/inventory/inbounds/{no}/void` | 作废（**写反向流水**） | 状态早退 | 同上 | D3 |
| POST | `/biz/inventory/outbounds` | 建出库草稿（**拒绝 `SALE`**） | `requestId` | `biz:stock` | D3 |
| POST | `/biz/inventory/outbounds/{no}/post` | 过账出库 | 状态早退 | `biz:stock` | D3 |
| POST | `/biz/inventory/outbounds/{no}/void` | 作废 | 状态早退 | `biz:stock` | D3 |
| GET | `/biz/inventory/documents` | 单据中心（四类，游标） | — | `biz:stock` | D3 |
| POST | `/biz/inventory/transfers` | 建调拨单 | `requestId` | `biz:stock` ＋ `inv_warehouse` | D3 |
| POST | `/biz/inventory/transfers/{no}/ship` | 发出 → 生成出库单 | 状态早退 | 同上 | D3 |
| POST | `/biz/inventory/transfers/{no}/receive` | 收到 → 生成入库单 | 状态早退 | 同上 | D3 |
| GET | `/biz/inventory/locations` | 库位列表 | — | `biz:stock` 读 | D3 |
| POST | `/biz/inventory/locations` | 建仓 | `requestId` | `biz:store:admin` | D3 |
| PUT | `/biz/inventory/locations/{id}` | 改发货源（**拦链式**） | 全量覆盖 | `biz:store:admin` | D3 |
| GET | `/biz/inventory/report/monthly` | 期初/进/销/损/期末 | — | `biz:customer` | D3 |
| GET | `/biz/inventory/report/ranking` | 动销/滞销/毛利榜 | — | `biz:customer` | D3 |
| GET | `/biz/inventory/export` | CSV（**UTF-8 带 BOM**） | — | `biz:customer` | D3 |

**24 个端点，其中 D1 只有 5 个** —— 与矩阵里「D1 只有四行」对得上。

### 3.2 运营端 `/ops/inventory/**`

| 方法 | 路径 | 说明 | 权限 | 期 |
|---|---|---|---|---|
| GET | `/ops/inventory/recon` | 平台库存 vs 进销存逐 SKU 对差 | `product:sku:read` | **D1** |
| GET | `/ops/inventory/ledger` | 商家台账（**只读**） | `product:sku:read` | D2 |
| GET | `/ops/inventory/health` | 负库存 / 零库存在架 / 90 天未动 | `product:sku:read` | D3 |

**运营端一个写端点都没有** —— 需求 O-2 的原话是「运营能改商家库存的那一刻，
『这个数是谁改的』就多了一个答案，而商家不会知道」。

### 3.3 内部（交易域调，不走 HTTP）

`StockPort` 今天的 `lock / release / confirm` **就是预留协议的形状**，语义升级即可：

| 现在 | 升级为 | 变化 |
|---|---|---|
| `lock(lockNo, items)` | `reserve(externalRef, lines, ttl)` | 多一个 `expires_at`；返回 `reservationId` |
| `release(lockNo)` | `release(externalRef)` | 不变 |
| `confirm(lockNo)` | `commit(externalRef)` | **多产生一张 `SALE` 出库单** |
| —— | `restore(afterSaleNo, lines)` | **新增**：退货入库（今天的缺口，见全链路 §四 J4） |

**调用方一行不改**（`SkuQty` 的形状保持），拆服务时换 Port 实现。

### 3.4 Open API `/open/v1/**`（D4，需先拍板）

`GET /items` · `GET /orders?since=` · `GET /stock-ledger?since=` · `POST /stock:sync`
——见 [领域模型 §八](./TDD-进销存领域模型.md)。

---

## 四、几个形状要定死

### 4.1 余额：三个数下发，表里只有两列

```jsonc
// GET /biz/inventory/balances
{ "items": [{
    "itemId": "ITM…", "name": "东北大米", "specText": "5斤装", "baseUom": "BAG",
    "onHand": 5, "reserved": 2, "available": 3,   // available 是算出来的，不落库
    "safetyStock": 10, "lastMovedAt": "2026-08-26T14:22:00",
    "flags": ["SHORTAGE"]                          // SHORTAGE | STALE，服务端判，前端不算
  }], "nextCursor": "…" }
```

**`flags` 由服务端给**：缺货 = `available < safetyStock`，滞销 = `lastMovedAt` 超阈值。
前端自己算的话，「要处理」的口径会在列表页与报表页各有一份。

### 4.2 流水：游标不是页码

```jsonc
// GET /biz/inventory/ledger?itemId=&locationId=&cursor=&size=20
{ "entries": [{
    "id": 8812345, "docKind": "OUT", "docNo": "OUT-2408260031", "reasonCode": "SALE",
    "qtyDelta": -2, "balanceAfter": 3,
    "occurredAt": "2026-08-26T14:22:00", "operator": "系统"
  }], "nextCursor": "8812345" }
```

**游标是 `id` 不是时间**：时钟回拨会让时间游标漏行，而漏的那几行不会有任何报错。

### 4.3 过账：请求里不带数量

`POST …/post` **只带单号**。数量在草稿里已经存好了 ——
请求里再带一次，就有了「以哪一份为准」的问题，而两份不一致时没人知道该信谁。

### 4.4 错误：库存不足要说清差多少

```jsonc
{ "code": "INV_INSUFFICIENT",
  "shortages": [{ "itemId": "ITM…", "name": "东北大米", "requested": 5, "available": 3 }] }
```

只回一个「库存不足」，用户要逐个试才知道是哪件、差几个。

---

## 五、四层对齐检查：三处对不上

| # | 缺口 | 处置 |
|---|---|---|
| 1 | **界面选的是「门店」，领域认的是「库位」** | B 端下发 `storeNo`，服务端经 `inv_location.external_ref` 解析成 `locationId`。**映射放服务端**，前端不该知道有两套 ID |
| 2 | **`biz:stock` 是 B 端权限码，而进销存独立库里没有权限体系** | 鉴权留在 portal 层，领域只认 `ownerId`。领域层出现 `biz:stock` 的那一刻，独立交付就少一分 |
| 3 | **导出要写审计日志，而 `sys_audit_log` 在平台库** | 由 **portal 层**写审计，领域只负责出数据。跨库写审计等于把平台表拉进领域的事务 |

---

## 六、怎么防漂

| 手段 | 防住什么 |
|---|---|
| 端点表进 `backend/scripts` 的端点扫描 | 端点加了没登记 → 静默不进 spec（**注释别夹在 `{` 与 `method:` 之间**，这个仓库栽过） |
| 契约由前端生成的 OpenAPI 反向对账（`npm run check:api`） | 前端在调、后端没实现（或反之） |
| **写权守卫**（S0 那条） | `shop-inventory` 以外的模块写 `inv_*` 表 |
| **回放守卫** | `prev.balance_after + qtyDelta == balanceAfter` 全链闭合 |

---

## 七、待确认

> ⚠️ **取值已定**，见[决策记录](./进销存-决策记录.md)（2026-08-26「一切都先按照建议执行」）。
> 本节原文保留 —— 它记录的是当时的权衡，不是当前取值。


| # | 决策 | 挡住谁 |
|---|---|---|
| ① | B 端路径用 `/biz/inventory/**` 还是更短的 `/biz/inv/**` | 契约生成。倾向 `inventory`，短名省 4 个字符换来一个要解释的缩写 |
| ② | `GET /biz/inventory/documents` 是四张头表 union，还是各自独立端点 | 单据中心。倾向 union —— 界面就是一个列表，拆成四个前端要自己合并与排序 |
| ③ | 单件「改数」是否给一个便捷端点（一次调用完成开单+录+过账） | s01 的「改数」按钮。倾向给 —— 三次往返换一次点击不值 |
| ④ | 报表在 D3 前直接聚合 `inv_ledger` 是否可接受 | 性能。今天量级完全可以；`inv_daily_snapshot` 是量上来之后的事 |

---

确认记录：待用户确认
