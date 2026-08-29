# 09 · API 参考

> 本层回答：**接口长什么样**。全量端点 + 关键报文。与 07 的状态/常量配合使用。
> 端点落在现有前缀（`/mp` `/biz` `/ops`），**不开 `/v2` 平行命名空间**。

## 0. 全局约定

| 约定 | 内容 |
|---|---|
| 认证与作用域 | `/biz` 作用域取自 `BizContext.requireMerchantNo()` —— **路径与入参一律不接受 merchantNo**（接受=越权入口）；门店作用域显式传 `storeNo` 并校验归属 |
| 幂等 | 所有产生副作用的写接口带 `Idempotency-Key` 头（或 body `idemKey`）；重复提交返回首次结果 |
| 语义动作 | 状态迁移用 `:verb`（`:sold-out` `:settle` `:confirm`），不用裸 PUT 表状态 —— 状态机在服务端 |
| 金额 | 一律最小货币单位整数（分）+ `currency`（缺省 CNY）；**绝不浮点** |
| 时间 | 毫秒时间戳或 ISO-8601（与既有 `IsoTime` 口径一致），本参考用 ISO 示例 |
| 分页 | 列表 `cursor` + `limit`（新端点）；存量 `PageData` 形状不动 |
| 错误 | 既有 `ApiResult` 形状：`{code, message, data}`；业务拒绝给**可行动的 message**（"该时段已满，请换时间" 而非 "参数错误"） |
| 权限 | 每个新端点登记 `scripts/perm-endpoint-map.mjs` 并重跑生成器 —— pre-push 矩阵闸 |
| 可见性 | C 端响应按能力开关过滤、Modifier 按 `visibleToBuyer` 裁剪、Station 永不给买家 |

---

## 1. 商品域

### 1.1 目录（biz）

| 端点 | 说明 |
|---|---|
| `GET /biz/goods` | 列表；`?withSegments=` 才带 traits（省 N+1） |
| `GET /biz/goods/{goodsNo}` | 详情：公共字段 + traits + modifiers + afterSalePolicy |
| `POST /biz/goods/save` | 建/改，见报文 ①；**不传=不改** |
| `POST /biz/goods/{no}/submit` | 送审 |
| `GET /biz/goods/form-schema?categoryNo=&storeNo=` | 建品表单唯一来源，见报文 ② |
| `GET/POST /biz/modifier-groups` · `POST /biz/modifier-groups/{g}/modifiers:batch` | 选配组（商家级复用） |
| `POST /biz/goods/{no}/modifier-groups:attach` | body `{groupNos:[]}` |

**报文 ① `POST /biz/goods/save`（美业项目，含 traits 段）**
```jsonc
{ "title": "深层护理", "categoryNo": "CAT-face", "productTypeNo": "PT-BEAUTY",
  "specGroups": [{ "name": "时长档", "options": ["60分钟", "90分钟"] }],
  "skus": [{ "optionValues": ["60分钟"], "price": 28800, "saleUnit": "次" },
           { "optionValues": ["90分钟"], "price": 38800 }],
  "traits": {                                     // 按 productType 白名单校验，能力关着=拒收（不是忽略）
    "service": { "durationMin": 60, "bufferAfterMin": 15,
                 "resourceType": "STAFF", "resourceCount": 1 },
    "serviceVariant": [{ "skuNo": "SKU-90", "durationMin": 90 }] },
  "modifierGroupNos": ["MG-pickstaff"],
  "fulfillments": ["STORE_VERIFY", "APPOINTMENT"],
  "afterSalePolicy": "BY_CANCEL_RULE",
  "caution": "孕期禁用精油" }
```

**报文 ② `GET /biz/goods/form-schema` 响应（节选）**
```jsonc
{ "type": "SERVICE",
  "common": [ { "key": "title", "label": "项目名", "required": true }, … ],
  "traits": [ { "trait": "SCHEDULED_SERVICE",
                "fields": [ { "key": "durationMin", "label": "服务时长", "type": "INT" }, … ] } ],
  "modifierGroups": { "attachable": true,
      "optionsFrom": "/biz/modifier-groups" },        // 选项来源端点，前端不硬编码
  "specs": [ /* 规格库四层给出的 SALE 维度，形状不变 */ ],
  "labels": "beauty" }                                // 术语层词条包（行业叫法）
```

### 1.2 售卖（biz，门店作用域）

| 端点 | 说明 |
|---|---|
| `POST /biz/stores/{storeNo}/listings:activate` | body `{goodsNos:[]}`，幂等批量上架 |
| `POST /biz/listings/{l}:delist` / `:sold-out` | 下架 / 沽清（今日 quota 置 0，次日 Job 回满） |
| `PUT /biz/listings/{l}` | `{channels, stationNo, courseNo, dailyQuota, quotaReset, moq, disabledModifierGroups}` |
| `GET/PUT /biz/stores/{s}/availability-rules` · `POST /biz/listings/{l}/availability:bind` | 可售时段 |
| `GET/PUT /biz/stores/{s}/collections` · `PUT /biz/collections/{c}/items` | 陈列（菜单分类/项目分组） |
| `PUT /biz/price-entries:batch` | 报文 ③ |
| `GET /biz/price:resolve?skuNo=&storeNo=&channel=&tier=` | 判定处调试出口，报文 ④ |

**报文 ③ 改价（渠道价 + 明日时价一次提交）**
```jsonc
{ "entries": [
  { "skuNo": "SKU-1", "storeNo": "S1", "channel": "TAKEOUT", "price": 2000 },
  { "skuNo": "SKU-fish", "storeNo": "S1", "price": 6200,
    "validFrom": "2026-08-29T00:00", "validTo": "2026-08-29T23:59" } ] }
```
**报文 ④ resolve 响应**
```jsonc
{ "price": 2000, "currency": "CNY", "fallback": false,
  "hit": { "entryNo": "PE-9", "specificity": "store+channel" } }   // 进订单快照，事后可复现
```

### 1.3 C 端（mp）

| 端点 | 说明 |
|---|---|
| `GET /mp/storefront/{storeNo}?channel=&at=` | **合成视图**：陈列×时段×沽清×解析价服务端算完，报文 ⑤ |
| `GET /mp/goods/{no}?storeNo=&channel=` | 详情：modifiers（按 visibleToBuyer）+ 售后策略 + caution |

**报文 ⑤ storefront 响应（节选）**
```jsonc
{ "collections": [ { "name": "面食", "items": [
    { "goodsNo": "G-1", "title": "招牌牛肉面",
      "price": { "from": 1800, "resolved": true },
      "available": true, "soldOut": false,
      "modifiersBrief": { "required": ["辣度"] } } ] } ] }
```

### 1.4 治理（ops）
`GET/PUT /ops/product-types`（改 traits 走审批）· `GET/PUT /ops/categories/{no}/default-type`

---

## 2. 订单域

### 2.1 下单与订单

| 端点 | 说明 |
|---|---|
| `POST /mp/order` | 跨商家混合下单（**恒走基座**，cross=Y 否决项），报文 ⑥ |
| `GET /mp/order/{no}` / `GET /biz/order/...` | 行含 modifiers 明细与 priceHit |
| `POST /biz/order-items/{id}:metered` | 计量回写 `{actualQty}`，称重/计时共用；超允差返回需确认 |

**报文 ⑥ 下单（含选配）→ 响应（节选）**
```jsonc
// 请求
{ "lines": [ { "skuNo": "SKU-noodle-S", "qty": 2,
               "modifiers": [ { "groupNo": "MG-spicy", "modifierNos": ["M-none"] },
                              { "groupNo": "MG-extra", "modifierNos": ["M-noodle"] } ] } ],
  "fulfillment": "DINE_IN", "idemKey": "place-x1" }
// 响应（行）
{ "amount": 4200, "priceHit": "base",
  "modifiers": [ { "name": "免辣", "delta": 0 }, { "name": "加面", "delta": 300 } ] }
```

### 2.2 收款与收银台（biz）

| 端点 | 说明 |
|---|---|
| `POST /biz/payments` | `{refType, refNo, orderNo?, channel, amount, idemKey}` 一笔收款 |
| `POST /biz/payments/{n}:reverse` | `{reason}`；仅 SUCCESS→REVERSED 一次 |
| `POST /biz/payments:settle` | 报文 ⑦；足额→逐单 `markPaid(MIXED, refNo)` |
| `GET /biz/payments?storeNo=&from=&to=&refType=` | 交班/对账 |
| `POST /biz/cashier/tickets` → `…/:pay` → `…/:close` | 收银台（基座）：开单/收款/结清 |

**报文 ⑦ settle（餐饮台账三单一次结）**
```jsonc
{ "refType": "CHECK", "refNo": "CHK-A3", "orderNos": ["O-1","O-2","O-3"] }
→ { "due": 10100, "paid": 10100, "settled": ["O-1","O-2","O-3"],
    "payments": [ {"channel":"WECHAT","amount":8000}, {"channel":"CASH","amount":2100} ] }
```

---

## 3. 预约/资源域

| 端点 | 说明 |
|---|---|
| `GET/POST /biz/resources` · `POST /biz/resources/{n}:occupy` / `:release` | 资源 CRUD；开放占用（开台/转台由行业编排先占后放） |
| `GET/POST /biz/staffs` · `PUT /biz/staffs/{n}/skills` | 人员档案与技能 |
| `GET/POST /biz/schedule-rules` · `POST /biz/schedule:adjust` | 排班规则；调班返回**受影响占位数** |
| `GET /biz/schedule/day?date=` | 排程视图（资源×格×占用） |
| `GET /mp/booking/slots?storeNo=&goodsNo=&staffNo=&date=` | 可约查询：技能∩排班∩余量∩时长缓冲一次算完 |
| `POST /biz/bookings:hold` → `/{h}:confirm` → `/{h}:release` | 占位单，报文 ⑧ |

**报文 ⑧ hold（美业双人 90 分钟）**
```jsonc
{ "bySpan": { "storeNo": "S1", "resourceNos": ["RES-07","RES-12"],
              "startAt": "2026-08-29T14:00", "spanMin": 105 },     // 90 服务+15 清洁
  "qty": 1, "ttlSec": 900, "idemKey": "appt-x1" }
→ { "holdNo": "H-88", "units": 8, "expireAt": "…14:15" }           // 4 格×2 资源，原子
// 团课报名: {"bySlots":[{"slotNo":"SL-wed19","qty":1}]}；拼场 qty=3；包场 qty=余量
```

---

## 4. 行业入口（行业包提供，编排后调基座）

| 前缀 | 主要端点 | 细节 |
|---|---|---|
| `/mp|biz/x/food/**` | 桌码进店 · 台账购物车 · place · KDS 六动作 · settle 编排 · 沽清 | [餐饮包 TDD](../technical/design/TDD-餐饮包-场景与工作流.md) §6.4 |
| `/mp|biz/x/beauty/**` | 预约建改核销 · 工单开始/换人/完成 · 耗卡 · 合并结账 | [美业包 TDD](../technical/design/TDD-美业包-场景与工作流.md) §6.3 |
| `/mp|biz/x/elc/**` | BOM 解析匹配 · RFQ/Quote/议价版本 · 转单锁价（部分转单）· 报价 PDF | [14 册](./14-电子元器件行业规格.md) §七 |

AR（基座新域）：`GET /biz/ar/receivables` · `POST /biz/ar/settlements` · `GET/PUT /ops/ar/credit-profiles/{m}`（额度调整单独权限码）。

行业入口**只做编排**：钱经 `payments/settle`、单经 `place`、占位经 `bookings` —— 不代理基座读写（ADR-020）。
