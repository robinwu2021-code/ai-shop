# 平台端 API 详情 · ops-web（运营后台）

> 由 `npm run gen:api-detail` 从 OpenAPI 生成，**请勿手改**。
> 契约源：[`openapi-ops.yaml`](openapi-ops.yaml)　总表：[API 清单](API清单.md)

## 通用约定

| 项 | 约定 |
|---|---|
| 响应包 | `{ code, msg, data }`，`code=0` 表示成功；下文「出参」只描述 `data` |
| 分页 | 入参 `page`（从 1 起）、`size`；出参 `{ records, total, page, size }` |
| 金额 | 一律**最小货币单位整数**（分），字段名以 `Minor` 结尾。禁止浮点 |
| 时间 | 毫秒时间戳整数，字段名以 `At` 结尾 |
| 业务单号 | 字符串，字段名以 `No` 结尾（`orderNo`/`goodsNo`…），非自增 ID |
| 枚举 | 大写下划线常量；取值见「数据模型」对应条目 |
| 命名 | camelCase |
| 鉴权 | 🔒 = 需 Bearer token；越权拦截以后端为准，前端仅做展示裁剪 |

完整口径（错误码分段、HTTP 状态码取舍、空值语义、幂等）见 [响应格式规范](响应格式规范.md)。

---

## 接口

### aftersale

#### GET `/ops/after-sales`

listAfterSales

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`AfterSale`](#aftersale)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/after-sales/{asNo}/decide`

平台介入裁决（P-6.1.3 + 6.1.4）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `asNo` | path | — | 是 | 售后单号（平台端写法） |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `asNo` | `string` | 是 | 售后单号 |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `amount` | `number` | 是 | 申请退款金额（分）。**不得超过订单实付** —— 校验要跨域查订单。 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `evidenceCount` | `number` | 是 | 举证材料数量（照片/聊天记录） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。口径未定（M4），先存结构 |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：裁决完成但资金域（P-12）尚未接。 留这个标记而不是假装已完成 —— 接资金域时按它补跑。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### POST `/ops/after-sales/{no}/status`

状态推进，非法迁移抛错（驳回不是终点，用户可上升平台）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `asNo` | `string` | 是 | 售后单号 |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `amount` | `number` | 是 | 申请退款金额（分）。**不得超过订单实付** —— 校验要跨域查订单。 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `evidenceCount` | `number` | 是 | 举证材料数量（照片/聊天记录） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。口径未定（M4），先存结构 |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：裁决完成但资金域（P-12）尚未接。 留这个标记而不是假装已完成 —— 接资金域时按它补跑。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### GET `/ops/after-sales/fast-refund-rule`

getFastRefundRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FastRefundRule`](#fastrefundrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enabled` | `boolean` | 是 | 总开关。关掉后所有小额售后都走人工 |
| `maxAmount` | `number` | 是 | 金额上限（分），必须 > 0 |
| `withinHours` | `number` | 是 | 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） |
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/after-sales/fast-refund-rule`

极速退阈值（P-6.1.2）：金额上限 > 0、时限 ≥ 1 小时

**入参**

_无字段_

**出参**（`data`）

类型：[`FastRefundRule`](#fastrefundrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enabled` | `boolean` | 是 | 总开关。关掉后所有小额售后都走人工 |
| `maxAmount` | `number` | 是 | 金额上限（分），必须 > 0 |
| `withinHours` | `number` | 是 | 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） |
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


### community

#### GET `/ops/communities`

listCommunities

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Community`](#community)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/communities/{no}/archive`

archiveCommunity

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Community`](#community)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `communityNo` | `string` | 是 | 社区单号。平台端数据域裁剪的主键之一 |
| `name` | `string` | 是 | 社区名（小区名） |
| `city` | `string` | 是 | 所属城市 |
| `grid` | `string` | 是 | 网格：城市与社区之间的运营划分单位 |
| `opened` | `boolean` | 是 | 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 |
| `fenceRadius` | `number` | 是 | 覆盖围栏半径，米（P-2.1.3） |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/communities/{no}/fence`

覆盖围栏半径，米（P-2.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Community`](#community)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `communityNo` | `string` | 是 | 社区单号。平台端数据域裁剪的主键之一 |
| `name` | `string` | 是 | 社区名（小区名） |
| `city` | `string` | 是 | 所属城市 |
| `grid` | `string` | 是 | 网格：城市与社区之间的运营划分单位 |
| `opened` | `boolean` | 是 | 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 |
| `fenceRadius` | `number` | 是 | 覆盖围栏半径，米（P-2.1.3） |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/communities/{no}/open`

开城/停城（P-2.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Community`](#community)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `communityNo` | `string` | 是 | 社区单号。平台端数据域裁剪的主键之一 |
| `name` | `string` | 是 | 社区名（小区名） |
| `city` | `string` | 是 | 所属城市 |
| `grid` | `string` | 是 | 网格：城市与社区之间的运营划分单位 |
| `opened` | `boolean` | 是 | 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 |
| `fenceRadius` | `number` | 是 | 覆盖围栏半径，米（P-2.1.3） |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/communities/{no}/unarchive`

unarchiveCommunity

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Community`](#community)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `communityNo` | `string` | 是 | 社区单号。平台端数据域裁剪的主键之一 |
| `name` | `string` | 是 | 社区名（小区名） |
| `city` | `string` | 是 | 所属城市 |
| `grid` | `string` | 是 | 网格：城市与社区之间的运营划分单位 |
| `opened` | `boolean` | 是 | 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 |
| `fenceRadius` | `number` | 是 | 覆盖围栏半径，米（P-2.1.3） |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |


#### GET `/ops/pickups`

listPickups

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`PickupPoint`](#pickuppoint)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/pickups/{no}/archive`

archivePickup

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupType`](#definitionspickuptype) | 是 | 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `merchantNo` | `string` | 否 | 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/service-fee`

履约服务费费率，万分比（P-2.2.4）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupType`](#definitionspickuptype) | 是 | 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `merchantNo` | `string` | 否 | 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/status`

启停与迁移（P-2.2.2），非法迁移抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupType`](#definitionspickuptype) | 是 | 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `merchantNo` | `string` | 否 | 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/unarchive`

unarchivePickup

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupType`](#definitionspickuptype) | 是 | 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `merchantNo` | `string` | 否 | 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### GET `/ops/pickups/risky`

疑似职业化的临时自提点（P-2.2.5）：近 30 天承接次数 ≥ 阈值

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`PickupPoint`](#pickuppoint)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


### content

#### GET `/ops/contents/posts`

listPosts

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Post`](#post)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/contents/posts/{postNo}/decide`

裁决一条种草内容

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `postNo` | path | — | 是 | 种草内容单号 |

_无字段_

**出参**（`data`）

类型：[`Post`](#post)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `postNo` | `string` | 是 | 内容单号 |
| `authorType` | [`#/definitions/PostAuthorType`](#definitionspostauthortype) | 是 | 作者类型：普通用户 or 商家。商家发的内容审核标准更严 |
| `authorName` | `string` | 是 | 作者昵称/店名 |
| `title` | `string` | 是 | 内容标题 |
| `content` | `string` | 是 | 正文 |
| `communityNo` | `string` | 是 | 归属社区。内容只在本社区露出 |
| `communityName` | `string` | 是 | 社区名快照 |
| `skuNo` | `string,null` | 否 | 关联商品；纯分享贴可以没有 |
| `riskHits` | `string`\[\] | 是 | 命中的风险词。 ⚠️ 命中的内容**不进批量通过** —— 批量 + 风险内容 = 事故，必须逐条看。 |
| `status` | [`#/definitions/PostStatus`](#definitionspoststatus) | 是 | 审核状态。允许的流转见 `POST_TRANSITIONS`（`PASSED → OFFLINE` 是单独一条路） |
| `auditRemark` | `string,null` | 否 | 审核意见 / 下架原因。原样回作者 |
| `likeCount` | `number` | 是 | 点赞数 |
| `createdAt` | `string` | 是 | 发布时间 |
| `decidedAt` | `string,null` | 否 | 审核完成时间。未审为 null |
| `decidedBy` | `string,null` | 否 | 审核人（STAFF 账号）。未审为 null |


#### POST `/ops/contents/posts/batch-pass`

批量通过

**入参**

_无字段_

**出参**（`data`）

类型：[`Post`](#post)\[\]


#### GET `/ops/contents/questions`

listQuestions

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Question`](#question)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/contents/questions/{questionNo}/answer`

回答

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `questionNo` | path | — | 是 | 商品问答单号 |

_无字段_

**出参**（`data`）

类型：[`Question`](#question)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `questionNo` | `string` | 是 | 提问单号 |
| `skuNo` | `string` | 是 | 被提问的商品 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `content` | `string` | 是 | 提问正文 |
| `askedBy` | `string` | 是 | 提问人昵称 |
| `answer` | `string,null` | 否 | 回答正文。未回答为 null |
| `answeredBy` | `string,null` | 否 | 回答人（STAFF 或商家）。未回答为 null |
| `answeredAt` | `string,null` | 否 | 回答时间。未回答为 null |
| `status` | [`#/definitions/QuestionStatus`](#definitionsquestionstatus) | 是 | 问答状态 |
| `createdAt` | `string` | 是 | 提问时间 |
| `hideReason` | `string,null` | 否 | 隐藏原因。隐藏也要写清为什么，否则用户来问时没人说得清 |


#### POST `/ops/contents/questions/{questionNo}/hide`

隐藏提问（如导流、辱骂）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `questionNo` | path | — | 是 | 商品问答单号 |

_无字段_

**出参**（`data`）

类型：[`Question`](#question)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `questionNo` | `string` | 是 | 提问单号 |
| `skuNo` | `string` | 是 | 被提问的商品 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `content` | `string` | 是 | 提问正文 |
| `askedBy` | `string` | 是 | 提问人昵称 |
| `answer` | `string,null` | 否 | 回答正文。未回答为 null |
| `answeredBy` | `string,null` | 否 | 回答人（STAFF 或商家）。未回答为 null |
| `answeredAt` | `string,null` | 否 | 回答时间。未回答为 null |
| `status` | [`#/definitions/QuestionStatus`](#definitionsquestionstatus) | 是 | 问答状态 |
| `createdAt` | `string` | 是 | 提问时间 |
| `hideReason` | `string,null` | 否 | 隐藏原因。隐藏也要写清为什么，否则用户来问时没人说得清 |


#### GET `/ops/contents/rankings`

listRankings

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Ranking`](#ranking)\[\]


#### POST `/ops/contents/rankings`

保存榜单

**入参**

_无字段_

**出参**（`data`）

类型：[`Ranking`](#ranking)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rankNo` | `string` | 是 | 榜单单号 |
| `name` | `string` | 是 | 榜单名，如「本周热销」 |
| `kind` | [`#/definitions/RankingKind`](#definitionsrankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
| `size` | `number` | 是 | 取前 N 名 |
| `manualSkus` | `string`\[\] | 是 | 仅 MANUAL：人工指定的商品，顺序即榜位 |
| `enabled` | `boolean` | 是 | 是否启用。停用后 C 端不再展示该榜 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/contents/rankings/{rankNo}/enabled`

setRankingEnabled

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `rankNo` | path | — | 是 | 榜单单号 |

_无字段_

**出参**（`data`）

类型：[`Ranking`](#ranking)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rankNo` | `string` | 是 | 榜单单号 |
| `name` | `string` | 是 | 榜单名，如「本周热销」 |
| `kind` | [`#/definitions/RankingKind`](#definitionsrankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
| `size` | `number` | 是 | 取前 N 名 |
| `manualSkus` | `string`\[\] | 是 | 仅 MANUAL：人工指定的商品，顺序即榜位 |
| `enabled` | `boolean` | 是 | 是否启用。停用后 C 端不再展示该榜 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/materials`

listMaterials

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Material`](#material)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/materials`

保存素材（P-15.1.1–15.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`Material`](#material)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `materialNo` | `string` | 是 | 素材单号 |
| `title` | `string` | 是 | 素材标题，供商家在素材中心检索 |
| `kind` | [`#/definitions/MaterialKind`](#definitionsmaterialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`#/definitions/MaterialScope`](#definitionsmaterialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
| `scopeRefs` | `string`\[\] | 是 | scope=COMMUNITY 时的社区列表；=MERCHANT 时的商家列表。ALL 时为空 |
| `langs` | `string`\[\] | 是 | 适用语言，空 = 不限 |
| `published` | `boolean` | 是 | 是否已发布。未发布的素材商家看不到 |
| `downloads` | `number` | 是 | 被下载次数，衡量素材有没有人用 |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/materials/{no}/published`

setMaterialPublished

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Material`](#material)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `materialNo` | `string` | 是 | 素材单号 |
| `title` | `string` | 是 | 素材标题，供商家在素材中心检索 |
| `kind` | [`#/definitions/MaterialKind`](#definitionsmaterialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`#/definitions/MaterialScope`](#definitionsmaterialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
| `scopeRefs` | `string`\[\] | 是 | scope=COMMUNITY 时的社区列表；=MERCHANT 时的商家列表。ALL 时为空 |
| `langs` | `string`\[\] | 是 | 适用语言，空 = 不限 |
| `published` | `boolean` | 是 | 是否已发布。未发布的素材商家看不到 |
| `downloads` | `number` | 是 | 被下载次数，衡量素材有没有人用 |
| `createdAt` | `string` | 是 | 创建时间 |


### dashboard

#### POST `/ops/auth/login`

登录换后端 token

**入参**

_无字段_

**出参**（`data`）

类型：[`LoginResp`](#loginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `merchantNo` | `string` | 否 | 商家运营（BD）等受限角色的数据域；平台全量角色为空 |
| `communityNo` | `string` | 否 | 受限角色的社区数据域 |


#### GET `/ops/dashboard/funnel`

getAcquisitionFunnel

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FunnelStep`](#funnelstep)\[\]


#### GET `/ops/dashboard/kpi`

getDashboardKpi

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`DashboardKpi`](#dashboardkpi)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `gmv` | `number` | 是 | 成交额（最小货币单位整数） |
| `orderCount` | `number` | 是 | 订单数 |
| `avgOrderValue` | `number` | 是 | 客单价 |
| `pendingMerchantAudit` | `number` | 是 | 待审商家数（P-11.1.1 提审队列） |
| `pendingAfterSale` | `number` | 是 | 待处理售后（P-6.1.1 工单池） |
| `redeemRate` | `number` | 是 | 今日核销率（P-5.1.3 核销监控），0–1 |


#### GET `/ops/dashboard/trend`

getDashboardTrend

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`TrendPoint`](#trendpoint)\[\]


### finance

#### GET `/ops/fee-rule`

getFeeRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FeeRule`](#feerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `byTrafficSource` | [`#/definitions/Record<TrafficSource,number>`](#definitionsrecordtrafficsourcenumber) | 是 | 按流量来源分档的平台佣金费率（R16）。 ⚠️ `MERCHANT_OWNED`（商家自带客流）**建议 0** —— 商家自己把客人带来的单还抽佣， 商家就会把客人带去别处成交（ADR-004 的增长模型立不住）。口径未定，故可配。 |
| `pickupServiceFeeRate` | `number` | 是 | 自提点履约服务费默认费率（R15）；自提点自己配了就用它自己的 |
| `freezeDays` | `number` | 是 | 超时兜底天数（12.1.4）：冻结超过它仍未分账成功，解冻回平台 |
| `updatedAt` | `string` | 是 | 最后修改时间。**改费率不影响已生成的结算单** |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/fee-rule`

费率配置（P-12.1.7 / 12.1.8 / 12.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`FeeRule`](#feerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `byTrafficSource` | [`#/definitions/Record<TrafficSource,number>`](#definitionsrecordtrafficsourcenumber) | 是 | 按流量来源分档的平台佣金费率（R16）。 ⚠️ `MERCHANT_OWNED`（商家自带客流）**建议 0** —— 商家自己把客人带来的单还抽佣， 商家就会把客人带去别处成交（ADR-004 的增长模型立不住）。口径未定，故可配。 |
| `pickupServiceFeeRate` | `number` | 是 | 自提点履约服务费默认费率（R15）；自提点自己配了就用它自己的 |
| `freezeDays` | `number` | 是 | 超时兜底天数（12.1.4）：冻结超过它仍未分账成功，解冻回平台 |
| `updatedAt` | `string` | 是 | 最后修改时间。**改费率不影响已生成的结算单** |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/finance/invoices`

listInvoiceRequests

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`InvoiceRequest`](#invoicerequest)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/finance/invoices/{invoiceNo}/issue`

开票

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `invoiceNo` | path | — | 是 | 开票申请单号 |

_无字段_

**出参**（`data`）

类型：[`InvoiceRequest`](#invoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`#/definitions/InvoiceTitleType`](#definitionsinvoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`#/definitions/InvoiceStatus`](#definitionsinvoicestatus) | 是 | 开票状态 |
| `serialNo` | `string,null` | 否 | 开票后的发票流水号 |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 处理时间。未处理为 null |
| `remark` | `string,null` | 否 | 驳回原因。原样回商家 B 端 |


#### POST `/ops/finance/invoices/{invoiceNo}/reject`

rejectInvoice

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `invoiceNo` | path | — | 是 | 开票申请单号 |

_无字段_

**出参**（`data`）

类型：[`InvoiceRequest`](#invoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`#/definitions/InvoiceTitleType`](#definitionsinvoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`#/definitions/InvoiceStatus`](#definitionsinvoicestatus) | 是 | 开票状态 |
| `serialNo` | `string,null` | 否 | 开票后的发票流水号 |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 处理时间。未处理为 null |
| `remark` | `string,null` | 否 | 驳回原因。原样回商家 B 端 |


#### GET `/ops/finance/tax-rule`

getTaxRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`TaxRule`](#taxrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `threshold` | `number` | 是 | 起征点（分）：单期收入低于它不代扣 |
| `rate` | `number` | 是 | 代扣税率（万分比） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### PUT `/ops/finance/tax-rule`

个税代扣规则

**入参**

_无字段_

**出参**（`data`）

类型：[`TaxRule`](#taxrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `threshold` | `number` | 是 | 起征点（分）：单期收入低于它不代扣 |
| `rate` | `number` | 是 | 代扣税率（万分比） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/finance/withdrawals`

listWithdrawals

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Withdrawal`](#withdrawal)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/finance/withdrawals/{withdrawNo}/decide`

审批一笔提现

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `withdrawNo` | path | — | 是 | 提现单号 |

_无字段_

**出参**（`data`）

类型：[`Withdrawal`](#withdrawal)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `withdrawNo` | `string` | 是 | 提现单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `amount` | `number` | 是 | 申请金额（分） |
| `availableBalance` | `number` | 是 | 申请时的可提余额（分）。快照，不是实时值 —— 审批看的是申请那一刻的口径 |
| `bankAccountMasked` | `string` | 是 | 收款账户，展示一律脱敏 |
| `status` | [`#/definitions/WithdrawStatus`](#definitionswithdrawstatus) | 是 | 提现状态。**`APPROVED → PAID` 由渠道回执驱动，运营点不了** |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 审批时间。未审为 null |
| `decidedBy` | `string,null` | 否 | 审批人（STAFF 账号）。未审为 null |
| `remark` | `string,null` | 否 | 驳回原因 / 大额复核说明。原样回商家 B 端 |


#### GET `/ops/refund-split-backs`

待回退分账的售后单（P-12.1.5 / E4）：售后裁决打的 `refundSplitPending` 标记

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AfterSale`](#aftersale)\[\]


#### POST `/ops/refund-split-backs/{asNo}/execute`

执行退款回退分账，**执行后清除该售后单的标记**，否则队列永远消不掉

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `asNo` | path | — | 是 | 售后单号（平台端写法） |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `asNo` | `string` | 是 | 售后单号 |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `amount` | `number` | 是 | 申请退款金额（分）。**不得超过订单实付** —— 校验要跨域查订单。 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `evidenceCount` | `number` | 是 | 举证材料数量（照片/聊天记录） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。口径未定（M4），先存结构 |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：裁决完成但资金域（P-12）尚未接。 留这个标记而不是假装已完成 —— 接资金域时按它补跑。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### GET `/ops/settlements`

listSettlements

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Settlement`](#settlement)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/settlements/{no}/freeze-back`

超时兜底（P-12.1.4）：冻结超过 freezeDays 仍未成功的，解冻回平台

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Settlement`](#settlement)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 结算周期，如 2026-08-上 |
| `orderCount` | `number` | 是 | 本期结算的子订单笔数 |
| `grossAmount` | `number` | 是 | 应结总额（分）= 子订单实付合计 |
| `platformFee` | `number` | 是 | 平台佣金（分）。按「分账内扣」实现（12.1.6 口径待定） |
| `serviceFee` | `number` | 是 | 自提点履约服务费（分，R15） |
| `netAmount` | `number` | 是 | 实付商家（分） |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态。允许的流转见 `SETTLE_TRANSITIONS` |
| `retryCount` | `number` | 是 | 分账指令重试次数（上限见 lib/constants.ts） |
| `failReason` | `string` | 否 | 失败原因。`status=FAILED` 时有值，人工介入据此判断 |
| `frozenAt` | `string` | 是 | 冻结开始时间：超过 freezeDays 未成功就解冻回平台 |
| `createdAt` | `string` | 是 | 结算单生成时间 |


#### POST `/ops/settlements/{no}/split`

下发分账指令（P-12.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Settlement`](#settlement)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 结算周期，如 2026-08-上 |
| `orderCount` | `number` | 是 | 本期结算的子订单笔数 |
| `grossAmount` | `number` | 是 | 应结总额（分）= 子订单实付合计 |
| `platformFee` | `number` | 是 | 平台佣金（分）。按「分账内扣」实现（12.1.6 口径待定） |
| `serviceFee` | `number` | 是 | 自提点履约服务费（分，R15） |
| `netAmount` | `number` | 是 | 实付商家（分） |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态。允许的流转见 `SETTLE_TRANSITIONS` |
| `retryCount` | `number` | 是 | 分账指令重试次数（上限见 lib/constants.ts） |
| `failReason` | `string` | 否 | 失败原因。`status=FAILED` 时有值，人工介入据此判断 |
| `frozenAt` | `string` | 是 | 冻结开始时间：超过 freezeDays 未成功就解冻回平台 |
| `createdAt` | `string` | 是 | 结算单生成时间 |


#### GET `/ops/split-records`

listSplitRecords

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`SplitRecord`](#splitrecord)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


### fulfillment

#### GET `/ops/freight-templates`

`showArchived` 为真时连归档的一起返回（G1：归档不是删除，得看得见）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FreightTemplate`](#freighttemplate)\[\]


#### POST `/ops/freight-templates`

新建/保存运费模板（含超区规则）

**入参**

_无字段_

**出参**（`data`）

类型：[`FreightTemplate`](#freighttemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `freeThreshold` | `number` | 是 | 满多少分免邮；0 = 不免邮 |
| `isDefault` | `boolean` | 是 | 默认模板不可删：删掉之后新商家没有模板可用 |
| `outOfRange` | [`#/definitions/OutOfRangeRule`](#definitionsoutofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/freight-templates/{templateNo}/archive`

归档模板（G1：软删除，不是删除）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `templateNo` | path | — | 是 | 模板单号 |

_无字段_

**出参**（`data`）

类型：[`FreightTemplate`](#freighttemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `freeThreshold` | `number` | 是 | 满多少分免邮；0 = 不免邮 |
| `isDefault` | `boolean` | 是 | 默认模板不可删：删掉之后新商家没有模板可用 |
| `outOfRange` | [`#/definitions/OutOfRangeRule`](#definitionsoutofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/freight-templates/{templateNo}/unarchive`

unarchiveFreightTemplate

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `templateNo` | path | — | 是 | 模板单号 |

_无字段_

**出参**（`data`）

类型：[`FreightTemplate`](#freighttemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `freeThreshold` | `number` | 是 | 满多少分免邮；0 = 不免邮 |
| `isDefault` | `boolean` | 是 | 默认模板不可删：删掉之后新商家没有模板可用 |
| `outOfRange` | [`#/definitions/OutOfRangeRule`](#definitionsoutofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/fulfillment/batches`

listArrivalBatches

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ArrivalBatch`](#arrivalbatch)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/fulfillment/batches/{batchNo}/status`

批次推进（计划→已发车→已到货→已签收），跳步抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `batchNo` | path | — | 是 | 到货批次号 |

_无字段_

**出参**（`data`）

类型：[`ArrivalBatch`](#arrivalbatch)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次单号 |
| `status` | [`#/definitions/BatchStatus`](#definitionsbatchstatus) | 是 | 批次状态。**有序推进不允许跳步**，见 `BATCH_TRANSITIONS` |
| `communityNo` | `string` | 是 | 目的社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 是 | 目的自提点 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `planArriveAt` | `string` | 是 | 计划到货时间 |
| `vehicle` | `string` | 是 | 车次/司机标识；一期人肉填，二期接运力系统 |
| `itemCount` | `number` | 是 | 本批件数 |
| `merchantCount` | `number` | 是 | 涉及的商家数（跨商家拆单后，一个批次会混装多家的货） |


#### GET `/ops/fulfillment/carriers`

listCarriers

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CarrierConfig`](#carrierconfig)\[\]


#### PUT `/ops/fulfillment/carriers/{carrier}`

保存一家运力的接入配置

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `carrier` | path | — | 是 | 承运商标识 |

_无字段_

**出参**（`data`）

类型：[`CarrierConfig`](#carrierconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `carrier` | [`#/definitions/Carrier`](#definitionscarrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/fulfillment/carriers/{carrier}/enabled`

启停一家运力

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `carrier` | path | — | 是 | 承运商标识 |

_无字段_

**出参**（`data`）

类型：[`CarrierConfig`](#carrierconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `carrier` | [`#/definitions/Carrier`](#definitionscarrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/fulfillment/overdue-rule`

getOverdueRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OverdueRule`](#overduerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `action` | [`#/definitions/OverdueAction`](#definitionsoverdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
| `graceHours` | `number` | 是 | 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。 校验在 mock/后端两侧都有，不只是表单提示。 |
| `maxPostpone` | `number` | 是 | 顺延次数上限（action=POSTPONE 时有意义） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/fulfillment/overdue-rule`

逾期规则（P-5.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`OverdueRule`](#overduerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `action` | [`#/definitions/OverdueAction`](#definitionsoverdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
| `graceHours` | `number` | 是 | 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。 校验在 mock/后端两侧都有，不只是表单提示。 |
| `maxPostpone` | `number` | 是 | 顺延次数上限（action=POSTPONE 时有意义） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/fulfillment/redeem`

核销监控与逾期看板（P-5.1.3）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RedeemStat`](#redeemstat)\[\]


#### GET `/ops/fulfillment/sorting`

按自提点汇总分拣（P-5.1.2）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SortingRow`](#sortingrow)\[\]


#### GET `/ops/shipments`

listShipments

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Shipment`](#shipment)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/shipments/{shipmentNo}/waybill`

换运单号（录错了、或承运商重新出单）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `shipmentNo` | path | — | 是 | 运单记录单号（平台侧主键，非快递单号） |

_无字段_

**出参**（`data`）

类型：[`Shipment`](#shipment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `shipmentNo` | `string` | 是 | 运单记录单号（平台侧主键，不是快递单号） |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `carrier` | [`#/definitions/Carrier`](#definitionscarrier) | 是 | 承运商 |
| `waybillNo` | `string` | 是 | 承运商的快递单号 |
| `status` | [`#/definitions/ShipmentStatus`](#definitionsshipmentstatus) | 是 | 快递状态。**`EXCEPTION` 不是终态**，疑难件可能之后又派送成功 |
| `receiver` | `string` | 是 | 收件人姓名 |
| `region` | `string` | 是 | 收件地区（省/市），超区判断看的就是它 |
| `createdAt` | `string` | 是 | 建单时间 |
| `updatedAt` | `string` | 是 | 最后一次轨迹更新时间 |
| `traces` | [`#/definitions/ShipmentTrace`](#definitionsshipmenttrace)\[\] | 是 | 轨迹节点，按时间正序 |


### group

#### GET `/ops/demands`

listDemands

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`DemandOrder`](#demandorder)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/demands/{demandNo}/quotes`

人肉指派商家报价（P-8.2.2，初期靠运营撮合）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `demandNo` | path | — | 是 | 求团需求单号 |

_无字段_

**出参**（`data`）

类型：[`Quote`](#quote)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 报价单号 |
| `demandNo` | `string` | 是 | 所报的需求单。**同一需求同一商家只能有一条** |
| `demandTitle` | `string` | 是 | 需求标题快照 |
| `merchantNo` | `string` | 是 | 报价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `price` | `number` | 是 | 单价（分） |
| `minQty` | `number` | 是 | 起订量 |
| `validTo` | `string` | 是 | 报价有效期。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `string` | 是 | 报价时间 |


#### GET `/ops/groups`

listGroupCampaigns

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`GroupCampaign`](#groupcampaign)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/groups/{no}/audit`

团模板审核（P-8.1.1）：起团人数 ≥2、团购价必须低于原价

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`GroupCampaign`](#groupcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
| `merchantNo` | `string` | 是 | 开团商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `originPrice` | `number` | 是 | 原价（分） |
| `groupPrice` | `number` | 是 | 团购价（分）。**必须低于原价**，否则"团购"是假的 |
| `minCount` | `number` | 是 | 起团人数，必须 ≥ 2（1 个人不叫团） |
| `joined` | `number` | 是 | 已参团人数 |
| `status` | [`#/definitions/GroupStatus`](#definitionsgroupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
| `endAt` | `string` | 是 | 成团截止时间 |
| `createdAt` | `string` | 是 | 开团时间 |


#### POST `/ops/groups/{no}/status`

setGroupStatus

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`GroupCampaign`](#groupcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
| `merchantNo` | `string` | 是 | 开团商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `originPrice` | `number` | 是 | 原价（分） |
| `groupPrice` | `number` | 是 | 团购价（分）。**必须低于原价**，否则"团购"是假的 |
| `minCount` | `number` | 是 | 起团人数，必须 ≥ 2（1 个人不叫团） |
| `joined` | `number` | 是 | 已参团人数 |
| `status` | [`#/definitions/GroupStatus`](#definitionsgroupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
| `endAt` | `string` | 是 | 成团截止时间 |
| `createdAt` | `string` | 是 | 开团时间 |


#### GET `/ops/quotes`

listQuotes

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Quote`](#quote)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/quotes/{no}/breach`

标记毁约（P-8.2.5）：累计进商家信用档案

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Quote`](#quote)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 报价单号 |
| `demandNo` | `string` | 是 | 所报的需求单。**同一需求同一商家只能有一条** |
| `demandTitle` | `string` | 是 | 需求标题快照 |
| `merchantNo` | `string` | 是 | 报价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `price` | `number` | 是 | 单价（分） |
| `minQty` | `number` | 是 | 起订量 |
| `validTo` | `string` | 是 | 报价有效期。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `string` | 是 | 报价时间 |


#### POST `/ops/quotes/{no}/price`

改价（P-8.2.4）：留痕并公示，超过阈值禁止再改

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Quote`](#quote)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 报价单号 |
| `demandNo` | `string` | 是 | 所报的需求单。**同一需求同一商家只能有一条** |
| `demandTitle` | `string` | 是 | 需求标题快照 |
| `merchantNo` | `string` | 是 | 报价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `price` | `number` | 是 | 单价（分） |
| `minQty` | `number` | 是 | 起订量 |
| `validTo` | `string` | 是 | 报价有效期。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `string` | 是 | 报价时间 |


### growth

#### GET `/ops/attribution-rule`

getAttributionRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AttributionRule`](#attributionrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priority` | [`#/definitions/AttrSource`](#definitionsattrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`#/definitions/ConflictPolicy`](#definitionsconflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`#/definitions/NewUserFactor`](#definitionsnewuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/attribution-rule`

归因规则（P-9.1.1/9.1.2/9.1.5）

**入参**

_无字段_

**出参**（`data`）

类型：[`AttributionRule`](#attributionrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priority` | [`#/definitions/AttrSource`](#definitionsattrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`#/definitions/ConflictPolicy`](#definitionsconflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`#/definitions/NewUserFactor`](#definitionsnewuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/attribution-traces`

归因链路查询与审计（P-9.1.3）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`AttributionTrace`](#attributiontrace)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/fission-campaigns`

listFissionCampaigns

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`FissionCampaign`](#fissioncampaign)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/fission-campaigns`

邀请有礼（P-9.2.1）

**入参**

_无字段_

**出参**（`data`）

类型：[`FissionCampaign`](#fissioncampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`#/definitions/RewardType`](#definitionsrewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
| `couponNo` | `string` | 是 | 奖励券模板号（对应营销域的 Coupon） |
| `inviterCount` | `number` | 是 | 邀请人得几张 |
| `inviteeCount` | `number` | 是 | 被邀请人得几张 |
| `enabled` | `boolean` | 是 | 是否启用 |
| `invitedCount` | `number` | 是 | 累计邀请人数 |
| `convertedCount` | `number` | 是 | 其中转化（完成首单）的人数 |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/fission-campaigns/{no}/enabled`

setFissionEnabled

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`FissionCampaign`](#fissioncampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`#/definitions/RewardType`](#definitionsrewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
| `couponNo` | `string` | 是 | 奖励券模板号（对应营销域的 Coupon） |
| `inviterCount` | `number` | 是 | 邀请人得几张 |
| `inviteeCount` | `number` | 是 | 被邀请人得几张 |
| `enabled` | `boolean` | 是 | 是否启用 |
| `invitedCount` | `number` | 是 | 累计邀请人数 |
| `convertedCount` | `number` | 是 | 其中转化（完成首单）的人数 |
| `createdAt` | `string` | 是 | 创建时间 |


### iam

#### GET `/ops/audit-logs`

审计日志（P-1.1.4）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`AuditLog`](#auditlog)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/roles`

listRoles

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RoleDef`](#roledef)\[\]


#### POST `/ops/roles/{role}/perms`

改角色权限

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `role` | path | — | 是 | 角色码 |

_无字段_

**出参**（`data`）

类型：[`RoleDef`](#roledef)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色码 |
| `label` | `string` | 是 | 角色展示名 |
| `builtin` | `boolean` | 是 | 内置角色（超管）：定义就是"全部"，不可编辑 —— 可编辑意味着能把自己降权 |
| `perms` | `string`\[\] | 是 | 权限码集合；'*' 表示全部 |
| `staffCount` | `number` | 是 | 持有该角色的账号数 |


#### GET `/ops/staffs`

listStaffs

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Staff`](#staff)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/staffs/{no}/enabled`

停用/启用（软删除语义，不删账号 —— 审计要能追溯到人）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。决定权限码集合，见 `RoleDef` |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/staffs/{no}/role`

改角色

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。决定权限码集合，见 `RoleDef` |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/staffs/{no}/scope`

数据域授权（P-1.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。决定权限码集合，见 `RoleDef` |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


### marketing

#### GET `/ops/campaigns`

listCampaigns

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Campaign`](#campaign)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/campaigns`

保存活动（P-7.2）

**入参**

_无字段_

**出参**（`data`）

类型：[`Campaign`](#campaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `type` | [`#/definitions/CampaignType`](#definitionscampaigntype) | 是 | 活动类型 |
| `status` | [`#/definitions/CampaignStatus`](#definitionscampaignstatus) | 是 | 活动状态 |
| `startAt` | `string` | 是 | 开始时间 |
| `endAt` | `string` | 是 | 结束时间。须晚于 startAt |
| `position` | `string` | 是 | 投放位置：秒杀场次的重叠校验按位置分组（跨位置可并行） |
| `skuCount` | `number` | 是 | 参与商品数 |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/campaigns/{no}/archive`

archiveCampaign

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Campaign`](#campaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `type` | [`#/definitions/CampaignType`](#definitionscampaigntype) | 是 | 活动类型 |
| `status` | [`#/definitions/CampaignStatus`](#definitionscampaignstatus) | 是 | 活动状态 |
| `startAt` | `string` | 是 | 开始时间 |
| `endAt` | `string` | 是 | 结束时间。须晚于 startAt |
| `position` | `string` | 是 | 投放位置：秒杀场次的重叠校验按位置分组（跨位置可并行） |
| `skuCount` | `number` | 是 | 参与商品数 |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/campaigns/{no}/unarchive`

unarchiveCampaign

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Campaign`](#campaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `type` | [`#/definitions/CampaignType`](#definitionscampaigntype) | 是 | 活动类型 |
| `status` | [`#/definitions/CampaignStatus`](#definitionscampaignstatus) | 是 | 活动状态 |
| `startAt` | `string` | 是 | 开始时间 |
| `endAt` | `string` | 是 | 结束时间。须晚于 startAt |
| `position` | `string` | 是 | 投放位置：秒杀场次的重叠校验按位置分组（跨位置可并行） |
| `skuCount` | `number` | 是 | 参与商品数 |
| `createdAt` | `string` | 是 | 创建时间 |


#### GET `/ops/content-slots`

listContentSlots

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ContentSlot`](#contentslot)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/content-slots/{no}/archive`

archiveSlot

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ContentSlot`](#contentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`#/definitions/SlotKind`](#definitionsslotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |


#### POST `/ops/content-slots/{no}/enabled`

上下线开关（P-7.3.5）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ContentSlot`](#contentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`#/definitions/SlotKind`](#definitionsslotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |


#### POST `/ops/content-slots/{no}/schedule`

定时上下线：下线必须晚于上线

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ContentSlot`](#contentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`#/definitions/SlotKind`](#definitionsslotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |


#### POST `/ops/content-slots/{no}/unarchive`

unarchiveSlot

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ContentSlot`](#contentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`#/definitions/SlotKind`](#definitionsslotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |


#### GET `/ops/coupon-issues`

listCouponIssues

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`CouponIssue`](#couponissue)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/coupons`

listCoupons

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Coupon`](#coupon)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/coupons/{couponNo}/issue`

发券（P-7.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `couponNo` | path | — | 是 | 券单号 |

_无字段_

**出参**（`data`）

类型：[`CouponIssue`](#couponissue)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `issueNo` | `string` | 是 | 发放记录单号 |
| `couponNo` | `string` | 是 | 发放的券模板 |
| `couponName` | `string` | 是 | 券名快照 |
| `target` | [`#/definitions/IssueTarget`](#definitionsissuetarget) | 是 | 发放对象类型 |
| `targetDesc` | `string` | 是 | 定向说明：社区名 / 用户昵称 / 人群名 |
| `count` | `number` | 是 | 本次发放张数 |
| `amount` | `number` | 是 | 本次发放占用的预算（分） |
| `operator` | `string` | 是 | 操作人（STAFF 账号）。**客服也持有发券权限**，留痕不能省 |
| `createdAt` | `string` | 是 | 发放时间 |


#### POST `/ops/coupons/{no}/archive`

archiveCoupon

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Coupon`](#coupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`#/definitions/CouponType`](#definitionscoupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`#/definitions/CouponStatus`](#definitionscouponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
| `value` | `number` | 是 | 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） |
| `threshold` | `number` | 是 | 使用门槛，0 表示无门槛 |
| `validFrom` | `string` | 是 | 生效开始时间 |
| `validTo` | `string` | 是 | 生效结束时间 |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分） |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/coupons/{no}/budget`

调预算（P-7.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Coupon`](#coupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`#/definitions/CouponType`](#definitionscoupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`#/definitions/CouponStatus`](#definitionscouponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
| `value` | `number` | 是 | 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） |
| `threshold` | `number` | 是 | 使用门槛，0 表示无门槛 |
| `validFrom` | `string` | 是 | 生效开始时间 |
| `validTo` | `string` | 是 | 生效结束时间 |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分） |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/coupons/{no}/status`

状态推进（草稿→启用⇄暂停→结束），非法迁移抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Coupon`](#coupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`#/definitions/CouponType`](#definitionscoupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`#/definitions/CouponStatus`](#definitionscouponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
| `value` | `number` | 是 | 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） |
| `threshold` | `number` | 是 | 使用门槛，0 表示无门槛 |
| `validFrom` | `string` | 是 | 生效开始时间 |
| `validTo` | `string` | 是 | 生效结束时间 |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分） |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/coupons/{no}/unarchive`

unarchiveCoupon

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Coupon`](#coupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`#/definitions/CouponType`](#definitionscoupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`#/definitions/CouponStatus`](#definitionscouponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
| `value` | `number` | 是 | 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） |
| `threshold` | `number` | 是 | 使用门槛，0 表示无门槛 |
| `validFrom` | `string` | 是 | 生效开始时间 |
| `validTo` | `string` | 是 | 生效结束时间 |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分） |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `string` | 是 | 创建时间 |


#### GET `/ops/marketing/member-cards`

listMemberCards

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MemberCard`](#membercard)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/marketing/member-cards`

保存会员卡

**入参**

_无字段_

**出参**（`data`）

类型：[`MemberCard`](#membercard)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `cardNo` | `string` | 是 | 会员卡单号 |
| `name` | `string` | 是 | 卡名 |
| `level` | `number` | 是 | 等级，数字越大越高 |
| `priceMonthly` | `number` | 是 | 月费（分） |
| `benefits` | [`#/definitions/Benefit`](#definitionsbenefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`#/definitions/MemberCardStatus`](#definitionsmembercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/marketing/member-cards/{cardNo}/archive`

归档

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `cardNo` | path | — | 是 | 卡号 / 会员卡单号 |

_无字段_

**出参**（`data`）

类型：[`MemberCard`](#membercard)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `cardNo` | `string` | 是 | 会员卡单号 |
| `name` | `string` | 是 | 卡名 |
| `level` | `number` | 是 | 等级，数字越大越高 |
| `priceMonthly` | `number` | 是 | 月费（分） |
| `benefits` | [`#/definitions/Benefit`](#definitionsbenefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`#/definitions/MemberCardStatus`](#definitionsmembercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/marketing/member-cards/{cardNo}/status`

状态推进（草稿→启用⇄暂停→停售），非法迁移抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `cardNo` | path | — | 是 | 卡号 / 会员卡单号 |

_无字段_

**出参**（`data`）

类型：[`MemberCard`](#membercard)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `cardNo` | `string` | 是 | 会员卡单号 |
| `name` | `string` | 是 | 卡名 |
| `level` | `number` | 是 | 等级，数字越大越高 |
| `priceMonthly` | `number` | 是 | 月费（分） |
| `benefits` | [`#/definitions/Benefit`](#definitionsbenefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`#/definitions/MemberCardStatus`](#definitionsmembercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/marketing/member-cards/{cardNo}/unarchive`

unarchiveMemberCard

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `cardNo` | path | — | 是 | 卡号 / 会员卡单号 |

_无字段_

**出参**（`data`）

类型：[`MemberCard`](#membercard)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `cardNo` | `string` | 是 | 会员卡单号 |
| `name` | `string` | 是 | 卡名 |
| `level` | `number` | 是 | 等级，数字越大越高 |
| `priceMonthly` | `number` | 是 | 月费（分） |
| `benefits` | [`#/definitions/Benefit`](#definitionsbenefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`#/definitions/MemberCardStatus`](#definitionsmembercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


### merchant

#### GET `/ops/merchants`

listMerchants

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Merchant`](#merchant)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/merchants/{merchantNo}`

getMerchant

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### POST `/ops/merchants/{merchantNo}/archive`

archiveMerchant

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### PUT `/ops/merchants/{merchantNo}/auth-codes`

改一个商家的类目授权范围

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### POST `/ops/merchants/{merchantNo}/status`

审核推进（DRAFT→SUBMITTED→REVIEWING→APPROVED/REJECTED），非法迁移抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### POST `/ops/merchants/{merchantNo}/unarchive`

unarchiveMerchant

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### POST `/ops/merchants/{merchantNo}/verified`

认证标授予/撤销（P-11.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |


#### POST `/ops/merchants/{merchantNo}/violations`

记一条违规并执行处置

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | — | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Violation`](#violation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `violationNo` | `string` | 是 | 违规记录单号 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `type` | [`#/definitions/ViolationType`](#definitionsviolationtype) | 是 | 违规类型。**只有 `BREACH` 计入 breachCount** |
| `action` | [`#/definitions/ViolationAction`](#definitionsviolationaction) | 是 | 处置动作。`SUSPEND` 会真的把商家状态推到 SUSPENDED |
| `detail` | `string` | 是 | 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 |
| `operator` | `string` | 是 | 处置人（STAFF 账号） |
| `at` | `string` | 是 | 处置时间 |


#### GET `/ops/merchants/auth-codes`

授权码目录

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AuthCode`](#authcode)\[\]


#### GET `/ops/merchants/violations`

listViolations

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Violation`](#violation)\[\]


### message

#### GET `/ops/faqs`

listFaqs

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`FaqEntry`](#faqentry)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/faqs`

帮助中心（P-14.2.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`FaqEntry`](#faqentry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `faqNo` | `string` | 是 | 条目单号 |
| `question` | `string` | 是 | 问题 |
| `answer` | `string` | 是 | 答案正文 |
| `category` | `string` | 是 | 所属分类，用于帮助中心分组 |
| `published` | `boolean` | 是 | 是否已发布。未发布的用户看不到 |
| `views` | `number` | 是 | 浏览量，用来发现「大家其实在问什么」 |


#### POST `/ops/faqs/{no}/published`

setFaqPublished

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`FaqEntry`](#faqentry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `faqNo` | `string` | 是 | 条目单号 |
| `question` | `string` | 是 | 问题 |
| `answer` | `string` | 是 | 答案正文 |
| `category` | `string` | 是 | 所属分类，用于帮助中心分组 |
| `published` | `boolean` | 是 | 是否已发布。未发布的用户看不到 |
| `views` | `number` | 是 | 浏览量，用来发现「大家其实在问什么」 |


#### GET `/ops/msg-templates`

listMsgTemplates

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MsgTemplate`](#msgtemplate)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/msg-templates/{no}/enabled`

setTemplateEnabled

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`MsgTemplate`](#msgtemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `channel` | [`#/definitions/MsgChannel`](#definitionsmsgchannel) | 是 | 触达渠道：订阅消息 / App 推送 / 站内信 |
| `content` | `string` | 是 | 模板正文，含 {占位符} |
| `enabled` | `boolean` | 是 | 是否启用。停用后引用它的推送任务发不出去 |
| `sentCount` | `number` | 是 | 近 30 天发送量 |


#### GET `/ops/notify-quota`

getNotifyQuota

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`NotifyQuota`](#notifyquota)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dailyPerUser` | `number` | 是 | 单用户单日消息上限 |
| `minIntervalHours` | `number` | 是 | 同一模板对同一用户的最小间隔（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/notify-quota`

触达频控（P-14.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`NotifyQuota`](#notifyquota)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dailyPerUser` | `number` | 是 | 单用户单日消息上限 |
| `minIntervalHours` | `number` | 是 | 同一模板对同一用户的最小间隔（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/push-tasks`

listPushTasks

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`PushTask`](#pushtask)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/push-tasks/{no}/cancel`

cancelPushTask

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PushTask`](#pushtask)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务单号 |
| `name` | `string` | 是 | 任务名 |
| `templateNo` | `string` | 是 | 使用的消息模板 |
| `audience` | `string` | 是 | 人群描述，如「近 7 日未下单的老客」 |
| `estimatedReach` | `number` | 是 | 预估触达数。为 0 说明人群是空的，发了等于白发 |
| `status` | [`#/definitions/PushStatus`](#definitionspushstatus) | 是 | 任务状态 |
| `scheduledAt` | `string` | 否 | 计划发送时间。`status=SCHEDULED` 时有值 |
| `createdAt` | `string` | 是 | 创建时间 |


#### POST `/ops/push-tasks/{no}/send`

发送推送（P-14.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PushTask`](#pushtask)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务单号 |
| `name` | `string` | 是 | 任务名 |
| `templateNo` | `string` | 是 | 使用的消息模板 |
| `audience` | `string` | 是 | 人群描述，如「近 7 日未下单的老客」 |
| `estimatedReach` | `number` | 是 | 预估触达数。为 0 说明人群是空的，发了等于白发 |
| `status` | [`#/definitions/PushStatus`](#definitionspushstatus) | 是 | 任务状态 |
| `scheduledAt` | `string` | 否 | 计划发送时间。`status=SCHEDULED` 时有值 |
| `createdAt` | `string` | 是 | 创建时间 |


#### GET `/ops/tickets`

listTickets

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Ticket`](#ticket)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/tickets/{no}/assign`

分派工单（P-14.2.1）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Ticket`](#ticket)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 工单号 |
| `title` | `string` | 是 | 工单标题 |
| `userNickname` | `string` | 是 | 提单用户昵称 |
| `orderNo` | `string` | 否 | 关联订单，可空 |
| `status` | [`#/definitions/TicketStatus`](#definitionsticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
| `assignee` | `string` | 否 | 处理人（员工登录名）；未分派为空 |
| `proxyActions` | `string`\[\] | 是 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么 |
| `createdAt` | `string` | 是 | 提单时间 |


#### POST `/ops/tickets/{no}/close`

closeTicket

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Ticket`](#ticket)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 工单号 |
| `title` | `string` | 是 | 工单标题 |
| `userNickname` | `string` | 是 | 提单用户昵称 |
| `orderNo` | `string` | 否 | 关联订单，可空 |
| `status` | [`#/definitions/TicketStatus`](#definitionsticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
| `assignee` | `string` | 否 | 处理人（员工登录名）；未分派为空 |
| `proxyActions` | `string`\[\] | 是 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么 |
| `createdAt` | `string` | 是 | 提单时间 |


#### POST `/ops/tickets/{no}/proxy-actions`

记录代客操作（P-14.2.3）：谁、对什么、做了什么

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Ticket`](#ticket)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 工单号 |
| `title` | `string` | 是 | 工单标题 |
| `userNickname` | `string` | 是 | 提单用户昵称 |
| `orderNo` | `string` | 否 | 关联订单，可空 |
| `status` | [`#/definitions/TicketStatus`](#definitionsticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
| `assignee` | `string` | 否 | 处理人（员工登录名）；未分派为空 |
| `proxyActions` | `string`\[\] | 是 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么 |
| `createdAt` | `string` | 是 | 提单时间 |


### order

#### GET `/ops/orders`

listOrders

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Order`](#order)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/orders/{orderNo}`

getOrder

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | — | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`#/definitions/FulfillType`](#definitionsfulfilltype) | 是 | 履约方式 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`#/definitions/OrderItem`](#definitionsorderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


#### POST `/ops/orders/{orderNo}/intervene`

人工把订单推到另一个状态

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | — | 是 | 订单单号（按商家拆单后的子订单） |

_无字段_

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`#/definitions/FulfillType`](#definitionsfulfilltype) | 是 | 履约方式 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`#/definitions/OrderItem`](#definitionsorderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


#### GET `/ops/orders/{orderNo}/interventions`

某单的人工干预历史

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | — | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`OrderIntervention`](#orderintervention)\[\]


#### POST `/ops/orders/{orderNo}/proxy-cancel`

代客取消

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | — | 是 | 订单单号（按商家拆单后的子订单） |

_无字段_

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`#/definitions/FulfillType`](#definitionsfulfilltype) | 是 | 履约方式 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`#/definitions/OrderItem`](#definitionsorderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


#### GET `/ops/orders/exceptions`

异常单队列

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`OrderException`](#orderexception)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/orders/parent/{parentNo}`

同一次结算拆出的全部子订单（E3 按商家拆单，详情抽屉要能看到兄弟单）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `parentNo` | path | — | 是 | 父单号（同一次结算拆出的子订单共享） |

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### POST `/ops/orders/proxy`

代客下单（客服电话代下）

**入参**

_无字段_

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`#/definitions/FulfillType`](#definitionsfulfilltype) | 是 | 履约方式 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`#/definitions/OrderItem`](#definitionsorderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


### payment

#### GET `/ops/payments/close-rule`

getCloseRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CloseRule`](#closerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `unpaidMinutes` | `number` | 是 | 未支付订单多少分钟后自动关单 |
| `remindBeforeMinutes` | `number` | 是 | 关单前多少分钟提醒用户（0 = 不提醒） |
| `autoRefundOnLateCallback` | `boolean` | 是 | 关单后仍收到渠道支付回调时是否自动退款。 关掉它意味着这笔钱要人工处理 —— 但至少不会静默退掉一笔本可以补单的钱。 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### PUT `/ops/payments/close-rule`

关单策略（P-4.2.3）

**入参**

_无字段_

**出参**（`data`）

类型：[`CloseRule`](#closerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `unpaidMinutes` | `number` | 是 | 未支付订单多少分钟后自动关单 |
| `remindBeforeMinutes` | `number` | 是 | 关单前多少分钟提醒用户（0 = 不提醒） |
| `autoRefundOnLateCallback` | `boolean` | 是 | 关单后仍收到渠道支付回调时是否自动退款。 关掉它意味着这笔钱要人工处理 —— 但至少不会静默退掉一笔本可以补单的钱。 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/payments/recon-diffs`

对账差异列表（P-4.2.1）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ReconDiff`](#recondiff)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/payments/recon-diffs/{diffNo}/ignore`

忽略一条差异（如渠道手续费导致的分位差）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `diffNo` | path | — | 是 | 对账差异单号 |

_无字段_

**出参**（`data`）

类型：[`ReconDiff`](#recondiff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `diffNo` | `string` | 是 | 差异单号 |
| `billDate` | `string` | 是 | 对账日期（渠道账单的账期），YYYY-MM-DD |
| `channel` | [`#/definitions/PayChannel`](#definitionspaychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`#/definitions/ReconDiffType`](#definitionsrecondifftype) | 是 | 差异类型。**三类的处置方式完全不同**，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`#/definitions/ReconStatus`](#definitionsreconstatus) | 是 | 处置状态 |
| `resolution` | `string,null` | 否 | 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 |
| `recoveredOrderNo` | `string,null` | 否 | 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） |
| `createdAt` | `string` | 是 | 差异产生时间 |
| `resolvedAt` | `string,null` | 否 | 处置时间。未处置为 null |
| `resolvedBy` | `string,null` | 否 | 处置人（STAFF 账号）。未处置为 null |


#### POST `/ops/payments/recon-diffs/{diffNo}/resolve`

处置一条差异（P-4.2.1 / 4.2.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `diffNo` | path | — | 是 | 对账差异单号 |

_无字段_

**出参**（`data`）

类型：[`ReconDiff`](#recondiff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `diffNo` | `string` | 是 | 差异单号 |
| `billDate` | `string` | 是 | 对账日期（渠道账单的账期），YYYY-MM-DD |
| `channel` | [`#/definitions/PayChannel`](#definitionspaychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`#/definitions/ReconDiffType`](#definitionsrecondifftype) | 是 | 差异类型。**三类的处置方式完全不同**，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`#/definitions/ReconStatus`](#definitionsreconstatus) | 是 | 处置状态 |
| `resolution` | `string,null` | 否 | 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 |
| `recoveredOrderNo` | `string,null` | 否 | 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） |
| `createdAt` | `string` | 是 | 差异产生时间 |
| `resolvedAt` | `string,null` | 否 | 处置时间。未处置为 null |
| `resolvedBy` | `string,null` | 否 | 处置人（STAFF 账号）。未处置为 null |


### product

#### GET `/ops/categories`

类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Category`](#category)\[\]


#### POST `/ops/categories`

saveCategory

**入参**

_无字段_

**出参**（`data`）

类型：[`Category`](#category)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `categoryNo` | `string` | 是 | 类目单号 |
| `name` | `string` | 是 | 类目名（运营侧展示用中文名） |
| `parentNo` | `string` | 否 | 顶级为空 |
| `level` | `number` | 是 | 1–3，见 MAX_CATEGORY_LEVEL |
| `template` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### POST `/ops/categories/{no}/archive`

有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Category`](#category)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `categoryNo` | `string` | 是 | 类目单号 |
| `name` | `string` | 是 | 类目名（运营侧展示用中文名） |
| `parentNo` | `string` | 否 | 顶级为空 |
| `level` | `number` | 是 | 1–3，见 MAX_CATEGORY_LEVEL |
| `template` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### POST `/ops/categories/{no}/unarchive`

unarchiveCategory

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Category`](#category)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `categoryNo` | `string` | 是 | 类目单号 |
| `name` | `string` | 是 | 类目名（运营侧展示用中文名） |
| `parentNo` | `string` | 否 | 顶级为空 |
| `level` | `number` | 是 | 1–3，见 MAX_CATEGORY_LEVEL |
| `template` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### GET `/ops/skus`

listSkus

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Sku`](#sku)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/skus/{no}/audit`

商品审核（P-3.2.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Sku`](#sku)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 商品单号 |
| `title` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`#/definitions/SkuStatus`](#definitionsskustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`#/definitions/Partial<Record<Market,number>>`](#definitionspartialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
| `stock` | `number` | 是 | 现货库存 |
| `presaleQuota` | `number` | 是 | 预售额度（P-3.3.1）。0 = 不做预售 |
| `soldCount` | `number` | 是 | 已售（预售期内） |
| `cutoffAt` | `string` | 否 | 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 |
| `arriveAt` | `string` | 否 | 到货时间（与履约批次对齐） |
| `createdAt` | `string` | 是 | 创建时间 |
| `reason` | `string` | 否 | 驳回/强制下架原因，原样进商家 B 端 |


#### POST `/ops/skus/{no}/force-off`

强制下架（P-3.2.3）：必须带原因，原样进商家 B 端

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Sku`](#sku)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 商品单号 |
| `title` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`#/definitions/SkuStatus`](#definitionsskustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`#/definitions/Partial<Record<Market,number>>`](#definitionspartialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
| `stock` | `number` | 是 | 现货库存 |
| `presaleQuota` | `number` | 是 | 预售额度（P-3.3.1）。0 = 不做预售 |
| `soldCount` | `number` | 是 | 已售（预售期内） |
| `cutoffAt` | `string` | 否 | 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 |
| `arriveAt` | `string` | 否 | 到货时间（与履约批次对齐） |
| `createdAt` | `string` | 是 | 创建时间 |
| `reason` | `string` | 否 | 驳回/强制下架原因，原样进商家 B 端 |


#### POST `/ops/skus/{no}/presale`

预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Sku`](#sku)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 商品单号 |
| `title` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`#/definitions/SkuStatus`](#definitionsskustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`#/definitions/Partial<Record<Market,number>>`](#definitionspartialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
| `stock` | `number` | 是 | 现货库存 |
| `presaleQuota` | `number` | 是 | 预售额度（P-3.3.1）。0 = 不做预售 |
| `soldCount` | `number` | 是 | 已售（预售期内） |
| `cutoffAt` | `string` | 否 | 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 |
| `arriveAt` | `string` | 否 | 到货时间（与履约批次对齐） |
| `createdAt` | `string` | 是 | 创建时间 |
| `reason` | `string` | 否 | 驳回/强制下架原因，原样进商家 B 端 |


#### GET `/ops/skus/oversell`

超卖告警（P-3.3.3）：已售 > 预售额度

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Sku`](#sku)\[\]


### review

#### GET `/ops/review-appeals`

listReviewAppeals

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ReviewAppeal`](#reviewappeal)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/review-appeals/{no}/decide`

申诉裁决（P-13.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ReviewAppeal`](#reviewappeal)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `appealNo` | `string` | 是 | 申诉单号 |
| `reviewNo` | `string` | 是 | 被申诉的评价 |
| `merchantNo` | `string` | 是 | 申诉方商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `reason` | `string` | 是 | 商家的申诉理由 |
| `evidenceCount` | `number` | 是 | 举证材料数量（截图/聊天记录） |
| `status` | [`#/definitions/AppealStatus`](#definitionsappealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），DISMISSED = 驳回申诉（差评保留） |
| `submittedAt` | `string` | 是 | 申诉提交时间 |
| `verdict` | `string` | 否 | 裁决说明：无论支持还是驳回都必须写，商家会看到 |


#### GET `/ops/review-score-config`

getScoreConfig

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ScoreConfig`](#scoreconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `weightProduct` | `number` | 是 | 三维权重，百分比，**和必须为 100** |
| `weightFulfill` | `number` | 是 | 履约维度权重（百分比） |
| `weightService` | `number` | 是 | 服务维度权重（百分比） |
| `newMerchantProtectDays` | `number` | 是 | 新商家保护期（天）：期内不展示低于阈值的均分，避免首单差评直接判死 |
| `decayHalfLifeDays` | `number` | 是 | 时效衰减半衰期（天）：越久远的评价权重越低 |
| `updatedAt` | `string` | 是 | 最后修改时间。改参数会**改变历史评价的呈现**，必须留痕 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/review-score-config`

评分算法参数（P-13.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`ScoreConfig`](#scoreconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `weightProduct` | `number` | 是 | 三维权重，百分比，**和必须为 100** |
| `weightFulfill` | `number` | 是 | 履约维度权重（百分比） |
| `weightService` | `number` | 是 | 服务维度权重（百分比） |
| `newMerchantProtectDays` | `number` | 是 | 新商家保护期（天）：期内不展示低于阈值的均分，避免首单差评直接判死 |
| `decayHalfLifeDays` | `number` | 是 | 时效衰减半衰期（天）：越久远的评价权重越低 |
| `updatedAt` | `string` | 是 | 最后修改时间。改参数会**改变历史评价的呈现**，必须留痕 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/reviews`

listReviews

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Review`](#review)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/reviews/{no}/decide`

审核裁决（P-13.1.1/13.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Review`](#review)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reviewNo` | `string` | 是 | 评价单号 |
| `orderNo` | `string` | 是 | 关联订单。一单一评 |
| `merchantNo` | `string` | 是 | 被评价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `authorNickname` | `string` | 是 | 评价人昵称 |
| `score` | `number` | 是 | 总评 1–5 |
| `scoreProduct` | `number` | 是 | 三维分（商品 / 履约 / 服务），评分算法按权重合成 |
| `scoreFulfill` | `number` | 是 | 履约分 1–5（快慢、包装、缺损） |
| `scoreService` | `number` | 是 | 服务分 1–5（沟通、售后态度） |
| `content` | `string` | 是 | 评价正文 |
| `imageCount` | `number` | 是 | 配图数量。列表页不下发图本身，点进详情才取 |
| `status` | [`#/definitions/ReviewStatus`](#definitionsreviewstatus) | 是 | 审核状态 |
| `riskFlags` | [`#/definitions/RiskFlag`](#definitionsriskflag)\[\] | 是 | 命中的刷评信号。**是线索不是结论** —— 命中不等于判定 |
| `createdAt` | `string` | 是 | 评价提交时间 |
| `reason` | `string` | 否 | 驳回原因：与门店审核同一条规矩 —— 驳回必须写清楚 |


### risk

#### GET `/ops/blacklists`

listBlacklists

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`BlacklistEntry`](#blacklistentry)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/blacklists`

拉黑（P-16.2.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`BlacklistEntry`](#blacklistentry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `blackNo` | `string` | 是 | 黑名单单号 |
| `subjectType` | [`#/definitions/SubjectType`](#definitionssubjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`#/definitions/BlacklistAppealStatus`](#definitionsblacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
| `appealReason` | `string` | 否 | 被拉黑者提交的申诉理由 |
| `appealVerdict` | `string` | 否 | 申诉裁决说明 |
| `active` | `boolean` | 是 | 是否生效中。到期或申诉通过后置 false，记录保留 |
| `createdAt` | `string` | 是 | 拉黑时间 |


#### POST `/ops/blacklists/{no}/appeal`

解禁申诉裁决：接受则解除拉黑，两种结论都要写说明

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`BlacklistEntry`](#blacklistentry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `blackNo` | `string` | 是 | 黑名单单号 |
| `subjectType` | [`#/definitions/SubjectType`](#definitionssubjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`#/definitions/BlacklistAppealStatus`](#definitionsblacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
| `appealReason` | `string` | 否 | 被拉黑者提交的申诉理由 |
| `appealVerdict` | `string` | 否 | 申诉裁决说明 |
| `active` | `boolean` | 是 | 是否生效中。到期或申诉通过后置 false，记录保留 |
| `createdAt` | `string` | 是 | 拉黑时间 |


#### GET `/ops/risk-events`

listRiskEvents

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`RiskEvent`](#riskevent)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/risk-events/{no}/decide`

事件处置（P-16.2.1–3）：确认或排除，都要写结论

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | — | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`RiskEvent`](#riskevent)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `eventNo` | `string` | 是 | 风险事件单号 |
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | `USER` \| `MERCHANT` \| `DEVICE` | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
| `signals` | `string`\[\] | 是 | 命中的信号。**不给分值** —— 分值口径要等有真实样本后由风控定， 现在编一个看起来很准的分数，只会让人照着它做决定。 |
| `refs` | `string`\[\] | 是 | 关联证据：订单号 / 归因链路号 |
| `status` | [`#/definitions/RiskStatus`](#definitionsriskstatus) | 是 | 处置状态 |
| `createdAt` | `string` | 是 | 事件产生时间 |
| `verdict` | `string` | 否 | 处置结论 |


#### GET `/ops/risk-rules`

listRiskRules

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RiskRule`](#riskrule)\[\]


#### POST `/ops/risk-rules/{type}`

拦截规则（P-16.2.5）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `type` | path | — | 是 | 类型筛选，取值见对应枚举 |

_无字段_

**出参**（`data`）

类型：[`RiskRule`](#riskrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 适用的风险类型，一类一条 |
| `threshold` | `number` | 是 | 触发阈值（如同设备下单数、同 IP 邀请数、30 天退款次数），必须 > 0 |
| `autoBlock` | `boolean` | 是 | 命中后是否自动拦截；false = 只记事件等人工 |
| `updatedAt` | `string` | 是 | 最后修改时间 |


### store

#### GET `/ops/stores/acquisition`

门店获客效果（P-10.1.4）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`StoreAcquisition`](#storeacquisition)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/stores/audits`

listStoreAudits

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`StorePageAudit`](#storepageaudit)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/stores/audits/{auditNo}/decide`

审核裁决（P-10.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `auditNo` | path | — | 是 | 审核单号 |

_无字段_

**出参**（`data`）

类型：[`StorePageAudit`](#storepageaudit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `auditNo` | `string` | 是 | 审核单号 |
| `merchantNo` | `string` | 是 | 提审商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `kind` | [`#/definitions/StoreAuditKind`](#definitionsstoreauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL 或公告文本 |
| `status` | [`#/definitions/StoreAuditStatus`](#definitionsstoreauditstatus) | 是 | 审核状态 |
| `hits` | `string`\[\] | 是 | 机审命中的敏感词/风险项，随数据下发。 人审要看到「机器为什么标它」，否则只能凭感觉判，同一类内容两个人两个结论。 |
| `submittedAt` | `string` | 是 | 提审时间 |
| `reason` | `string` | 否 | 驳回原因：**原样出现在商家 B 端**，所以驳回必须填 |


#### GET `/ops/stores/qrcodes`

店铺码（P-10.1.3），供 BD 批量导出去印刷

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`StoreQrcode`](#storeqrcode)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/stores/templates`

listStoreTemplates

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`StoreTemplate`](#storetemplate)\[\]


#### POST `/ops/stores/templates`

新建/保存模板

**入参**

_无字段_

**出参**（`data`）

类型：[`StoreTemplate`](#storetemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | `GRID` \| `LIST` \| `FEATURE` | 是 | 商品区排布 |
| `sections` | [`#/definitions/TemplateSection`](#definitionstemplatesection)\[\] | 是 | 板块开关列表 |
| `enabled` | `boolean` | 是 | 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 |
| `isDefault` | `boolean` | 是 | 默认模板：新店开出来就用它，所以停用不了 |
| `usedByCount` | `number` | 是 | 正在使用该模板的店铺数（只读） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/stores/templates/{templateNo}/enabled`

启用/停用模板

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `templateNo` | path | — | 是 | 模板单号 |

_无字段_

**出参**（`data`）

类型：[`StoreTemplate`](#storetemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | `GRID` \| `LIST` \| `FEATURE` | 是 | 商品区排布 |
| `sections` | [`#/definitions/TemplateSection`](#definitionstemplatesection)\[\] | 是 | 板块开关列表 |
| `enabled` | `boolean` | 是 | 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 |
| `isDefault` | `boolean` | 是 | 默认模板：新店开出来就用它，所以停用不了 |
| `usedByCount` | `number` | 是 | 正在使用该模板的店铺数（只读） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


### system

#### GET `/ops/appearance`

getAppearance

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AppearanceConfig`](#appearanceconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `defaultSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 否 | 节日皮肤：留空表示不启用 |
| `festivalFrom` | `string` | 否 | 节日皮肤生效开始时间。启用节日皮肤时必填 |
| `festivalTo` | `string` | 否 | 节日皮肤生效结束时间 |
| `fallbackLang` | `string` | 是 | 语言回落规则（R9）：缺译时回落到哪个语言 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/appearance`

皮肤下发（P-17.1.1 / C-TH-05）

**入参**

_无字段_

**出参**（`data`）

类型：[`AppearanceConfig`](#appearanceconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `defaultSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 否 | 节日皮肤：留空表示不启用 |
| `festivalFrom` | `string` | 否 | 节日皮肤生效开始时间。启用节日皮肤时必填 |
| `festivalTo` | `string` | 否 | 节日皮肤生效结束时间 |
| `fallbackLang` | `string` | 是 | 语言回落规则（R9）：缺译时回落到哪个语言 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/feature-flags`

listFeatureFlags

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FeatureFlag`](#featureflag)\[\]


#### POST `/ops/feature-flags/{key}`

开关与灰度（P-17.1.5）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `key` | path | — | 是 | 开关标识（FeatureFlag.key） |

_无字段_

**出参**（`data`）

类型：[`FeatureFlag`](#featureflag)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `key` | `string` | 是 | 开关标识，代码里读的就是它 |
| `name` | `string` | 是 | 开关展示名 |
| `enabled` | `boolean` | 是 | 总开关。关掉时 `rolloutPercent` 不生效 |
| `rolloutPercent` | `number` | 是 | 灰度比例 0–100 |
| `updatedAt` | `string` | 是 | 最后修改时间 |


#### GET `/ops/markets`

listMarkets

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MarketConfig`](#marketconfig)\[\]


#### POST `/ops/markets/{code}`

市场与汇率（P-17.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `code` | path | — | 是 | 取货码 / 核销码 |

_无字段_

**出参**（`data`）

类型：[`MarketConfig`](#marketconfig)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 市场编码，如 `CN` / `SG` |
| `name` | `string` | 是 | 市场展示名 |
| `currency` | `string` | 是 | 结算与展示货币，如 `CNY` |
| `timezone` | `string` | 是 | 时区标识，如 `Asia/Shanghai`。截单时间按它切分自然日 |
| `rate` | `number` | 是 | 对基准货币的汇率。 ⚠️ 基准货币（CNY）恒为 1 且**不可改** —— 改了整套价格换算的原点就没了。 |
| `enabled` | `boolean` | 是 | 是否开放该市场。关掉后该市场的商品不再售卖 |


#### GET `/ops/rule-texts`

getRuleTexts

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RuleTexts`](#ruletexts)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `refund` | `string` | 是 | 退款规则文案，C 端售后页展示 |
| `pickup` | `string` | 是 | 自提规则文案，C 端下单与取货页展示 |
| `weighDiff` | `string` | 是 | 称重差价规则文案，生鲜订单展示 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/rule-texts`

规则文案（P-17.1.4）

**入参**

_无字段_

**出参**（`data`）

类型：[`RuleTexts`](#ruletexts)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `refund` | `string` | 是 | 退款规则文案，C 端售后页展示 |
| `pickup` | `string` | 是 | 自提规则文案，C 端下单与取货页展示 |
| `weighDiff` | `string` | 是 | 称重差价规则文案，生鲜订单展示 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


---

## 数据模型

### AfterSale

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `asNo` | `string` | 是 | 售后单号 |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `amount` | `number` | 是 | 申请退款金额（分）。**不得超过订单实付** —— 校验要跨域查订单。 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `evidenceCount` | `number` | 是 | 举证材料数量（照片/聊天记录） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。口径未定（M4），先存结构 |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：裁决完成但资金域（P-12）尚未接。 留这个标记而不是假装已完成 —— 接资金域时按它补跑。 |
| `createdAt` | `string` | 是 | 售后发起时间 |

### AppearanceConfig

全局外观与语言（P-17.1.1 / 17.1.2）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `defaultSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`#/definitions/ThemeKey`](#definitionsthemekey) | 否 | 节日皮肤：留空表示不启用 |
| `festivalFrom` | `string` | 否 | 节日皮肤生效开始时间。启用节日皮肤时必填 |
| `festivalTo` | `string` | 否 | 节日皮肤生效结束时间 |
| `fallbackLang` | `string` | 是 | 语言回落规则（R9）：缺译时回落到哪个语言 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### ArrivalBatch

到货批次与配车（P-5.1.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次单号 |
| `status` | [`#/definitions/BatchStatus`](#definitionsbatchstatus) | 是 | 批次状态。**有序推进不允许跳步**，见 `BATCH_TRANSITIONS` |
| `communityNo` | `string` | 是 | 目的社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 是 | 目的自提点 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `planArriveAt` | `string` | 是 | 计划到货时间 |
| `vehicle` | `string` | 是 | 车次/司机标识；一期人肉填，二期接运力系统 |
| `itemCount` | `number` | 是 | 本批件数 |
| `merchantCount` | `number` | 是 | 涉及的商家数（跨商家拆单后，一个批次会混装多家的货） |

### AttributionRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priority` | [`#/definitions/AttrSource`](#definitionsattrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`#/definitions/ConflictPolicy`](#definitionsconflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`#/definitions/NewUserFactor`](#definitionsnewuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### AttributionTrace

一条归因链路（P-9.1.3）。风控从这里看"这个人是怎么被带进来的"。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `traceNo` | `string` | 是 | 归因链路单号 |
| `userNickname` | `string` | 是 | 被归因的用户昵称 |
| `source` | [`#/definitions/AttrSource`](#definitionsattrsource) | 是 | 归因来源 |
| `sourceRef` | `string` | 是 | 归因载体：店铺码 / 邀请人昵称 / 渠道名 |
| `attributedAt` | `string` | 是 | 归因发生时间 |
| `orderNo` | `string` | 否 | 首单订单号；还没下单则为空 |
| `conflictWith` | `string` | 否 | 与之冲突的另一次归因（B1 的现实场景） |
| `riskSignals` | `string`\[\] | 是 | 命中的风控信号（与风险事件同一套口径） |

### AuditLog

审计日志（P-1.1.4）。只读不可删（合规）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `logNo` | `string` | 是 | 日志单号 |
| `at` | `string` | 是 | 操作时间 |
| `operator` | `string` | 是 | 操作人（STAFF 账号） |
| `action` | `string` | 是 | 动作描述，如「授予角色权限」「停用员工」 |
| `target` | `string` | 是 | 操作对象，如员工号 / 角色名 |
| `detail` | `string` | 是 | 详细内容，含变更前后值 |
| `critical` | `boolean` | 是 | 是否涉及高危权限（矩阵 §2.3 的那批码） |

### AuthCode

类目授权码。 它与类目树是**多对一**：`CAT111 叶菜`、`CAT112 根茎菜` 都要 `FRESH_VEG`。 按码授权而不是按类目节点授权，是因为类目树会重构，而"能不能卖菜"这件事不会。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 授权码，如 `FRESH_VEG`。**按码授权而不是按类目节点** —— 类目树会重构，能不能卖菜不会 |
| `name` | `string` | 是 | 授权码展示名 |
| `requiredQualification` | `string` | 否 | 需要的资质名。为空表示无门槛类目 |

### BlacklistEntry

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `blackNo` | `string` | 是 | 黑名单单号 |
| `subjectType` | [`#/definitions/SubjectType`](#definitionssubjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`#/definitions/BlacklistAppealStatus`](#definitionsblacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
| `appealReason` | `string` | 否 | 被拉黑者提交的申诉理由 |
| `appealVerdict` | `string` | 否 | 申诉裁决说明 |
| `active` | `boolean` | 是 | 是否生效中。到期或申诉通过后置 false，记录保留 |
| `createdAt` | `string` | 是 | 拉黑时间 |

### Campaign

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `type` | [`#/definitions/CampaignType`](#definitionscampaigntype) | 是 | 活动类型 |
| `status` | [`#/definitions/CampaignStatus`](#definitionscampaignstatus) | 是 | 活动状态 |
| `startAt` | `string` | 是 | 开始时间 |
| `endAt` | `string` | 是 | 结束时间。须晚于 startAt |
| `position` | `string` | 是 | 投放位置：秒杀场次的重叠校验按位置分组（跨位置可并行） |
| `skuCount` | `number` | 是 | 参与商品数 |
| `createdAt` | `string` | 是 | 创建时间 |

### CarrierConfig

一家承运商的接入配置。 ⚠️ 这一页配错的后果不是"显示不对"，而是**订单发不出去**： 全停、启用没配密钥的、或者停掉还有在途单的那家，都会让快递链路当场断掉。 所以规则全部落在 mock 层，页面写不出违规配置。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `carrier` | [`#/definitions/Carrier`](#definitionscarrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Category

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `categoryNo` | `string` | 是 | 类目单号 |
| `name` | `string` | 是 | 类目名（运营侧展示用中文名） |
| `parentNo` | `string` | 否 | 顶级为空 |
| `level` | `number` | 是 | 1–3，见 MAX_CATEGORY_LEVEL |
| `template` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |

### CloseRule

关单策略（P-4.2.3）。 ⚠️ 这份配置与掉单**直接因果**：关单时限设得越短，"用户正在付款、订单已被关掉" 的窗口就越大，而那正是 CHANNEL_ONLY 差异的主要来源。所以两者放同一页。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `unpaidMinutes` | `number` | 是 | 未支付订单多少分钟后自动关单 |
| `remindBeforeMinutes` | `number` | 是 | 关单前多少分钟提醒用户（0 = 不提醒） |
| `autoRefundOnLateCallback` | `boolean` | 是 | 关单后仍收到渠道支付回调时是否自动退款。 关掉它意味着这笔钱要人工处理 —— 但至少不会静默退掉一笔本可以补单的钱。 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Community

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `communityNo` | `string` | 是 | 社区单号。平台端数据域裁剪的主键之一 |
| `name` | `string` | 是 | 社区名（小区名） |
| `city` | `string` | 是 | 所属城市 |
| `grid` | `string` | 是 | 网格：城市与社区之间的运营划分单位 |
| `opened` | `boolean` | 是 | 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 |
| `fenceRadius` | `number` | 是 | 覆盖围栏半径，米（P-2.1.3） |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |

### ContentSlot

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`#/definitions/SlotKind`](#definitionsslotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |

### Coupon

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`#/definitions/CouponType`](#definitionscoupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`#/definitions/CouponStatus`](#definitionscouponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
| `value` | `number` | 是 | 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） |
| `threshold` | `number` | 是 | 使用门槛，0 表示无门槛 |
| `validFrom` | `string` | 是 | 生效开始时间 |
| `validTo` | `string` | 是 | 生效结束时间 |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分） |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `string` | 是 | 创建时间 |

### CouponIssue

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `issueNo` | `string` | 是 | 发放记录单号 |
| `couponNo` | `string` | 是 | 发放的券模板 |
| `couponName` | `string` | 是 | 券名快照 |
| `target` | [`#/definitions/IssueTarget`](#definitionsissuetarget) | 是 | 发放对象类型 |
| `targetDesc` | `string` | 是 | 定向说明：社区名 / 用户昵称 / 人群名 |
| `count` | `number` | 是 | 本次发放张数 |
| `amount` | `number` | 是 | 本次发放占用的预算（分） |
| `operator` | `string` | 是 | 操作人（STAFF 账号）。**客服也持有发券权限**，留痕不能省 |
| `createdAt` | `string` | 是 | 发放时间 |

### DashboardKpi

KPI 卡（金额为最小货币单位整数）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `gmv` | `number` | 是 | 成交额（最小货币单位整数） |
| `orderCount` | `number` | 是 | 订单数 |
| `avgOrderValue` | `number` | 是 | 客单价 |
| `pendingMerchantAudit` | `number` | 是 | 待审商家数（P-11.1.1 提审队列） |
| `pendingAfterSale` | `number` | 是 | 待处理售后（P-6.1.1 工单池） |
| `redeemRate` | `number` | 是 | 今日核销率（P-5.1.3 核销监控），0–1 |

### DemandOrder

邻里求团需求单（P-8.2）。发起人是 C 端用户，不是商家。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `demandNo` | `string` | 是 | 需求单号 |
| `title` | `string` | 是 | 需求标题。发起时**商品还不存在**，只有这句话 |
| `initiatorNickname` | `string` | 是 | 发起人昵称。**是 C 端用户，不是商家** |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `plusOneCount` | `number` | 是 | +1 人数（想要的人有多少） |
| `status` | [`#/definitions/DemandStatus`](#definitionsdemandstatus) | 是 | 需求单状态 |
| `quoteCount` | `number` | 是 | 已收到的报价数 |
| `createdAt` | `string` | 是 | 发起时间 |

### FaqEntry

帮助中心条目（P-14.2.4）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `faqNo` | `string` | 是 | 条目单号 |
| `question` | `string` | 是 | 问题 |
| `answer` | `string` | 是 | 答案正文 |
| `category` | `string` | 是 | 所属分类，用于帮助中心分组 |
| `published` | `boolean` | 是 | 是否已发布。未发布的用户看不到 |
| `views` | `number` | 是 | 浏览量，用来发现「大家其实在问什么」 |

### FastRefundRule

极速退阈值（P-6.1.2）：满足条件的小额售后由系统自动通过，不占人工。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enabled` | `boolean` | 是 | 总开关。关掉后所有小额售后都走人工 |
| `maxAmount` | `number` | 是 | 金额上限（分），必须 > 0 |
| `withinHours` | `number` | 是 | 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） |
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### FeatureFlag

开关与灰度（P-17.1.5）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `key` | `string` | 是 | 开关标识，代码里读的就是它 |
| `name` | `string` | 是 | 开关展示名 |
| `enabled` | `boolean` | 是 | 总开关。关掉时 `rolloutPercent` 不生效 |
| `rolloutPercent` | `number` | 是 | 灰度比例 0–100 |
| `updatedAt` | `string` | 是 | 最后修改时间 |

### FeeRule

费率配置（P-12.1.7 / 12.1.8 / 12.1.4）。全部万分比。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `byTrafficSource` | [`#/definitions/Record<TrafficSource,number>`](#definitionsrecordtrafficsourcenumber) | 是 | 按流量来源分档的平台佣金费率（R16）。 ⚠️ `MERCHANT_OWNED`（商家自带客流）**建议 0** —— 商家自己把客人带来的单还抽佣， 商家就会把客人带去别处成交（ADR-004 的增长模型立不住）。口径未定，故可配。 |
| `pickupServiceFeeRate` | `number` | 是 | 自提点履约服务费默认费率（R15）；自提点自己配了就用它自己的 |
| `freezeDays` | `number` | 是 | 超时兜底天数（12.1.4）：冻结超过它仍未分账成功，解冻回平台 |
| `updatedAt` | `string` | 是 | 最后修改时间。**改费率不影响已生成的结算单** |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### FissionCampaign

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`#/definitions/RewardType`](#definitionsrewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
| `couponNo` | `string` | 是 | 奖励券模板号（对应营销域的 Coupon） |
| `inviterCount` | `number` | 是 | 邀请人得几张 |
| `inviteeCount` | `number` | 是 | 被邀请人得几张 |
| `enabled` | `boolean` | 是 | 是否启用 |
| `invitedCount` | `number` | 是 | 累计邀请人数 |
| `convertedCount` | `number` | 是 | 其中转化（完成首单）的人数 |
| `createdAt` | `string` | 是 | 创建时间 |

### FreightTemplate

运费模板。 重量一律用**克**、金额一律用**分** —— 两个都是整数，避免 0.1kg + 0.2kg 这类浮点误差 在算钱的地方冒出来。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `freeThreshold` | `number` | 是 | 满多少分免邮；0 = 不免邮 |
| `isDefault` | `boolean` | 是 | 默认模板不可删：删掉之后新商家没有模板可用 |
| `outOfRange` | [`#/definitions/OutOfRangeRule`](#definitionsoutofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### FunnelStep

获客漏斗（P-16.1.4 扫码→进店→注册→首单）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `step` | `SCAN` \| `ENTER_STORE` \| `REGISTER` \| `FIRST_ORDER` | 是 | 漏斗环节：扫码 → 进店 → 注册 → 首单 |
| `count` | `number` | 是 | 该环节人数 |

### GroupCampaign

商家团（P-8.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
| `merchantNo` | `string` | 是 | 开团商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `originPrice` | `number` | 是 | 原价（分） |
| `groupPrice` | `number` | 是 | 团购价（分）。**必须低于原价**，否则"团购"是假的 |
| `minCount` | `number` | 是 | 起团人数，必须 ≥ 2（1 个人不叫团） |
| `joined` | `number` | 是 | 已参团人数 |
| `status` | [`#/definitions/GroupStatus`](#definitionsgroupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
| `endAt` | `string` | 是 | 成团截止时间 |
| `createdAt` | `string` | 是 | 开团时间 |

### InvoiceRequest

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`#/definitions/InvoiceTitleType`](#definitionsinvoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`#/definitions/InvoiceStatus`](#definitionsinvoicestatus) | 是 | 开票状态 |
| `serialNo` | `string,null` | 否 | 开票后的发票流水号 |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 处理时间。未处理为 null |
| `remark` | `string,null` | 否 | 驳回原因。原样回商家 B 端 |

### LoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `merchantNo` | `string` | 否 | 商家运营（BD）等受限角色的数据域；平台全量角色为空 |
| `communityNo` | `string` | 否 | 受限角色的社区数据域 |

### MarketConfig

市场与货币（P-17.1.3）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 市场编码，如 `CN` / `SG` |
| `name` | `string` | 是 | 市场展示名 |
| `currency` | `string` | 是 | 结算与展示货币，如 `CNY` |
| `timezone` | `string` | 是 | 时区标识，如 `Asia/Shanghai`。截单时间按它切分自然日 |
| `rate` | `number` | 是 | 对基准货币的汇率。 ⚠️ 基准货币（CNY）恒为 1 且**不可改** —— 改了整套价格换算的原点就没了。 |
| `enabled` | `boolean` | 是 | 是否开放该市场。关掉后该市场的商品不再售卖 |

### Material

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `materialNo` | `string` | 是 | 素材单号 |
| `title` | `string` | 是 | 素材标题，供商家在素材中心检索 |
| `kind` | [`#/definitions/MaterialKind`](#definitionsmaterialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`#/definitions/MaterialScope`](#definitionsmaterialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
| `scopeRefs` | `string`\[\] | 是 | scope=COMMUNITY 时的社区列表；=MERCHANT 时的商家列表。ALL 时为空 |
| `langs` | `string`\[\] | 是 | 适用语言，空 = 不限 |
| `published` | `boolean` | 是 | 是否已发布。未发布的素材商家看不到 |
| `downloads` | `number` | 是 | 被下载次数，衡量素材有没有人用 |
| `createdAt` | `string` | 是 | 创建时间 |

### MemberCard

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `cardNo` | `string` | 是 | 会员卡单号 |
| `name` | `string` | 是 | 卡名 |
| `level` | `number` | 是 | 等级，数字越大越高 |
| `priceMonthly` | `number` | 是 | 月费（分） |
| `benefits` | [`#/definitions/Benefit`](#definitionsbenefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`#/definitions/MemberCardStatus`](#definitionsmembercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Merchant

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 |
| `communityNo` | `string` | 是 | 归属社区（数据域裁剪键之一） |
| `communityName` | `string` | 是 | 社区名快照 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 是 | 已上传并通过的资质名。授权需要资质的类目码时要对照它 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |

### MsgTemplate

订阅消息模板（P-14.1.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `channel` | [`#/definitions/MsgChannel`](#definitionsmsgchannel) | 是 | 触达渠道：订阅消息 / App 推送 / 站内信 |
| `content` | `string` | 是 | 模板正文，含 {占位符} |
| `enabled` | `boolean` | 是 | 是否启用。停用后引用它的推送任务发不出去 |
| `sentCount` | `number` | 是 | 近 30 天发送量 |

### NotifyQuota

触达频控（P-14.1.4）。 两个上限都必须 > 0 —— 0 等于没有频控，但界面上看着像配了，比不配更危险。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dailyPerUser` | `number` | 是 | 单用户单日消息上限 |
| `minIntervalHours` | `number` | 是 | 同一模板对同一用户的最小间隔（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Order

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`#/definitions/FulfillType`](#definitionsfulfilltype) | 是 | 履约方式 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`#/definitions/OrderItem`](#definitionsorderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |

### OrderException

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `order` | [`#/definitions/Order`](#definitionsorder) | 是 | 关联的订单快照。异常单是**实时算出来的视图**，不落表 |
| `kind` | [`#/definitions/ExceptionKind`](#definitionsexceptionkind) | 是 | 异常成因 |
| `stuckMinutes` | `number` | 是 | 已经卡了多少分钟（从进入当前状态算起，mock 用 createdAt/paidAt 近似） |
| `thresholdMinutes` | `number` | 是 | 该状态允许卡多久（分钟），用于在界面上说明"为什么它算异常" |

### OrderIntervention

人工干预的留痕。改状态这件事必须留下是谁、为什么。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 被干预的订单 |
| `from` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 原状态 |
| `to` | [`#/definitions/OrderStatus`](#definitionsorderstatus) | 是 | 改为的状态。**必须是 `ORDER_TRANSITIONS` 允许的迁移** |
| `remark` | `string` | 是 | 干预原因，必填 —— 改状态这件事必须说清楚为什么 |
| `operator` | `string` | 是 | 操作人（STAFF 账号） |
| `at` | `string` | 是 | 操作时间 |

### OverdueRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `action` | [`#/definitions/OverdueAction`](#definitionsoverdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
| `graceHours` | `number` | 是 | 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。 校验在 mock/后端两侧都有，不只是表单提示。 |
| `maxPostpone` | `number` | 是 | 顺延次数上限（action=POSTPONE 时有意义） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### PickupPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupType`](#definitionspickuptype) | 是 | 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `merchantNo` | `string` | 否 | 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |

### Post

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `postNo` | `string` | 是 | 内容单号 |
| `authorType` | [`#/definitions/PostAuthorType`](#definitionspostauthortype) | 是 | 作者类型：普通用户 or 商家。商家发的内容审核标准更严 |
| `authorName` | `string` | 是 | 作者昵称/店名 |
| `title` | `string` | 是 | 内容标题 |
| `content` | `string` | 是 | 正文 |
| `communityNo` | `string` | 是 | 归属社区。内容只在本社区露出 |
| `communityName` | `string` | 是 | 社区名快照 |
| `skuNo` | `string,null` | 否 | 关联商品；纯分享贴可以没有 |
| `riskHits` | `string`\[\] | 是 | 命中的风险词。 ⚠️ 命中的内容**不进批量通过** —— 批量 + 风险内容 = 事故，必须逐条看。 |
| `status` | [`#/definitions/PostStatus`](#definitionspoststatus) | 是 | 审核状态。允许的流转见 `POST_TRANSITIONS`（`PASSED → OFFLINE` 是单独一条路） |
| `auditRemark` | `string,null` | 否 | 审核意见 / 下架原因。原样回作者 |
| `likeCount` | `number` | 是 | 点赞数 |
| `createdAt` | `string` | 是 | 发布时间 |
| `decidedAt` | `string,null` | 否 | 审核完成时间。未审为 null |
| `decidedBy` | `string,null` | 否 | 审核人（STAFF 账号）。未审为 null |

### PushTask

推送任务（P-14.1.2）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务单号 |
| `name` | `string` | 是 | 任务名 |
| `templateNo` | `string` | 是 | 使用的消息模板 |
| `audience` | `string` | 是 | 人群描述，如「近 7 日未下单的老客」 |
| `estimatedReach` | `number` | 是 | 预估触达数。为 0 说明人群是空的，发了等于白发 |
| `status` | [`#/definitions/PushStatus`](#definitionspushstatus) | 是 | 任务状态 |
| `scheduledAt` | `string` | 否 | 计划发送时间。`status=SCHEDULED` 时有值 |
| `createdAt` | `string` | 是 | 创建时间 |

### Question

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `questionNo` | `string` | 是 | 提问单号 |
| `skuNo` | `string` | 是 | 被提问的商品 |
| `skuTitle` | `string` | 是 | 商品标题快照 |
| `content` | `string` | 是 | 提问正文 |
| `askedBy` | `string` | 是 | 提问人昵称 |
| `answer` | `string,null` | 否 | 回答正文。未回答为 null |
| `answeredBy` | `string,null` | 否 | 回答人（STAFF 或商家）。未回答为 null |
| `answeredAt` | `string,null` | 否 | 回答时间。未回答为 null |
| `status` | [`#/definitions/QuestionStatus`](#definitionsquestionstatus) | 是 | 问答状态 |
| `createdAt` | `string` | 是 | 提问时间 |
| `hideReason` | `string,null` | 否 | 隐藏原因。隐藏也要写清为什么，否则用户来问时没人说得清 |

### Quote

商家对需求的报价（P-8.2.3）。同一需求同一商家只能有一条。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 报价单号 |
| `demandNo` | `string` | 是 | 所报的需求单。**同一需求同一商家只能有一条** |
| `demandTitle` | `string` | 是 | 需求标题快照 |
| `merchantNo` | `string` | 是 | 报价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `price` | `number` | 是 | 单价（分） |
| `minQty` | `number` | 是 | 起订量 |
| `validTo` | `string` | 是 | 报价有效期。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `string` | 是 | 报价时间 |

### Ranking

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rankNo` | `string` | 是 | 榜单单号 |
| `name` | `string` | 是 | 榜单名，如「本周热销」 |
| `kind` | [`#/definitions/RankingKind`](#definitionsrankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
| `size` | `number` | 是 | 取前 N 名 |
| `manualSkus` | `string`\[\] | 是 | 仅 MANUAL：人工指定的商品，顺序即榜位 |
| `enabled` | `boolean` | 是 | 是否启用。停用后 C 端不再展示该榜 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### ReconDiff

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `diffNo` | `string` | 是 | 差异单号 |
| `billDate` | `string` | 是 | 对账日期（渠道账单的账期），YYYY-MM-DD |
| `channel` | [`#/definitions/PayChannel`](#definitionspaychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`#/definitions/ReconDiffType`](#definitionsrecondifftype) | 是 | 差异类型。**三类的处置方式完全不同**，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`#/definitions/ReconStatus`](#definitionsreconstatus) | 是 | 处置状态 |
| `resolution` | `string,null` | 否 | 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 |
| `recoveredOrderNo` | `string,null` | 否 | 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） |
| `createdAt` | `string` | 是 | 差异产生时间 |
| `resolvedAt` | `string,null` | 否 | 处置时间。未处置为 null |
| `resolvedBy` | `string,null` | 否 | 处置人（STAFF 账号）。未处置为 null |

### RedeemStat

核销监控（P-5.1.3）：一行 = 一个自提点当日的履约健康度。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `pickupName` | `string` | 是 | 自提点名称 |
| `communityName` | `string` | 是 | 所属社区名 |
| `pending` | `number` | 是 | 待核销单数（已到货、还没人来取） |
| `redeemed` | `number` | 是 | 已核销单数 |
| `overdue` | `number` | 是 | 逾期未取单数 |
| `rate` | `number` | 是 | 已核销 /（已核销 + 待核销 + 逾期），0–1 |

### Review

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reviewNo` | `string` | 是 | 评价单号 |
| `orderNo` | `string` | 是 | 关联订单。一单一评 |
| `merchantNo` | `string` | 是 | 被评价商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `authorNickname` | `string` | 是 | 评价人昵称 |
| `score` | `number` | 是 | 总评 1–5 |
| `scoreProduct` | `number` | 是 | 三维分（商品 / 履约 / 服务），评分算法按权重合成 |
| `scoreFulfill` | `number` | 是 | 履约分 1–5（快慢、包装、缺损） |
| `scoreService` | `number` | 是 | 服务分 1–5（沟通、售后态度） |
| `content` | `string` | 是 | 评价正文 |
| `imageCount` | `number` | 是 | 配图数量。列表页不下发图本身，点进详情才取 |
| `status` | [`#/definitions/ReviewStatus`](#definitionsreviewstatus) | 是 | 审核状态 |
| `riskFlags` | [`#/definitions/RiskFlag`](#definitionsriskflag)\[\] | 是 | 命中的刷评信号。**是线索不是结论** —— 命中不等于判定 |
| `createdAt` | `string` | 是 | 评价提交时间 |
| `reason` | `string` | 否 | 驳回原因：与门店审核同一条规矩 —— 驳回必须写清楚 |

### ReviewAppeal

恶意差评申诉（P-13.1.3）。UPHELD = 支持商家（差评下架），DISMISSED = 驳回申诉（差评保留）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `appealNo` | `string` | 是 | 申诉单号 |
| `reviewNo` | `string` | 是 | 被申诉的评价 |
| `merchantNo` | `string` | 是 | 申诉方商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `reason` | `string` | 是 | 商家的申诉理由 |
| `evidenceCount` | `number` | 是 | 举证材料数量（截图/聊天记录） |
| `status` | [`#/definitions/AppealStatus`](#definitionsappealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），DISMISSED = 驳回申诉（差评保留） |
| `submittedAt` | `string` | 是 | 申诉提交时间 |
| `verdict` | `string` | 否 | 裁决说明：无论支持还是驳回都必须写，商家会看到 |

### RiskEvent

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `eventNo` | `string` | 是 | 风险事件单号 |
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | `USER` \| `MERCHANT` \| `DEVICE` | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
| `signals` | `string`\[\] | 是 | 命中的信号。**不给分值** —— 分值口径要等有真实样本后由风控定， 现在编一个看起来很准的分数，只会让人照着它做决定。 |
| `refs` | `string`\[\] | 是 | 关联证据：订单号 / 归因链路号 |
| `status` | [`#/definitions/RiskStatus`](#definitionsriskstatus) | 是 | 处置状态 |
| `createdAt` | `string` | 是 | 事件产生时间 |
| `verdict` | `string` | 否 | 处置结论 |

### RiskRule

拦截规则（P-16.2.5）：各类型的触发阈值 + 是否自动拦截。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 适用的风险类型，一类一条 |
| `threshold` | `number` | 是 | 触发阈值（如同设备下单数、同 IP 邀请数、30 天退款次数），必须 > 0 |
| `autoBlock` | `boolean` | 是 | 命中后是否自动拦截；false = 只记事件等人工 |
| `updatedAt` | `string` | 是 | 最后修改时间 |

### RoleDef

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色码 |
| `label` | `string` | 是 | 角色展示名 |
| `builtin` | `boolean` | 是 | 内置角色（超管）：定义就是"全部"，不可编辑 —— 可编辑意味着能把自己降权 |
| `perms` | `string`\[\] | 是 | 权限码集合；'*' 表示全部 |
| `staffCount` | `number` | 是 | 持有该角色的账号数 |

### RuleTexts

规则文案（P-17.1.4）。这三条是 C 端要展示给用户看的，不能为空。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `refund` | `string` | 是 | 退款规则文案，C 端售后页展示 |
| `pickup` | `string` | 是 | 自提规则文案，C 端下单与取货页展示 |
| `weighDiff` | `string` | 是 | 称重差价规则文案，生鲜订单展示 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### ScoreConfig

评分算法参数（P-13.1.4）。 ⚠️ 改这些参数会**改变历史评价的呈现**（时效衰减是实时算的）， 所以每次改动都要留痕；影响预览等有真实数据后再做。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `weightProduct` | `number` | 是 | 三维权重，百分比，**和必须为 100** |
| `weightFulfill` | `number` | 是 | 履约维度权重（百分比） |
| `weightService` | `number` | 是 | 服务维度权重（百分比） |
| `newMerchantProtectDays` | `number` | 是 | 新商家保护期（天）：期内不展示低于阈值的均分，避免首单差评直接判死 |
| `decayHalfLifeDays` | `number` | 是 | 时效衰减半衰期（天）：越久远的评价权重越低 |
| `updatedAt` | `string` | 是 | 最后修改时间。改参数会**改变历史评价的呈现**，必须留痕 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Settlement

结算单：一个商家一个周期一张。 ⚠️ **对账恒等式**：gross = platformFee + serviceFee + net。 这三个数分别来自三处（费率表、自提点配置、余数），不校验就会出现"分完了还差几分钱"。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 结算周期，如 2026-08-上 |
| `orderCount` | `number` | 是 | 本期结算的子订单笔数 |
| `grossAmount` | `number` | 是 | 应结总额（分）= 子订单实付合计 |
| `platformFee` | `number` | 是 | 平台佣金（分）。按「分账内扣」实现（12.1.6 口径待定） |
| `serviceFee` | `number` | 是 | 自提点履约服务费（分，R15） |
| `netAmount` | `number` | 是 | 实付商家（分） |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态。允许的流转见 `SETTLE_TRANSITIONS` |
| `retryCount` | `number` | 是 | 分账指令重试次数（上限见 lib/constants.ts） |
| `failReason` | `string` | 否 | 失败原因。`status=FAILED` 时有值，人工介入据此判断 |
| `frozenAt` | `string` | 是 | 冻结开始时间：超过 freezeDays 未成功就解冻回平台 |
| `createdAt` | `string` | 是 | 结算单生成时间 |

### Shipment

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `shipmentNo` | `string` | 是 | 运单记录单号（平台侧主键，不是快递单号） |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `carrier` | [`#/definitions/Carrier`](#definitionscarrier) | 是 | 承运商 |
| `waybillNo` | `string` | 是 | 承运商的快递单号 |
| `status` | [`#/definitions/ShipmentStatus`](#definitionsshipmentstatus) | 是 | 快递状态。**`EXCEPTION` 不是终态**，疑难件可能之后又派送成功 |
| `receiver` | `string` | 是 | 收件人姓名 |
| `region` | `string` | 是 | 收件地区（省/市），超区判断看的就是它 |
| `createdAt` | `string` | 是 | 建单时间 |
| `updatedAt` | `string` | 是 | 最后一次轨迹更新时间 |
| `traces` | [`#/definitions/ShipmentTrace`](#definitionsshipmenttrace)\[\] | 是 | 轨迹节点，按时间正序 |

### Sku

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 商品单号 |
| `title` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`#/definitions/SkuStatus`](#definitionsskustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`#/definitions/Partial<Record<Market,number>>`](#definitionspartialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
| `stock` | `number` | 是 | 现货库存 |
| `presaleQuota` | `number` | 是 | 预售额度（P-3.3.1）。0 = 不做预售 |
| `soldCount` | `number` | 是 | 已售（预售期内） |
| `cutoffAt` | `string` | 否 | 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 |
| `arriveAt` | `string` | 否 | 到货时间（与履约批次对齐） |
| `createdAt` | `string` | 是 | 创建时间 |
| `reason` | `string` | 否 | 驳回/强制下架原因，原样进商家 B 端 |

### SortingRow

按自提点汇总的分拣行（P-5.1.2）。只列**已签收**批次的货。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `pickupName` | `string` | 是 | 自提点名称 |
| `skuNo` | `string` | 是 | SKU 单号 |
| `title` | `string` | 是 | 商品标题 |
| `merchantName` | `string` | 是 | 供货商家名。一个批次会混装多家的货 |
| `qty` | `number` | 是 | 应分拣数量 |
| `shortQty` | `number` | 是 | 缺货标记回传（P-5.1.2 / B-10.3.4）：自提点上报的缺件数 |

### SplitRecord

分账明细：一条 = 一个子订单。费率按 trafficSource 分档（R16）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `splitNo` | `string` | 是 | 分账明细单号 |
| `settleNo` | `string` | 是 | 所属结算单 |
| `orderNo` | `string` | 是 | 对应的子订单。**一条明细 = 一个子订单** |
| `merchantName` | `string` | 是 | 商家名快照 |
| `trafficSource` | [`#/definitions/TrafficSource`](#definitionstrafficsource) | 是 | 该订单的流量来源，决定适用哪一档费率（R16） |
| `grossAmount` | `number` | 是 | 该订单实付金额（分） |
| `feeRate` | `number` | 是 | 本条实际适用的平台佣金费率（万分比），来自费率表 |
| `platformFee` | `number` | 是 | 本条的平台佣金（分） |
| `pickupNo` | `string` | 否 | 履约自提点。非自提单为空 |
| `serviceFee` | `number` | 是 | 自提点履约服务费（分）；非自提单为 0 |
| `netAmount` | `number` | 是 | 实付商家（分）。**恒等式**：grossAmount = platformFee + serviceFee + netAmount |

### Staff

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。决定权限码集合，见 `RoleDef` |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |

### StoreAcquisition

门店获客效果（P-10.1.4）：扫码 → 进店 → 注册 → 首单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `scan` | `number` | 是 | 扫码次数 |
| `enter` | `number` | 是 | 进店人数 |
| `register` | `number` | 是 | 注册人数 |
| `firstOrder` | `number` | 是 | 首单人数 |
| `convRate` | `number` | 是 | 首单转化率 = firstOrder / scan，0–1 |

### StorePageAudit

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `auditNo` | `string` | 是 | 审核单号 |
| `merchantNo` | `string` | 是 | 提审商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `kind` | [`#/definitions/StoreAuditKind`](#definitionsstoreauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL 或公告文本 |
| `status` | [`#/definitions/StoreAuditStatus`](#definitionsstoreauditstatus) | 是 | 审核状态 |
| `hits` | `string`\[\] | 是 | 机审命中的敏感词/风险项，随数据下发。 人审要看到「机器为什么标它」，否则只能凭感觉判，同一类内容两个人两个结论。 |
| `submittedAt` | `string` | 是 | 提审时间 |
| `reason` | `string` | 否 | 驳回原因：**原样出现在商家 B 端**，所以驳回必须填 |

### StoreQrcode

店铺码批量生成与导出（P-10.1.3，供 BD 地推）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityName` | `string` | 是 | 所属社区名，BD 按社区领码地推 |
| `code` | `string` | 是 | 码值（C 端扫码进店的深链参数），导出时给 BD 去印刷 |
| `size` | `string` | 是 | 贴纸尺寸规格，如 "10x10cm" |
| `printed` | `number` | 是 | 已印数量，用于对账印刷成本 |
| `scanCount` | `number` | 是 | 累计扫码次数 |

### StoreTemplate

店铺主页模板。 ⚠️ `usedByCount` 是**只读的引用计数**，不是配置项 —— 它存在的唯一理由是 拦住"停用一个正在被 12 家店用着的模板"这件事。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | `GRID` \| `LIST` \| `FEATURE` | 是 | 商品区排布 |
| `sections` | [`#/definitions/TemplateSection`](#definitionstemplatesection)\[\] | 是 | 板块开关列表 |
| `enabled` | `boolean` | 是 | 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 |
| `isDefault` | `boolean` | 是 | 默认模板：新店开出来就用它，所以停用不了 |
| `usedByCount` | `number` | 是 | 正在使用该模板的店铺数（只读） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### TaxRule

个税代扣规则（P-12.2.3）。 只对**个人主体**商家生效：个体户与企业自行申报，平台不代扣。 起征点以下不扣 —— 不设起征点会给每一笔几块钱的提现都产生一条扣税记录。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `threshold` | `number` | 是 | 起征点（分）：单期收入低于它不代扣 |
| `rate` | `number` | 是 | 代扣税率（万分比） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Ticket

客服工单（P-14.2.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 工单号 |
| `title` | `string` | 是 | 工单标题 |
| `userNickname` | `string` | 是 | 提单用户昵称 |
| `orderNo` | `string` | 否 | 关联订单，可空 |
| `status` | [`#/definitions/TicketStatus`](#definitionsticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
| `assignee` | `string` | 否 | 处理人（员工登录名）；未分派为空 |
| `proxyActions` | `string`\[\] | 是 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么 |
| `createdAt` | `string` | 是 | 提单时间 |

### TrendPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `date` | `string` | 是 | 日期 YYYY-MM-DD |
| `gmv` | `number` | 是 | 当日成交额（最小货币单位整数） |
| `orderCount` | `number` | 是 | 当日订单数 |

### Violation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `violationNo` | `string` | 是 | 违规记录单号 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `type` | [`#/definitions/ViolationType`](#definitionsviolationtype) | 是 | 违规类型。**只有 `BREACH` 计入 breachCount** |
| `action` | [`#/definitions/ViolationAction`](#definitionsviolationaction) | 是 | 处置动作。`SUSPEND` 会真的把商家状态推到 SUSPENDED |
| `detail` | `string` | 是 | 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 |
| `operator` | `string` | 是 | 处置人（STAFF 账号） |
| `at` | `string` | 是 | 处置时间 |

### Withdrawal

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `withdrawNo` | `string` | 是 | 提现单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `amount` | `number` | 是 | 申请金额（分） |
| `availableBalance` | `number` | 是 | 申请时的可提余额（分）。快照，不是实时值 —— 审批看的是申请那一刻的口径 |
| `bankAccountMasked` | `string` | 是 | 收款账户，展示一律脱敏 |
| `status` | [`#/definitions/WithdrawStatus`](#definitionswithdrawstatus) | 是 | 提现状态。**`APPROVED → PAID` 由渠道回执驱动，运营点不了** |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 审批时间。未审为 null |
| `decidedBy` | `string,null` | 否 | 审批人（STAFF 账号）。未审为 null |
| `remark` | `string,null` | 否 | 驳回原因 / 大额复核说明。原样回商家 B 端 |
