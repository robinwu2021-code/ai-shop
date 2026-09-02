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


#### POST `/ops/after-sales/{afterSaleNo}/decide`

平台介入裁决（`ARBITRATING` 的唯一出口）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### POST `/ops/communities/{no}/fence`

覆盖围栏半径，米（P-2.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### POST `/ops/communities/{no}/open`

开城/停城（P-2.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### POST `/ops/communities/{no}/region`

把社区挂到行政区划下（ADR-013）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### POST `/ops/communities/{no}/unarchive`

unarchiveCommunity

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### GET `/ops/communities/applies`

提报队列

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`CommunityApply`](#communityapply)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/communities/applies/{applyNo}/decide`

裁决

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `applyNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`CommunityApply`](#communityapply)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 提报单业务键。裁决按它定位，**不用自增 id** —— 那个不对外，重建库就变 |
| `merchantNo` | `string` | 是 | 提报的商家 |
| `merchantName` | `string` | 是 | 商家名。运营看着一串 M20260811… 判断不了任何事 |
| `name` | `string` | 是 | 小区名，商家填 |
| `address` | `string` | 否 | 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 同一个小区两条记录，商家会分不清该勾哪个 |
| `regionCode` | `string` | 否 | 商家选的区划，**只是建议**：最终以裁决时填的为准 |
| `regionPath` | `string` | 否 | 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 |
| `note` | `string` | 否 | 商家的补充说明：为什么要开这个点 |
| `kind` | [`#/definitions/SettlementKind`](#definitionssettlementkind) | 否 | ESTATE 小区 / VILLAGE 村。裁决的人要一眼看出这是哪种聚落 |
| `originCode` | `string` | 否 | 关联的官方村码；非空 = 从词典选的，重复开通会被后端拦 |
| `located` | `boolean` | 否 | 带没带定位。**没带的要显眼** —— 通过后聚落没有坐标， 买家用定位永远找不到它，运营得先补坐标再通过。 |
| `latE6` | `number,null` | 否 | 商家提报时带的坐标（gcj02，E6）。**要看得见具体值** —— 只给一个「有/无」，落点偏到隔壁区也照样显示「有定位」，判不出对错。 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `fallbackLatE6` | `number,null` | 否 | 官方村码在区划表里的坐标（高德批量补录）。没带定位时后端通过这条提报会自动用它兜底 —— 两个都空，才是真的「通过后无坐标、买家搜不到」。 |
| `fallbackLngE6` | `number,null` | 否 | 兜底经度：商家没选点时用提交那一刻的位置。**多半不在那个小区里**，裁决要留意 |
| `status` | [`#/definitions/CommunityApplyStatus`](#definitionscommunityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。**只有 PENDING 能裁**：裁完就是终态，再裁一次意味着同一条提报有两个结论 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因。**原样出现在商家 B 端**，所以驳回必须填 |
| `submittedAt` | `number` | 是 | 提报时间 |


#### GET `/ops/communities/duplicates`

疑似重复的聚落两两清单

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CommunityDuplicate`](#communityduplicate)\[\]


#### POST `/ops/communities/merge`

合并：把 fromNo 并进 intoNo

**入参**

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |


#### GET `/ops/communities/near`

一个坐标附近已开通的聚落，按距离升序 —— 裁决时查重用 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`NearbyCommunity`](#nearbycommunity)\[\]


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


#### POST `/ops/pickups`

建自提点

**入参**

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/archive`

archivePickup

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/decide`

裁决商家自建的自提点（P1）：PENDING → ACTIVE / REJECTED

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/service-fee`

履约服务费费率，万分比（P-2.2.4）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/status`

启停与迁移（P-2.2.2），非法迁移抛错

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/pickups/{no}/unarchive`

unarchivePickup

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`PickupPoint`](#pickuppoint)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
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


#### GET `/ops/regions`

某区划的直接下级

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Region`](#region)\[\]


#### POST `/ops/regions`

区划人工维护（新增 / 停用 / 改名）

**入参**

_无字段_

**出参**（`data`）

类型：[`Region`](#region)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath |
| `enabled` | `boolean` | 是 | 开城开关：停用只影响新的选择，存量商家不动 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 |


#### POST `/ops/regions/{code}/rename`

改名不动码，存量引用不受影响 */

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `code` | path | `string` | 是 | 取货码 / 核销码 |

_无字段_

**出参**（`data`）

类型：[`Region`](#region)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath |
| `enabled` | `boolean` | 是 | 开城开关：停用只影响新的选择，存量商家不动 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 |


#### POST `/ops/regions/{code}/toggle`

停用只影响新选择，存量商家的范围不动

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `code` | path | `string` | 是 | 取货码 / 核销码 |

_无字段_

**出参**（`data`）

类型：[`Region`](#region)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath |
| `enabled` | `boolean` | 是 | 开城开关：停用只影响新的选择，存量商家不动 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 |


#### GET `/ops/regions/path`

从省到自身的整条链路

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Region`](#region)\[\]


#### GET `/ops/regions/resolve`

按提报单的地址与坐标推断该挂哪个街道

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RegionSuggestion`](#regionsuggestion)\[\]


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
| `postNo` | path | `string` | 是 | 种草内容单号 |

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
| `questionNo` | path | `string` | 是 | 商品问答单号 |

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
| `questionNo` | path | `string` | 是 | 商品问答单号 |

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
| `rankNo` | path | `string` | 是 | 榜单单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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

#### POST `/ops/auth/forgot`

忘记密码：往登录名那个邮箱发一次性重置码

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/auth/login`

登录

**入参**

_无字段_

**出参**（`data`）

类型：[`LoginResp`](#loginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `perms` | `string`\[\] | 是 | **后端下发的权限码**（`staff.perms`）。判权以它为准。 `["*"]` = 超管通配。前端的 UI 码要先经 `UI_PERM_MAP` 翻译成后端码 再来这里查 —— 两边的粒度不同（前端 45 个、后端 14 个）， 直接比会全判 false。 |
| `merchantNo` | `string` | 否 | 商家运营（BD）等受限角色的数据域；平台全量角色为空 |
| `communityNo` | `string` | 否 | 受限角色的社区数据域 |


#### GET `/ops/auth/me`

拿当前登录人的最新身份（`GET /ops/auth/me`）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`LoginResp`](#loginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `perms` | `string`\[\] | 是 | **后端下发的权限码**（`staff.perms`）。判权以它为准。 `["*"]` = 超管通配。前端的 UI 码要先经 `UI_PERM_MAP` 翻译成后端码 再来这里查 —— 两边的粒度不同（前端 45 个、后端 14 个）， 直接比会全判 false。 |
| `merchantNo` | `string` | 否 | 商家运营（BD）等受限角色的数据域；平台全量角色为空 |
| `communityNo` | `string` | 否 | 受限角色的社区数据域 |


#### POST `/ops/auth/reset`

用邮件里的重置码设新密码

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/dashboard/funnel`

getAcquisitionFunnel

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FunnelRow`](#funnelrow)\[\]


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


#### GET `/ops/dashboard/merchants`

商家经营排行（P-16.1.2 / P-16.1.3）——大盘之下的第一层下钻

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MerchantRankRow`](#merchantrankrow)\[\]


#### GET `/ops/dashboard/trend`

getDashboardTrend

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`TrendPoint`](#trendpoint)\[\]


#### GET `/ops/menu`

当前登录人的**动态菜单**（`GET /ops/menu`）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MenuFunction`](#menufunction)\[\]


### finance

#### GET `/ops/debts/{entityNo}`

某商家的欠款余额与流水 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `entityNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`MerchantDebt`](#merchantdebt)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 欠款主体号 |
| `balanceMinor` | `number` | 是 | 当前欠款（分），恒 >= 0。0 = 没有欠款 |
| `txns` | [`#/definitions/DebtTxn`](#definitionsdebttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |


#### POST `/ops/debts/{entityNo}/deposit-offset`

用保证金抵掉一部分欠款

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `entityNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`MerchantDebt`](#merchantdebt)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 欠款主体号 |
| `balanceMinor` | `number` | 是 | 当前欠款（分），恒 >= 0。0 = 没有欠款 |
| `txns` | [`#/definitions/DebtTxn`](#definitionsdebttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |


#### GET `/ops/finance/invoice-title`

平台开票抬头

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvoiceTitle`](#invoicetitle)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `companyName` | `string` | 是 | 公司全称。**必填** |
| `taxNo` | `string` | 是 | 纳税人识别号。**必填** |
| `address` | `string` | 是 | 注册地址 |
| `phone` | `string` | 是 | 注册电话 |
| `bankAccount` | `string` | 是 | 开户行与账号 |


#### POST `/ops/finance/invoice-title`

公司全称与税号必填 —— 缺了供应商开不出票，存下去只会让人以为已经配好了

**入参**

_无字段_

**出参**（`data`）

类型：[`InvoiceTitle`](#invoicetitle)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `companyName` | `string` | 是 | 公司全称。**必填** |
| `taxNo` | `string` | 是 | 纳税人识别号。**必填** |
| `address` | `string` | 是 | 注册地址 |
| `phone` | `string` | 是 | 注册电话 |
| `bankAccount` | `string` | 是 | 开户行与账号 |


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
| `invoiceNo` | path | `string` | 是 | 开票申请单号 |

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
| `invoiceNo` | path | `string` | 是 | 开票申请单号 |

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
| `withdrawNo` | path | `string` | 是 | 提现单号 |

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


#### GET `/ops/invoice-requests`

listBuyerInvoiceRequests

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`BuyerInvoiceRequest`](#buyerinvoicerequest)\[\]


#### POST `/ops/invoice-requests/{requestNo}/issued`

markBuyerInvoiceIssued

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

_无字段_

**出参**（`data`）

类型：[`BuyerInvoiceRequest`](#buyerinvoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | 开票申请号 |
| `orderNo` | `string` | 是 | 针对哪一单 |
| `titleType` | `string` | 是 | PERSONAL / COMPANY |
| `title` | `string` | 是 | 抬头 |
| `taxNo` | `string,null` | 否 | 税号。单位抬头必填 |
| `email` | `string,null` | 否 | 发到哪个邮箱。电子票唯一的交付方式 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `status` | `string` | 是 | PENDING / ISSUED / REJECTED |
| `invoiceNo` | `string,null` | 否 | 已开出的发票号 |
| `issuedAt` | `number,null` | 否 | 开出来的时刻。空 = 还没开 |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `createdAt` | `number,null` | 否 | 申请时刻 |


#### POST `/ops/invoice-requests/{requestNo}/reject`

rejectBuyerInvoiceRequest

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

_无字段_

**出参**（`data`）

类型：[`BuyerInvoiceRequest`](#buyerinvoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | 开票申请号 |
| `orderNo` | `string` | 是 | 针对哪一单 |
| `titleType` | `string` | 是 | PERSONAL / COMPANY |
| `title` | `string` | 是 | 抬头 |
| `taxNo` | `string,null` | 否 | 税号。单位抬头必填 |
| `email` | `string,null` | 否 | 发到哪个邮箱。电子票唯一的交付方式 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `status` | `string` | 是 | PENDING / ISSUED / REJECTED |
| `invoiceNo` | `string,null` | 否 | 已开出的发票号 |
| `issuedAt` | `number,null` | 否 | 开出来的时刻。空 = 还没开 |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `createdAt` | `number,null` | 否 | 申请时刻 |


#### GET `/ops/payables`

listPayables

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Settlement`](#settlement)\[\]


#### POST `/ops/payables/{settleNo}/confirm`

确认对账：双方认了这个数

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `settleNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Settlement`](#settlement)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `netMinor` | `number` | 是 | 实付商家（分） |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |


#### POST `/ops/payables/{settleNo}/no-invoice`

标记无票供应商：**不进发票流程，但要在应付列表上标出来** —— 让财务付款前就看见 */

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `settleNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Settlement`](#settlement)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `netMinor` | `number` | 是 | 实付商家（分） |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |


#### POST `/ops/payables/{settleNo}/paid`

登记已付款

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `settleNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Settlement`](#settlement)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `netMinor` | `number` | 是 | 实付商家（分） |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |


#### GET `/ops/points/client-policy`

积分的**端策略**：哪个端不发放、哪个端不核销、当面付能不能抵扣

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ClientPointsPolicy`](#clientpointspolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `earnDeny` | `string`\[\] | 是 | 这些端不发放积分 |
| `redeemDeny` | `string`\[\] | 是 | 这些端不能用积分抵扣 |
| `offlineRedeem` | `boolean` | 是 | 当面付能不能用积分抵扣。**默认开** —— 成本本来就在商家，线下反而比线上简单 |


#### POST `/ops/points/client-policy`

savePointsClientPolicy

**入参**

_无字段_

**出参**（`data`）

类型：[`ClientPointsPolicy`](#clientpointspolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `earnDeny` | `string`\[\] | 是 | 这些端不发放积分 |
| `redeemDeny` | `string`\[\] | 是 | 这些端不能用积分抵扣 |
| `offlineRedeem` | `boolean` | 是 | 当面付能不能用积分抵扣。**默认开** —— 成本本来就在商家，线下反而比线上简单 |


#### GET `/ops/points/overview`

积分资金总览

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsOverview`](#pointsoverview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `circulatingPoints` | `number` | 是 | 流通中的积分（用户可用 + 待生效） |
| `poolBalanceMinor` | `number` | 是 | 池子余额（分）。与上一个数对不上就是失衡 |
| `periodRedeemMinor` | `number` | 是 | 本期兑付（分）：补给商家的钱 |
| `byChannel` | [`#/definitions/PoolByChannel`](#definitionspoolbychannel)\[\] | 是 | 按通道分的账本。**不能只看总数** —— 账面是一个池子，钱实际分散在两个通道账户； 一个溢一个空的时候，总数仍然是平的。 |


#### GET `/ops/purchase-invoices`

listPurchaseInvoices

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PurchaseInvoice`](#purchaseinvoice)\[\]


#### POST `/ops/purchase-invoices/{invoiceNo}/reject`

rejectPurchaseInvoice

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `invoiceNo` | path | `string` | 是 | 开票申请单号 |

_无字段_

**出参**（`data`）

类型：[`PurchaseInvoice`](#purchaseinvoice)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 平台侧的进项票记录号（不是发票上印的号） |
| `entityNo` | `string` | 是 | 哪家商家的票 |
| `period` | `string` | 是 | 所属账期 yyyyMM |
| `invoiceCode` | `string` | 是 | 发票代码，票面左上那一串 |
| `invoiceNumber` | `string` | 是 | 发票号码，票面右上那一串。**与 invoiceNo 不是一回事** |
| `invoiceType` | `string` | 是 | 票种：专票 / 普票 / 电子票 |
| `titleName` | `string` | 是 | 票面抬头 |
| `titleTaxNo` | `string` | 是 | 票面税号 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `taxAmountMinor` | `number` | 是 | 其中税额（分） |
| `taxRate` | `number` | 是 | 万分比 |
| `invoiceDate` | `number,null` | 否 | 开票日期 |
| `imageUrl` | `string,null` | 否 | 票面影像。核验要看原件 |
| `status` | `string` | 是 | PENDING / SUBMITTED / VERIFIED / REJECTED |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `titleMatched` | `boolean` | 是 | 抬头与主体名是否一致。**后端算，端上不重算** —— 两处判会走岔 |
| `settleNos` | `string`\[\] | 是 | 这张票覆盖了哪些结算单 |


#### POST `/ops/purchase-invoices/{invoiceNo}/verify`

verifyPurchaseInvoice

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `invoiceNo` | path | `string` | 是 | 开票申请单号 |

_无字段_

**出参**（`data`）

类型：[`PurchaseInvoice`](#purchaseinvoice)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 平台侧的进项票记录号（不是发票上印的号） |
| `entityNo` | `string` | 是 | 哪家商家的票 |
| `period` | `string` | 是 | 所属账期 yyyyMM |
| `invoiceCode` | `string` | 是 | 发票代码，票面左上那一串 |
| `invoiceNumber` | `string` | 是 | 发票号码，票面右上那一串。**与 invoiceNo 不是一回事** |
| `invoiceType` | `string` | 是 | 票种：专票 / 普票 / 电子票 |
| `titleName` | `string` | 是 | 票面抬头 |
| `titleTaxNo` | `string` | 是 | 票面税号 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `taxAmountMinor` | `number` | 是 | 其中税额（分） |
| `taxRate` | `number` | 是 | 万分比 |
| `invoiceDate` | `number,null` | 否 | 开票日期 |
| `imageUrl` | `string,null` | 否 | 票面影像。核验要看原件 |
| `status` | `string` | 是 | PENDING / SUBMITTED / VERIFIED / REJECTED |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `titleMatched` | `boolean` | 是 | 抬头与主体名是否一致。**后端算，端上不重算** —— 两处判会走岔 |
| `settleNos` | `string`\[\] | 是 | 这张票覆盖了哪些结算单 |


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
| `asNo` | path | `string` | 是 | 售后单号（平台端写法） |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### GET `/ops/settle-batches`

账期批次列表

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SettleBatch`](#settlebatch)\[\]


#### POST `/ops/settle-batches/{batchNo}/hold`

继续挂起

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `batchNo` | path | `string` | 是 | 到货批次号 |

_无字段_

**出参**（`data`）

类型：[`SettleBatch`](#settlebatch)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`#/definitions/SettleBatchStatus`](#definitionssettlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分） |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`#/definitions/ReconScope`](#definitionsreconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |


#### POST `/ops/settle-batches/{batchNo}/release`

人工放行一批

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `batchNo` | path | `string` | 是 | 到货批次号 |

_无字段_

**出参**（`data`）

类型：[`SettleBatch`](#settlebatch)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`#/definitions/SettleBatchStatus`](#definitionssettlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分） |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`#/definitions/ReconScope`](#definitionsreconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |


#### GET `/ops/settle/fee-rules`

全部费率版本，含历史

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`FeeRuleVersion`](#feeruleversion)\[\]


#### POST `/ops/settle/fee-rules`

新增一个费率版本

**入参**

_无字段_

**出参**（`data`）

类型：[`FeeRuleVersion`](#feeruleversion)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ruleNo` | `string` | 是 | 规则版本号 |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) | 是 | 经营模式，费率的第一个维度 |
| `trafficSource` | [`#/definitions/FeeTrafficSource`](#definitionsfeetrafficsource) | 是 | 适用的流量来源，费率的第二个维度 |
| `rateBp` | `number` | 是 | 万分比。500 = 5% |
| `effectiveFrom` | `number` | 是 | 生效时刻（毫秒）。**填未来时刻 = 预约生效** |
| `enabled` | `number` | 是 | 1 = 该版本生效；0 = 已停用（回退到上一版） |
| `remark` | `string,null` | 否 | 为什么调这一次 —— 回查时这句话比数字更有用 |
| `createdAt` | `string` | 否 | 创建时间 |
| `createdBy` | `string` | 否 | 创建人 |


#### GET `/ops/settle/fee-rules/effective`

某时刻实际生效的四格费率

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`EffectiveFeeRates`](#effectivefeerates)

_无字段_


#### GET `/ops/settle/pay-channels`

支付通道设置 + 每个通道的费率版本

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PayChannelSetting`](#paychannelsetting)\[\]


#### PUT `/ops/settle/pay-channels/{channel}`

改通道的开关与结算属性

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `channel` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`PayChannelSetting`](#paychannelsetting)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码，如 WECHAT / ALIPAY |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 停用只影响**新进件与新下单**，已开通的商户与在途的单不受影响 |
| `markets` | `string,null` | 是 | JSON 数组文本，如 `["CN"]`。空 = 全市场可用 |
| `currency` | `string,null` | 是 | 结算币种，如 CNY |
| `settleCycle` | `string,null` | 是 | 通道结算周期，如 T+1。展示与对账预期用 |
| `supportsSubsidy` | `boolean` | 是 | 能否补差。**为 false 时该通道不开积分抵扣** —— 这是通道的事实，运营改不了 |
| `currentRate` | [`#/definitions/PayChannelRateVersion`](#definitionspaychannelrateversion) \| `null` | 是 | 此刻生效的那一版；**一条都没配时为 null**，要显示成「未配置」而不是 0 |
| `rates` | [`#/definitions/PayChannelRateVersion`](#definitionspaychannelrateversion)\[\] | 是 | 全部版本，按生效时间倒序 |


#### POST `/ops/settle/pay-channels/{channel}/rates`

加一版通道费率

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `channel` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`PayChannelRateVersion`](#paychannelrateversion)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rateNo` | `string` | 是 | 规则版本号 |
| `payChannel` | `string` | 是 | 通道码，与 sys_pay_channel 同值域 |
| `payMethod` | `string` | 是 | `*` = 该通道全部支付方式 |
| `legalForm` | `string` | 是 | `*` = 全部主体形态 |
| `rateBp` | `number` | 是 | 万分比。38 = 0.38% |
| `minFeeMinor` | `number` | 是 | 单笔最低手续费（分）。0 = 无保底 |
| `effectiveFrom` | `number` | 是 | 生效时刻（毫秒）。**填未来时刻 = 预约生效** |
| `enabled` | `boolean` | 否 | 停用的版本不参与取值。停用最新版 = 回退到上一版 |
| `remark` | `string,null` | 否 | 为什么调这一次 —— 回查时这句话比数字更有用 |


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


#### GET `/ops/split-records`

listSplitRecords

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`SplitLog`](#splitlog)\[\] | 是 | — |
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
| `templateNo` | path | `string` | 是 | 模板单号 |

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
| `templateNo` | path | `string` | 是 | 模板单号 |

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
| `batchNo` | path | `string` | 是 | 到货批次号 |

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
| `carrier` | path | `string` | 是 | 承运商标识 |

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
| `carrier` | path | `string` | 是 | 承运商标识 |

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
| `shipmentNo` | path | `string` | 是 | 运单记录单号（平台侧主键，非快递单号） |

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
| `demandNo` | path | `string` | 是 | 求团需求单号 |

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
| `validTo` | `number` | 是 | 报价有效期（毫秒时间戳）。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `number` | 是 | 报价时间（毫秒时间戳） |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `endAt` | `number` | 是 | 成团截止时间（毫秒时间戳） |
| `createdAt` | `number` | 是 | 开团时间（毫秒时间戳） |


#### POST `/ops/groups/{no}/status`

setGroupStatus

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `endAt` | `number` | 是 | 成团截止时间（毫秒时间戳） |
| `createdAt` | `number` | 是 | 开团时间（毫秒时间戳） |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validTo` | `number` | 是 | 报价有效期（毫秒时间戳）。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `number` | 是 | 报价时间（毫秒时间戳） |


#### POST `/ops/quotes/{no}/price`

改价（P-8.2.4）：留痕并公示，超过阈值禁止再改

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validTo` | `number` | 是 | 报价有效期（毫秒时间戳）。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `number` | 是 | 报价时间（毫秒时间戳） |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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

#### GET `/ops/audit-log`

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


#### GET `/ops/perm/functions`

功能与功能点全集 —— 权限树的数据源

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MenuFunction`](#menufunction)\[\]


#### POST `/ops/perm/functions/${encodeURIComponent(functionCode)}/move`

菜单调序：同级内上移/下移

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/functions/reorder`

整段重排（拖动用）：传该父级下的**完整顺序**

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/points/${encodeURIComponent(pointCode)}/move`

movePermPoint

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/points/reorder`

reorderPermPoints

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/perm/roles`

listRoles

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`RoleDef`](#roledef)\[\]


#### POST `/ops/perm/roles`

createRole

**入参**

_无字段_

**出参**（`data`）

类型：[`RoleDef`](#roledef)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。自定义角色不在 `Role` 联合类型里，所以是 string |
| `name` | `string` | 是 | 角色展示名 |
| `endCode` | `string` | 是 | 端。运营端固定 OPS |
| `builtin` | `boolean` | 是 | 内置角色：是 `Perms.java` 的镜像，改了会与回落表分叉 —— 渲染但禁用 |
| `pointCount` | `number` | 是 | 已授予的功能点数 |
| `staffCount` | `number` | 是 | 持有该角色的账号数。 **删角色前唯一能看出「会影响谁」的信息** —— 后端也拦（10441），但那是拦在点下去之后。 |


#### POST `/ops/perm/roles/{roleCode}/delete`

删角色

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/roles/{roleCode}/force-logout`

**强制该角色的成员重新登录**（紧急撤回）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/perm/roles/{roleCode}/points`

某个角色已勾的功能点码

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

**出参**（`data`）

类型：`object`\[\]


#### POST `/ops/perm/roles/{roleCode}/points`

改角色的功能点

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`RoleDef`](#roledef)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。自定义角色不在 `Role` 联合类型里，所以是 string |
| `name` | `string` | 是 | 角色展示名 |
| `endCode` | `string` | 是 | 端。运营端固定 OPS |
| `builtin` | `boolean` | 是 | 内置角色：是 `Perms.java` 的镜像，改了会与回落表分叉 —— 渲染但禁用 |
| `pointCount` | `number` | 是 | 已授予的功能点数 |
| `staffCount` | `number` | 是 | 持有该角色的账号数。 **删角色前唯一能看出「会影响谁」的信息** —— 后端也拦（10441），但那是拦在点下去之后。 |


#### POST `/ops/perm/roles/{roleCode}/rename`

改角色展示名

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`RoleDef`](#roledef)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。自定义角色不在 `Role` 联合类型里，所以是 string |
| `name` | `string` | 是 | 角色展示名 |
| `endCode` | `string` | 是 | 端。运营端固定 OPS |
| `builtin` | `boolean` | 是 | 内置角色：是 `Perms.java` 的镜像，改了会与回落表分叉 —— 渲染但禁用 |
| `pointCount` | `number` | 是 | 已授予的功能点数 |
| `staffCount` | `number` | 是 | 持有该角色的账号数。 **删角色前唯一能看出「会影响谁」的信息** —— 后端也拦（10441），但那是拦在点下去之后。 |


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


#### POST `/ops/staffs`

新建员工

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/staffs/{no}/enabled`

停用/启用（软删除语义，不删账号 —— 审计要能追溯到人）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `roles` | `string`\[\] | 是 | 角色（**可多个**）。权限码取所有角色的并集。 <p>2026-08-12 从单值 `role` 换成数组：库早就支持多角色 （`sys_role_member` 唯一键含 role_code、`Perms.of` 取并集）， 是写接口把它压成了单值。 |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `mustChangePassword` | `boolean` | 否 | 首登必须改密。 建号时后端生成的一次性初始密码只是「拿到账号」的凭据，不是长期口令。 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/staffs/{no}/roles`

改角色（**多角色**）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `roles` | `string`\[\] | 是 | 角色（**可多个**）。权限码取所有角色的并集。 <p>2026-08-12 从单值 `role` 换成数组：库早就支持多角色 （`sys_role_member` 唯一键含 role_code、`Perms.of` 取并集）， 是写接口把它压成了单值。 |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `mustChangePassword` | `boolean` | 否 | 首登必须改密。 建号时后端生成的一次性初始密码只是「拿到账号」的凭据，不是长期口令。 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


#### POST `/ops/staffs/{no}/scope`

数据域授权（P-1.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`Staff`](#staff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `roles` | `string`\[\] | 是 | 角色（**可多个**）。权限码取所有角色的并集。 <p>2026-08-12 从单值 `role` 换成数组：库早就支持多角色 （`sys_role_member` 唯一键含 role_code、`Perms.of` 取并集）， 是写接口把它压成了单值。 |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `mustChangePassword` | `boolean` | 否 | 首登必须改密。 建号时后端生成的一次性初始密码只是「拿到账号」的凭据，不是长期口令。 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |


### inventory

#### GET `/ops/inventory/balances`

**某一个商家**的库存待办（健康度页点进一行之后看的）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvBalanceRow`](#invbalancerow)\[\]


#### GET `/ops/inventory/credentials`

某个商家发过哪些开放对接的钥匙

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvCredential`](#invcredential)\[\]


#### POST `/ops/inventory/credentials`

签发

**入参**

_无字段_

**出参**（`data`）

类型：[`InvCredentialIssued`](#invcredentialissued)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `credentialId` | `string` | 是 | 凭据号 |
| `appKey` | `string` | 是 | 开放接口的调用方标识 |
| `appSecret` | `string` | 是 | 密钥。**只在签发那一次返回**，之后取不回来 |


#### POST `/ops/inventory/credentials/{credentialId}/revoke`

吊销

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `credentialId` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/inventory/health`

库存健康度：负库存 / 零库存仍在架 / 长期未动销

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvHealthRow`](#invhealthrow)\[\]


#### GET `/ops/inventory/ledger`

商家台账（只读）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvLedgerPage`](#invledgerpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entries` | [`#/definitions/InvLedgerRow`](#definitionsinvledgerrow)\[\] | 是 | 本页的台账行 |
| `nextCursor` | `number,null` | 否 | null = 没有下一页 |


#### GET `/ops/inventory/recon`

库存对差

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvReconReport`](#invreconreport)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scannedSkus` | `number` | 是 | 扫了多少个 SKU |
| `moved` | `number` | 是 | 本轮搬动了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |
| `pending` | `number` | 是 | 扫到了但**还没搬**的。**它必须是 0 才准切真相源** —— 没搬的那些在进销存侧余额是 0，切过去就是「全都卖不了」。 这一列原本不存在：`moveOne` 只算不写时故意不把没搬过的算成差异， `doRun` 又把它们计成既不 moved 也不 skipped，于是它们在报告里一个字都不出现， 而 `clean` 只看 diffs —— 闸门守着一个它没在看的东西。 |
| `clean` | `boolean` | 是 | 没有差异**且**没有待搬的。两者缺一都不算干净 |
| `diffs` | [`#/definitions/InvReconDiff`](#definitionsinvrecondiff)\[\] | 是 | 对不上的行 |


### job

#### GET `/ops/jobs`

任务清单：定义与当前状态**已在后端合成一行**

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`JobRow`](#jobrow)\[\]


#### GET `/ops/jobs/${encodeURIComponent(name)}`

getJob

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`JobRow`](#jobrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### PUT `/ops/jobs/${encodeURIComponent(name)}/cron`

改频率

**入参**

_无字段_

**出参**（`data`）

类型：[`JobRow`](#jobrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### POST `/ops/jobs/${encodeURIComponent(name)}/disable`

关

**入参**

_无字段_

**出参**（`data`）

类型：[`JobRow`](#jobrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### POST `/ops/jobs/${encodeURIComponent(name)}/enable`

开

**入参**

_无字段_

**出参**（`data`）

类型：[`JobRow`](#jobrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### POST `/ops/jobs/${encodeURIComponent(name)}/trigger`

立即执行一次

**入参**

_无字段_

**出参**（`data`）

类型：[`JobRow`](#jobrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### GET `/ops/jobs/${encodeURIComponent(q.name)}/logs`

执行日志，倒序

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`JobLogRow`](#joblogrow)\[\]


### marketing

#### GET `/ops/campaigns`

**商家自建的店铺活动**（平台治理视角）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MerchantCampaign`](#merchantcampaign)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/campaigns/{no}/archive`

archiveCampaign

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`MerchantCampaign`](#merchantcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`#/definitions/MerchantCampaignType`](#definitionsmerchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
| `status` | `string` | 是 | RUNNING / ENDED / PAUSED |
| `startAt` | `number` | 是 | 开始时间（毫秒时间戳） |
| `endAt` | `number` | 是 | 结束时间（毫秒时间戳） |
| `goodsNos` | `string`\[\] \| `null` | 否 | 参与的商品号。**列表上只显示条数**，明细进详情看 |


#### POST `/ops/campaigns/{no}/toggle`

停用 / 启用商家活动（矩阵 §2.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`MerchantCampaign`](#merchantcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`#/definitions/MerchantCampaignType`](#definitionsmerchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
| `status` | `string` | 是 | RUNNING / ENDED / PAUSED |
| `startAt` | `number` | 是 | 开始时间（毫秒时间戳） |
| `endAt` | `number` | 是 | 结束时间（毫秒时间戳） |
| `goodsNos` | `string`\[\] \| `null` | 否 | 参与的商品号。**列表上只显示条数**，明细进详情看 |


#### POST `/ops/campaigns/{no}/unarchive`

unarchiveCampaign

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`MerchantCampaign`](#merchantcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`#/definitions/MerchantCampaignType`](#definitionsmerchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
| `status` | `string` | 是 | RUNNING / ENDED / PAUSED |
| `startAt` | `number` | 是 | 开始时间（毫秒时间戳） |
| `endAt` | `number` | 是 | 结束时间（毫秒时间戳） |
| `goodsNos` | `string`\[\] \| `null` | 否 | 参与的商品号。**列表上只显示条数**，明细进详情看 |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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


#### POST `/ops/coupons`

建券 / 改券（TDD-营销预算前置）

**入参**

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |


#### POST `/ops/coupons/{couponNo}/issue`

主动发券（P-7.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `couponNo` | path | `string` | 是 | 券单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |


#### POST `/ops/coupons/{no}/budget`

调预算（P-7.1.3）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |


#### POST `/ops/coupons/{no}/status`

改券状态（暂停 / 恢复 / 结束）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |


#### POST `/ops/coupons/{no}/unarchive`

unarchiveCoupon

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |


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
| `cardNo` | path | `string` | 是 | 卡号 / 会员卡单号 |

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
| `cardNo` | path | `string` | 是 | 卡号 / 会员卡单号 |

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
| `cardNo` | path | `string` | 是 | 卡号 / 会员卡单号 |

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


### member

#### GET `/ops/members`

跨商家会员名单

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OpsPerson`](#opsperson)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `personNo` | `string` | 是 | 平台人档号 |
| `phoneTail` | `string,null` | 是 | 手机号后四位。**永远不给完整号** |
| `userNo` | `string,null` | 是 | 用户号 |
| `memberships` | [`#/definitions/OpsMember`](#definitionsopsmember)\[\] | 是 | 他在各商家的会员关系。**一份人档串起几家** —— 这正是人档存在的理由 |
| `merges` | `string`\[\] | 是 | 合并过的人档号。合并不可逆，留痕是唯一的回溯手段 |


#### GET `/ops/members/reach-stats`

触达量与退订率，**按退订率倒序** —— 发得多不是成绩，发到有人关掉才是问题 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ReachStat`](#reachstat)\[\]


#### GET `/ops/persons/{personNo}`

人档：他是哪几家店的会员 —— 这正是人档存在的理由 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `personNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：`object`


#### POST `/ops/persons/{personNo}/reveal-phone`

查看完整手机号（申诉处置）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `personNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/promotion/activities`

全平台活动（新模型）：归属、受众、限量 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OpsPromoActivity`](#opspromoactivity)\[\]


#### POST `/ops/promotion/activities/{activityNo}/stop`

强制停止一个活动

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `activityNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`OpsPromoActivity`](#opspromoactivity)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | 活动号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `entityName` | `string` | 是 | 商家名 |
| `name` | `string` | 是 | 活动名 |
| `triggerType` | `string` | 是 | 触发条件：满额 / 满件 / 命中商品 / 无条件 |
| `benefitType` | `string` | 是 | 优惠方式：减钱 / 改单价 / 送商品 / 发券 |
| `scheduleType` | `string` | 是 | 排期：短期 / 长期 / 周期 |
| `quota` | `number,null` | 是 | 限量。空 = 不限量 |
| `quotaUsed` | `number` | 是 | 已用掉的限量 |
| `budgetMinor` | `number,null` | 是 | 预算上限（分）。空 = 不限 |
| `budgetUsedMinor` | `number` | 是 | 已花掉的预算（分） |
| `audienceCount` | `number` | 是 | 定向人数。**0 表示对所有人生效**，不是「谁也不发」 |
| `status` | `string` | 是 | 状态 |
| `endedReason` | `string,null` | 是 | 为什么停的：到期 / 限量用尽 / 预算用尽 / 人工停。商家问「怎么停了」要有答案 |
| `flags` | `string`\[\] | 是 | 风险标记。商家自己看不出来 —— 他只看得到他那一张，跨商家排在一起才看得见 |


#### GET `/ops/promotion/coupons`

全平台券（新模型）：归属、敞口、异常标记 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OpsPromoCoupon`](#opspromocoupon)\[\]


### merchant

#### GET `/ops/admission/deposits/{merchantNo}`

merchantDeposit

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`MerchantDeposit`](#merchantdeposit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体 |
| `paidMinor` | `number` | 是 | 实缴（分） |
| `frozenMinor` | `number` | 是 | 理赔冻结中（分） |
| `availableMinor` | `number` | 是 | 可用（分）= 实缴 − 冻结。**判够不够用它，不用实缴** |
| `requiredMinor` | `number` | 是 | 本档位应缴（分）；0 = 免缴 |
| `sufficient` | `boolean` | 是 | 可用是否已达应缴。不足则该商家不能上架 |
| `singleOrderLimitMinor` | `number` | 是 | 单笔限额（分）；0 = 不限 |
| `dailyAmountLimitMinor` | `number` | 是 | 日累计限额（分）；0 = 不限 |


#### GET `/ops/admission/deposits/{merchantNo}/txns`

depositTxns

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`DepositTxn`](#deposittxn)\[\]


#### POST `/ops/admission/deposits/{merchantNo}/txns`

addDepositTxn

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/admission/pay-quotas/{merchantNo}`

当前收款额度

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`PayQuota`](#payquota)\[\]


#### PUT `/ops/admission/pay-quotas/{merchantNo}`

设置收款额度上限

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/admission/policies`

三档准入策略

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AdmissionPolicy`](#admissionpolicy)\[\]


#### PUT `/ops/admission/policies/{legalForm}`

updateAdmissionPolicy

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `legalForm` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/merchant-plans`

到期与降级看板

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MerchantPlanRow`](#merchantplanrow)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/merchant-plans/{merchantNo}/grant`

授予 / 延长

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`MerchantPlanRow`](#merchantplanrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体号 |
| `merchantName` | `string` | 是 | 商家名 |
| `planCode` | `string` | 是 | 档位码。**文案用 name/planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `storeQuota` | `number` | 是 | 生效额度（覆盖值优先于快照）。与 storeUsed 一起显示成 2/3 |
| `staffQuota` | `number` | 是 | 员工数配额 |
| `storeUsed` | `number` | 是 | 已用门店数。**只数 ACTIVE**，与建店时那道额度闸同一口径 |
| `staffUsed` | `number` | 是 | 已用员工数 |
| `crossStoreStats` | `boolean` | 是 | 这一档给不给跨店统计 |
| `status` | [`#/definitions/PlanStatus`](#definitionsplanstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`#/definitions/PlanQuotaSource`](#definitionsplanquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |


#### PUT `/ops/merchant-plans/{merchantNo}/quota`

单商家额度覆盖

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`MerchantPlanRow`](#merchantplanrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体号 |
| `merchantName` | `string` | 是 | 商家名 |
| `planCode` | `string` | 是 | 档位码。**文案用 name/planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `storeQuota` | `number` | 是 | 生效额度（覆盖值优先于快照）。与 storeUsed 一起显示成 2/3 |
| `staffQuota` | `number` | 是 | 员工数配额 |
| `storeUsed` | `number` | 是 | 已用门店数。**只数 ACTIVE**，与建店时那道额度闸同一口径 |
| `staffUsed` | `number` | 是 | 已用员工数 |
| `crossStoreStats` | `boolean` | 是 | 这一档给不给跨店统计 |
| `status` | [`#/definitions/PlanStatus`](#definitionsplanstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`#/definitions/PlanQuotaSource`](#definitionsplanquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |


#### GET `/ops/merchant-plans/upgrade-signals`

升档信号：一个人名下多个主体 = 他已经在多店经营，只是绕过了额度

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PlanUpgradeSignal`](#planupgradesignal)\[\]


#### POST `/ops/merchant/apply/{applyNo}/accept`

受理：告诉商家「有人在看了」

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `applyNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/merchant/apply/{applyNo}/audit`

审核

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `applyNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/merchant/apply/search`

入驻申请检索

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MerchantApply`](#merchantapply)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


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
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### POST `/ops/merchants/{merchantNo}/archive`

archiveMerchant

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### PUT `/ops/merchants/{merchantNo}/auth-codes`

全量覆盖经营授权码

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`AuthCodeSetResult`](#authcodesetresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `codes` | `string`\[\] | 是 | 改完之后持有的码（全量） |
| `revoked` | `string`\[\] | 是 | 这次撤掉的码。空数组 = 只加不减 |
| `affected` | `number` | 是 | 因撤码而下次上架会被拒的在架商品数 |


#### GET `/ops/merchants/{merchantNo}/fulfillment`

商家履约配置（方案 v4，**只读**）：门店 × 送货方式矩阵

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`StoreFulfillmentRow`](#storefulfillmentrow)\[\]


#### PUT `/ops/merchants/{merchantNo}/funds-mode`

改资金路径

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### GET `/ops/merchants/{merchantNo}/qualifications`

某商家已登记的资质

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`Qualification`](#qualification)\[\]


#### POST `/ops/merchants/{merchantNo}/qualifications`

登记或更新

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Qualification`](#qualification)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qualNo` | `string` | 是 | 资质记录号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `qualType` | `string` | 是 | 证件类型 |
| `qualName` | `string` | 是 | 证件名。**要与 sys_auth_code.required_qualification 同一套字面量** —— 类目授权按名字比对 |
| `qualNumber` | `string` | 否 | 证件编号，证上印的那一串 |
| `imageUrl` | `string` | 否 | 图片地址 |
| `expireAt` | `number,null` | 否 | null = 长期有效。与「已过期」是两回事，扫描任务不碰它 |
| `status` | `string` | 是 | VALID / EXPIRED / REVOKED |


#### GET `/ops/merchants/{merchantNo}/staff`

这家商家的员工与门店授权（**只读**）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`MerchantStaffRow`](#merchantstaffrow)\[\]


#### POST `/ops/merchants/{merchantNo}/status`

审核推进

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### GET `/ops/merchants/{merchantNo}/store-modes`

storeModes

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`StoreMode`](#storemode)\[\]


#### POST `/ops/merchants/{merchantNo}/unarchive`

unarchiveMerchant

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### POST `/ops/merchants/{merchantNo}/verified`

认证标授予/撤销（P-11.1.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |


#### POST `/ops/merchants/{merchantNo}/violations`

记一条违规并执行处置

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

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
| `storeNo` | `string,null` | 否 | 门店级处置的对象门店。**`STORE_OFFLINE` 必有、其余动作必空** —— 主体级处置带上门店号会让人以为只压了那一家。 |
| `detail` | `string` | 是 | 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 |
| `operator` | `string` | 是 | 处置人（STAFF 账号） |
| `at` | `string` | 是 | 处置时间 |


#### GET `/ops/merchants/auth-codes`

授权码目录

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AuthCode`](#authcode)\[\]


#### GET `/ops/merchants/mode-risk`

无照主体 × 自营门店的税务敞口清单

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ModeRisk`](#moderisk)\[\]


#### GET `/ops/merchants/violations`

listViolations

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Violation`](#violation)\[\]


#### GET `/ops/onboarding`

进件看板

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`OnboardingRow`](#onboardingrow)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/onboarding/refresh`

人工回查：替卡在进件上的商家去通道问一次结果并落库

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/plan-defs`

档位定义

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PlanDef`](#plandef)\[\]


#### PUT `/ops/plan-defs/{planCode}`

改档位定义

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `planCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`PlanDef`](#plandef)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | 档位码。**文案用 name/planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `name` | `string` | 是 | 名称 |
| `storeQuota` | `number` | 是 | 门店数配额 |
| `staffQuota` | `number` | 是 | 员工数配额 |
| `crossStoreStats` | `boolean` | 是 | 这一档给不给跨店统计 |
| `trialDays` | `number` | 是 | 试用天数。0 = 这一档不提供试用 |
| `enabled` | `boolean` | 是 | 启用中 |
| `subscriberCount` | `number` | 是 | 当前有几家在用这一档。 **改定义的人必须看得到这个数** —— 它是「只影响之后新订阅的人」那句话的具体量。 不给这个数，改档位的人只能凭感觉判断影响面。 |


#### POST `/ops/qualifications/{qualNo}/revoke`

撤销

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `qualNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Qualification`](#qualification)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qualNo` | `string` | 是 | 资质记录号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `qualType` | `string` | 是 | 证件类型 |
| `qualName` | `string` | 是 | 证件名。**要与 sys_auth_code.required_qualification 同一套字面量** —— 类目授权按名字比对 |
| `qualNumber` | `string` | 否 | 证件编号，证上印的那一串 |
| `imageUrl` | `string` | 否 | 图片地址 |
| `expireAt` | `number,null` | 否 | null = 长期有效。与「已过期」是两回事，扫描任务不碰它 |
| `status` | `string` | 是 | VALID / EXPIRED / REVOKED |


#### PUT `/ops/stores/{storeNo}/business-mode`

改门店经营模式

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`StoreMode`](#storemode)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string` | 是 | 门店名，展示用 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 是 | 自营 / 第三方；空 = 尚未设置 |
| `payMerchantNo` | `string,null` | 是 | 该店实际可用的收款号（本店专属号优先，回落到主体默认号）。**空 = 不能切第三方** |


#### POST `/ops/stores/{storeNo}/channels/{channel}/lock`

lockChannel

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |
| `channel` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/stores/{storeNo}/channels/{channel}/unlock`

unlockChannel

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |
| `channel` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


### message

#### GET `/ops/captcha`

取一张图形验证码

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Captcha`](#captcha)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `captchaId` | `string` | 是 | 验证码会话号，校验时要带回来 |
| `imageBase64` | `string` | 是 | 图形验证码的图，base64 |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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


#### GET `/ops/inapp-messages`

站内信记录

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`InAppLog`](#inapplog)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/message`

listInbox

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InboxMessage`](#inboxmessage)\[\]


#### POST `/ops/message/{no}/read`

readInbox

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`InboxMessage`](#inboxmessage)\[\]


#### POST `/ops/message/read-all`

readAllInbox

**入参**

_无字段_

**出参**（`data`）

类型：[`InboxMessage`](#inboxmessage)\[\]


#### GET `/ops/message/unread-count`

未读数

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`integer`


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`MsgTemplate`](#msgtemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `channel` | [`#/definitions/MsgChannel`](#definitionsmsgchannel) | 是 | 触达渠道 |
| `lang` | `string` | 否 | 语言（zh-CN / en / ar）。 <p>同一个 templateNo 每种语言一行（V145）——**列表上必须显示它**， 否则运营看到的是两条一模一样的模板，改了其中一条还发现"没生效"。 |
| `content` | `string` | 是 | 模板正文，含 {占位符}。**模拟发送靠它展示「会发出什么」并做预览** |
| `providerTemplateId` | `string,null` | 否 | 渠道侧模板 ID（阿里云 `SMS_xxx` / 微信模板号）。站内信为空。 <p>后端 `TemplateVO` 一直有这个字段，端上类型此前漏了 —— 于是页面拿不到它， 而它正是运营核对「我们发的是哪个报备模板」的唯一凭据。 |
| `enabled` | `boolean` | 是 | 是否启用。停用后引用它的推送任务发不出去 |
| `sentCount` | `number` | 是 | 近 30 天发送量 |


#### GET `/ops/notify-channels`

四条通道的体检：开没开、凭据齐不齐、今天发了多少

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`NotifyChannelHealth`](#notifychannelhealth)\[\]


#### GET `/ops/notify-channels/default-lang`

getDefaultLang

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/notify-channels/default-lang`

saveDefaultLang

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/notify-channels/registry`

渠道注册表（触达推送中台 N2）：类型×供应商×接入范围×归属 + 读时派生状态

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`NotifyChannelRow`](#notifychannelrow)\[\]


#### POST `/ops/notify-channels/registry/{channelNo}/enabled`

软启停某条渠道（N2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `channelNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`NotifyChannelRow`](#notifychannelrow)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channelNo` | `string` | 是 | 渠道编号（业务主键，启停用它） |
| `channelType` | `string` | 是 | SMS / MAIL / WXSUB / PUSH / INAPP |
| `provider` | `string` | 是 | ALI / SMTP / WECHAT / GETUI / FCM / APNS / INTERNAL |
| `scope` | `string` | 是 | 接入范围 PLATFORM / MERCHANT / TEST |
| `ownerNo` | `string` | 是 | scope=MERCHANT 的商家号；平台/测试为空串 |
| `enabled` | `boolean` | 是 | 软开关（运营即时启停） |
| `status` | `string` | 是 | 读时派生 UNCONFIGURED / STUB / READY / DISABLED / DEGRADED |
| `priority` | `number` | 是 | 同类型同供应商多实例的选择优先级，小者先 |
| `credRef` | `string,null` | 否 | 凭据引用（env 前缀），不含密钥明文；可空 |
| `configJson` | `string` | 是 | 非密参数（签名/模板号/topic），JSON 串 |
| `missingCreds` | `string`\[\] | 是 | 平台接入还缺哪些环境变量（供运维照配）；商家/测试接入为空 |
| `locked` | `boolean` | 是 | INAPP 恒锁定：站内信不可关 |


#### GET `/ops/notify-channels/wx-templates`

getWxTemplates

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`WxTemplates`](#wxtemplates)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderArrived` | `string` | 是 | 「订单已送达」用的微信模板 id |
| `refunded` | `string` | 是 | 「退款成功」用的微信模板 id |


#### POST `/ops/notify-channels/wx-templates`

保存微信模板号

**入参**

_无字段_

**出参**（`data`）

类型：[`WxTemplates`](#wxtemplates)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderArrived` | `string` | 是 | 「订单已送达」用的微信模板 id |
| `refunded` | `string` | 是 | 「退款成功」用的微信模板 id |


#### GET `/ops/notify-logs`

发送记录（P-14.3）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`NotifyLog`](#notifylog)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/notify-logs/precheck`

收件人预检

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/notify-logs/push-devices`

某收件人绑定的推送终端列表（仅 PUSH 测试用）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PushDevice`](#pushdevice)\[\]


#### POST `/ops/notify-logs/test-inapp`

站内信的模拟发送：往某个收件箱塞一条

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/notify-logs/test-send`

测试发送

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/notify-quota`

发送推送（P-14.1.2）

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

营销广播任务列表（N6）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`NotifyPushTask`](#notifypushtask)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/push-tasks`

新建广播（N6）

**入参**

_无字段_

**出参**（`data`）

类型：[`NotifyPushTask`](#notifypushtask)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务号 |
| `name` | `string` | 是 | 任务名（运营自己看的） |
| `audienceType` | `string` | 是 | 人群 ALL_APP_USER（消费者）/ ALL_STAFF（商家员工） |
| `channel` | `string` | 是 | 下发通道，一期仅 PUSH |
| `title` | `string` | 是 | 标题 |
| `body` | `string` | 是 | 正文 |
| `link` | `string,null` | 否 | 点开落点，可空 |
| `scheduledAt` | `string,null` | 否 | 定时下发时刻 ISO；空=尽快发 |
| `status` | `string` | 是 | QUEUED / RUNNING / DONE / CANCELLED |
| `estimatedCount` | `number` | 是 | 创建时预估触达人数 |
| `sentCount` | `number` | 是 | 实际发出条数 |
| `finishedAt` | `string,null` | 否 | 结束时刻。空 = 还在发 |


#### POST `/ops/push-tasks/{taskNo}/cancel`

取消广播（仅 QUEUED 可取消）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `taskNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`NotifyPushTask`](#notifypushtask)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务号 |
| `name` | `string` | 是 | 任务名（运营自己看的） |
| `audienceType` | `string` | 是 | 人群 ALL_APP_USER（消费者）/ ALL_STAFF（商家员工） |
| `channel` | `string` | 是 | 下发通道，一期仅 PUSH |
| `title` | `string` | 是 | 标题 |
| `body` | `string` | 是 | 正文 |
| `link` | `string,null` | 否 | 点开落点，可空 |
| `scheduledAt` | `string,null` | 否 | 定时下发时刻 ISO；空=尽快发 |
| `status` | `string` | 是 | QUEUED / RUNNING / DONE / CANCELLED |
| `estimatedCount` | `number` | 是 | 创建时预估触达人数 |
| `sentCount` | `number` | 是 | 实际发出条数 |
| `finishedAt` | `string,null` | 否 | 结束时刻。空 = 还在发 |


#### GET `/ops/push-tasks/estimate`

预估触达：**建任务前**先看某人群当下覆盖多少人（N6b）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/scene-channel`

场景×通道矩阵

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SceneChannelCell`](#scenechannelcell)\[\]


#### POST `/ops/scene-channel/{scene}/{audience}/{channel}`

切换某一格

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `scene` | path | `string` | 是 | — |
| `audience` | path | `string` | 是 | — |
| `channel` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`SceneChannelCell`](#scenechannelcell)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scene` | `string` | 是 | 场景码（订单已支付、售后已受理…） |
| `audience` | `string` | 是 | 受众：买家 / 商家 / 运营 |
| `channel` | `string` | 是 | 通道 |
| `enabled` | `boolean` | 是 | 启用中 |
| `pushLevel` | `string` | 是 | 推送等级（App 推送用；其它通道为空） |
| `locked` | `boolean` | 是 | **恒锁定的格子**。站内信（INAPP）是事实记录，运营不可关 —— 后端会拒掉这一格的关闭请求，前端被绕过也兜得住，界面只是别让人白点。 |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |


#### POST `/ops/tickets/{no}/close`

closeTicket

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |


#### POST `/ops/tickets/{no}/proxy-actions`

记录代客操作（P-14.2.3）：谁、对什么、做了什么

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |


#### POST `/ops/tickets/{no}/reply`

客服回复（P-14.2.2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |


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
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

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
| `fulfillType` | [`#/definitions/FulfillmentType`](#definitionsfulfillmenttype) | 是 | 履约方式 |
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
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

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
| `fulfillType` | [`#/definitions/FulfillmentType`](#definitionsfulfillmenttype) | 是 | 履约方式 |
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
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`OrderIntervention`](#orderintervention)\[\]


#### POST `/ops/orders/{orderNo}/proxy-cancel`

代客取消

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

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
| `fulfillType` | [`#/definitions/FulfillmentType`](#definitionsfulfillmenttype) | 是 | 履约方式 |
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
| `parentNo` | path | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |

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
| `fulfillType` | [`#/definitions/FulfillmentType`](#definitionsfulfillmenttype) | 是 | 履约方式 |
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


#### GET `/ops/payments/recon-axes`

四条轴各跑一轮

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ReconAxisReport`](#reconaxisreport)\[\]


#### GET `/ops/payments/recon-coverage`

对账覆盖范围

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ReconCoverage`](#reconcoverage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channelBillConnected` | `boolean` | 是 | 渠道账单是否已接入。false 时 note 必须显示给运营 |
| `note` | `string` | 是 | 说明 |


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
| `diffNo` | path | `string` | 是 | 对账差异单号 |

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
| `diffNo` | path | `string` | 是 | 对账差异单号 |

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

新建 / 改类目

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
| `sort` | `number` | 是 | 同级内的展示顺序，小的在前。**C 端类目栏就按它排** —— 不下发就等于运营改不了顺序，「把生鲜挪到第一个」只能改库。 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### POST `/ops/categories/{no}/archive`

有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `sort` | `number` | 是 | 同级内的展示顺序，小的在前。**C 端类目栏就按它排** —— 不下发就等于运营改不了顺序，「把生鲜挪到第一个」只能改库。 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### GET `/ops/categories/{no}/archive-impact`

停用一个类目会影响什么

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：[`CategoryArchiveImpact`](#categoryarchiveimpact)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsCount` | `number` | 是 | 这个类目下有几件商品 |
| `onSaleCount` | `number` | 是 | 其中在架几件。**归档前要看** —— 在架的会一起下架 |
| `activeChildren` | `number` | 是 | 还开着的子类目数。**大于 0 时后端仍会拒** —— 会冒出渲染不出来的孤儿节点 |


#### POST `/ops/categories/{no}/unarchive`

unarchiveCategory

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `sort` | `number` | 是 | 同级内的展示顺序，小的在前。**C 端类目栏就按它排** —— 不下发就等于运营改不了顺序，「把生鲜挪到第一个」只能改库。 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |


#### GET `/ops/category-pay-modes`

类目 × 支付方式

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CategoryPayMode`](#categorypaymode)\[\]


#### POST `/ops/category-pay-modes/{categoryNo}`

saveCategoryPayMode

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `categoryNo` | path | `string` | 是 | 类目单号 |

_无字段_

**出参**（`data`）

类型：[`CategoryPayMode`](#categorypaymode)\[\]


#### GET `/ops/category-points`

类目 × 积分

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CategoryPoints`](#categorypoints)\[\]


#### POST `/ops/category-points/{categoryNo}`

`earnMode` 传 null = 清除这条规则，回到平台兜底 */

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `categoryNo` | path | `string` | 是 | 类目单号 |

_无字段_

**出参**（`data`）

类型：[`CategoryPoints`](#categorypoints)\[\]


#### GET `/ops/category-specs`

类目 × 规格总览（规格库 V195）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CategorySpec`](#categoryspec)\[\]


#### POST `/ops/category-specs/{categoryNo}`

整份替换一个类目的绑定 */

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `categoryNo` | path | `string` | 是 | 类目单号 |

_无字段_

**出参**（`data`）

类型：[`CategorySpec`](#categoryspec)\[\]


#### POST `/ops/community-pool/resync`

重建社区池（「这件商品出现在哪些社区」的派生索引）

**入参**

_无字段_

**出参**（`data`）

类型：`integer`


#### GET `/ops/goods`

商品池：按商家/类目/关键词/状态筛，goods 粒度（每行一个商品，SKU 嵌在 `skus[]` 里）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ProductGoods`](#productgoods)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/goods/{goodsNo}`

商品详情：三语文案、SKU 矩阵、规格组、驳回原因，审核抽屉读的就是它

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`GoodsDetail`](#goodsdetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 标题（按当前语言拍平后的那一份） |
| `subtitle` | `string` | 否 | 副标题 / 卖点 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 是 | 详情图。后端必发（可能是空数组） |
| `type` | `string` | 是 | 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD |
| `categoryNo` | `string` | 否 | 平台类目 |
| `merchant` | `object`（见下） | 否 | 归属商家 brief —— 审核要看得到是谁上的架 |
| `titleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`#/definitions/GoodsDetailSku`](#definitionsgoodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |


#### POST `/ops/goods/{goodsNo}/audit`

审核商品

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

_无字段_

**出参**（`data`）

类型：[`GoodsAudit`](#goodsaudit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号。审核动作打在它上面 |
| `title` | `string` | 是 | 标题。审核先看它 —— 违规多半从标题就能看出来 |
| `subtitle` | `string` | 否 | 副标题/卖点 |
| `cover` | `string` | 否 | 封面图。图文不符是驳回的主因之一，所以要能看到图 |
| `type` | `string` | 是 | 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD |
| `categoryNo` | `string` | 否 | 平台类目。**当前恒为空** —— 商品编辑页还没有选类目这一步 |
| `merchant` | `object`（见下） | 否 | 归属商家（后端下发的是一个 brief 对象，不是裸的 merchantNo）—— 审核时要看得到是谁上的架：同一个商家反复交同类违规品是有信号的。 |
| `status` | `string` | 否 | 商品状态。**字段名是 `status` 不是 `auditStatus`** —— 后端 `GoodsVO` 里它同时承载审核态与上下架态：AUDITING / ON_SALE / OFF_SALE / REJECTED。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |


#### GET `/ops/goods/{goodsNo}/draft-preview`

待审草稿的字段级差异（双版本）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：`object`


#### POST `/ops/goods/{goodsNo}/force-off`

平台强制下架（P-3.2.3），goods 粒度 = **撤销过审**：商品回到 `REJECTED`

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

_无字段_

**出参**（`data`）

类型：[`GoodsDetail`](#goodsdetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 标题（按当前语言拍平后的那一份） |
| `subtitle` | `string` | 否 | 副标题 / 卖点 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 是 | 详情图。后端必发（可能是空数组） |
| `type` | `string` | 是 | 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD |
| `categoryNo` | `string` | 否 | 平台类目 |
| `merchant` | `object`（见下） | 否 | 归属商家 brief —— 审核要看得到是谁上的架 |
| `titleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`#/definitions/GoodsDetailSku`](#definitionsgoodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |


#### GET `/ops/goods/audit-queue`

待审队列

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`GoodsAudit`](#goodsaudit)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/skus`

sku 粒度全量查询

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

商品审核（P-3.2.2），sku 粒度入口

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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

平台**压下架**（P-3.2.3）：必须带原因，原样进商家 B 端

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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


#### GET `/ops/spec-dims`

listSpecDims

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SpecDim`](#specdim)\[\]


#### POST `/ops/spec-dims`

saveSpecDim

**入参**

_无字段_

**出参**（`data`）

类型：[`SpecDim`](#specdim)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，**改码等于换一根聚合轴** |
| `name` | `string` | 是 | 维度名（「颜色」「净重」） |
| `valueType` | `string` | 是 | ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有归一量 |
| `unit` | `string,null` | 否 | 单位。QUANT 型必填，ENUM 型为空 |
| `usageType` | `string` | 是 | SALE 进 SKU 笛卡尔积 / PROP 只是描述 |
| `universal` | `boolean` | 是 | 通用维度：所有类目都能用 |
| `scope` | `string` | 是 | `PLATFORM` 平台的 / `MERCHANT` 商家自建的 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `valueCount` | `number` | 是 | 这个维度下有几个取值 |
| `inUse` | `number` | 是 | 被几个类目绑着 —— 归档前要知道自己在动多大范围 |
| `values` | [`#/definitions/SpecValue`](#definitionsspecvalue)\[\] | 是 | 取值列表 |


#### POST `/ops/spec-dims/{no}/archive`

archiveSpecDim

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecDim`](#specdim)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，**改码等于换一根聚合轴** |
| `name` | `string` | 是 | 维度名（「颜色」「净重」） |
| `valueType` | `string` | 是 | ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有归一量 |
| `unit` | `string,null` | 否 | 单位。QUANT 型必填，ENUM 型为空 |
| `usageType` | `string` | 是 | SALE 进 SKU 笛卡尔积 / PROP 只是描述 |
| `universal` | `boolean` | 是 | 通用维度：所有类目都能用 |
| `scope` | `string` | 是 | `PLATFORM` 平台的 / `MERCHANT` 商家自建的 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `valueCount` | `number` | 是 | 这个维度下有几个取值 |
| `inUse` | `number` | 是 | 被几个类目绑着 —— 归档前要知道自己在动多大范围 |
| `values` | [`#/definitions/SpecValue`](#definitionsspecvalue)\[\] | 是 | 取值列表 |


#### POST `/ops/spec-dims/{no}/unarchive`

unarchiveSpecDim

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecDim`](#specdim)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，**改码等于换一根聚合轴** |
| `name` | `string` | 是 | 维度名（「颜色」「净重」） |
| `valueType` | `string` | 是 | ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有归一量 |
| `unit` | `string,null` | 否 | 单位。QUANT 型必填，ENUM 型为空 |
| `usageType` | `string` | 是 | SALE 进 SKU 笛卡尔积 / PROP 只是描述 |
| `universal` | `boolean` | 是 | 通用维度：所有类目都能用 |
| `scope` | `string` | 是 | `PLATFORM` 平台的 / `MERCHANT` 商家自建的 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `valueCount` | `number` | 是 | 这个维度下有几个取值 |
| `inUse` | `number` | 是 | 被几个类目绑着 —— 归档前要知道自己在动多大范围 |
| `values` | [`#/definitions/SpecValue`](#definitionsspecvalue)\[\] | 是 | 取值列表 |


#### GET `/ops/spec-templates`

平台模板列表

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`SpecTemplate`](#spectemplate)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/spec-templates`

新建或更新（`templateNo` 为空即新建）

**入参**

_无字段_

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `string` | 是 | 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 |
| `categoryType` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`#/definitions/SpecTemplateOption`](#definitionsspectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
| `createdAt` | `string` | 否 | 创建时刻 |


#### POST `/ops/spec-templates/{no}/archive`

归档：商家侧立刻不再下发

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `string` | 是 | 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 |
| `categoryType` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`#/definitions/SpecTemplateOption`](#definitionsspectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
| `createdAt` | `string` | 否 | 创建时刻 |


#### POST `/ops/spec-templates/{no}/unarchive`

unarchiveSpecTemplate

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `string` | 是 | 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 |
| `categoryType` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`#/definitions/SpecTemplateOption`](#definitionsspectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
| `createdAt` | `string` | 否 | 创建时刻 |


#### POST `/ops/spec-values`

saveSpecValue

**入参**

_无字段_

**出参**（`data`）

类型：[`SpecValue`](#specvalue)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 取值编号 |
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 |
| `label` | `string` | 是 | 显示名 |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |
| `aliases` | `string`\[\] | 是 | 别名：识别、搜索与自动归一用 |
| `scope` | `string` | 是 | PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `merchantCount` | `number` | 是 | 多少个商家在用这个值 —— 停用前要知道影响面 |


#### POST `/ops/spec-values/{no}/archive`

archiveSpecValue

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecValue`](#specvalue)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 取值编号 |
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 |
| `label` | `string` | 是 | 显示名 |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |
| `aliases` | `string`\[\] | 是 | 别名：识别、搜索与自动归一用 |
| `scope` | `string` | 是 | PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `merchantCount` | `number` | 是 | 多少个商家在用这个值 —— 停用前要知道影响面 |


#### POST `/ops/spec-values/{no}/promote`

商家自有值 → 平台值

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecValue`](#specvalue)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 取值编号 |
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 |
| `label` | `string` | 是 | 显示名 |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |
| `aliases` | `string`\[\] | 是 | 别名：识别、搜索与自动归一用 |
| `scope` | `string` | 是 | PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `merchantCount` | `number` | 是 | 多少个商家在用这个值 —— 停用前要知道影响面 |


#### POST `/ops/spec-values/{no}/unarchive`

unarchiveSpecValue

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpecValue`](#specvalue)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 取值编号 |
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 |
| `label` | `string` | 是 | 显示名 |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |
| `aliases` | `string`\[\] | 是 | 别名：识别、搜索与自动归一用 |
| `scope` | `string` | 是 | PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `merchantCount` | `number` | 是 | 多少个商家在用这个值 —— 停用前要知道影响面 |


#### GET `/ops/spu-std`

标准品列表

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`SpuStd`](#spustd)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/spu-std`

新建 / 更新

**入参**

_无字段_

**出参**（`data`）

类型：[`SpuStd`](#spustd)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `stdNo` | `string` | 是 | 标准品号 |
| `categoryNo` | `string` | 是 | 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 |
| `categoryName` | `string` | 否 | 类目名 |
| `title` | `string` | 是 | 标题 |
| `titleI18n` | [`#/definitions/Record<string,string>`](#definitionsrecordstringstring) | 否 | 标题的多语言版本 |
| `subtitle` | `string` | 否 | 副标题 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 否 | 图集 |
| `specGroups` | `object`（见下）\[\] | 是 | 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 |
| `keywords` | `string` | 否 | 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 |
| `status` | `string` | 否 | 状态 |
| `refCount` | `number` | 否 | 被引用次数。只服务排序与去重判断，不参与任何校验 |
| `barcode` | `string` | 否 | 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 |
| `source` | `string` | 否 | 出处：`OPS` 运营手录 / `OFF` 从开放库导入。 <p>导进来的那批标题是原始众包文案（品牌写法不一、错别字都有）， 所以全部落成归档态等人过目。运营靠这一列把「还没人看过的」与「自己录的」分开审。 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |
| `optionCodes` | `string`\[\] | 否 | — |
| `templateNo` | `string` | 否 | — |


#### POST `/ops/spu-std/{no}/archive`

归档

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpuStd`](#spustd)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `stdNo` | `string` | 是 | 标准品号 |
| `categoryNo` | `string` | 是 | 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 |
| `categoryName` | `string` | 否 | 类目名 |
| `title` | `string` | 是 | 标题 |
| `titleI18n` | [`#/definitions/Record<string,string>`](#definitionsrecordstringstring) | 否 | 标题的多语言版本 |
| `subtitle` | `string` | 否 | 副标题 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 否 | 图集 |
| `specGroups` | `object`（见下）\[\] | 是 | 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 |
| `keywords` | `string` | 否 | 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 |
| `status` | `string` | 否 | 状态 |
| `refCount` | `number` | 否 | 被引用次数。只服务排序与去重判断，不参与任何校验 |
| `barcode` | `string` | 否 | 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 |
| `source` | `string` | 否 | 出处：`OPS` 运营手录 / `OFF` 从开放库导入。 <p>导进来的那批标题是原始众包文案（品牌写法不一、错别字都有）， 所以全部落成归档态等人过目。运营靠这一列把「还没人看过的」与「自己录的」分开审。 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |
| `optionCodes` | `string`\[\] | 否 | — |
| `templateNo` | `string` | 否 | — |


#### POST `/ops/spu-std/{no}/unarchive`

unarchiveSpuStd

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`SpuStd`](#spustd)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `stdNo` | `string` | 是 | 标准品号 |
| `categoryNo` | `string` | 是 | 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 |
| `categoryName` | `string` | 否 | 类目名 |
| `title` | `string` | 是 | 标题 |
| `titleI18n` | [`#/definitions/Record<string,string>`](#definitionsrecordstringstring) | 否 | 标题的多语言版本 |
| `subtitle` | `string` | 否 | 副标题 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 否 | 图集 |
| `specGroups` | `object`（见下）\[\] | 是 | 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 |
| `keywords` | `string` | 否 | 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 |
| `status` | `string` | 否 | 状态 |
| `refCount` | `number` | 否 | 被引用次数。只服务排序与去重判断，不参与任何校验 |
| `barcode` | `string` | 否 | 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 |
| `source` | `string` | 否 | 出处：`OPS` 运营手录 / `OFF` 从开放库导入。 <p>导进来的那批标题是原始众包文案（品牌写法不一、错别字都有）， 所以全部落成归档态等人过目。运营靠这一列把「还没人看过的」与「自己录的」分开审。 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |
| `optionCodes` | `string`\[\] | 否 | — |
| `templateNo` | `string` | 否 | — |


#### POST `/ops/spu-std/bulk-status`

批量改状态

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/topics`

专题列表

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Topic`](#topic)\[\]


#### POST `/ops/topics`

新建 / 改

**入参**

_无字段_

**出参**（`data`）

类型：[`Topic`](#topic)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `topicNo` | `string` | 是 | 专题号 |
| `title` | `string` | 是 | 标题 |
| `subtitle` | `string` | 否 | 一句话说明，如「7 点前送到」。空 = 不展示副标题 |
| `cover` | `string` | 否 | 封面图 |
| `sort` | `number` | 是 | 首页排序，小的在前 |
| `startAt` | `number` | 否 | 生效起止（毫秒）。**都可空 = 常设专题** —— 填一个假的结束时间会让它某天悄悄消失 |
| `endAt` | `number` | 否 | 结束时刻 |
| `status` | `string` | 否 | ACTIVE / ARCHIVED。归档不删：分享出去的海报还指着它 |
| `goodsCount` | `number` | 是 | 专题里有几件商品。**空专题在 C 端是一个点进去什么都没有的入口**，列表要看得见 |


#### POST `/ops/topics/{topicNo}/archived`

归档 / 取消归档

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `topicNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Topic`](#topic)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `topicNo` | `string` | 是 | 专题号 |
| `title` | `string` | 是 | 标题 |
| `subtitle` | `string` | 否 | 一句话说明，如「7 点前送到」。空 = 不展示副标题 |
| `cover` | `string` | 否 | 封面图 |
| `sort` | `number` | 是 | 首页排序，小的在前 |
| `startAt` | `number` | 否 | 生效起止（毫秒）。**都可空 = 常设专题** —— 填一个假的结束时间会让它某天悄悄消失 |
| `endAt` | `number` | 否 | 结束时刻 |
| `status` | `string` | 否 | ACTIVE / ARCHIVED。归档不删：分享出去的海报还指着它 |
| `goodsCount` | `number` | 是 | 专题里有几件商品。**空专题在 C 端是一个点进去什么都没有的入口**，列表要看得见 |


#### GET `/ops/topics/{topicNo}/goods`

专题里的商品，按专题内排序 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `topicNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ProductGoods`](#productgoods)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/topics/{topicNo}/goods`

整份替换专题里的商品，顺序即展示顺序

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `topicNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ProductGoods`](#productgoods)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


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
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`ReviewAppeal`](#reviewappeal)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `appealNo` | `string` | 是 | 申诉单号 |
| `reviewNo` | `string` | 是 | 被申诉的评价 |
| `merchantNo` | `string` | 是 | 申诉方商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `reviewRating` | `number` | 是 | 被申诉那条评价的星级与正文。 **裁决台必须显示它们** —— 要判断「这条差评是不是恶意的」， 而屏幕上只有单号和商家自己写的申诉理由的话，裁的是一面之词。 |
| `reviewContent` | `string` | 是 | 被申诉的那条评价原文。**不带上它，审的人要跳去另一页** |
| `reason` | `string` | 是 | 商家的申诉理由 |
| `evidenceCount` | `number` | 是 | 举证材料数量（截图/聊天记录） |
| `status` | [`#/definitions/AppealStatus`](#definitionsappealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），REJECTED = 驳回申诉（差评保留） |
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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

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
| `no` | path | `string` | 是 | 该资源的业务单号 |

_无字段_

**出参**（`data`）

类型：[`RiskEvent`](#riskevent)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `eventNo` | `string` | 是 | 风险事件单号 |
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | [`#/definitions/SubjectType`](#definitionssubjecttype) | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
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
| `type` | path | `string` | 是 | 类型筛选，取值见对应枚举 |

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

#### GET `/ops/stores`

跨主体门店检索

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`StoreGovern`](#storegovern)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/stores/{merchantNo}/qrcode/print`

登记一次店铺码印刷量（线下事实，系统无从自动知道）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/stores/{storeNo}`

门店档案详情：门面 + 配送规则 + 经营模式 + 收款商户号

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreGovern`](#storegovern)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 是 | 门店地址 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `isDefault` | `boolean` | 是 | 是否主体的默认门店。默认店承接「没指定门店」的那些流量 |
| `status` | [`#/definitions/StoreGovernStatus`](#definitionsstoregovernstatus) | 是 | 经营状态，见  {@link  StoreGovernStatus } |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) | 是 | 自营 / 第三方。决定这家店的钱怎么走、票怎么开 |
| `payMerchantNo` | `string,null` | 是 | 本店专属收款商户号。 **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。 |
| `announcement` | `string` | 是 | 门店公告（走 P-10.1 的机审 + 人审） |
| `openHours` | `string` | 是 | 营业时间，展示串 |
| `deliveryRadiusM` | `number` | 是 | 配送半径（米） |
| `deliveryMinOrderMinor` | `number` | 是 | 起送价（分） |
| `deliveryFeeMinor` | `number` | 是 | 配送费（分） |
| `deliveryFreeThresholdMinor` | `number` | 是 | 免配送费门槛（分） |


#### POST `/ops/stores/{storeNo}/restore`

解除门店强制下线，恢复被平台压下的货架行

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`StoreGovern`](#storegovern)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 是 | 门店地址 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `isDefault` | `boolean` | 是 | 是否主体的默认门店。默认店承接「没指定门店」的那些流量 |
| `status` | [`#/definitions/StoreGovernStatus`](#definitionsstoregovernstatus) | 是 | 经营状态，见  {@link  StoreGovernStatus } |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) | 是 | 自营 / 第三方。决定这家店的钱怎么走、票怎么开 |
| `payMerchantNo` | `string,null` | 是 | 本店专属收款商户号。 **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。 |
| `announcement` | `string` | 是 | 门店公告（走 P-10.1 的机审 + 人审） |
| `openHours` | `string` | 是 | 营业时间，展示串 |
| `deliveryRadiusM` | `number` | 是 | 配送半径（米） |
| `deliveryMinOrderMinor` | `number` | 是 | 起送价（分） |
| `deliveryFeeMinor` | `number` | 是 | 配送费（分） |
| `deliveryFreeThresholdMinor` | `number` | 是 | 免配送费门槛（分） |


#### GET `/ops/stores/{storeNo}/stats`

门店经营状况：今日/本月订单与 GMV，外加待发货/待自送/缺货三项待办堆积

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreStats`](#storestats)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `todayOrders` | `number` | 是 | 今日订单数 |
| `todayGmvMinor` | `number` | 是 | 今日 GMV（分） |
| `monthOrders` | `number` | 是 | 本月订单数 |
| `monthGmvMinor` | `number` | 是 | 本月 GMV（分） |
| `ownedTrafficRate` | `number` | 是 | 自带客流占比，0–1。**直接对应这家店少付的佣金**（ADR-004） |
| `toShip` | `number` | 是 | 待发货 |
| `toDeliver` | `number` | 是 | 待自送 |
| `toStock` | `number` | 是 | 缺货待补。运营看它判断「这家店是不是没人管了」 |


#### GET `/ops/stores/acquisition`

获客漏斗「扫码 → 进店 → 首次归因 → 首单」，按**主体**聚合（P-10.1.4）

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
| `auditNo` | path | `string` | 是 | 审核单号 |

_无字段_

**出参**（`data`）

类型：[`StorePageAudit`](#storepageaudit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `auditNo` | `string` | 是 | 审核单号 |
| `merchantNo` | `string` | 是 | 提审商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `storeName` | `string,null` | 否 | 这条内容发给哪家店。存量单（后端 V214 之前）没记，为空。 多店商家只看商家名判断不了「南门店今天停电」该不该放行 —— 而通过之后正是写回那家店。 |
| `kind` | [`#/definitions/StoreAuditKind`](#definitionsstoreauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL、公告文本，或 `DISTRICT:330106` 这样的覆盖项定位串 |
| `display` | `string` | 否 | 人话版的 content。`SERVICE_AREA` 时是「浙江省 / 杭州市 / 西湖区」，其余与 content 相同。 **列表与详情一律显示它**：让运营对着 `DISTRICT:330106` 判断 「这家菜摊该不该覆盖整个西湖区」，等于让他去别处查一次再回来。 |
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
| `layout` | [`#/definitions/SectionLayout`](#definitionssectionlayout) | 是 | 商品区排布 |
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
| `templateNo` | path | `string` | 是 | 模板单号 |

_无字段_

**出参**（`data`）

类型：[`StoreTemplate`](#storetemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | [`#/definitions/SectionLayout`](#definitionssectionlayout) | 是 | 商品区排布 |
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


#### GET `/ops/auth-codes`

<b>全量，含停用</b>，带商家数与类目引用数

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`AuthCodeAdmin`](#authcodeadmin)\[\]


#### POST `/ops/auth-codes`

新建或更新

**入参**

_无字段_

**出参**（`data`）

类型：[`AuthCodeAdmin`](#authcodeadmin)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 授权码，如 `FRESH_VEG`。**建成之后不可改** —— 改它等于换一张证 |
| `name` | `string` | 是 | 展示名，运营给商家发证时看到的就是它 |
| `requiredQualification` | `string` | 否 | 需要的资质证件名。空 = 无证件要求（不是「漏填」） |
| `sort` | `number` | 是 | 列表里的排序权重，小的在前。同值按 code 兜底，保证顺序稳定 |
| `enabled` | `boolean` | 是 | 是否可发放。停用**不撤销**存量商家已持有的授权 |
| `merchantCount` | `number` | 是 | 持有该码的商家数 —— 停之前要知道影响面 |
| `categoryCount` | `number` | 是 | 引用该码的在用类目数。> 0 时停用会被拒（那些类目会变成永远拒绝所有人） |


#### POST `/ops/auth-codes/{code}/enabled`

启停

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `code` | path | `string` | 是 | 取货码 / 核销码 |

_无字段_

**出参**（`data`）

类型：[`AuthCodeAdmin`](#authcodeadmin)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 授权码，如 `FRESH_VEG`。**建成之后不可改** —— 改它等于换一张证 |
| `name` | `string` | 是 | 展示名，运营给商家发证时看到的就是它 |
| `requiredQualification` | `string` | 否 | 需要的资质证件名。空 = 无证件要求（不是「漏填」） |
| `sort` | `number` | 是 | 列表里的排序权重，小的在前。同值按 code 兜底，保证顺序稳定 |
| `enabled` | `boolean` | 是 | 是否可发放。停用**不撤销**存量商家已持有的授权 |
| `merchantCount` | `number` | 是 | 持有该码的商家数 —— 停之前要知道影响面 |
| `categoryCount` | `number` | 是 | 引用该码的在用类目数。> 0 时停用会被拒（那些类目会变成永远拒绝所有人） |


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
| `key` | path | `string` | 是 | 开关标识（FeatureFlag.key） |

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


#### GET `/ops/industries`

listIndustries

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Industry`](#industry)\[\]


#### POST `/ops/industries/{industry}/enabled`

停用后入驻表单里不再出现这个行业

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `industry` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Industry`](#industry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industry` | `string` | 是 | 行业码，入驻申请回传的就是它 |
| `name` | `string` | 是 | 展示名。三端都取服务端的，不各自维护翻译 |
| `sort` | `number` | 是 | 排序 |
| `enabled` | `boolean` | 是 | 是否启用。关掉后入驻表单里不再出现这个行业 |
| `wechatMicroAllowed` | `boolean` | 是 | 微信是否允许该行业以小微进件 |
| `alipayMicroAllowed` | `boolean` | 是 | 支付宝是否允许 |
| `pointsForced` | `boolean` | 是 | 是否**强制开启积分**（商家不可自行关闭）。 它是 `mch_entity.points_forced` 的来源 —— 高毛利行业平台会要求让利。 |
| `remark` | `string` | 否 | 备注：为什么这么配。改白名单是会被商家追问的操作 |


#### POST `/ops/industries/{industry}/micro-allowed`

改某通道的小微白名单

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `industry` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Industry`](#industry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industry` | `string` | 是 | 行业码，入驻申请回传的就是它 |
| `name` | `string` | 是 | 展示名。三端都取服务端的，不各自维护翻译 |
| `sort` | `number` | 是 | 排序 |
| `enabled` | `boolean` | 是 | 是否启用。关掉后入驻表单里不再出现这个行业 |
| `wechatMicroAllowed` | `boolean` | 是 | 微信是否允许该行业以小微进件 |
| `alipayMicroAllowed` | `boolean` | 是 | 支付宝是否允许 |
| `pointsForced` | `boolean` | 是 | 是否**强制开启积分**（商家不可自行关闭）。 它是 `mch_entity.points_forced` 的来源 —— 高毛利行业平台会要求让利。 |
| `remark` | `string` | 否 | 备注：为什么这么配。改白名单是会被商家追问的操作 |


#### POST `/ops/industries/{industry}/points-forced`

强制开启积分：商家不可自行关闭 */

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `industry` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Industry`](#industry)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industry` | `string` | 是 | 行业码，入驻申请回传的就是它 |
| `name` | `string` | 是 | 展示名。三端都取服务端的，不各自维护翻译 |
| `sort` | `number` | 是 | 排序 |
| `enabled` | `boolean` | 是 | 是否启用。关掉后入驻表单里不再出现这个行业 |
| `wechatMicroAllowed` | `boolean` | 是 | 微信是否允许该行业以小微进件 |
| `alipayMicroAllowed` | `boolean` | 是 | 支付宝是否允许 |
| `pointsForced` | `boolean` | 是 | 是否**强制开启积分**（商家不可自行关闭）。 它是 `mch_entity.points_forced` 的来源 —— 高毛利行业平台会要求让利。 |
| `remark` | `string` | 否 | 备注：为什么这么配。改白名单是会被商家追问的操作 |


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
| `code` | path | `string` | 是 | 取货码 / 核销码 |

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


#### POST `/ops/media/backfill`

磁盘对账：把「磁盘上有、库里没有」的文件补录进来

**入参**

_无字段_

**出参**（`data`）

类型：[`MediaBackfillResult`](#mediabackfillresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scanned` | `number` | 是 | 扫了多少个对象 |
| `inserted` | `number` | 是 | 补录了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |


#### GET `/ops/media/batches`

listMediaBatches

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MediaPurgeBatch`](#mediapurgebatch)\[\]


#### GET `/ops/media/batches/{batchNo}`

getMediaBatch

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `batchNo` | path | `string` | 是 | 到货批次号 |

**出参**（`data`）

类型：[`MediaBatchDetail`](#mediabatchdetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batch` | [`#/definitions/MediaPurgeBatch`](#definitionsmediapurgebatch) | 是 | 批次本身 |
| `items` | [`#/definitions/MediaReclaimable`](#definitionsmediareclaimable)\[\] | 是 | 这一批里的每一张 |


#### GET `/ops/media/overview`

getMediaOverview

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MediaOverview`](#mediaoverview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `totalBytes` | `number` | 是 | 合计字节数 |
| `totalCount` | `number` | 是 | 总发行量。空 = 不限量 |
| `activeBytes` | `number` | 是 | 在用的字节数 |
| `activeCount` | `number` | 是 | 在用的对象数 |
| `reclaimableBytes` | `number` | 是 | 可回收的字节数 |
| `reclaimableCount` | `number` | 是 | 可回收的对象数 |
| `abnormal` | `boolean` | 是 | 可回收占比 > 50%。多半是有图片列没登记进 MediaRefSource —— 先查，别照删 |


#### POST `/ops/media/purge`

提交回收

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/media/purge/preview`

预览这一票有多少张、多少字节

**入参**

_无字段_

**出参**（`data`）

类型：[`MediaPurgePreview`](#mediapurgepreview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `count` | `number` | 是 | 数量 |
| `bytes` | `number` | 是 | 占用字节数 |
| `sample` | `string`\[\] | 是 | 抽样：**先给人看几张再让他按** —— 清理不可逆 |


#### GET `/ops/media/reclaimable`

listMediaReclaimable

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`MediaReclaimable`](#mediareclaimable)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/media/scan`

重扫

**入参**

_无字段_

**出参**（`data`）

类型：[`MediaScanResult`](#mediascanresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 扫到多少张 |
| `referenced` | `number` | 是 | 其中仍被引用的 |
| `marked` | `number` | 是 | 本轮标记为可回收的 |
| `rescued` | `number` | 是 | 本轮被救回的（重新有引用了） |
| `abnormal` | `boolean` | 是 | 异常对象数 |


#### GET `/ops/media/stores`

门店占用

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MediaStoreUsage`](#mediastoreusage)\[\]


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


#### GET `/ops/service-scopes`

listServiceScopes

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ServiceScopeConfig`](#servicescopeconfig)\[\]


#### POST `/ops/service-scopes/{scope}/enabled`

开关某一档，返回最新的三档全量

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `scope` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`ServiceScopeConfig`](#servicescopeconfig)\[\]


---

## 数据模型

### AdmissionPolicy

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) | 是 | 主体档位，三档锁定 |
| `requiredDepositMinor` | `number` | 是 | 应缴保证金（分）；0 = 免缴 |
| `singleOrderLimitMinor` | `number` | 是 | 单笔限额（分）；0 = 不限 |
| `dailyAmountLimitMinor` | `number` | 是 | 日累计限额（分）；0 = 不限 |
| `banQualifiedCategory` | `number` | 是 | 1 = 禁止经营任何「需资质」品类 |
| `bannedCategoryCodes` | `string,null` | 否 | 额外禁售类目编码，JSON 数组字符串；空 = 无额外禁售 |
| `enabled` | `number` | 是 | 1 = 该档位的限制生效；0 = 该档位不做任何限制 |
| `remark` | `string,null` | 否 | 为什么这么定 —— 回查时这句话比数字更有用 |

### AfterSale

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`#/definitions/AfterSaleType`](#definitionsaftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`#/definitions/AfterSaleStatus`](#definitionsaftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`#/definitions/Liability`](#definitionsliability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`#/definitions/LiabilityShare`](#definitionsliabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
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
| `userNo` | `string` | 是 | 被归因的用户号。**后端下发的是它**（`MktAttributionLog.userNo`）。 |
| `userNickname` | `string` | 否 | 用户昵称。**后端目前不下发** —— 它要连 usr_account 才拿得到。 页面回落显示 userNo：空着一列比显示用户号更难查。 |
| `source` | [`#/definitions/AttrSource`](#definitionsattrsource) | 是 | 归因来源 |
| `sourceRef` | `string` | 是 | 归因载体：店铺码 / 邀请人昵称 / 渠道名 |
| `attributedAt` | `string` | 是 | 归因发生时间 |
| `orderNo` | `string` | 否 | 首单订单号；还没下单则为空 |
| `conflictWith` | `string` | 否 | 与之冲突的另一次归因（B1 的现实场景） |
| `riskSignals` | `string`\[\] | 否 | 命中的风控信号。**可选：后端目前一条都不下发。** <p>归因链路是从 `mkt_attribution_log` 拼的，那张表没有风控信号 —— 也就是说这一列在真实后端上永远是空的（mock 里有「同设备」「同 IP」这类样例， 所以开发时看着是有的）。 <p>声明成必填的代价是**整页崩**：页面 `t.riskSignals.length` 打在 undefined 上， TypeError 直接把 /growth?tab=traces 变成白屏，而这一页在生产上是点得到的。 改成可选只是让它不撒谎，**风控信号本身仍然是个没做的功能**。 |

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
| `ip` | `string` | 否 | 操作者 IP。后端拿不到（非请求线程）时为空，不是所有旧数据都有 |
| `clientType` | `string` | 否 | 操作端，如 WEB_OPS。同上，可能没有 |
| `before` | `string` | 否 | 变更前结构化快照（JSON 字符串）。只有员工与权限域的部分动作有，其余为空——不伪造 |
| `after` | `string` | 否 | 变更后结构化快照，同上 |

### AuthCode

类目授权码。 它与类目树是**多对一**：`CAT111 叶菜`、`CAT112 根茎菜` 都要 `FRESH_VEG`。 按码授权而不是按类目节点授权，是因为类目树会重构，而"能不能卖菜"这件事不会。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 授权码，如 `FRESH_VEG`。**按码授权而不是按类目节点** —— 类目树会重构，能不能卖菜不会 |
| `name` | `string` | 是 | 授权码展示名 |
| `requiredQualification` | `string` | 否 | 需要的资质名。为空表示无门槛类目 |
| `qualType` | `string,null` | 否 | 这个门槛要哪一类证（`BUSINESS_LICENSE` / `FOOD_PERMIT` / …），与 `Qualification.qualType` 同值域；空 = 无需证件。 **有了它，「这家店传了什么证」与「该授哪些码」才对得上** —— 在它之前 只能靠人对着两张表比对文案，而没人比过：线上一条资质、一条授权都没有。 |

### AuthCodeAdmin

授权码字典（运营视图）。 ⚠️ 与 `AuthCode`（给商家发证时的可选项，见 types/merchant.ts）**是两个口径**： 那个只给启用的，这个含停用的并带影响面计数。合并的话，停用过的码就再也 恢复不了 —— 页面上根本看不见它。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 授权码，如 `FRESH_VEG`。**建成之后不可改** —— 改它等于换一张证 |
| `name` | `string` | 是 | 展示名，运营给商家发证时看到的就是它 |
| `requiredQualification` | `string` | 否 | 需要的资质证件名。空 = 无证件要求（不是「漏填」） |
| `sort` | `number` | 是 | 列表里的排序权重，小的在前。同值按 code 兜底，保证顺序稳定 |
| `enabled` | `boolean` | 是 | 是否可发放。停用**不撤销**存量商家已持有的授权 |
| `merchantCount` | `number` | 是 | 持有该码的商家数 —— 停之前要知道影响面 |
| `categoryCount` | `number` | 是 | 引用该码的在用类目数。> 0 时停用会被拒（那些类目会变成永远拒绝所有人） |

### AuthCodeSetResult

改授权码的结果。 <p>`affected` 是**代价**，不是统计：撤掉一个码，那些在架商品下次上架就会被拒。 运营按下确认之前看不见它的话，一次「顺手收紧」会在几天后变成商家的 「我的货怎么上不去了」，而两件事没人会联系起来。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `codes` | `string`\[\] | 是 | 改完之后持有的码（全量） |
| `revoked` | `string`\[\] | 是 | 这次撤掉的码。空数组 = 只加不减 |
| `affected` | `number` | 是 | 因撤码而下次上架会被拒的在架商品数 |

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

### BuyerInvoiceRequest

买家的开票申请（`/ops/invoice-requests`）。 ⚠️ **这个域里有三张不同的「票」，名字很近，别混：** \| 类型 \| 谁开给谁 \| 决定什么 \| 端点 \| \|---\|---\|---\|---\| \|  {@link  PurchaseInvoice }  进项票 \| 供应商 → 平台 \| 平台能不能付款（票到付款）\| `/ops/purchase-invoices` \| \|  {@link  InvoiceRequest }  商家开票申请 \| 平台 → 商家 \| 商家的服务费发票 \| `/ops/finance/invoices` \| \| 本类型 买家开票申请 \| 平台 → 买家 \| 买家能不能报销 \| `/ops/invoice-requests` \| 前两个此前已有类型，本类型是补的 —— 它按订单走（`orderNo`），前两个按主体/账期走。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | 开票申请号 |
| `orderNo` | `string` | 是 | 针对哪一单 |
| `titleType` | `string` | 是 | PERSONAL / COMPANY |
| `title` | `string` | 是 | 抬头 |
| `taxNo` | `string,null` | 否 | 税号。单位抬头必填 |
| `email` | `string,null` | 否 | 发到哪个邮箱。电子票唯一的交付方式 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `status` | `string` | 是 | PENDING / ISSUED / REJECTED |
| `invoiceNo` | `string,null` | 否 | 已开出的发票号 |
| `issuedAt` | `number,null` | 否 | 开出来的时刻。空 = 还没开 |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `createdAt` | `number,null` | 否 | 申请时刻 |

### Captcha

图形验证码挑战。`imageBase64` 不带 data: 前缀，端上自己拼

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `captchaId` | `string` | 是 | 验证码会话号，校验时要带回来 |
| `imageBase64` | `string` | 是 | 图形验证码的图，base64 |

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
| `sort` | `number` | 是 | 同级内的展示顺序，小的在前。**C 端类目栏就按它排** —— 不下发就等于运营改不了顺序，「把生鲜挪到第一个」只能改库。 |
| `skuCount` | `number` | 是 | 该类目下的在售商品数（归档校验要用） |

### CategoryArchiveImpact

停用一个类目的影响面（`GET /ops/categories/{no}/archive-impact`）。 **有在售商品不再是拦截**：运营停一个类目多半是政策要求（这一类这期不做、 资质链路没接上），拦住他并不能让那批商品消失。界面把后果说清楚，由他决定。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsCount` | `number` | 是 | 这个类目下有几件商品 |
| `onSaleCount` | `number` | 是 | 其中在架几件。**归档前要看** —— 在架的会一起下架 |
| `activeChildren` | `number` | 是 | 还开着的子类目数。**大于 0 时后端仍会拒** —— 会冒出渲染不出来的孤儿节点 |

### CategoryPayMode

类目 × 支付方式（线下）。 **`offlineAllowed` 的默认是「允许」**：后端那张表的语义是 「没有行即放行，插 allowed=0 才是禁止」。设计成白名单的话， 上线当天得先把 57 个类目全配一遍才有人下得了单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目号 |
| `categoryName` | `string` | 是 | 类目名 |
| `parentName` | `string` | 是 | 父类目名。**同名子类目很常见**，只给自己的名字分不清是哪个 |
| `offlineAllowed` | `boolean` | 是 | 这个类目准不准线下付。**默认放行** —— 没有行即不限制 |
| `configured` | `boolean` | 是 | 是否**显式配过**。与 offlineAllowed 分开：没配过也是允许，但两者含义不同 |

### CategoryPoints

类目 × 积分发放规则。**平台按类目统一管理，商家不参与配置** —— 依据是实测：线上 199 件商品里，用商品级配置配了积分的是 0 件。 `earnValue` 是**整数**：FIXED 存分、RATIO 存万分比（千分之一 = 10）。 不用浮点 —— 金额与比例一旦用 double，对账时的分位差没人说得清。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目号 |
| `categoryName` | `string` | 是 | 类目名 |
| `parentName` | `string` | 是 | 父类目名。**同名子类目很常见**，只给自己的名字分不清是哪个 |
| `earnMode` | `FIXED` \| `RATIO` \| `null` | 是 | FIXED 定额 / RATIO 按成交额比例；**空 = 没配**，走平台兜底 |
| `earnValue` | `number,null` | 是 | 发分比例（万分比） |

### CategorySpec

类目 × 规格总览的一行（规格库 V195，`GET /ops/category-specs`）。 **一条规格都没绑的类目也会返回**：这张表真正要回答的是「哪些类目还没配」—— 只列已配的，缺口就永远看不见，而缺口的代价是那一类商家建品只能手打， 手打的选项没有 code，跨店聚合就此断掉。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目号 |
| `categoryName` | `string` | 是 | 类目名 |
| `parentName` | `string` | 是 | 一级类目名，用来分组 |
| `categoryType` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) \| `null` | 否 | 类目形态 |
| `dimCount` | `number` | 是 | 已绑维度数。0 就是缺口 |
| `dims` | [`#/definitions/CategorySpecDim`](#definitionscategoryspecdim)\[\] | 是 | 这个类目能用的规格维度 |

### ClientPointsPolicy

积分的**端策略**。存的是**禁用名单，不是允许名单** —— `X-Client` 头今天还没有哪个端全量在发，用允许名单会让开关一上线就把全站积分静默关掉。 ⚠️ 它**不是合规硬闸**：端标识来自客户端、可伪造，只能用于平台策略。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `earnDeny` | `string`\[\] | 是 | 这些端不发放积分 |
| `redeemDeny` | `string`\[\] | 是 | 这些端不能用积分抵扣 |
| `offlineRedeem` | `boolean` | 是 | 当面付能不能用积分抵扣。**默认开** —— 成本本来就在商家，线下反而比线上简单 |

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
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |

### CommunityApply

商家提报的新社区（ADR-013 阶段三）。 **它不是社区**：审过之后平台才建出来，`communityNo` 这时才有值。 待审的小区不在任何选点列表里 —— 进了主表就会出现在用户面前，而点进去什么都没有。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 提报单业务键。裁决按它定位，**不用自增 id** —— 那个不对外，重建库就变 |
| `merchantNo` | `string` | 是 | 提报的商家 |
| `merchantName` | `string` | 是 | 商家名。运营看着一串 M20260811… 判断不了任何事 |
| `name` | `string` | 是 | 小区名，商家填 |
| `address` | `string` | 否 | 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 同一个小区两条记录，商家会分不清该勾哪个 |
| `regionCode` | `string` | 否 | 商家选的区划，**只是建议**：最终以裁决时填的为准 |
| `regionPath` | `string` | 否 | 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 |
| `note` | `string` | 否 | 商家的补充说明：为什么要开这个点 |
| `kind` | [`#/definitions/SettlementKind`](#definitionssettlementkind) | 否 | ESTATE 小区 / VILLAGE 村。裁决的人要一眼看出这是哪种聚落 |
| `originCode` | `string` | 否 | 关联的官方村码；非空 = 从词典选的，重复开通会被后端拦 |
| `located` | `boolean` | 否 | 带没带定位。**没带的要显眼** —— 通过后聚落没有坐标， 买家用定位永远找不到它，运营得先补坐标再通过。 |
| `latE6` | `number,null` | 否 | 商家提报时带的坐标（gcj02，E6）。**要看得见具体值** —— 只给一个「有/无」，落点偏到隔壁区也照样显示「有定位」，判不出对错。 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `fallbackLatE6` | `number,null` | 否 | 官方村码在区划表里的坐标（高德批量补录）。没带定位时后端通过这条提报会自动用它兜底 —— 两个都空，才是真的「通过后无坐标、买家搜不到」。 |
| `fallbackLngE6` | `number,null` | 否 | 兜底经度：商家没选点时用提交那一刻的位置。**多半不在那个小区里**，裁决要留意 |
| `status` | [`#/definitions/CommunityApplyStatus`](#definitionscommunityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。**只有 PENDING 能裁**：裁完就是终态，再裁一次意味着同一条提报有两个结论 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因。**原样出现在商家 B 端**，所以驳回必须填 |
| `submittedAt` | `number` | 是 | 提报时间 |

### CommunityDuplicate

疑似重复的一对聚落。 `reason` 是**判据不是结论**：SAME_NAME 归一名相同、NEARBY 坐标很近且名字相似。 两条都可能是误报（同一条街道里真有「一期」「二期」两个小区）， 所以界面上给的是「合并」按钮而不是自动合并 —— 合并会改一批商家的可见范围， 错了要一条条捞回来。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `left` | [`#/definitions/Community`](#definitionscommunity) | 是 | 疑似重复的一方 |
| `right` | [`#/definitions/Community`](#definitionscommunity) | 是 | 另一方 |
| `reason` | [`#/definitions/DuplicateReason`](#definitionsduplicatereason) | 是 | 原因 |
| `distanceM` | `number,null` | 否 | 两点直线距离（米）。有一方没坐标时为空 |

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
| `validFrom` | `number` | 是 | 生效开始时间（毫秒时间戳，后端全域口径） |
| `validTo` | `number` | 是 | 生效结束时间（毫秒时间戳） |
| `budget` | `number` | 是 | 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方， 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。 `0` = 不限。存量券全是这样：加预算列的迁移不改变已在跑的券的行为。 服务端的校验在领券那条 UPDATE 里与张数一起判（原子）， 见 `CouponMappers.tryReceive`。⚠️ 折扣券挡不住 —— 它的实际支出 取决于用券那一单的金额，发放时算不出来。 |
| `issuedAmount` | `number` | 是 | 已发放金额（分）= 已领张数 × 面额。折扣券算不出来，恒为 0 |
| `issued` | `number` | 是 | 已发放张数 |
| `redeemed` | `number` | 是 | 已核销张数（P-7.1.4 效果） |
| `createdAt` | `number` | 是 | 创建时间（毫秒时间戳） |
| `totalCount` | `number` | 是 | 发行量。**建券时敞口 = totalCount × 单张最大优惠**（TDD-营销预算前置）， 是预算前置校验的另一半——只有它和面额/封顶一起，敞口才算得出来。 |
| `perUserLimit` | `number` | 是 | 每人限领张数 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（分）。仅 `type=DISCOUNT` 有意义，其余类型恒为 0。 **建券时必填 >0**——0 = 不封顶已取消，敞口在建券那一刻就必须算得出来。 与 `value`（折扣万分比）分开：一个决定打几折，一个决定最多减多少。 |

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

### DepositTxn

保证金流水。**只有余额字段的账户是不可审计的** —— 说不清这笔钱什么时候少的、谁扣的。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `txnNo` | `string` | 是 | 流水号 |
| `txnType` | [`#/definitions/DepositTxnType`](#definitionsdeposittxntype) | 是 | 变动类型 |
| `amountMinor` | `number` | 是 | 有符号：扣划为负 |
| `balanceAfterMinor` | `number` | 是 | 变动后实缴余额（分），对账用 |
| `reason` | `string,null` | 否 | 变动原因 |
| `operator` | `string,null` | 否 | 操作人 |
| `createdAt` | `string,null` | 否 | 发生时间 |

### EffectiveFeeRates

某时刻实际生效的费率表，键为 `${businessMode}\|${trafficSource}`。

类型：`#/definitions/Record<string,number>`

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

### FeeRuleVersion

费率的一个版本（后端 `stl_fee_rule`）。 ⚠️ 这里与旧的 `FeeRule` 形状完全不同，是有意的。旧那个是一维（只按流量来源）、 单值、原地改；**后端从未实现过它**（守卫清单里 `fee-rule` 一直挂在「整域未开工」）。 真正落地的是二维 + 版本化： - **二维**：经营模式 × 流量来源。两者正交 —— 只按经营模式分档，   等哪天想给自营也区分客流就要改表结构，而费率表最不该改结构（历史行要一直可读）。 - **版本化**：调费率是**插新版本**，旧版本永久保留。原地改只能回答「现在是多少」，   而真正会被问到的是「上个月那批单当时按什么费率算的」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ruleNo` | `string` | 是 | 规则版本号 |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) | 是 | 经营模式，费率的第一个维度 |
| `trafficSource` | [`#/definitions/FeeTrafficSource`](#definitionsfeetrafficsource) | 是 | 适用的流量来源，费率的第二个维度 |
| `rateBp` | `number` | 是 | 万分比。500 = 5% |
| `effectiveFrom` | `number` | 是 | 生效时刻（毫秒）。**填未来时刻 = 预约生效** |
| `enabled` | `number` | 是 | 1 = 该版本生效；0 = 已停用（回退到上一版） |
| `remark` | `string,null` | 否 | 为什么调这一次 —— 回查时这句话比数字更有用 |
| `createdAt` | `string` | 否 | 创建时间 |
| `createdBy` | `string` | 否 | 创建人 |

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

### FunnelRow

获客漏斗的一行（P-16.1.4 扫码→进店→注册→首单）。 ⚠️ 此前 interface 与环节枚举撞名叫 FunnelStep，字段写成 `step: FunnelStep` —— 自我引用

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `step` | [`#/definitions/FunnelStep`](#definitionsfunnelstep) | 是 | 漏斗环节：扫码 → 进店 → 注册 → 首单 |
| `count` | `number` | 是 | 该环节人数 |

### GoodsAudit

待审商品（后端 `prd_goods`，**goods 粒度不是 sku 粒度**）。 <p>与本文件里 `Sku` 的差别：审核判的是「这件商品能不能卖」—— 标题、图、类目、资质都在 goods 上；sku 只是规格与价格。 拿 sku 粒度去审，同一件商品会被审好几遍。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号。审核动作打在它上面 |
| `title` | `string` | 是 | 标题。审核先看它 —— 违规多半从标题就能看出来 |
| `subtitle` | `string` | 否 | 副标题/卖点 |
| `cover` | `string` | 否 | 封面图。图文不符是驳回的主因之一，所以要能看到图 |
| `type` | `string` | 是 | 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD |
| `categoryNo` | `string` | 否 | 平台类目。**当前恒为空** —— 商品编辑页还没有选类目这一步 |
| `merchant` | `object`（见下） | 否 | 归属商家（后端下发的是一个 brief 对象，不是裸的 merchantNo）—— 审核时要看得到是谁上的架：同一个商家反复交同类违规品是有信号的。 |
| `status` | `string` | 否 | 商品状态。**字段名是 `status` 不是 `auditStatus`** —— 后端 `GoodsVO` 里它同时承载审核态与上下架态：AUDITING / ON_SALE / OFF_SALE / REJECTED。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |

### GoodsDetail

商品详情（后端 `GoodsVO`，`GET /ops/goods/{goodsNo}`）。 <p>**只声明运营端抽屉真的会读的字段** —— 后端那份 VO 是 C 端契约， 有近三十个字段（评分、销量、拼团配置、称重克重…），照抄一遍等于在前端 维护一份"我们从不显示"的清单，而它每次后端调整都会假性变更。 <p>与  {@link  ProductGoods }  的关系：那是**列表行**（一次给一页，字段窄）， 这是**单条详情**（一次一件，字段全）。两者故意不是同一个类型： 列表塞进详情的字段会让分页响应大一个量级。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 标题（按当前语言拍平后的那一份） |
| `subtitle` | `string` | 否 | 副标题 / 卖点 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 是 | 详情图。后端必发（可能是空数组） |
| `type` | `string` | 是 | 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD |
| `categoryNo` | `string` | 否 | 平台类目 |
| `merchant` | `object`（见下） | 否 | 归属商家 brief —— 审核要看得到是谁上的架 |
| `titleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`#/definitions/Partial<Record<("zh"|"en"|"ar"),string>>`](#definitionspartialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`#/definitions/GoodsDetailSku`](#definitionsgoodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |

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
| `endAt` | `number` | 是 | 成团截止时间（毫秒时间戳） |
| `createdAt` | `number` | 是 | 开团时间（毫秒时间戳） |

### InAppLog

站内信的平台侧记录（发送记录页第二个 tab）。 <p><b>没有 status</b>：站内信入库即到达，不存在「发送中/失败」—— 这正是它与 NotifyLog 不能合成一张表的原因。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 消息号 |
| `receiverType` | `string` | 是 | USER / STAFF / OPS |
| `receiverNo` | `string` | 是 | 收件人编号。**不掩码**：它是平台内部标识（userNo），不是手机号邮箱 |
| `type` | [`#/definitions/InboxMessageType`](#definitionsinboxmessagetype) | 是 | 类型 |
| `title` | `string` | 是 | 标题 |
| `templateNo` | `string,null` | 否 | 模板号 |
| `read` | `boolean` | 是 | 已读 |
| `at` | `number` | 是 | 发生时刻 |

### InboxMessage

运营自己的通知收件箱（顶栏铃铛）。 与 NotifyLog 是两回事：这个是**发给运营的待办**（新工单/待审核/告警）， 那个是**平台发给用户**的触达留痕。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 消息号 |
| `type` | [`#/definitions/InboxMessageType`](#definitionsinboxmessagetype) | 是 | 类型 |
| `title` | `string` | 是 | 标题 |
| `body` | `string` | 是 | 正文 |
| `link` | `string,null` | 否 | 点开跳哪儿。空 = 只是一条通知，点不动 |
| `read` | `boolean` | 是 | 已读 |
| `at` | `number` | 是 | 发生时刻 |

### Industry

行业主数据（后端 `sys_industry`）。**已接真后端。** <p>它不是一张普通的字典表：**行业决定商家能不能以小微主体进件** —— 微信的小微白名单按行业给，判错一次商家就是进件被拒，而那时他已经开完店、上完架。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industry` | `string` | 是 | 行业码，入驻申请回传的就是它 |
| `name` | `string` | 是 | 展示名。三端都取服务端的，不各自维护翻译 |
| `sort` | `number` | 是 | 排序 |
| `enabled` | `boolean` | 是 | 是否启用。关掉后入驻表单里不再出现这个行业 |
| `wechatMicroAllowed` | `boolean` | 是 | 微信是否允许该行业以小微进件 |
| `alipayMicroAllowed` | `boolean` | 是 | 支付宝是否允许 |
| `pointsForced` | `boolean` | 是 | 是否**强制开启积分**（商家不可自行关闭）。 它是 `mch_entity.points_forced` 的来源 —— 高毛利行业平台会要求让利。 |
| `remark` | `string` | 否 | 备注：为什么这么配。改白名单是会被商家追问的操作 |

### InvBalanceRow

某一个商家的一行库存余额（`BalanceVO`）。健康度页点进某一行时看的东西。 <p>与  {@link  InvHealthRow }  **不是同一件事**：那边是「不知道该看谁」时的平台级扫描， 这边必须先知道看哪个商家。两者共用过同一个路径名，代价是运营端照着名字接错。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | 物料号（进销存自己的编号） |
| `name` | `string` | 是 | 维度名（「颜色」「净重」） |
| `specText` | `string` | 否 | 规格描述。人读的，不参与匹配 |
| `baseUom` | `string` | 否 | 基本计量单位。**所有数量以它为准** |
| `onHand` | `number` | 是 | 实存 |
| `reserved` | `number` | 是 | 预留：下了单还没付钱的量 |
| `available` | `number` | 是 | 可用 = 实存 − 预留 |
| `safetyStock` | `number,null` | 否 | 安全库存。低于它算缺货，0 = 不设 |
| `lastMovedAt` | `string,null` | 否 | 最后一次动过的时间。滞销判据 |
| `flags` | `string`\[\] | 是 | SHORTAGE 缺货 · STALE 滞销。**空数组 = 这件没事** |

### InvCredential

一把开放对接的钥匙。**没有 secret 字段，一个都没有。** 库里存的是哈希，明文只在签发那一刻的响应里出现一次。列表若带上它， 会让人以为丢了还能回来找 —— 而实际上只能吊销重发。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `credentialId` | `string` | 是 | 凭据号 |
| `appKey` | `string` | 是 | 开放接口的调用方标识 |
| `name` | `string` | 是 | 给人看的：这把钥匙给了谁 |
| `scopes` | `string` | 是 | 逗号分隔：read / stock:sync |
| `status` | `string` | 是 | ACTIVE / REVOKED。**吊销不删行** —— 「什么时候停的」要查得到 |
| `expiresAt` | `string,null` | 否 | 空 = 不过期 |
| `lastUsedAt` | `string,null` | 否 | 发现「这把钥匙半年没人用了」的唯一依据 |
| `createdAt` | `string,null` | 否 | 申请时刻 |

### InvCredentialIssued

签发的返回。**`appSecret` 这辈子只出现这一次**

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `credentialId` | `string` | 是 | 凭据号 |
| `appKey` | `string` | 是 | 开放接口的调用方标识 |
| `appSecret` | `string` | 是 | 密钥。**只在签发那一次返回**，之后取不回来 |

### InvHealthRow

一条不健康的库存。`kind` 决定这一行要怎么念，也决定该找谁

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `kind` | [`#/definitions/InvHealthKind`](#definitionsinvhealthkind) | 是 | NEGATIVE 负库存 · ZERO_ON_SALE 零库存仍在架 · STALE 长期未动销 |
| `entityNo` | `string` | 是 | 哪家商家的票 |
| `merchantName` | `string` | 否 | 商家名 |
| `storeNo` | `string` | 否 | 门店号 |
| `itemId` | `string` | 是 | 物料号（进销存自己的编号） |
| `itemName` | `string` | 是 | 货品名 |
| `specText` | `string` | 否 | 规格描述。人读的，不参与匹配 |
| `onHand` | `number` | 是 | 实存 |
| `reserved` | `number` | 是 | 预留：下了单还没付钱的量 |
| `available` | `number` | 是 | 可用 = 实存 − 预留 |
| `idleDays` | `number` | 否 | STALE 才有：多少天没动过 |

### InvLedgerPage

台账一页。**后端返回的是分页对象，不是裸数组** —— `nextCursor` 由服务端给，前端不要拿「最后一行的 id」自己推： 那样在同一毫秒有多笔时会漏行，而漏的那几行不会有任何报错。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entries` | [`#/definitions/InvLedgerRow`](#definitionsinvledgerrow)\[\] | 是 | 本页的台账行 |
| `nextCursor` | `number,null` | 否 | null = 没有下一页 |

### InvReconReport

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scannedSkus` | `number` | 是 | 扫了多少个 SKU |
| `moved` | `number` | 是 | 本轮搬动了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |
| `pending` | `number` | 是 | 扫到了但**还没搬**的。**它必须是 0 才准切真相源** —— 没搬的那些在进销存侧余额是 0，切过去就是「全都卖不了」。 这一列原本不存在：`moveOne` 只算不写时故意不把没搬过的算成差异， `doRun` 又把它们计成既不 moved 也不 skipped，于是它们在报告里一个字都不出现， 而 `clean` 只看 diffs —— 闸门守着一个它没在看的东西。 |
| `clean` | `boolean` | 是 | 没有差异**且**没有待搬的。两者缺一都不算干净 |
| `diffs` | [`#/definitions/InvReconDiff`](#definitionsinvrecondiff)\[\] | 是 | 对不上的行 |

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

### InvoiceTitle

平台开票抬头（P0-11）。**供应商照着它给平台开票** —— 缺公司全称或税号，票就开不出来。 <p>五个字段都是字符串，后端存成一条扁平 JSON 配置（`finance.invoice-title`）； **默认值是五项全空而不是编一份假的** —— 空着能让人立刻发现「还没配」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `companyName` | `string` | 是 | 公司全称。**必填** |
| `taxNo` | `string` | 是 | 纳税人识别号。**必填** |
| `address` | `string` | 是 | 注册地址 |
| `phone` | `string` | 是 | 注册电话 |
| `bankAccount` | `string` | 是 | 开户行与账号 |

### JobLogRow

执行日志一行。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `runId` | `string` | 是 | 这一轮的编号 |
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `triggerType` | [`#/definitions/JobTriggerType`](#definitionsjobtriggertype) | 是 | 这一轮是被什么触发的。**排障第一个看它** —— 定时跑失败和人手动补跑失败要找的人不同 |
| `bizDate` | `string,null` | 是 | 业务日期。**补数跑的是历史某一天，与执行时刻不是一回事** |
| `startedAt` | `string` | 是 | 开始时刻 |
| `finishedAt` | `string,null` | 是 | 结束时刻。空 = 还没回（可能仍在跑，也可能超时了） |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `status` | [`#/definitions/JobStatus`](#definitionsjobstatus) | 是 | 这一轮的结局 |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `workerInstance` | `string,null` | 是 | 哪个实例跑的。**多实例抢锁时排障靠它** —— 只有一台在跑不等于只有一台部署 |
| `httpStatus` | `number,null` | 是 | 调用业务系统时的 HTTP 状态。UNREACHABLE 时看它区分「没连上」与「连上了但报错」 |

### JobRow

列表里的一行：**任务定义 + 当前状态**，后端已经合好。 前端不该发两次请求再自己 join —— 那样「有定义但从没跑过」这种状态要靠前端拼， 而它恰恰是今天最常见的状态。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `displayName` | `string` | 是 | 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 |
| `description` | `string,null` | 是 | 这个任务做什么，运营看的一句话 |
| `ownerModule` | `string,null` | 是 | 归哪个模块。出问题时据此找人 |
| `cron` | `string` | 是 | 排期表达式 |
| `enabled` | `boolean` | 是 | 开着没有。关掉的任务不会被调度器捡起来 |
| `missing` | `boolean` | 是 | 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 |
| `manualTrigger` | `boolean` | 是 | 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 |
| `lastRunAt` | `string,null` | 是 | `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 |
| `lastStatus` | [`#/definitions/JobStatus`](#definitionsjobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |

### LoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`#/definitions/Role`](#definitionsrole) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `perms` | `string`\[\] | 是 | **后端下发的权限码**（`staff.perms`）。判权以它为准。 `["*"]` = 超管通配。前端的 UI 码要先经 `UI_PERM_MAP` 翻译成后端码 再来这里查 —— 两边的粒度不同（前端 45 个、后端 14 个）， 直接比会全判 false。 |
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

### MediaBackfillResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scanned` | `number` | 是 | 扫了多少个对象 |
| `inserted` | `number` | 是 | 补录了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |

### MediaBatchDetail

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batch` | [`#/definitions/MediaPurgeBatch`](#definitionsmediapurgebatch) | 是 | 批次本身 |
| `items` | [`#/definitions/MediaReclaimable`](#definitionsmediareclaimable)\[\] | 是 | 这一批里的每一张 |

### MediaOverview

顶部四张卡。`abnormal` 为真时页面置顶红条并禁用批量回收。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `totalBytes` | `number` | 是 | 合计字节数 |
| `totalCount` | `number` | 是 | 总发行量。空 = 不限量 |
| `activeBytes` | `number` | 是 | 在用的字节数 |
| `activeCount` | `number` | 是 | 在用的对象数 |
| `reclaimableBytes` | `number` | 是 | 可回收的字节数 |
| `reclaimableCount` | `number` | 是 | 可回收的对象数 |
| `abnormal` | `boolean` | 是 | 可回收占比 > 50%。多半是有图片列没登记进 MediaRefSource —— 先查，别照删 |

### MediaPurgeBatch

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次号 |
| `operator` | `string` | 是 | 发起人账号 |
| `operatorName` | `string,null` | 否 | 发起时的显示名快照 —— 人离职改名之后这条记录还得说得清是谁 |
| `status` | [`#/definitions/MediaPurgeStatus`](#definitionsmediapurgestatus) | 是 | 状态 |
| `totalCount` | `number` | 是 | 这一批有多少张 |
| `totalBytes` | `number` | 是 | 这一批合计多少字节 |
| `purgedCount` | `number` | 是 | 真删掉了多少张 |
| `failedCount` | `number` | 是 | 删失败多少张。**多半是已经不在了** —— 所以整批的结局是 PARTIAL 而不是 FAILED |
| `startedAt` | `string,null` | 否 | 开始时刻 |
| `finishedAt` | `string,null` | 否 | 结束时刻。空 = 还在跑 |
| `createdAt` | `string,null` | 否 | 上传时刻 |

### MediaPurgePreview

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `count` | `number` | 是 | 数量 |
| `bytes` | `number` | 是 | 占用字节数 |
| `sample` | `string`\[\] | 是 | 抽样：**先给人看几张再让他按** —— 清理不可逆 |

### MediaReclaimable

待回收的一行。`reason` 是这一列的全部意义 —— 运营靠它判断「这张能不能删」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `assetKey` | `string` | 是 | 对象存储里的键。**删的就是它** —— 删错一张图，引用它的页面从此是破图 |
| `entityNo` | `string` | 是 | 上传方商家 |
| `storeNo` | `string` | 是 | 上传方门店 |
| `bizType` | [`#/definitions/MediaBizType`](#definitionsmediabiztype) | 是 | 这张图当初是为什么传的。运营靠它判断能不能删 |
| `bytes` | `number` | 是 | 占用字节数。回收的价值全在这个数上 |
| `width` | `number,null` | 否 | 像素宽 |
| `height` | `number,null` | 否 | 像素高 |
| `uploadedBy` | `string,null` | 否 | 上传人 |
| `createdAt` | `string,null` | 否 | 上传时刻 |
| `markedAt` | `string,null` | 否 | 被标记为可回收的时刻。**与 createdAt 分开**：刚失去引用就删，容易删掉正在编辑的东西 |
| `reason` | `string` | 是 | 「从未被引用」或「曾被『商品 G0012 · 主图』引用，… 后失去引用」 |
| `status` | `string` | 是 | 状态 |

### MediaScanResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 扫到多少张 |
| `referenced` | `number` | 是 | 其中仍被引用的 |
| `marked` | `number` | 是 | 本轮标记为可回收的 |
| `rescued` | `number` | 是 | 本轮被救回的（重新有引用了） |
| `abnormal` | `boolean` | 是 | 异常对象数 |

### MediaStoreUsage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | `_ENTITY` = 主体级（证件，以及门店维度出现之前的存量图） |
| `entityNo` | `string` | 是 | 所属商家 |
| `count` | `number` | 是 | 数量 |
| `activeBytes` | `number` | 是 | 在用的字节数 |
| `reclaimableBytes` | `number` | 是 | 可回收的字节数 |

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

### MenuFunction

服务端下发的菜单分区（`GET /ops/menu`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `functionCode` | `string` | 是 | 功能点编码，菜单树的一级节点 |
| `name` | `string` | 是 | 菜单显示名 |
| `icon` | `string,null` | 否 | 图标名；为空由前端按 functionCode 兜底 |
| `href` | `string,null` | 否 | 一级节点自身的落地路径；为空表示它只是个分组 |
| `sort` | `number` | 是 | 同级排序，小的在前 |
| `points` | [`#/definitions/MenuPoint`](#definitionsmenupoint)\[\] | 是 | 这个功能点下的二级菜单/按钮 |

### Merchant

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`#/definitions/MerchantTier`](#definitionsmerchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`#/definitions/MerchantStatus`](#definitionsmerchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
| `communityNos` | `string`\[\] | 是 | 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区 （后端 `mch_entity_community`，服务范围三档见 ADR-009）。 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 展示一律脱敏（中间四位掩码），完整号码不下发前端 |
| `categoryCodes` | `string`\[\] | 是 | 经营类目编码，审核通过后即类目授权范围（P-11.1.3） |
| `verified` | `boolean` | 是 | 认证标（P-11.1.2） |
| `qualifications` | `string`\[\] | 否 | 已登记的结构化资质名。授权需要资质的类目码时要对照它。 **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段， 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length` 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。 |
| `breachCount` | `number` | 是 | 信用档案：毁约次数（P-11.1.5 / ADR-003） |
| `settleAccountReady` | `boolean` | 是 | 分账接收方报备状态（P-12.1.1，ADR-002） |
| `createdAt` | `string` | 是 | 入驻申请提交时间 |
| `auditRemark` | `string` | 否 | 最近一次审核意见（驳回原因/补交项） |
| `asPickupPoint` | `boolean` | 否 | 申请人是否愿意承接自提点（ADR-005）。 **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的， 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**： 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。 |
| `legalForm` | [`#/definitions/LegalForm`](#definitionslegalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`#/definitions/FundsMode`](#definitionsfundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |

### MerchantApply

入驻申请单（后端 `mch_entity_apply`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 申请单号。审核动作都打在它上面，不是商家号 |
| `merchantNo` | `string` | 否 | 通过后生成的主体号。**未通过时为空** —— 商家在通过之前根本不存在 |
| `name` | `string` | 是 | 拟用店铺名。**存快照** —— 后来改名不该让历史申请跟着变 |
| `subject` | `string` | 是 | 法律形态 NATURAL_PERSON / INDIVIDUAL / ENTERPRISE |
| `contactName` | `string` | 是 | 联系人姓名。审核要打电话找人 |
| `contactPhone` | `string` | 是 | 联系手机号（申请人自己填的，不一定是登录号）。**通过后它就是商家账号的登录号** |
| `category` | `string` | 是 | 主营类目。**商家自己的说法**（「食品」），不是权威码 |
| `categoryCodes` | `string`\[\] | 否 | 审核通过时授予的经营类目码 —— **平台的裁定**，与 `category` 并存。 <p>两者分开是为了留痕：追溯时要的恰恰是这两者的差 （「他说卖食品，我们批的是预包装食品」）。 |
| `desc` | `string` | 是 | 店铺简介。通过后会写进主体档案，C 端门店页读的就是它 |
| `industry` | `string` | 否 | 行业。**决定这家店能不能以小微进件** —— 审核页要看得到它， 否则运营批了一个行业不允许小微的小微商家，通道那边才会拒。 |
| `serviceScope` | `string` | 否 | 期望服务范围。**商家可以留空，但通过时必须确定** —— 空的后果是商家上着架却对谁都不可见，且没有任何报错。 |
| `communityNos` | `string`\[\] | 否 | 覆盖的小区。scope=COMMUNITY 时**空 = 通过之后对谁都不可见** |
| `licenses` | `string`\[\] | 否 | 已传的资质图。个体户/企业必传，自然人免 —— 缺它正是驳回的主因 |
| `qualificationItems` | [`#/definitions/QualificationItem`](#definitionsqualificationitem)\[\] | 否 | 结构化资质（V79）。**审核台看的是这一份** —— 上面的 licenses 只有图片 URL，审核员看不出「这是执照还是食品证」「什么时候过期」。 而通过之后转存进 mch_qualification 的正是它。 |
| `asPickupPoint` | `boolean` | 是 | 是否愿意承接自提点（ADR-005）。**只是意愿，不代表点已建立** |
| `status` | [`#/definitions/ApplyStatus`](#definitionsapplystatus) | 是 | 审核状态 |
| `rejectReason` | `string` | 否 | 驳回原因。**驳回必写** —— 不写对方只能猜着改 |
| `createdAt` | `number` | 是 | 提交时间 |
| `auditedAt` | `number` | 否 | 审核完成时间。待审期间为空 |

### MerchantCampaign

**商家自建的店铺活动**（`GET /ops/campaigns` 真正返回的东西）。 <p>平台对它只有治理权：看得见、能停、能归档，**不能建也不能改内容** —— 那是商家自己的经营决定（矩阵 §2.3「平台停券与停活动」）。 <p>字段对齐后端 `CampaignVO`。与  {@link  PlatformSlot }  是两个领域对象， 曾经被一根 HTTP 路径连着，见 `docs/technical/运营端营销列表契约错配.md`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`#/definitions/MerchantCampaignType`](#definitionsmerchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
| `status` | `string` | 是 | RUNNING / ENDED / PAUSED |
| `startAt` | `number` | 是 | 开始时间（毫秒时间戳） |
| `endAt` | `number` | 是 | 结束时间（毫秒时间戳） |
| `goodsNos` | `string`\[\] \| `null` | 否 | 参与的商品号。**列表上只显示条数**，明细进详情看 |

### MerchantDebt

商家欠款：退款追不回来时先记在账上，从后续货款里扣。 ⚠️ **与保证金方向相反**：保证金是商家的钱（平台代管、将来要退还）， 欠款是商家欠平台的。两者不能合成一个数看。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 欠款主体号 |
| `balanceMinor` | `number` | 是 | 当前欠款（分），恒 >= 0。0 = 没有欠款 |
| `txns` | [`#/definitions/DebtTxn`](#definitionsdebttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |

### MerchantDeposit

商家保证金账户。**可用余额 = 实缴 − 冻结**，判「够不够」用可用而非实缴。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体 |
| `paidMinor` | `number` | 是 | 实缴（分） |
| `frozenMinor` | `number` | 是 | 理赔冻结中（分） |
| `availableMinor` | `number` | 是 | 可用（分）= 实缴 − 冻结。**判够不够用它，不用实缴** |
| `requiredMinor` | `number` | 是 | 本档位应缴（分）；0 = 免缴 |
| `sufficient` | `boolean` | 是 | 可用是否已达应缴。不足则该商家不能上架 |
| `singleOrderLimitMinor` | `number` | 是 | 单笔限额（分）；0 = 不限 |
| `dailyAmountLimitMinor` | `number` | 是 | 日累计限额（分）；0 = 不限 |

### MerchantPlanRow

到期看板的一行（`GET /ops/merchant-plans`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体号 |
| `merchantName` | `string` | 是 | 商家名 |
| `planCode` | `string` | 是 | 档位码。**文案用 name/planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `storeQuota` | `number` | 是 | 生效额度（覆盖值优先于快照）。与 storeUsed 一起显示成 2/3 |
| `staffQuota` | `number` | 是 | 员工数配额 |
| `storeUsed` | `number` | 是 | 已用门店数。**只数 ACTIVE**，与建店时那道额度闸同一口径 |
| `staffUsed` | `number` | 是 | 已用员工数 |
| `crossStoreStats` | `boolean` | 是 | 这一档给不给跨店统计 |
| `status` | [`#/definitions/PlanStatus`](#definitionsplanstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`#/definitions/PlanQuotaSource`](#definitionsplanquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |

### MerchantRankRow

商家经营排行的一行（P-16.1.2 / P-16.1.3）—— 大盘之下的第一层下钻。 大盘回答「平台整体怎么样」，运营下一句必然是「哪几家在拉高、哪几家在拖后腿」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体号 |
| `merchantName` | `string` | 是 | 商家名。**必须有** —— 只给编号的话运营还要再查一次「这家是谁」 |
| `gmv` | `number` | 是 | 成交额（最小货币单位整数） |
| `orderCount` | `number` | 是 | 订单数 |
| `avgOrderValue` | `number` | 是 | 客单价（最小货币单位整数） |
| `afterSaleCount` | `number` | 是 | 售后单数 |
| `afterSaleRate` | `number` | 是 | 售后率 0–1。与 GMV 并列才看得出「卖得多」是不是「赔得也多」 |

### MerchantStaffRow

商家的一个员工，以及他在各门店的角色（**运营端只读**）。 为什么运营要看得到：客服接到「我们店的配送员看不到订单」时， 在此之前只能让老板自己截图 —— 而问题往往正是「他以为授了、其实没授」， 截图里看不出这一点。 平台**不能改**这些授权：谁能进这家店是商家的雇佣关系。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mchAccountNo` | `string` | 是 | 商家账号号 |
| `displayName` | `string,null` | 否 | 姓名（老板自己写的）。认人靠它；可能为空 |
| `loginPhone` | `string` | 是 | 登录手机号。**它就是这个员工的登录用户名**（手机号 + 验证码，没有密码） |
| `isOwner` | `boolean` | 是 | 老板。**不受门店授权限制**，所以 roles 为空不代表他没权限 |
| `status` | `string` | 是 | ACTIVE / DISABLED |
| `roles` | `object`（见下）\[\] | 是 | 他在各门店的角色。一人一店可多角色，权限取并集 |

`roles[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | — |
| `storeName` | `string` | 是 | — |
| `role` | `string` | 是 | — |

### ModeRisk

无营业执照的主体 × 自营门店 —— **税务敞口清单**。 自营下平台是销售主体，列支成本要进项发票，而无照主体开不出票 —— 这笔支出**不得在企业所得税前扣除**，不是「多交一点税」， 是账面上凭空多出等额利润。 而这个组合**是默认会发生的**：`mch_store.business_mode` 默认就是自营， 且后端没有任何一处校验「无照不得自营」。所以这份清单不是异常报表， 是**现状盘点**。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家主体号 |
| `merchantName` | `string` | 是 | 商家名 |
| `legalForm` | `string` | 是 | 主体档位（免执照的那一档） |
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string` | 是 | 门店名 |
| `businessMode` | `string` | 是 | 销售主体是谁：自营 / 第三方 |
| `settledBills` | `number` | 是 | 已产生的自营结算单数。**0 表示「查过了，没有」** —— 与「还没查」在界面上要分开 |
| `settledMinor` | `number` | 是 | 累计商家实得（分）。**这就是不可税前扣除的成本规模** |

### MsgTemplate

订阅消息模板（P-14.1.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `channel` | [`#/definitions/MsgChannel`](#definitionsmsgchannel) | 是 | 触达渠道 |
| `lang` | `string` | 否 | 语言（zh-CN / en / ar）。 <p>同一个 templateNo 每种语言一行（V145）——**列表上必须显示它**， 否则运营看到的是两条一模一样的模板，改了其中一条还发现"没生效"。 |
| `content` | `string` | 是 | 模板正文，含 {占位符}。**模拟发送靠它展示「会发出什么」并做预览** |
| `providerTemplateId` | `string,null` | 否 | 渠道侧模板 ID（阿里云 `SMS_xxx` / 微信模板号）。站内信为空。 <p>后端 `TemplateVO` 一直有这个字段，端上类型此前漏了 —— 于是页面拿不到它， 而它正是运营核对「我们发的是哪个报备模板」的唯一凭据。 |
| `enabled` | `boolean` | 是 | 是否启用。停用后引用它的推送任务发不出去 |
| `sentCount` | `number` | 是 | 近 30 天发送量 |

### NearbyCommunity

附近已开通的聚落。裁决查重用：名字不同、位置只差 50 米的两条，靠文字比对看不出来

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 社区号 |
| `name` | `string` | 是 | 名称 |
| `latE6` | `number` | 是 | 纬度 ×1e6（gcj02） |
| `lngE6` | `number` | 是 | 经度 ×1e6（gcj02） |
| `distanceM` | `number` | 是 | 距提报坐标的直线距离（米） |
| `regionPath` | `string` | 是 | 「广东省 / 深圳市 / 龙华区 / 福城街道」 |

### NotifyChannelHealth

通道体检（TDD-运营端触达中心 §4.1）。 <p><b>凭据只有「配没配」，没有值</b>：一个能在 Web 上读出生产短信密钥的接口， 泄漏一次就是全平台可群发。要改密钥去改环境变量并重启，不在这个页面上改。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | [`#/definitions/NotifyChannel`](#definitionsnotifychannel) | 是 | 通道 |
| `stub` | `boolean` | 是 | 走桩：不真发，只记日志 |
| `enabled` | `boolean` | 是 | 真实通道已启用（!stub） |
| `credentials` | `object`（见下）\[\] | 是 | 这条通道的凭据配没配全。**没配全就发不出**，而症状是「发送成功」后没人收到 |
| `params` | `object`（见下）\[\] | 是 | 非密业务参数，可回显（模板号、endpoint 这类本就印在短信里的东西） |
| `todaySent` | `number` | 是 | 今天发了多少条 |
| `todayFailed` | `number` | 是 | 今天失败多少条 |

`credentials[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `envVar` | `string` | 是 | — |
| `present` | `boolean` | 是 | — |
| `required` | `boolean` | 是 | — |

`params[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `key` | `string` | 是 | — |
| `value` | `string` | 是 | — |

### NotifyChannelRow

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channelNo` | `string` | 是 | 渠道编号（业务主键，启停用它） |
| `channelType` | `string` | 是 | SMS / MAIL / WXSUB / PUSH / INAPP |
| `provider` | `string` | 是 | ALI / SMTP / WECHAT / GETUI / FCM / APNS / INTERNAL |
| `scope` | `string` | 是 | 接入范围 PLATFORM / MERCHANT / TEST |
| `ownerNo` | `string` | 是 | scope=MERCHANT 的商家号；平台/测试为空串 |
| `enabled` | `boolean` | 是 | 软开关（运营即时启停） |
| `status` | `string` | 是 | 读时派生 UNCONFIGURED / STUB / READY / DISABLED / DEGRADED |
| `priority` | `number` | 是 | 同类型同供应商多实例的选择优先级，小者先 |
| `credRef` | `string,null` | 否 | 凭据引用（env 前缀），不含密钥明文；可空 |
| `configJson` | `string` | 是 | 非密参数（签名/模板号/topic），JSON 串 |
| `missingCreds` | `string`\[\] | 是 | 平台接入还缺哪些环境变量（供运维照配）；商家/测试接入为空 |
| `locked` | `boolean` | 是 | INAPP 恒锁定：站内信不可关 |

### NotifyLog

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `notifyNo` | `string` | 是 | 触达记录号 |
| `channel` | [`#/definitions/NotifyChannel`](#definitionsnotifychannel) | 是 | 通道 |
| `provider` | `string,null` | 否 | 供应商 ALI/SMTP/WECHAT/GETUI/FCM/APNS（N3）；旧行与单供应商推出为空 |
| `bizType` | `string` | 是 | OTP / OPS_INIT_PASSWORD / OPS_RESET_PASSWORD / TEST |
| `target` | `string` | 是 | 发给谁。**已脱敏** —— 这张表运营都看得到 |
| `templateCode` | `string,null` | 否 | 短信是阿里云模板号；邮件是主题 |
| `status` | [`#/definitions/NotifyStatus`](#definitionsnotifystatus) | 是 | 状态 |
| `error` | `string,null` | 否 | 失败时通道返回的原文。**排查第一眼看它** |
| `providerMsgId` | `string,null` | 否 | 阿里云 BizId / 邮件 Message-ID |
| `operatorNo` | `string,null` | 否 | 谁发的（人工触达时） |
| `createdAt` | `string` | 是 | 创建时刻 |

### NotifyPushTask

平台营销广播推送任务（触达推送中台 N6）。运营主动发起的群发： 圈人群 → 预估触达 → 定时下发。与事件驱动触达（发给用户的必达通知）分开。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | 任务号 |
| `name` | `string` | 是 | 任务名（运营自己看的） |
| `audienceType` | `string` | 是 | 人群 ALL_APP_USER（消费者）/ ALL_STAFF（商家员工） |
| `channel` | `string` | 是 | 下发通道，一期仅 PUSH |
| `title` | `string` | 是 | 标题 |
| `body` | `string` | 是 | 正文 |
| `link` | `string,null` | 否 | 点开落点，可空 |
| `scheduledAt` | `string,null` | 否 | 定时下发时刻 ISO；空=尽快发 |
| `status` | `string` | 是 | QUEUED / RUNNING / DONE / CANCELLED |
| `estimatedCount` | `number` | 是 | 创建时预估触达人数 |
| `sentCount` | `number` | 是 | 实际发出条数 |
| `finishedAt` | `string,null` | 否 | 结束时刻。空 = 还在发 |

### NotifyQuota

触达频控（P-14.1.4）。 两个上限都必须 > 0 —— 0 等于没有频控，但界面上看着像配了，比不配更危险。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dailyPerUser` | `number` | 是 | 单用户单日消息上限 |
| `minIntervalHours` | `number` | 是 | 同一模板对同一用户的最小间隔（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### OnboardingRow

进件看板的一行（`GET /ops/onboarding`）。**每主体每通道一条**。 它补的是入驻审核与收款进件两条链之间的盲区：审核通过 = 能上架卖货， 进件通过 = 能收钱。审核过了但进件没走完的商家「货照上、单照来、钱收不到」， 此前运营端没有一个跨商家的地方能看见 —— 这份看板就是那个地方。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `merchantName` | `string` | 是 | 商家名，展示用 |
| `storeNo` | `string` | 是 | 为哪家门店进的件；**空串 = 主体级默认号** |
| `payChannel` | `string` | 是 | WECHAT / ALIPAY |
| `applyStatus` | [`#/definitions/OnboardingStatus`](#definitionsonboardingstatus) | 是 | — |
| `rejectReason` | `string,null` | 是 | 被拒原因，原样给商家看；null = 没被拒 |
| `settleAccountType` | `string,null` | 是 | PERSONAL_BANK / CORPORATE_BANK；null = 还没填 |
| `settleAccountMasked` | `string,null` | 是 | 结算账号掩码 —— 真实账号只在后端 |
| `subMchid` | `string,null` | 是 | 通道侧二级商户号；null = 还没开出来 |
| `payMerchantNo` | `string,null` | 是 | 进件成功才生成的收款号业务键；**空/null = 还收不了钱** |
| `appliedAt` | `number,null` | 是 | 提交进件的时间（毫秒）；null = 还没提交（占位记录） |
| `ageMs` | `number,null` | 是 | 从提交到现在的停留时长（毫秒）；null = 还没提交。越大越该有人去问 |
| `canReceiveMoney` | `boolean` | 是 | applyStatus === "ACTIVE" |

### OpsPerson

人档：一份人档串起几家商家的会员关系 —— 这正是它存在的理由

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `personNo` | `string` | 是 | 平台人档号 |
| `phoneTail` | `string,null` | 是 | 手机号后四位。**永远不给完整号** |
| `userNo` | `string,null` | 是 | 用户号 |
| `memberships` | [`#/definitions/OpsMember`](#definitionsopsmember)\[\] | 是 | 他在各商家的会员关系。**一份人档串起几家** —— 这正是人档存在的理由 |
| `merges` | `string`\[\] | 是 | 合并过的人档号。合并不可逆，留痕是唯一的回溯手段 |

### OpsPromoActivity

运营看到的一场活动（新模型）。`audienceCount === 0` 表示对所有人生效

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | 活动号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `entityName` | `string` | 是 | 商家名 |
| `name` | `string` | 是 | 活动名 |
| `triggerType` | `string` | 是 | 触发条件：满额 / 满件 / 命中商品 / 无条件 |
| `benefitType` | `string` | 是 | 优惠方式：减钱 / 改单价 / 送商品 / 发券 |
| `scheduleType` | `string` | 是 | 排期：短期 / 长期 / 周期 |
| `quota` | `number,null` | 是 | 限量。空 = 不限量 |
| `quotaUsed` | `number` | 是 | 已用掉的限量 |
| `budgetMinor` | `number,null` | 是 | 预算上限（分）。空 = 不限 |
| `budgetUsedMinor` | `number` | 是 | 已花掉的预算（分） |
| `audienceCount` | `number` | 是 | 定向人数。**0 表示对所有人生效**，不是「谁也不发」 |
| `status` | `string` | 是 | 状态 |
| `endedReason` | `string,null` | 是 | 为什么停的：到期 / 限量用尽 / 预算用尽 / 人工停。商家问「怎么停了」要有答案 |
| `flags` | `string`\[\] | 是 | 风险标记。商家自己看不出来 —— 他只看得到他那一张，跨商家排在一起才看得见 |

### OpsPromoCoupon

运营看到的一张券（新模型）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | 券模板号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `entityName` | `string` | 是 | 商家名 |
| `title` | `string` | 是 | 券名 |
| `benefitMode` | `string` | 是 | `CASH` 减固定金额 / `PERCENT` 打折 / `GIFT` 换赠品 / `TIMES` 次卡 |
| `benefitValue` | `number` | 是 | 优惠力度。含义**跟着 benefitMode 变**：CASH 是分、PERCENT 是万分比、TIMES 是次数 |
| `benefitCapMinor` | `number,null` | 是 | 折扣券封顶（分）。空 = 不封顶 —— 与 UNLIMITED 一起出现时敞口无上限 |
| `totalCount` | `number,null` | 是 | 总发行量。空 = 不限量 |
| `receivedCount` | `number` | 是 | 已领取数 |
| `budgetMinor` | `number,null` | 是 | 预算上限（分）。空 = 不限 |
| `maxExposureMinor` | `number,null` | 是 | 最大敞口 = 限量 × 单张优惠。**这一页真正要看的数** —— 不限量时它算不出来 |
| `status` | `string` | 是 | 状态 |
| `flags` | `string`\[\] | 是 | 风险标记。商家自己看不出来 —— 他只看得到他那一张，跨商家排在一起才看得见 |

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
| `fulfillType` | [`#/definitions/FulfillmentType`](#definitionsfulfillmenttype) | 是 | 履约方式 |
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

### PayChannelRateVersion

通道费率的一个版本（后端 `sys_pay_channel_rate`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rateNo` | `string` | 是 | 规则版本号 |
| `payChannel` | `string` | 是 | 通道码，与 sys_pay_channel 同值域 |
| `payMethod` | `string` | 是 | `*` = 该通道全部支付方式 |
| `legalForm` | `string` | 是 | `*` = 全部主体形态 |
| `rateBp` | `number` | 是 | 万分比。38 = 0.38% |
| `minFeeMinor` | `number` | 是 | 单笔最低手续费（分）。0 = 无保底 |
| `effectiveFrom` | `number` | 是 | 生效时刻（毫秒）。**填未来时刻 = 预约生效** |
| `enabled` | `boolean` | 否 | 停用的版本不参与取值。停用最新版 = 回退到上一版 |
| `remark` | `string,null` | 否 | 为什么调这一次 —— 回查时这句话比数字更有用 |

### PayChannelSetting

一个支付通道的设置与费率。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码，如 WECHAT / ALIPAY |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 停用只影响**新进件与新下单**，已开通的商户与在途的单不受影响 |
| `markets` | `string,null` | 是 | JSON 数组文本，如 `["CN"]`。空 = 全市场可用 |
| `currency` | `string,null` | 是 | 结算币种，如 CNY |
| `settleCycle` | `string,null` | 是 | 通道结算周期，如 T+1。展示与对账预期用 |
| `supportsSubsidy` | `boolean` | 是 | 能否补差。**为 false 时该通道不开积分抵扣** —— 这是通道的事实，运营改不了 |
| `currentRate` | [`#/definitions/PayChannelRateVersion`](#definitionspaychannelrateversion) \| `null` | 是 | 此刻生效的那一版；**一条都没配时为 null**，要显示成「未配置」而不是 0 |
| `rates` | [`#/definitions/PayChannelRateVersion`](#definitionspaychannelrateversion)\[\] | 是 | 全部版本，按生效时间倒序 |

### PayQuota

一个收款号的额度。主体级一条 + 每个已进件门店一条。 <p>**空列表 ≠ 额度为零**：空表示这家还没进过件，界面上必须画成两样东西 —— 读成「额度为零」的运营会去调大额度，而实际该做的是先走进件。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 空串 = 主体级默认收款号 |
| `payChannel` | `string,null` | 否 | WECHAT / ALIPAY |
| `applyStatus` | `string,null` | 否 | 进件状态；未 ACTIVE 时额度设了也不生效 |
| `limitMinor` | `number` | 是 | 上限（分）；**0 = 未设置，不拦**，不是「额度为零」 |
| `usedMinor` | `number` | 是 | 已用（分）。支付累加出来的事实，运营改不了 |

### PickupPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`#/definitions/PickupPointType`](#definitionspickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`#/definitions/PickupFeeMode`](#definitionspickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`#/definitions/PickupStatus`](#definitionspickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
| `latE6` | `number,null` | 否 | 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `rejectReason` | `string,null` | 否 | 驳回理由，只有 REJECTED 有值 |
| `communityNo` | `string` | 是 | 归属社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `storeNo` | `string` | 否 | 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16）， 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。 |
| `merchantName` | `string` | 否 | 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 |
| `address` | `string` | 是 | 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 |
| `openHours` | `string` | 是 | 营业/可取货时段，形如 "09:00-21:00" |
| `arriveTime` | `string` | 是 | 到货时间（运营排车依据） |
| `serviceFeeRate` | `number` | 是 | 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。 |
| `serviceFeePerItemMinor` | `number` | 是 | 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 |
| `acceptCount30d` | `number` | 是 | 近 30 天承接次数（P-2.2.5 职业化风控依据） |
| `createdAt` | `string` | 是 | 建档时间 |

### PlanDef

档位定义（`GET /ops/plan-defs`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | 档位码。**文案用 name/planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `name` | `string` | 是 | 名称 |
| `storeQuota` | `number` | 是 | 门店数配额 |
| `staffQuota` | `number` | 是 | 员工数配额 |
| `crossStoreStats` | `boolean` | 是 | 这一档给不给跨店统计 |
| `trialDays` | `number` | 是 | 试用天数。0 = 这一档不提供试用 |
| `enabled` | `boolean` | 是 | 启用中 |
| `subscriberCount` | `number` | 是 | 当前有几家在用这一档。 **改定义的人必须看得到这个数** —— 它是「只影响之后新订阅的人」那句话的具体量。 不给这个数，改档位的人只能凭感觉判断影响面。 |

### PlanUpgradeSignal

升档信号的一行（`GET /ops/merchant-plans/upgrade-signals`）。 **按 owner 分组而不是按主体**：「同一个人开了两个主体」正是要找的人 —— 他已经在多店经营，只是绕过了额度。主体表上没有联系电话（那在申请单上）， 所以这里只给 owner 号，销售拿它去后台查人。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ownerUserNo` | `string` | 是 | 店主的用户号。要联系他升档时用 |
| `entityNos` | `string`\[\] | 是 | 命中的商家号 |
| `entityNames` | `string`\[\] | 是 | 命中的商家名 |
| `entityCount` | `number` | 是 | 命中几家 |

### PointsOverview

积分资金总览。 **三个数摆在一起是刻意的** —— 恒等式是「流通中的积分 == 池子里的钱」， 分开看的话，失衡要等到有人主动比对才会发现。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `circulatingPoints` | `number` | 是 | 流通中的积分（用户可用 + 待生效） |
| `poolBalanceMinor` | `number` | 是 | 池子余额（分）。与上一个数对不上就是失衡 |
| `periodRedeemMinor` | `number` | 是 | 本期兑付（分）：补给商家的钱 |
| `byChannel` | [`#/definitions/PoolByChannel`](#definitionspoolbychannel)\[\] | 是 | 按通道分的账本。**不能只看总数** —— 账面是一个池子，钱实际分散在两个通道账户； 一个溢一个空的时候，总数仍然是平的。 |

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

### ProductGoods

商品池里的一行（goods 粒度，SKU 收在 `skus` 里）——**不是**  {@link  Sku }  的复数形式。 <p>后端 `prd_goods`/`prd_sku` 本来就是一对多：标题、图、类目、审核状态都在 goods 上， 价格/库存/规格才是 sku 的。商品池按 goods 展示、审核/强制下架/预售这几个动作 仍然打在具体某个 sku 上（见 `skus[].skuNo`）——两者granularity 不同，别混用。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | [`#/definitions/I18nText`](#definitionsi18ntext) | 是 | 标题（三语） |
| `cover` | `string` | 否 | 封面图 |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 否 | 归属类目 |
| `categoryName` | `string` | 否 | 类目名快照 |
| `status` | `string` | 是 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `skus` | [`#/definitions/GoodsSkuRow`](#definitionsgoodsskurow)\[\] | 是 | 这件商品下的所有规格 |
| `storeOnSale` | `boolean,null` | 否 | 门店投影（列表查询带 `storeNo` 时才有值）：这件商品在**那家店**上不上架。 `null`/缺失 = 未按店管理，跟随主体级 `status` —— 与「在那家店下架了」是两回事， 显示成同一个「否」会让运营去催商家上架一件其实全店都在卖的商品。 |

### PurchaseInvoice

进项票（供应商开给平台的）。自营链路专用 —— **票到才付款**。 `titleMatched` 是后端算好的：抬头与主体名对不上时不给核验通过， 而这一条**在界面上必须显示原因** —— 财务看到「不能核验」而不知道为什么， 只会去问开票的人，而对方也不知道。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `invoiceNo` | `string` | 是 | 平台侧的进项票记录号（不是发票上印的号） |
| `entityNo` | `string` | 是 | 哪家商家的票 |
| `period` | `string` | 是 | 所属账期 yyyyMM |
| `invoiceCode` | `string` | 是 | 发票代码，票面左上那一串 |
| `invoiceNumber` | `string` | 是 | 发票号码，票面右上那一串。**与 invoiceNo 不是一回事** |
| `invoiceType` | `string` | 是 | 票种：专票 / 普票 / 电子票 |
| `titleName` | `string` | 是 | 票面抬头 |
| `titleTaxNo` | `string` | 是 | 票面税号 |
| `amountMinor` | `number` | 是 | 价税合计（分） |
| `taxAmountMinor` | `number` | 是 | 其中税额（分） |
| `taxRate` | `number` | 是 | 万分比 |
| `invoiceDate` | `number,null` | 否 | 开票日期 |
| `imageUrl` | `string,null` | 否 | 票面影像。核验要看原件 |
| `status` | `string` | 是 | PENDING / SUBMITTED / VERIFIED / REJECTED |
| `rejectReason` | `string,null` | 否 | 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 |
| `titleMatched` | `boolean` | 是 | 抬头与主体名是否一致。**后端算，端上不重算** —— 两处判会走岔 |
| `settleNos` | `string`\[\] | 是 | 这张票覆盖了哪些结算单 |

### PushDevice

某收件人绑定的一台推送终端（运营端「选择终端发起测试」用）。 `clientId` 是原始设备标识，发送时回传；`clientIdMask` 只用于展示。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `receiverType` | `string` | 是 | 收件人类型：买家 / 商家 |
| `platform` | `string` | 是 | 平台 |
| `provider` | `string` | 是 | 厂商通道（华为/小米/…）。**真机稳不稳看它** —— 走不了厂商通道就只能靠自建长连 |
| `clientId` | `string` | 是 | 个推的 CID。**推送真正寻址靠它**，不是设备号 |
| `clientIdMask` | `string` | 是 | 打码后的 CID，列表里显示这个 |
| `updatedAt` | `string` | 否 | 更新时刻 |

### Qualification

主体档案上**已登记**的一条资质（mch_qualification）。上架闸门读的就是它

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qualNo` | `string` | 是 | 资质记录号 |
| `entityNo` | `string` | 是 | 所属商家 |
| `qualType` | `string` | 是 | 证件类型 |
| `qualName` | `string` | 是 | 证件名。**要与 sys_auth_code.required_qualification 同一套字面量** —— 类目授权按名字比对 |
| `qualNumber` | `string` | 否 | 证件编号，证上印的那一串 |
| `imageUrl` | `string` | 否 | 图片地址 |
| `expireAt` | `number,null` | 否 | null = 长期有效。与「已过期」是两回事，扫描任务不碰它 |
| `status` | `string` | 是 | VALID / EXPIRED / REVOKED |

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
| `validTo` | `number` | 是 | 报价有效期（毫秒时间戳）。过期不可被选定 —— 报价不能无限期挂着 |
| `priceChanges` | `number` | 是 | 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**， 超过阈值禁止再改 —— 频繁改价本身就是信号。 |
| `breached` | `boolean` | 是 | 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） |
| `createdAt` | `number` | 是 | 报价时间（毫秒时间戳） |

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

### ReachStat

触达健康度。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 所属商家 |
| `entityName` | `string` | 是 | 商家名 |
| `sent` | `number` | 是 | 发出多少条 |
| `members` | `number` | 是 | 覆盖多少会员 |
| `optOut` | `number` | 是 | 其中退订多少人 |
| `optOutRate` | `number` | 是 | 退订率。**这条线唯一的健康指标** —— 发得多不是成绩，发到有人关掉才是问题 |

### ReconAxisReport

一条对账轴的一轮结果。 ⚠️ **`coverage.note` 必须显示** —— 四条轴今天都只有 A 侧（我方自查）， 渠道账单、分账查询、银行流水三种外部数据都还没接。 不说的话，「今天没有差异」对四条轴都是假话。 `error` 非空 = **这条轴今天没跑成**。它与「零差异」在页面上长得一样、 含义却完全相反，所以要单独标出来。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `axis` | `string` | 是 | PAYMENT 收款 / SPLIT 分账 / PAYOUT 出款 / POINTS_POOL 积分池 |
| `outcome` | `object`（见下） \| `null` | 否 | 结论 |
| `coverage` | `object`（见下） | 是 | 覆盖率 |
| `error` | `string,null` | 否 | 错误信息 |

`coverage` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `complete` | `boolean` | 是 | — |
| `note` | `string` | 是 | — |

### ReconCoverage

对账的**覆盖范围说明**。 ⚠️ 它存在的理由只有一个：**不说的话「今天没有差异」是句假话。** 一期只有平台侧自查（扫我方停在 PENDING 的收款逐笔查单）， 渠道账单比对要等通道能力 —— 也就是说「渠道扣了钱而我方没记录」 那一整类差异**现在根本看不见**。 `note` **直接展示，不在端上写死** —— 写死的话，后端接上渠道账单之后， 页面还在说「看不见」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channelBillConnected` | `boolean` | 是 | 渠道账单是否已接入。false 时 note 必须显示给运营 |
| `note` | `string` | 是 | 说明 |

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

### Region

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath |
| `enabled` | `boolean` | 是 | 开城开关：停用只影响新的选择，存量商家不动 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 |

### RegionSuggestion

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 国标区划码 |
| `level` | `string` | 是 | 层级 |
| `name` | `string` | 是 | 名称 |
| `path` | `string` | 是 | 「广东省 / 深圳市 / 龙华区 / 福城街道」 |
| `source` | [`#/definitions/RegionMatchSource`](#definitionsregionmatchsource) | 是 | 来源 |
| `detail` | `string` | 是 | 依据：匹配到的地址片段，或「茜坑社区 · 320 米」 |

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

恶意差评申诉（P-13.1.3）。UPHELD = 支持商家（差评下架），REJECTED = 驳回申诉（差评保留）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `appealNo` | `string` | 是 | 申诉单号 |
| `reviewNo` | `string` | 是 | 被申诉的评价 |
| `merchantNo` | `string` | 是 | 申诉方商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `reviewRating` | `number` | 是 | 被申诉那条评价的星级与正文。 **裁决台必须显示它们** —— 要判断「这条差评是不是恶意的」， 而屏幕上只有单号和商家自己写的申诉理由的话，裁的是一面之词。 |
| `reviewContent` | `string` | 是 | 被申诉的那条评价原文。**不带上它，审的人要跳去另一页** |
| `reason` | `string` | 是 | 商家的申诉理由 |
| `evidenceCount` | `number` | 是 | 举证材料数量（截图/聊天记录） |
| `status` | [`#/definitions/AppealStatus`](#definitionsappealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），REJECTED = 驳回申诉（差评保留） |
| `submittedAt` | `string` | 是 | 申诉提交时间 |
| `verdict` | `string` | 否 | 裁决说明：无论支持还是驳回都必须写，商家会看到 |

### RiskEvent

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `eventNo` | `string` | 是 | 风险事件单号 |
| `type` | [`#/definitions/RiskType`](#definitionsrisktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | [`#/definitions/SubjectType`](#definitionssubjecttype) | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
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

角色定义（`GET /ops/perm/roles`）。 **2026-08-12 换形**：原来带 `perms: string[]`（权限码集合）， 现在授权的单位是**功能点**（`sys_role_point`）—— 与后端存的东西一致。 为什么不继续用权限码：库里存功能点，界面勾权限码的话，保存时要把码反向 翻译成功能点集合，而一个码对应多个功能点，反向只能「全给」。 **那就是翻译层**，而这个仓库里绝大多数跨端缺陷都出自翻译层两边各写一套。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。自定义角色不在 `Role` 联合类型里，所以是 string |
| `name` | `string` | 是 | 角色展示名 |
| `endCode` | `string` | 是 | 端。运营端固定 OPS |
| `builtin` | `boolean` | 是 | 内置角色：是 `Perms.java` 的镜像，改了会与回落表分叉 —— 渲染但禁用 |
| `pointCount` | `number` | 是 | 已授予的功能点数 |
| `staffCount` | `number` | 是 | 持有该角色的账号数。 **删角色前唯一能看出「会影响谁」的信息** —— 后端也拦（10441），但那是拦在点下去之后。 |

### RuleTexts

规则文案（P-17.1.4）。这三条是 C 端要展示给用户看的，不能为空。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `refund` | `string` | 是 | 退款规则文案，C 端售后页展示 |
| `pickup` | `string` | 是 | 自提规则文案，C 端下单与取货页展示 |
| `weighDiff` | `string` | 是 | 称重差价规则文案，生鲜订单展示 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### SceneChannelCell

场景 × 受众 × 通道 的一格（P-14.1）。 <p>「哪个事件走哪些通道」以前**硬编码在编排里** —— 后端把它做成了可配置， 而运营端此前没有入口，于是这份配置存在、能改，却没人看得见。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scene` | `string` | 是 | 场景码（订单已支付、售后已受理…） |
| `audience` | `string` | 是 | 受众：买家 / 商家 / 运营 |
| `channel` | `string` | 是 | 通道 |
| `enabled` | `boolean` | 是 | 启用中 |
| `pushLevel` | `string` | 是 | 推送等级（App 推送用；其它通道为空） |
| `locked` | `boolean` | 是 | **恒锁定的格子**。站内信（INAPP）是事实记录，运营不可关 —— 后端会拒掉这一格的关闭请求，前端被绕过也兜得住，界面只是别让人白点。 |

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

### ServiceScopeConfig

经营范围档位的启用状态（ADR-009 三档）。 档位本身是枚举，永远是那三个；这里配的是**这一期开放哪几档**。 一期自营模式关掉了 PLATFORM —— 没有虚拟商品/卡券/自营快递品支撑它。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scope` | `string` | 是 | COMMUNITY / CITY / PLATFORM |
| `enabled` | `boolean` | 是 | 这一期是否开放这一档。关掉**不影响已经是这一档的存量商家**，只是不能再选 |
| `merchantCount` | `number` | 是 | 当前在用的商家数。不带计数的开关是盲操作 |

### SettleBatch

账期批次：<b>一个主体、一个通道、一个账期，一批</b>。 <p>批次管「能不能放」，单据管「放得成不成」—— 所以这一页回答的是「这家的钱卡在哪一批」，而不是「这一笔多少钱」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`#/definitions/SettleBatchStatus`](#definitionssettlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分） |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`#/definitions/ReconScope`](#definitionsreconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |

### Settlement

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `netMinor` | `number` | 是 | 实付商家（分） |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`#/definitions/SettleStatus`](#definitionssettlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |

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

### SpecDim

规格项（规格库 V195）。**通用与专用是运营端的两个页面**： 通用维度改一条全站生效，专用维度只影响一个类目 —— 混在一张表里，改的人不知道自己动了多大范围。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，**改码等于换一根聚合轴** |
| `name` | `string` | 是 | 维度名（「颜色」「净重」） |
| `valueType` | `string` | 是 | ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有归一量 |
| `unit` | `string,null` | 否 | 单位。QUANT 型必填，ENUM 型为空 |
| `usageType` | `string` | 是 | SALE 进 SKU 笛卡尔积 / PROP 只是描述 |
| `universal` | `boolean` | 是 | 通用维度：所有类目都能用 |
| `scope` | `string` | 是 | `PLATFORM` 平台的 / `MERCHANT` 商家自建的 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `valueCount` | `number` | 是 | 这个维度下有几个取值 |
| `inUse` | `number` | 是 | 被几个类目绑着 —— 归档前要知道自己在动多大范围 |
| `values` | [`#/definitions/SpecValue`](#definitionsspecvalue)\[\] | 是 | 取值列表 |

### SpecTemplate

平台规格模板（P-3.4 / E27，后端 `prd_spec_template` 里 `scope=PLATFORM` 的那些）。 <p>B-4.4 商家建品时能选它，而平台端此前**没有维护入口** —— 表里只有初始化时 塞进去的几行，谁也改不了、加不了。三端联动表把这条记成「❌ 断裂：模板是死的」。 <p>与商家自存的模板（`scope=MERCHANT`）不是同一批数据：那些归商家， 平台端一条都不该列出来，更不该改 —— 改了那家店的历史规格就对不上了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `string` | 是 | 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 |
| `categoryType` | [`#/definitions/CategoryTemplate`](#definitionscategorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`#/definitions/SpecTemplateOption`](#definitionsspectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
| `createdAt` | `string` | 否 | 创建时刻 |

### SpecValue

规格值。**有编号有归一量**，才谈得上聚合、排序与比价。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 取值编号 |
| `dimNo` | `string` | 是 | 维度号 |
| `code` | `string` | 是 | 语义码 |
| `label` | `string` | 是 | 显示名 |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |
| `aliases` | `string`\[\] | 是 | 别名：识别、搜索与自动归一用 |
| `scope` | `string` | 是 | PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 |
| `entityNo` | `string,null` | 否 | 哪家商家的票 |
| `sort` | `number` | 是 | 排序权重 |
| `status` | `string` | 是 | 状态 |
| `merchantCount` | `number` | 是 | 多少个商家在用这个值 —— 停用前要知道影响面 |

### SplitLog

分账指令流水（后端 `stl_split_log`）。 <b>结算单说的是「该给多少」，这里说的是「发了几条指令、成没成、失败在哪」</b>—— 出问题时要看的是后者。失败的记录也在这里。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 所属结算单 |
| `subOrderNo` | `string` | 是 | 对应的子订单 |
| `splitAction` | `string` | 是 | SPLIT / REVERSE / SUBSIDY / SUBSIDY_RETURN |
| `amountMinor` | `number` | 是 | 该指令的金额。**补差与分账口径不同** |
| `result` | `string` | 是 | SUCCESS / FAIL |
| `requestNo` | `string` | 是 | 平台侧幂等号 |
| `providerNo` | `string,null` | 否 | 通道返回的单号；失败时为空 |
| `message` | `string,null` | 否 | 失败原因。**这一列是这张表存在的意义** |
| `createdAt` | `number` | 是 | 指令时刻（毫秒） |

### SpuStd

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `stdNo` | `string` | 是 | 标准品号 |
| `categoryNo` | `string` | 是 | 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 |
| `categoryName` | `string` | 否 | 类目名 |
| `title` | `string` | 是 | 标题 |
| `titleI18n` | [`#/definitions/Record<string,string>`](#definitionsrecordstringstring) | 否 | 标题的多语言版本 |
| `subtitle` | `string` | 否 | 副标题 |
| `cover` | `string` | 否 | 封面图 |
| `images` | `string`\[\] | 否 | 图集 |
| `specGroups` | `object`（见下）\[\] | 是 | 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 |
| `keywords` | `string` | 否 | 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 |
| `status` | `string` | 否 | 状态 |
| `refCount` | `number` | 否 | 被引用次数。只服务排序与去重判断，不参与任何校验 |
| `barcode` | `string` | 否 | 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 |
| `source` | `string` | 否 | 出处：`OPS` 运营手录 / `OFF` 从开放库导入。 <p>导进来的那批标题是原始众包文案（品牌写法不一、错别字都有）， 所以全部落成归档态等人过目。运营靠这一列把「还没人看过的」与「自己录的」分开审。 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `options` | `string`\[\] | 是 | — |
| `optionCodes` | `string`\[\] | 否 | — |
| `templateNo` | `string` | 否 | — |

### Staff

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `staffNo` | `string` | 是 | 员工单号 |
| `username` | `string` | 是 | 登录名 |
| `name` | `string` | 是 | 姓名 |
| `roles` | `string`\[\] | 是 | 角色（**可多个**）。权限码取所有角色的并集。 <p>2026-08-12 从单值 `role` 换成数组：库早就支持多角色 （`sys_role_member` 唯一键含 role_code、`Perms.of` 取并集）， 是写接口把它压成了单值。 |
| `merchantNo` | `string` | 否 | 数据域（P-1.1.3）。只对**受限角色**有意义： 社区运营 → communityNo、商家运营 → merchantNo。 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。 |
| `communityNo` | `string` | 否 | 社区运营的社区数据域 |
| `pickupNo` | `string` | 否 | 自提点数据域 |
| `enabled` | `boolean` | 是 | 是否启用。停用后立即无法登录，历史操作留痕保留 |
| `mustChangePassword` | `boolean` | 否 | 首登必须改密。 建号时后端生成的一次性初始密码只是「拿到账号」的凭据，不是长期口令。 |
| `lastLoginAt` | `string` | 否 | 最近登录时间。从未登录为空 |
| `createdAt` | `string` | 是 | 建档时间 |

### StoreAcquisition

门店获客效果（P-10.1.4）：扫码 → 进店 → 注册 → 首单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `scan` | `number` | 是 | 扫码次数（PV）。同一个人扫三次算三次 |
| `scanUv` | `number` | 是 | 扫码人数（UV）。匿名访客按设备号去重 —— 他还没有账号 |
| `enter` | `number` | 是 | 进店人数：归因到本店的去重用户数 |
| `register` | `number` | 是 | **首次归因人数**（后端 `decision=CREATED`）。 ⚠️ **不等于「平台新注册」**：一个注册了很久的老用户，第一次扫这家店的码 也会计入。字段名沿用 `register` 是为了不动既有契约，口径以这句为准。 |
| `firstOrder` | `number` | 是 | 其中已产生首单的人数 |
| `convRate` | `number` | 是 | 首单转化率 = firstOrder / **scanUv**，0–1。 分母用 UV 不用 PV：同一个人扫三次不该把转化率摊薄成三分之一。 |

### StoreFulfillmentRow

门店送货方式（方案 v4，P0 只读）：每店四路开关的快照。 channel 值域 = STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string,null` | 是 | 门店名 |
| `storeStatus` | `string` | 是 | 门店状态 |
| `channels` | `object`（见下）\[\] | 是 | 这家店开了哪几条履约渠道 |

`channels[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | `string` | 是 | — |
| `enabled` | `boolean` | 是 | — |
| `denied` | `boolean` | 是 | 准入矩阵不允许（按主体类型） |
| `templateNo` | `string,null` | 否 | 仅 EXPRESS：运费模板号 |
| `locked` | `boolean` | 否 | 运营锁路（P2）：买家侧不可选、商家侧置灰。解锁只能运营 |
| `scopeMode` | `string` | 否 | ALL / SUBSET（P2 范围子集） |
| `areaNos` | `string`\[\] | 否 | — |

### StoreGovern

平台视角的门店档案（后端 `mch_store`，`GET /ops/stores`）。 **只读为主**：门店资料、价格、库存运营一律不改 —— 平台的边界是「裁、定、兜」， 不替商家运营。这份类型里唯一会被写回的是 `status`（解除强制下线）。 与  {@link  StoreMode  }  的关系：那份是「准入与保证金」页里**只关心经营模式与收款号** 的窄投影，这份是门店档案的全貌。两者共用 storeNo，故意不合并 —— 合并会让那一页凭空多出十个它不该关心的字段。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 是 | 门店地址 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `isDefault` | `boolean` | 是 | 是否主体的默认门店。默认店承接「没指定门店」的那些流量 |
| `status` | [`#/definitions/StoreGovernStatus`](#definitionsstoregovernstatus) | 是 | 经营状态，见  {@link  StoreGovernStatus } |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) | 是 | 自营 / 第三方。决定这家店的钱怎么走、票怎么开 |
| `payMerchantNo` | `string,null` | 是 | 本店专属收款商户号。 **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。 |
| `announcement` | `string` | 是 | 门店公告（走 P-10.1 的机审 + 人审） |
| `openHours` | `string` | 是 | 营业时间，展示串 |
| `deliveryRadiusM` | `number` | 是 | 配送半径（米） |
| `deliveryMinOrderMinor` | `number` | 是 | 起送价（分） |
| `deliveryFeeMinor` | `number` | 是 | 配送费（分） |
| `deliveryFreeThresholdMinor` | `number` | 是 | 免配送费门槛（分） |

### StoreMode

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string` | 是 | 门店名，展示用 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `businessMode` | [`#/definitions/BusinessMode`](#definitionsbusinessmode) \| `null` | 是 | 自营 / 第三方；空 = 尚未设置 |
| `payMerchantNo` | `string,null` | 是 | 该店实际可用的收款号（本店专属号优先，回落到主体默认号）。**空 = 不能切第三方** |

### StorePageAudit

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `auditNo` | `string` | 是 | 审核单号 |
| `merchantNo` | `string` | 是 | 提审商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `storeName` | `string,null` | 否 | 这条内容发给哪家店。存量单（后端 V214 之前）没记，为空。 多店商家只看商家名判断不了「南门店今天停电」该不该放行 —— 而通过之后正是写回那家店。 |
| `kind` | [`#/definitions/StoreAuditKind`](#definitionsstoreauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL、公告文本，或 `DISTRICT:330106` 这样的覆盖项定位串 |
| `display` | `string` | 否 | 人话版的 content。`SERVICE_AREA` 时是「浙江省 / 杭州市 / 西湖区」，其余与 content 相同。 **列表与详情一律显示它**：让运营对着 `DISTRICT:330106` 判断 「这家菜摊该不该覆盖整个西湖区」，等于让他去别处查一次再回来。 |
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
| `size` | `string,null` | 是 | 最近一次印刷的尺寸规格，如 "10x10cm"；**从没印过是 null**（尺寸属于那一次印刷，不是门店属性） |
| `printed` | `number,null` | 是 | 累计已印数量，用于对账印刷成本。 ⚠️ **null = 还没人登记，不是「印了 0 张」**。两者在界面上必须分开显示 —— 混成一个数之后，运营没法知道该去催谁登记。 |
| `scanCount` | `number` | 是 | 区间内扫码次数。**这个 0 是真的 0**（埋点一直在记），与 printed 的 null 不同 |

### StoreStats

门店经营状况（`GET /ops/stores/{storeNo}/stats`）。 后端复用商家自己在 B 端看的那套统计，不另存计数器 —— 另存的迟早出现「总览说 3 单、点进去只有 2 单」。 待办只有**门店维度**三项：核销与分拣是自提点维度且不限商家， 摆进门店页会被读成「这家店的活」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `todayOrders` | `number` | 是 | 今日订单数 |
| `todayGmvMinor` | `number` | 是 | 今日 GMV（分） |
| `monthOrders` | `number` | 是 | 本月订单数 |
| `monthGmvMinor` | `number` | 是 | 本月 GMV（分） |
| `ownedTrafficRate` | `number` | 是 | 自带客流占比，0–1。**直接对应这家店少付的佣金**（ADR-004） |
| `toShip` | `number` | 是 | 待发货 |
| `toDeliver` | `number` | 是 | 待自送 |
| `toStock` | `number` | 是 | 缺货待补。运营看它判断「这家店是不是没人管了」 |

### StoreTemplate

店铺主页模板。 ⚠️ `usedByCount` 是**只读的引用计数**，不是配置项 —— 它存在的唯一理由是 拦住"停用一个正在被 12 家店用着的模板"这件事。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | [`#/definitions/SectionLayout`](#definitionssectionlayout) | 是 | 商品区排布 |
| `sections` | [`#/definitions/TemplateSection`](#definitionstemplatesection)\[\] | 是 | 板块开关列表 |
| `enabled` | `boolean` | 是 | 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 |
| `isDefault` | `boolean` | 是 | 默认模板：新店开出来就用它，所以停用不了 |
| `usedByCount` | `number` | 是 | 正在使用该模板的店铺数（只读） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### TaxRule

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
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |

### Topic

主题分类（陈列）。 <p><b>与类目正交、与活动分开</b>：类目回答「这是什么货、要什么资质」， 活动回答「打几折」，主题只回答「这周首页摆什么」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `topicNo` | `string` | 是 | 专题号 |
| `title` | `string` | 是 | 标题 |
| `subtitle` | `string` | 否 | 一句话说明，如「7 点前送到」。空 = 不展示副标题 |
| `cover` | `string` | 否 | 封面图 |
| `sort` | `number` | 是 | 首页排序，小的在前 |
| `startAt` | `number` | 否 | 生效起止（毫秒）。**都可空 = 常设专题** —— 填一个假的结束时间会让它某天悄悄消失 |
| `endAt` | `number` | 否 | 结束时刻 |
| `status` | `string` | 否 | ACTIVE / ARCHIVED。归档不删：分享出去的海报还指着它 |
| `goodsCount` | `number` | 是 | 专题里有几件商品。**空专题在 C 端是一个点进去什么都没有的入口**，列表要看得见 |

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
| `storeNo` | `string,null` | 否 | 门店级处置的对象门店。**`STORE_OFFLINE` 必有、其余动作必空** —— 主体级处置带上门店号会让人以为只压了那一家。 |
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

### WxTemplates

微信订阅消息的模板号映射。**唯一一项开放到运营端的通道参数**（模板号不是凭据）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderArrived` | `string` | 是 | 「订单已送达」用的微信模板 id |
| `refunded` | `string` | 是 | 「退款成功」用的微信模板 id |
