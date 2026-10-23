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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `refundExecution` | [`RefundExecutionProgress`](#refundexecutionprogress) \| `null` | 否 | 原资金执行与独立资源恢复进度。 |
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`Liability`](#liability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`LiabilityShare`](#liabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### POST `/ops/after-sales/{afterSaleNo}/recover`

仅恢复原执行，不确认资金成功、不新建退款

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

_无字段_

**出参**（`data`）

类型：[`AfterSale`](#aftersale)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `refundExecution` | [`RefundExecutionProgress`](#refundexecutionprogress) \| `null` | 否 | 原资金执行与独立资源恢复进度。 |
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`Liability`](#liability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`LiabilityShare`](#liabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
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
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类。**目前只存不判**（后端注释里记着这条账） |
| `replyHours` | `number` | 是 | 商家响应时限（小时）：超时系统替他同意并退款 |
| `shipBackDays` | `number` | 是 | 买家寄回时限（天）：逾期未寄出则关闭本次申请 |
| `confirmHours` | `number` | 是 | 商家确认收货时限（小时）：超时系统退款 |
| `interveneWorkDays` | `number` | 是 | 平台介入承诺时限（工作日）：仅展示与超期提醒，不自动裁决 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/after-sales/fast-refund-rule`

售后规则（P-6.1.2）：金额上限 > 0、各时限 ≥ 1

**入参**

_无字段_

**出参**（`data`）

类型：[`FastRefundRule`](#fastrefundrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enabled` | `boolean` | 是 | 总开关。关掉后所有小额售后都走人工 |
| `maxAmount` | `number` | 是 | 金额上限（分），必须 > 0 |
| `withinHours` | `number` | 是 | 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） |
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类。**目前只存不判**（后端注释里记着这条账） |
| `replyHours` | `number` | 是 | 商家响应时限（小时）：超时系统替他同意并退款 |
| `shipBackDays` | `number` | 是 | 买家寄回时限（天）：逾期未寄出则关闭本次申请 |
| `confirmHours` | `number` | 是 | 商家确认收货时限（小时）：超时系统退款 |
| `interveneWorkDays` | `number` | 是 | 平台介入承诺时限（工作日）：仅展示与超期提醒，不自动裁决 |
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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


#### GET `/ops/communities/{no}/fence-impact`

改围栏之前先看影响：这个半径会圈进来多少条收货地址

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：[`FenceImpact`](#fenceimpact)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `currentRadiusM` | `number` | 是 | 当前服务半径，米 |
| `previewRadiusM` | `number` | 是 | 预览服务半径，米 |
| `currentInside` | `number` | 是 | 当前半径内对象数 |
| `previewInside` | `number` | 是 | 预览半径内对象数 |
| `addressesWithCoords` | `number` | 是 | 有坐标的收货地址总数。**分母要给** —— 「多进来 0 户」在一个没几条地址有坐标的库里说明不了任何事 |


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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


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
| `kind` | [`SettlementKind`](#settlementkind) | 否 | ESTATE 小区 / VILLAGE 村。裁决的人要一眼看出这是哪种聚落 |
| `originCode` | `string` | 否 | 关联的官方村码；非空 = 从词典选的，重复开通会被后端拦 |
| `located` | `boolean` | 否 | 带没带定位。**没带的要显眼** —— 通过后聚落没有坐标， 买家用定位永远找不到它，运营得先补坐标再通过。 |
| `latE6` | `number,null` | 否 | 商家提报时带的坐标（gcj02，E6）。**要看得见具体值** —— 只给一个「有/无」，落点偏到隔壁区也照样显示「有定位」，判不出对错。 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `fallbackLatE6` | `number,null` | 否 | 官方村码在区划表里的坐标（高德批量补录）。没带定位时后端通过这条提报会自动用它兜底 —— 两个都空，才是真的「通过后无坐标、买家搜不到」。 |
| `fallbackLngE6` | `number,null` | 否 | 兜底经度：商家没选点时用提交那一刻的位置。**多半不在那个小区里**，裁决要留意 |
| `status` | [`CommunityApplyStatus`](#communityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。**只有 PENDING 能裁**：裁完就是终态，再裁一次意味着同一条提报有两个结论 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因。**原样出现在商家 B 端**，所以驳回必须填 |
| `submittedAt` | `number` | 是 | 提报时间 |


#### POST `/ops/communities/buildings`

建一栋楼

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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


#### GET `/ops/communities/duplicates`

duplicateCommunities

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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |


#### GET `/ops/communities/near`

一个坐标附近已开通的聚落，按距离升序 —— 裁决时查重用 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`NearbyCommunity`](#nearbycommunity)\[\]


#### POST `/ops/communities/open-map`

把某个区划前缀下、**地图导入**的聚落批量开城

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/coverage/distribution`

位置分布（P-2.1）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CoverageDistribution`](#coveragedistribution)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regions` | [`RegionRow`](#regionrow)\[\] | 是 | 区县概览：一区县一行。两万多聚落不平铺，点开区县走 communitiesInRegion 下钻 |
| `supplyGaps` | [`DistributionRow`](#distributionrow)\[\] | 是 | 招商清单：有买家、无商家覆盖的聚落（全局小集合，可行动到小区） |
| `totals` | [`DistributionTotals`](#distributiontotals) | 是 | 全局四桶计数 + 买家总数 |
| `unattributable` | [`Unattributable`](#unattributable) | 是 | 无法归属覆盖片区的数量 |


#### GET `/ops/coverage/distribution/communities`

下钻：一个区县（6 位码）下的聚落明细，分页（区县本身也可能有几千个聚落）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`DistributionRow`](#distributionrow)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/coverage/health`

坐标健康度（P-2.1）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`CoverageHealth`](#coveragehealth)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `stores` | `object`（见下） | 是 | 门店统计摘要 |
| `addresses` | `object`（见下） | 是 | 地址**只给聚合数**：那是个人信息，看总数就够判断分母有多脏 |
| `communities` | `object`（见下） | 是 | 社区统计摘要 |

`stores` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |
| `missing` | `object`（见下）\[\] | 是 | 没标点的那些。**给明细不只给数字** —— 只给一个数，运营下一步无从做起 |

`stores.missing[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店编号 |
| `storeName` | `string` | 是 | 门店显示名称 |
| `merchantNo` | `string` | 是 | 从这里跳到商家去催他标点。**刻意不带商家名**（取名字要绕数据域） |
| `deliveryRadiusM` | `number,null` | 是 | 他以为自己限了多少米，而实际一米都没限 —— 后果有多大就看这个数 |

`addresses` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |

`communities` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |
| `missing` | `object`（见下）\[\] | 是 | 没坐标的聚落**谁也匹配不到** —— 而它看起来一切正常：建档成功、列表里有 |

`communities.missing[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | — |
| `name` | `string` | 是 | 业务对象显示名称 |


#### GET `/ops/geo/places`

固定地址库这一屏

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`GeoPlacePage`](#geoplacepage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`GeoPlace`](#geoplace)\[\] | 是 | 这一页的行。按命中次数降序 —— 最该沉淀的排最前 |
| `total` | `number` | 是 | **全量的那个数**，不是这一页的 —— 只给一页的条数，「还要依赖地图多久」就判不出来 |
| `mapStatus` | `string` | 是 | CLOSED / OPEN / QUOTA_EXHAUSTED。这一行是唯一能提前发现「地图快不行了」的地方 |


#### POST `/ops/geo/places/promote`

把高频建筑沉淀成聚落（kind=BUILDING、source=MAP、默认 CLOSED）

**入参**

_无字段_

**出参**（`data`）

类型：[`CommunityImportResult`](#communityimportresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `received` | `number` | 是 | 收到多少条 |
| `created` | `number` | 是 | 新建了多少条 |
| `updated` | `number` | 是 | 更新了多少条（按幂等键命中既有行） |
| `skipped` | `number` | 是 | 跳过多少条。**没坐标的一律跳** —— 建出来买家永远搜不到它，而这件事没有任何报错 |
| `deduped` | `number` | 是 | 认出是「同一个小区的另一个 POI」而没有建档的条数（名字完全相同且相距 ≤300m）。 与 `skipped`（数据不合格）分开计：导一个区时这个数就是 「地图给的 POI 比小区多出来多少」，是判断导入质量的唯一依据。 |
| `dryRun` | `boolean` | 是 | 试算。**默认就是它** —— 一次动几百行的接口，默认值要在安全那一边 |


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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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
| `authorType` | [`PostAuthorType`](#postauthortype) | 是 | 作者类型：普通用户 or 商家。商家发的内容审核标准更严 |
| `authorName` | `string` | 是 | 作者昵称/店名 |
| `title` | `string` | 是 | 内容标题 |
| `content` | `string` | 是 | 正文 |
| `communityNo` | `string` | 是 | 归属社区。内容只在本社区露出 |
| `communityName` | `string` | 是 | 社区名快照 |
| `skuNo` | `string,null` | 否 | 关联商品；纯分享贴可以没有 |
| `riskHits` | `string`\[\] | 是 | 命中的风险词。 ⚠️ 命中的内容**不进批量通过** —— 批量 + 风险内容 = 事故，必须逐条看。 |
| `status` | [`PostStatus`](#poststatus) | 是 | 审核状态。允许的流转见 `POST_TRANSITIONS`（`PASSED → OFFLINE` 是单独一条路） |
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
| `status` | [`QuestionStatus`](#questionstatus) | 是 | 问答状态 |
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
| `status` | [`QuestionStatus`](#questionstatus) | 是 | 问答状态 |
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
| `kind` | [`RankingKind`](#rankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
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
| `kind` | [`RankingKind`](#rankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
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
| `kind` | [`MaterialKind`](#materialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`MaterialScope`](#materialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
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
| `kind` | [`MaterialKind`](#materialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`MaterialScope`](#materialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
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
| `role` | [`Role`](#role) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
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
| `role` | [`Role`](#role) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
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
| `pendingGoodsAudit` | `number` | 是 | 待审商品数（P-3.2 商品审核队列） |
| `goodsAuditOldestDays` | `number` | 是 | 最早那件待审商品等了几天。**与数量成对出现才有意义** —— 「194 件待审」既可能是今天涌进来的一批，也可能是积了两周没人管， 而这两件事该做的反应完全不同。没有待审时为 0 |


#### GET `/ops/dashboard/merchants`

商家经营排行（P-16.1.2 / P-16.1.3）——大盘之下的第一层下钻

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MerchantRankRow`](#merchantrankrow)\[\]


#### GET `/ops/dashboard/stores`

门店经营排行（门店③）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`StoreRankRow`](#storerankrow)\[\]


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
| `txns` | [`DebtTxn`](#debttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |


#### POST `/ops/debts/{entityNo}/deposit-offset`

这个动作不是自然幂等的：它算 min(欠款, 请求额, 保证金可用)， 点第二次时三个数都变小了，于是会接着扣，而每次单看都「算得对」

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
| `txns` | [`DebtTxn`](#debttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |


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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`InvoiceStatus`](#invoicestatus) | 是 | 开票状态 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`InvoiceStatus`](#invoicestatus) | 是 | 开票状态 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣。 **不含运费**（V367 / TDD-快递100商家寄件 §9 AC21，2026-09-29 改的口径）： 运费是代收代付的钱，进了这里等于让商家为平台代收的运费付佣金。 它单列在结算单的 `freight_income_minor`。 口径改之前这个数是「货款 + 运费」，按它做过的报表要重对一遍。 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `freightIncomeMinor` | `number` | 是 | 代收的运费（分）。**不在 grossMinor 里**，所以不进佣金基数。 非快递单为 0。 |
| `freightCostMinor` | `number` | 是 | 平台实付给快递公司、从商家收款里扣回的快递费（分）。 **只有平台代寄才有**；商家自己填单号发货是他自付，这里是 0。 判据是 `freightShipMode`，不是「这个数是不是 0」—— 平台代寄但还没称重回传时它也是 0。 |
| `freightShipMode` | `string` | 否 | PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为空 |
| `freightDiffReason` | `string` | 否 | 实付高于代收时的原因：OVERWEIGHT / REGION_SURCHARGE / OVER_CAP。没有差额时为空 |
| `netMinor` | `number` | 是 | 实付商家（分）= 基数 − 佣金 − 服务费 + 运费收入 − 实付快递费 |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`SettleStatus`](#settlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 否 | 自营 / 第三方 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣。 **不含运费**（V367 / TDD-快递100商家寄件 §9 AC21，2026-09-29 改的口径）： 运费是代收代付的钱，进了这里等于让商家为平台代收的运费付佣金。 它单列在结算单的 `freight_income_minor`。 口径改之前这个数是「货款 + 运费」，按它做过的报表要重对一遍。 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `freightIncomeMinor` | `number` | 是 | 代收的运费（分）。**不在 grossMinor 里**，所以不进佣金基数。 非快递单为 0。 |
| `freightCostMinor` | `number` | 是 | 平台实付给快递公司、从商家收款里扣回的快递费（分）。 **只有平台代寄才有**；商家自己填单号发货是他自付，这里是 0。 判据是 `freightShipMode`，不是「这个数是不是 0」—— 平台代寄但还没称重回传时它也是 0。 |
| `freightShipMode` | `string` | 否 | PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为空 |
| `freightDiffReason` | `string` | 否 | 实付高于代收时的原因：OVERWEIGHT / REGION_SURCHARGE / OVER_CAP。没有差额时为空 |
| `netMinor` | `number` | 是 | 实付商家（分）= 基数 − 佣金 − 服务费 + 运费收入 − 实付快递费 |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`SettleStatus`](#settlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 否 | 自营 / 第三方 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣。 **不含运费**（V367 / TDD-快递100商家寄件 §9 AC21，2026-09-29 改的口径）： 运费是代收代付的钱，进了这里等于让商家为平台代收的运费付佣金。 它单列在结算单的 `freight_income_minor`。 口径改之前这个数是「货款 + 运费」，按它做过的报表要重对一遍。 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `freightIncomeMinor` | `number` | 是 | 代收的运费（分）。**不在 grossMinor 里**，所以不进佣金基数。 非快递单为 0。 |
| `freightCostMinor` | `number` | 是 | 平台实付给快递公司、从商家收款里扣回的快递费（分）。 **只有平台代寄才有**；商家自己填单号发货是他自付，这里是 0。 判据是 `freightShipMode`，不是「这个数是不是 0」—— 平台代寄但还没称重回传时它也是 0。 |
| `freightShipMode` | `string` | 否 | PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为空 |
| `freightDiffReason` | `string` | 否 | 实付高于代收时的原因：OVERWEIGHT / REGION_SURCHARGE / OVER_CAP。没有差额时为空 |
| `netMinor` | `number` | 是 | 实付商家（分）= 基数 − 佣金 − 服务费 + 运费收入 − 实付快递费 |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`SettleStatus`](#settlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |


#### POST `/ops/payables/bank-flows/import`

导入银行流水（TDD §10）

**入参**

_无字段_

**出参**（`data`）

类型：[`BankFlowImportResult`](#bankflowimportresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 文件里认出来的行数 = imported + skipped + failed |
| `imported` | `number` | 是 | 真正入库的 |
| `skipped` | `number` | 是 | 流水号已存在、跳过的。**重复上传是常态，不是错误** |
| `failed` | `number` | 是 | 没解析成功的行数 |
| `failures` | [`BankFlowImportFailure`](#bankflowimportfailure)\[\] | 是 | 失败明细。`line` 是原始文件里的行号 —— 财务要对着原文件看 |


#### GET `/ops/payables/payout-list`

付款清单（P2）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PayoutList`](#payoutlist)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PayoutRow`](#payoutrow)\[\] | 是 | 可付的 |
| `blocked` | [`PayoutBlockedRow`](#payoutblockedrow)\[\] | 是 | 被挡下的 |
| `totalMinor` | `number` | 是 | rows 的合计（分） |


#### GET `/ops/payout-accounts`

listPayoutAccounts

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`PayoutAccount`](#payoutaccount)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/ops/payout-accounts/{accountNo}/audit`

审核

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `accountNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`PayoutAccount`](#payoutaccount)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `accountNo` | `string` | 是 | 平台内部单号，审核时按它定位 |
| `entityNo` | `string` | 是 | 供应商主体。**账户挂主体不挂门店** —— 收款是主体的事，门店只是统计维度 |
| `accountType` | `string` | 是 | PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公 |
| `accountName` | `string` | 是 | 户名。**必须等于营业执照主体名** —— 后端硬校验，对不上提交就被拒 |
| `accountMasked` | `string` | 是 | 账号掩码，只留尾四位 |
| `bankName` | `string,null` | 否 | 开户银行。商家可不填，所以可能为空 |
| `bankBranch` | `string,null` | 否 | 开户支行。同上，转账时财务据它核对 |
| `status` | `string` | 是 | PENDING 待审 / ACTIVE 生效中 / REJECTED 已驳回 / DISABLED 已被新卡顶替 |
| `auditRemark` | `string,null` | 否 | 驳回原因，原样回商家 |
| `auditedAt` | `number,null` | 否 | 审核时刻（毫秒）。未审为空 —— 与「审过但没写原因」是两回事 |


#### GET `/ops/payouts`

listPayouts

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`Payout`](#payout)\[\]


#### POST `/ops/payouts/{payoutNo}/fail`

打款失败或退回

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `payoutNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Payout`](#payout)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payoutNo` | `string` | 是 | 放款单号（PO…） |
| `batchNo` | `string` | 是 | 所属账期批次 |
| `entityNo` | `string` | 是 | 收款主体业务键 |
| `payMerchantNo` | `string,null` | 是 | 收款号（分组键）。自营一主体一账户时为空 |
| `accountName` | `string,null` | 是 | 付款时快照：户名。银行要户名，而账号会改 |
| `bankName` | `string,null` | 是 | 付款时快照：开户行 |
| `bankBranch` | `string,null` | 是 | 付款时快照：支行 |
| `accountNoMasked` | `string,null` | 是 | 付款时快照：账号掩码。明文只在导出付款清单那一刻存在 |
| `amountMinor` | `number` | 是 | 本笔金额（分）= 组内结算单 net 之和 |
| `billCount` | `number` | 是 | 本笔包含几张结算单 |
| `currency` | `string` | 是 | 币种 |
| `status` | [`PayoutStatus`](#payoutstatus) | 是 | 放款状态。只有一个方向，见 PayoutStatus |
| `channel` | [`PayoutChannel`](#payoutchannel) | 是 | 放款通道。一期全是 MANUAL（网银手工） |
| `paymentRef` | `string,null` | 是 | 凭证号（网银流水号）。登记付款时必填 |
| `bankFlowNo` | `string,null` | 是 | 对上的银行流水号。出款对账勾上时写 |
| `exportedAt` | `number,null` | 是 | 导出进付款清单的时刻（毫秒） |
| `paidAt` | `number,null` | 是 | 登记凭证的时刻（毫秒） |
| `paidBy` | `string,null` | 是 | 登记凭证的操作人 |
| `matchedAt` | `number,null` | 是 | 银行流水勾上的时刻（毫秒） |
| `failReason` | `string,null` | 是 | 退回原因。给运营看，也给商家看 |
| `settleNos` | `string`\[\] | 是 | 本笔包含的结算单 |


#### POST `/ops/payouts/{payoutNo}/paid`

登记凭证

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `payoutNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`Payout`](#payout)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payoutNo` | `string` | 是 | 放款单号（PO…） |
| `batchNo` | `string` | 是 | 所属账期批次 |
| `entityNo` | `string` | 是 | 收款主体业务键 |
| `payMerchantNo` | `string,null` | 是 | 收款号（分组键）。自营一主体一账户时为空 |
| `accountName` | `string,null` | 是 | 付款时快照：户名。银行要户名，而账号会改 |
| `bankName` | `string,null` | 是 | 付款时快照：开户行 |
| `bankBranch` | `string,null` | 是 | 付款时快照：支行 |
| `accountNoMasked` | `string,null` | 是 | 付款时快照：账号掩码。明文只在导出付款清单那一刻存在 |
| `amountMinor` | `number` | 是 | 本笔金额（分）= 组内结算单 net 之和 |
| `billCount` | `number` | 是 | 本笔包含几张结算单 |
| `currency` | `string` | 是 | 币种 |
| `status` | [`PayoutStatus`](#payoutstatus) | 是 | 放款状态。只有一个方向，见 PayoutStatus |
| `channel` | [`PayoutChannel`](#payoutchannel) | 是 | 放款通道。一期全是 MANUAL（网银手工） |
| `paymentRef` | `string,null` | 是 | 凭证号（网银流水号）。登记付款时必填 |
| `bankFlowNo` | `string,null` | 是 | 对上的银行流水号。出款对账勾上时写 |
| `exportedAt` | `number,null` | 是 | 导出进付款清单的时刻（毫秒） |
| `paidAt` | `number,null` | 是 | 登记凭证的时刻（毫秒） |
| `paidBy` | `string,null` | 是 | 登记凭证的操作人 |
| `matchedAt` | `number,null` | 是 | 银行流水勾上的时刻（毫秒） |
| `failReason` | `string,null` | 是 | 退回原因。给运营看，也给商家看 |
| `settleNos` | `string`\[\] | 是 | 本笔包含的结算单 |


#### GET `/ops/points/account`

pointsAccount

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsAccountView`](#pointsaccountview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `snapshot` | `object`（见下） | 是 | 服务端冻结的账务或准备快照 |
| `exists` | `boolean` | 是 | 账户是否已经创建，未创建不代表查询失败 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

`snapshot` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `buckets` | [`Partial<Record<PointsBucket,number>>`](#partialrecordpointsbucketnumber) | 是 | 按账本分桶保存的积分余额 |
| `frozen` | `boolean` | 是 | 是否冻结该账户新增使用 |
| `expireAt` | `number,null` | 是 | 服务端计算的过期时间，Unix 毫秒；无则为空 |
| `expiryRevision` | `number` | 是 | 账户失效计划修订号 |
| `lastActiveAt` | `number` | 是 | 最近有效账务活动时间，Unix 毫秒 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `holderType` | [`PointsHolder`](#pointsholder) | 是 | 账本持有人类型 |
| `holderNo` | `string` | 是 | 账本持有人编号，与持有人类型联合定位 |


#### POST `/ops/points/account-freeze`

freezePointsAccount

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/accounts`

pointsAccounts

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/points/adjustment-review`

reviewPointsAdjustment

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/points/adjustments`

pointsAdjustments

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/points/adjustments`

submitPointsAdjustment

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### GET `/ops/points/agreements`

pointsAgreements

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsAgreementPage`](#pointsagreementpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `agreement` | [`PointsAgreement`](#pointsagreement) | 是 | 服务端冻结的门店协议快照 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |


#### GET `/ops/points/earning-context`

pointsEarningContext

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`EarningContext`](#earningcontext)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`PointsTypeConfig`](#pointstypeconfig) \| `null` | 是 | 业务类型编码或类型摘要，按当前接口定义解释 |
| `categories` | [`EarningCategory`](#earningcategory)\[\] | 是 | 可用商品分类列表 |
| `types` | [`PointsTypeConfig`](#pointstypeconfig)\[\] | 是 | 可用积分类型列表 |


#### POST `/ops/points/earning-preview`

previewPointsEarning

**入参**

_无字段_

**出参**（`data`）

类型：[`EarningCalculation`](#earningcalculation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `object`（见下） | 是 | 业务类型编码或类型摘要，按当前接口定义解释 |
| `matched` | `object`（见下） | 是 | 是否命中可用赠送规则 |
| `cashMinor` | `number` | 是 | 参与计算的现金金额，货币最小单位 |
| `points` | `number` | 是 | 按当前规则计算的积分数量 |

`type` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `name` | `string` | 是 | 业务对象显示名称 |
| `market` | `string` | 是 | — |
| `currency` | `string` | 是 | ISO货币代码，金额必须结合此字段解释 |
| `scale` | `number` | 是 | — |
| `ratePoints` | `number` | 是 | — |
| `rateMinor` | `number` | 是 | — |

`matched` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scope` | `string` | 是 | 规则适用范围编码 |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |
| `rule` | `object`（见下） | 是 | — |

`matched.rule` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |


#### GET `/ops/points/earning-rule-history`

pointsEarningHistory

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`EarningRule`](#earningrule)\[\]


#### GET `/ops/points/earning-rules`

pointsEarningRules

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`EarningRulePage`](#earningrulepage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`EarningRule`](#earningrule)\[\] | 是 | 当前页数据行 |
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `page` | `number` | 是 | 当前页码，从1开始 |
| `size` | `number` | 是 | 请求的每页记录数量 |


#### POST `/ops/points/earning-rules`

changePointsEarningRule

**入参**

_无字段_

**出参**（`data`）

类型：[`EarningRule`](#earningrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `scope` | `string` | 是 | 规则适用范围编码 |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |
| `activeRevision` | `number,null` | 是 | 当前生效版本号 |
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |
| `activeMode` | [`PointsEarningRuleMode`](#pointsearningrulemode) \| `null` | 是 | 已生效规则模式 |
| `activeValue` | `number,null` | 是 | 已生效规则值 |
| `actor` | `string` | 是 | 操作人标识 |
| `remark` | `string` | 是 | 操作备注 |
| `at` | `number` | 是 | 业务发生时间 |
| `action` | [`PointsEarningRuleAction`](#pointsearningruleaction) | 否 | 本次执行的业务动作 |


#### GET `/ops/points/entries`

pointsEntries

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/points/event-retry`

retryPointsEvent

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/events`

pointsEvents

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsEventPage`](#pointseventpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsEventView`](#pointseventview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |


#### GET `/ops/points/fallback-rule`

pointsFallback

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsFallback`](#pointsfallback)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `revision` | `number` | 是 | 不可变修订版本 |
| `mode` | [`PointsFallbackMode`](#pointsfallbackmode) | 是 | 服务端参与模式或已发布回退规则模式 |
| `value` | `number` | 是 | 回退规则配置值 |


#### POST `/ops/points/fallback-rule`

savePointsFallback

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/flows`

pointsFlows

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/points/funds`

pointsFunds

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsFundsView`](#pointsfundsview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `operation` | [`PointsRecoveryOperation`](#pointsrecoveryoperation) | 是 | 服务端接受的操作类型 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `grossMinor` | `number` | 是 | 原退款金额，最小货币单位 |
| `offsetMinor` | `number` | 是 | 已抵销金额，最小货币单位 |
| `cashMinor` | `number` | 是 | 实际现金退款金额，最小货币单位 |


#### POST `/ops/points/funds-recovery`

recoverPointsFunds

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsRecoveryExecution`](#pointsrecoveryexecution)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reference` | `string` | 是 | 原业务引用编号 |
| `status` | [`PointsExecutionStatus`](#pointsexecutionstatus) | 是 | 服务端业务状态 |


#### GET `/ops/points/order-earning`

pointsOrderEarning

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OrderEarningInspection`](#orderearninginspection)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单内部业务编号 |
| `subOrderNo` | `string` | 是 | 子订单内部业务编号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `currency` | `string` | 是 | ISO货币代码，金额必须结合此字段解释 |
| `snapshot` | `object`（见下） | 是 | 业务处理时冻结的权威快照 |
| `ledger` | `object`（见下） \| `null` | 是 | 权威账务查询结果 |

`snapshot` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `schemaVersion` | `number` | 是 | — |
| `ruleTraces` | `object`（见下）\[\] | 是 | — |
| `lines` | `object`（见下）\[\] | 是 | — |

`snapshot.ruleTraces[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `categoryNo` | `string,null` | 是 | 商品分类编号 |
| `matchedScope` | `string` | 是 | — |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |

`snapshot.lines[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `goodsNo` | `string` | 是 | — |
| `categoryNo` | `string,null` | 是 | 商品分类编号 |
| `quantity` | `number` | 是 | — |
| `goodsCashMinor` | `number` | 是 | — |
| `rule` | `object`（见下） | 是 | — |

`snapshot.lines[].rule` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |


#### GET `/ops/points/order-integration`

orderPointsIntegration

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OrderPointsIntegrationPolicy`](#orderpointsintegrationpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `scene` | [`OrderPointsIntegrationQScene`](#orderpointsintegrationqscene) | 是 | 线上或线下交易场景 |
| `mode` | [`OrderPointsIntegrationPolicyMode`](#orderpointsintegrationpolicymode) | 是 | 服务端参与模式或已发布回退规则模式 |
| `earnEnabled` | `boolean` | 是 | 是否接收新的返分承诺 |
| `redeemEnabled` | `boolean` | 是 | 是否接收新的抵扣承诺 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `actor` | `string,null` | 是 | 服务端取得的操作人编号 |
| `reason` | `string,null` | 是 | 服务端不可用原因或操作审计原因 |
| `changedAt` | `number` | 是 | 服务端配置变更时间，Unix 毫秒 |


#### POST `/ops/points/order-integration`

版本化接线变更，操作人和审计时间由服务端取得

**入参**

_无字段_

**出参**（`data`）

类型：[`OrderPointsIntegrationPolicy`](#orderpointsintegrationpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `scene` | [`OrderPointsIntegrationQScene`](#orderpointsintegrationqscene) | 是 | 线上或线下交易场景 |
| `mode` | [`OrderPointsIntegrationPolicyMode`](#orderpointsintegrationpolicymode) | 是 | 服务端参与模式或已发布回退规则模式 |
| `earnEnabled` | `boolean` | 是 | 是否接收新的返分承诺 |
| `redeemEnabled` | `boolean` | 是 | 是否接收新的抵扣承诺 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `actor` | `string,null` | 是 | 服务端取得的操作人编号 |
| `reason` | `string,null` | 是 | 服务端不可用原因或操作审计原因 |
| `changedAt` | `number` | 是 | 服务端配置变更时间，Unix 毫秒 |


#### GET `/ops/points/order-integration/inspection`

inspectOrderPoints

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OrderPointsInspection`](#orderpointsinspection)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `bindingPhase` | `string,null` | 是 | 源绑定当前阶段，无绑定为空 |
| `bindingVersion` | `number` | 是 | 源绑定版本，无绑定为空 |
| `placementPhase` | `string,null` | 是 | 下单工作流阶段，无记录为空 |
| `placementVersion` | `number` | 是 | 下单工作流版本，无记录为空 |
| `originalHoldNo` | `string,null` | 是 | 原积分占用编号，不生成新编号替换 |
| `refundPhases` | `string`\[\] | 是 | 该子单所有售后执行阶段 |
| `pendingSourceEvents` | `number` | 是 | 尚未确认送达的源事实数量 |
| `paymentVerified` | `boolean` | 是 | 原支付证据是否核验成功 |
| `differences` | `string`\[\] | 是 | 只读一致性差异项，不返回敏感正文 |


#### GET `/ops/points/order-integration/obligations`

订单积分动态接线策略，门店和支付场景精确查询

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OrderPointsStoreObligations`](#orderpointsstoreobligations)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `known` | `boolean` | 是 | 核验是否完整可用；false 时禁止按零义务判断 |
| `activePolicies` | `number` | 是 | 仍处于 ACTIVE 的场景策略数量 |
| `openBindings` | `number` | 是 | 尚未关闭的源订单绑定数量 |
| `sourceSettled` | `boolean` | 是 | 源端义务是否全部结清 |
| `pay` | `object`（见下） | 是 | 支付侧义务摘要，不可用时为 null |

`pay` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `known` | `boolean` | 是 | 核验是否完整可用；false 时禁止按零义务判断 |
| `openHolds` | `number` | 是 | 未释放的原占用数量 |
| `pendingPromises` | `number` | 是 | 未完成的原承诺数量 |
| `openRefunds` | `number` | 是 | 未完成的原退款数量 |
| `openStatements` | `number` | 是 | 未完成的门店结算单数量 |
| `unknownFunds` | `number` | 是 | 资金结果未知的原指令数量 |
| `pendingEvents` | `number` | 是 | 未处理完的原事件数量 |


#### POST `/ops/points/order-integration/refunds/{afterSaleNo}/close`

closeOrderPointsRefund

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

_无字段_

**出参**（`data`）

类型：[`OrderPointsRefundActionResult`](#orderpointsrefundactionresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `status` | `string` | 是 | 服务端业务状态 |


#### POST `/ops/points/order-integration/refunds/{afterSaleNo}/retry`

retryOrderPointsRefund

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

_无字段_

**出参**（`data`）

类型：[`OrderPointsRefundActionResult`](#orderpointsrefundactionresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `status` | `string` | 是 | 服务端业务状态 |


#### GET `/ops/points/order-integration/refunds/manual`

manualOrderPointsRefunds

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OrderPointsManualRefundPage`](#orderpointsmanualrefundpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `string,null` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `createdAt` | `number` | 是 | 服务端创建时间，Unix 毫秒 |


#### GET `/ops/points/overview`

积分资金总览

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsOverview`](#pointsoverview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型，不按不同类型合并余额 |
| `market` | `string` | 是 | 市场 |
| `currency` | `string` | 是 | ISO货币代码 |
| `scale` | `number` | 是 | 金额最小单位的小数位数 |
| `ratePoints` | `number` | 是 | 兑换比例的积分单位 |
| `rateMinor` | `number` | 是 | 兑换比例的最小货币单位 |
| `uMinor` | `number` | 是 | 发行备款 U，最小货币单位 |
| `cMinor` | `number` | 是 | 积分负债对应资金 C，最小货币单位 |
| `dMinor` | `number` | 是 | 待兑付资金 D，最小货币单位 |
| `bMinor` | `number` | 是 | 退款备付 B，最小货币单位 |
| `reservedBMinor` | `number` | 是 | 已承诺退款的 B 预留 |
| `lockedUMinor` | `number` | 是 | 待决提取占用的 U |
| `lockedBMinor` | `number` | 是 | 待决提取占用的 B |
| `receivableMinor` | `number` | 是 | 门店未回收应收 R，不属于现金 |


#### POST `/ops/points/plan-cancel`

cancelPointsPeriod

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/plan-generate`

generatePointsPeriods

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/plan-receipt`

declarePointsReceipt

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/plans`

pointsPlans

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsPlanPage`](#pointsplanpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsPlanView`](#pointsplanview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |


#### POST `/ops/points/plans`

savePointsPlan

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/position`

pointsPosition

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsPositionView`](#pointspositionview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `kind` | [`PointsPositionKind`](#pointspositionkind) | 是 | 原业务持仓或事件种类 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `state` | `string` | 是 | 服务端处理状态 |
| `originalPoints` | `number` | 是 | 原持仓积分总数 |
| `processedPoints` | `number` | 是 | 该持仓已处理积分数 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |


#### POST `/ops/points/receivable-recovery`

preparePointsReceivable

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/receivables`

门店应收分页

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsReceivablePage`](#pointsreceivablepage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型，不按不同类型合并余额 |
| `currency` | `string` | 是 | ISO货币代码 |
| `scale` | `number` | 是 | 金额最小单位的小数位数 |
| `rows` | [`PointsReceivable`](#pointsreceivable)\[\] | 是 | 当前门店的应收记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否可能还有下一页 |


#### POST `/ops/points/reconcile`

reconcilePoints

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/reconciliation`

pointsReconciliation

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/points/refund`

pointsRefund

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsRefundView`](#pointsrefundview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `preparation` | [`PointsRefundPreparation`](#pointsrefundpreparation) | 是 | 本次售后冻结的退款准备 |


#### POST `/ops/points/refund-revise`

revisePointsRefund

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/request-review`

reviewPointsRequest

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/requests`

pointsRequests

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsRequestPage`](#pointsrequestpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsRequestView`](#pointsrequestview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |


#### POST `/ops/points/requests`

submitPointsRequest

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/statement-change`

changePointsStatement

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/statement-draft`

draftPointsStatements

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/statements`

pointsStatements

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsStatementPage`](#pointsstatementpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `snapshot` | [`PointsStatementSnapshot`](#pointsstatementsnapshot) | 是 | 服务端冻结的账务或准备快照 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |


#### GET `/ops/points/stats`

pointsStats

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/points/stats-period`

pointsPeriodStats

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsStatsPeriod`](#pointsstatsperiod)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码。 |
| `from` | `string` | 是 | 统计周期起始日期，YYYY-MM-DD。 |
| `to` | `string` | 是 | 统计周期结束日期，YYYY-MM-DD。 |
| `metrics` | [`Record<string,number>`](#recordstringnumber) | 是 | 周期内由服务端聚合的指标。 |
| `availableDays` | `number` | 是 | 统计数据可用的日期数量。 |
| `missingDays` | `number` | 是 | 统计数据缺失的日期数量。 |


#### POST `/ops/points/stats-rebuild`

rebuildPointsStats

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsOperation`](#pointsoperation)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/switches`

pointsSwitches

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsSwitchPage`](#pointsswitchpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsSwitchView`](#pointsswitchview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |


#### POST `/ops/points/switches`

changePointsSwitch

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/terms`

pointsTerms

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/points/terms`

publishPointsTerms

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/transactions`

pointsTransactions

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/points/transactions/{voucherNo}`

pointsTransaction

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `voucherNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：`object`


#### POST `/ops/points/type-name`

renamePointsType

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### POST `/ops/points/type-state`

transitionPointsType

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/types`

pointsTypes

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PointsTypeConfig`](#pointstypeconfig)\[\]


#### POST `/ops/points/types`

createPointsType

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsChangeResult`](#pointschangeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |


#### GET `/ops/points/vouchers`

pointsVouchers

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### POST `/ops/points/withdrawal-execute`

executePointsWithdrawal

**入参**

_无字段_

**出参**（`data`）

类型：[`PointsExecution`](#pointsexecution)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 持久资金指令编号 |
| `status` | [`PointsExecutionStatus`](#pointsexecutionstatus) | 是 | 服务端业务状态 |
| `confirmedMinor` | `number` | 是 | 已核验入账金额，最小货币单位 |
| `replayed` | `boolean` | 是 | 是否返回原请求的幂等结果 |


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
| `settleDocumentNos` | `string`\[\] | 否 | 关联结算单的展示单号列表 |
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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
| `settleDocumentNos` | `string`\[\] | 否 | 关联结算单的展示单号列表 |
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `refundExecution` | [`RefundExecutionProgress`](#refundexecutionprogress) \| `null` | 否 | 原资金执行与独立资源恢复进度。 |
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`Liability`](#liability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`LiabilityShare`](#liabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
| `createdAt` | `string` | 是 | 售后发起时间 |


#### GET `/ops/settle-batches`

账期批次列表

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SettleBatch`](#settlebatch)\[\]


#### POST `/ops/settle-batches/{batchNo}/approve`

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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`SettleBatchStatus`](#settlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分）。与单据上同名字段一个口径 —— **不含运费** |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`ReconScope`](#reconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |
| `payoutStatus` | [`PayoutStatus`](#payoutstatus) \| `null` | 否 | 本批最近一笔放款的摘要（V391）。空 = 还没放款 |
| `paymentRef` | `string,null` | 否 | 凭证号（网银流水号） |
| `paidAt` | `number,null` | 否 | 财务登记凭证的时刻（毫秒） |


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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`SettleBatchStatus`](#settlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分）。与单据上同名字段一个口径 —— **不含运费** |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`ReconScope`](#reconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |
| `payoutStatus` | [`PayoutStatus`](#payoutstatus) \| `null` | 否 | 本批最近一笔放款的摘要（V391）。空 = 还没放款 |
| `paymentRef` | `string,null` | 否 | 凭证号（网银流水号） |
| `paidAt` | `number,null` | 否 | 财务登记凭证的时刻（毫秒） |


#### POST `/ops/settle-batches/{batchNo}/release`

**放款**（V391）：RECONCILED → RELEASED，按收款号生成放款记录

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `batchNo` | path | `string` | 是 | 到货批次号 |

_无字段_

**出参**（`data`）

类型：[`Payout`](#payout)\[\]


#### GET `/ops/settle-stats`

listSettleStats

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`SettleStatRow`](#settlestatrow)\[\]


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
| `businessMode` | [`BusinessMode`](#businessmode) | 是 | 经营模式，费率的第一个维度 |
| `trafficSource` | [`FeeTrafficSource`](#feetrafficsource) | 是 | 适用的流量来源，费率的第二个维度 |
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
| `currentRate` | [`PayChannelRateVersion`](#paychannelrateversion) \| `null` | 是 | 此刻生效的那一版；**一条都没配时为 null**，要显示成「未配置」而不是 0 |
| `rates` | [`PayChannelRateVersion`](#paychannelrateversion)\[\] | 是 | 全部版本，按生效时间倒序 |


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

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`FreightTemplate`](#freighttemplate)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


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
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/freight-templates/{templateNo}/archive`

archiveFreightTemplate

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
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 超区规则 |
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
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### POST `/ops/freight-templates/draft`

按发货城市从快递100 报价生成模板草稿（31 省 × 2 个重量的查价）

**入参**

_无字段_

**出参**（`data`）

类型：[`FreightDraft`](#freightdraft)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 建议的模板名，如「运城市发 · 中通快递」 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 地区规则：贵的省份加收、查不到价的省份不配送 |
| `rows` | [`FreightProvincePrice`](#freightprovinceprice)\[\] | 是 | 31 个省的原始报价，运营据此核对 |
| `unquoted` | `number` | 是 | 查不到价的省份数 |


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
| `status` | [`BatchStatus`](#batchstatus) | 是 | 批次状态。**有序推进不允许跳步**，见 `BATCH_TRANSITIONS` |
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
| `carrier` | [`Carrier`](#carrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |
| `codes` | [`Record<string,string>`](#recordstringstring) | 否 | 这家承运商在各物流渠道里叫什么（`{ kuaidi100: "shentong", wx: "STO" }`）。 某个渠道没有这一项 = 那家渠道不覆盖它，订阅时会跳过 |


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
| `carrier` | [`Carrier`](#carrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |
| `codes` | [`Record<string,string>`](#recordstringstring) | 否 | 这家承运商在各物流渠道里叫什么（`{ kuaidi100: "shentong", wx: "STO" }`）。 某个渠道没有这一项 = 那家渠道不覆盖它，订阅时会跳过 |


#### GET `/ops/fulfillment/overdue-rule`

getOverdueRule

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OverdueRule`](#overduerule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `action` | [`OverdueAction`](#overdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
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
| `action` | [`OverdueAction`](#overdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
| `graceHours` | `number` | 是 | 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。 校验在 mock/后端两侧都有，不只是表单提示。 |
| `maxPostpone` | `number` | 是 | 顺延次数上限（action=POSTPONE 时有意义） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |


#### GET `/ops/fulfillment/redeem`

核销监控与逾期看板（P-5.1.3）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`RedeemStat`](#redeemstat)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/fulfillment/sorting`

按自提点汇总分拣（P-5.1.2）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`SortingRow`](#sortingrow)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/ops/logistics/channels`

物流渠道总览（O4）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`LogisticsChannel`](#logisticschannel)\[\]


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


#### POST `/ops/shipments/{shipmentNo}/replay`

重放（TDD-物流模块 O3）：重新订阅（可点名渠道）或重新换微信 token

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `shipmentNo` | path | `string` | 是 | 运单记录单号（平台侧主键，非快递单号） |

_无字段_

**出参**（`data`）

类型：`object`


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
| `carrier` | [`Carrier`](#carrier) | 是 | 承运商 |
| `waybillNo` | `string` | 是 | 承运商的快递单号 |
| `status` | [`ShipmentStatus`](#shipmentstatus) | 是 | 快递状态。**`EXCEPTION` 不是终态**，疑难件可能之后又派送成功 |
| `receiver` | `string` | 是 | 收件人姓名 |
| `region` | `string` | 是 | 收件地区（省/市），超区判断看的就是它 |
| `createdAt` | `string` | 是 | 建单时间 |
| `updatedAt` | `string` | 是 | 最后一次轨迹更新时间 |
| `traces` | [`ShipmentTrace`](#shipmenttrace)\[\] | 是 | 轨迹节点，按时间正序 |
| `displayChannel` | `string,null` | 否 | 这一单备好的展示渠道（`wx-plugin` / `self-map`）。**运营要看见它** —— 买家说「看不到物流」时，第一个要回答的就是「走的哪条链、哪一环断了」 |
| `displayFailReason` | `string,null` | 否 | 最近一次备载荷失败的原因，只给运营看，不给买家看 |
| `profile` | [`ShipmentProfile`](#shipmentprofile) | 否 | `WX` 微信支付单（可用微信物流全套）/ `SELF` 线下付款等（不调任何微信物流接口） |
| `storeNo` | `string,null` | 否 | 门店号（登记时快照） |
| `entityNo` | `string,null` | 否 | 商家主体号（登记时快照） |
| `subState` | [`SubscribeState`](#subscribestate) | 否 | 订阅状态 |
| `subChannel` | `string,null` | 否 | 受理订阅的渠道（`kuaidi100` / `yto` …） |
| `subError` | `string,null` | 否 | 订阅最后一次失败：渠道 + 码 + 原文 |
| `subAttempts` | `number` | 否 | 订阅累计尝试次数 |
| `subscriptions` | `object`（见下）\[\] | 否 | Current shipment identity only; WeChat follow and Kuaidi100 are independent. |
| `bindState` | [`BindState`](#bindstate) | 否 | 微信 token 状态 |
| `bindError` | `string,null` | 否 | 换微信 token 最后一次失败的原因 |
| `signedAt` | `string,null` | 否 | 签收时间 |
| `lastEventAt` | `string,null` | 否 | 最近一次有新进展的时刻 |
| `atLocker` | `boolean` | 否 | 已放到驿站或快递柜 |
| `carrierCorrectedFrom` | `string,null` | 否 | 渠道纠正过承运商时的原值 |
| `receiverPhoneLast4` | `string,null` | 否 | 收件人手机号后四位（完整号只在物流模块里加密存，签收后清空） |

`subscriptions[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | `string` | 是 | 消息或支付通道编码 |
| `state` | `string` | 是 | 服务端处理状态编码 |
| `error` | `string,null` | 否 | — |
| `lastAttemptAt` | `number,null` | 否 | — |
| `nextRetryAt` | `number,null` | 否 | — |


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
| `status` | [`GroupStatus`](#groupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
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
| `status` | [`GroupStatus`](#groupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
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
| `priority` | [`AttrSource`](#attrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`ConflictPolicy`](#conflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`NewUserFactor`](#newuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
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
| `priority` | [`AttrSource`](#attrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`ConflictPolicy`](#conflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`NewUserFactor`](#newuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`RewardType`](#rewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`RewardType`](#rewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
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


#### POST `/ops/perm/functions/{functionCode}/move`

菜单调序：同级内上移/下移

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `functionCode` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/functions/reorder`

整段重排（拖动用）：传该父级下的**完整顺序**

**入参**

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/perm/points/{pointCode}/move`

movePermPoint

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `pointCode` | path | `string` | 是 | — |

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
| `entries` | [`InvLedgerRow`](#invledgerrow)\[\] | 是 | 本页的台账行 |
| `nextCursor` | `number,null` | 否 | null = 没有下一页 |


#### GET `/ops/inventory/link-health`

投影链路健康度（M3）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvLinkHealth`](#invlinkhealth)\[\]


#### GET `/ops/inventory/merchant-digest`

单商家进销存概况（M5）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


#### GET `/ops/inventory/order-trace`

Whole reservation → issue → return chain for a parent order. */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvOrderTrace`](#invordertrace)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | Order identifier whose inventory lifecycle is being audited. |
| `reservations` | [`InvTraceReservation`](#invtracereservation)\[\] | 是 | Physical stock reservations associated with this order. |
| `syncEvents` | [`InvTraceSyncEvent`](#invtracesyncevent)\[\] | 是 | Inventory synchronization events associated with this order. |


#### GET `/ops/inventory/policy`

进销存平台规则（M7）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`InvPolicy`](#invpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reconCleanStreakRequired` | `number` | 是 | 对差要连续几轮为零，才算够格切换真相源（G3）。 **此前这个 N 根本不存在** —— 判据写的是「连续为零」，而连续多少是空的， 于是「够了没有」谁都答不了。 |


#### POST `/ops/inventory/policy`

saveInvPolicy

**入参**

_无字段_

**出参**（`data`）

类型：[`InvPolicy`](#invpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reconCleanStreakRequired` | `number` | 是 | 对差要连续几轮为零，才算够格切换真相源（G3）。 **此前这个 N 根本不存在** —— 判据写的是「连续为零」，而连续多少是空的， 于是「够了没有」谁都答不了。 |


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
| `diffs` | [`InvReconDiff`](#invrecondiff)\[\] | 是 | 对不上的行 |


#### POST `/ops/inventory/repair-projection`

手动补投影（M2）

**入参**

_无字段_

**出参**（`data`）

类型：[`InvRepairResult`](#invrepairresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scannedSkus` | `number` | 是 | 本次扫描 SKU 数量 |
| `moved` | `number` | 是 | 真搬了几条。**试算时恒为 0** —— 不传 apply 就一条都不该动 |
| `skipped` | `number` | 是 | 本次跳过的记录数量 |
| `pending` | `number` | 是 | 仍待搬的 |
| `clean` | `boolean` | 是 | 未发现投影差异的数量 |


#### POST `/ops/merchant/{entityNo}/stock-doubt`

库存存疑打标（M2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `entityNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：`object`


### job

#### GET `/ops/jobs`

任务清单：定义与当前状态**已在后端合成一行**

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`JobRow`](#jobrow)\[\]


#### GET `/ops/jobs/{name}`

getJob

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### PUT `/ops/jobs/{name}/cron`

改频率

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### POST `/ops/jobs/{name}/disable`

关

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### POST `/ops/jobs/{name}/enable`

开

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


#### GET `/ops/jobs/{name}/logs`

执行日志，倒序

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`JobLogRow`](#joblogrow)\[\]


#### POST `/ops/jobs/{name}/trigger`

立即执行一次

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `name` | path | `string` | 是 | — |

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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |


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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`MerchantCampaignType`](#merchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`MerchantCampaignType`](#merchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`MerchantCampaignType`](#merchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
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


#### POST `/ops/content-slots`

建 / 改内容位

**入参**

_无字段_

**出参**（`data`）

类型：[`ContentSlot`](#contentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |


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
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |


#### POST `/ops/content-slots/{no}/enabled`

setSlotEnabled

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
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |


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
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |


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
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |


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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `issueNo` | `string` | 是 | 发放记录单号 |
| `couponNo` | `string` | 是 | 发放的券模板 |
| `couponName` | `string` | 是 | 券名快照 |
| `target` | [`IssueTarget`](#issuetarget) | 是 | 发放对象类型 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `benefits` | [`Benefit`](#benefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`MemberCardStatus`](#membercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
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
| `benefits` | [`Benefit`](#benefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`MemberCardStatus`](#membercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
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
| `benefits` | [`Benefit`](#benefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`MemberCardStatus`](#membercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
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
| `benefits` | [`Benefit`](#benefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`MemberCardStatus`](#membercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
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
| `memberships` | [`OpsMember`](#opsmember)\[\] | 是 | 他在各商家的会员关系。**一份人档串起几家** —— 这正是人档存在的理由 |
| `merges` | `string`\[\] | 是 | 合并过的人档号。合并不可逆，留痕是唯一的回溯手段 |


#### GET `/ops/members/level-policy`

会员分层口径

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MemberLevelPolicy`](#memberlevelpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sleepDays` | `number` | 是 | 超过这么多天没下单算沉睡 |
| `loyalD90Orders` | `number` | 是 | 近 90 天至少这么多单算熟客 |
| `regularD90Orders` | `number` | 是 | 近 90 天至少这么多单算常客；再少是新客 |
| `lastRun` | [`LevelRecomputeRun`](#levelrecomputerun) \| `null` | 否 | 上一次重算。从没跑过为 null —— 页面要说「还没跑过」，而不是显示一排 0 |


#### POST `/ops/members/level-policy`

改分层口径

**入参**

_无字段_

**出参**（`data`）

类型：[`MemberLevelPolicy`](#memberlevelpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sleepDays` | `number` | 是 | 超过这么多天没下单算沉睡 |
| `loyalD90Orders` | `number` | 是 | 近 90 天至少这么多单算熟客 |
| `regularD90Orders` | `number` | 是 | 近 90 天至少这么多单算常客；再少是新客 |
| `lastRun` | [`LevelRecomputeRun`](#levelrecomputerun) \| `null` | 否 | 上一次重算。从没跑过为 null —— 页面要说「还没跑过」，而不是显示一排 0 |


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
| `quotaReleased` | `number` | 否 | 被关单退回的份数（待办设计 P4）。`quotaUsed` 已经不含它们 —— 这个数让运营知道「真实卖出」与「曾经被占过」差多少 |
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


#### POST `/ops/promotion/enrollments/{enrollmentNo}/review`

通过 / 驳回

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `enrollmentNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`OpsEnrollment`](#opsenrollment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enrollmentNo` | `string` | 是 | 报名单号 |
| `activityNo` | `string` | 是 | 平台活动 |
| `entityNo` | `string` | 是 | 商家 |
| `merchantName` | `string` | 是 | 商家名 |
| `goodsNos` | `string`\[\] | 是 | 报名的货 |
| `quota` | `number` | 是 | 报的份数 |
| `quotaUsed` | `number` | 是 | 已用份数 |
| `platformMaxMinor` | `number` | 是 | 最多平台出资（分） |
| `merchantMaxMinor` | `number` | 是 | 最多商家承担（分） |
| `rating` | `number` | 是 | 商家评分 |
| `status` | `string` | 是 | SUBMITTED / APPROVED / REJECTED / WITHDRAWN |
| `rejectReason` | `string,null` | 是 | 驳回理由 |
| `reviewedAt` | `number,null` | 是 | 审核时间 |
| `createdAt` | `number` | 是 | 提交时间 |


#### GET `/ops/promotion/platform-activities`

平台活动（s29）：全部，含草稿，带审核计数与预算占用 */

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OpsPlatformActivity`](#opsplatformactivity)\[\]


#### POST `/ops/promotion/platform-activities`

建 / 改平台活动

**入参**

_无字段_

**出参**（`data`）

类型：[`OpsPlatformActivity`](#opsplatformactivity)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `activityNo` | `string` | 是 | 活动号 |
| `name` | `string` | 是 | 名称 |
| `triggerType` | `string` | 是 | 触发：NONE 立减 / AMOUNT 满额 / QTY 满件 |
| `triggerAmountMinor` | `number,null` | 是 | 满多少（分） |
| `triggerQty` | `number,null` | 是 | 满几件 |
| `benefitType` | `string` | 是 | 优惠方式（现只有 CUT） |
| `benefitAmountMinor` | `number,null` | 是 | 减多少（分） |
| `startAt` | `number,null` | 是 | 活动开始 |
| `endAt` | `number,null` | 是 | 活动结束 |
| `enrollDeadline` | `number,null` | 是 | 报名截止 |
| `platformShareBp` | `number` | 是 | 平台出资万分比 |
| `budgetMinor` | `number,null` | 是 | 平台预算（分） |
| `reservedMinor` | `number` | 是 | 已通过的报名占掉的预算（分） |
| `perOrderPlatformMinor` | `number` | 是 | 每单平台最多补贴（分） |
| `perOrderMerchantMinor` | `number` | 是 | 每单商家最多承担（分） |
| `enrollRule` | [`PlatformEnrollRule`](#platformenrollrule) | 是 | 报名门槛 |
| `status` | `string` | 是 | DRAFT / RUNNING / ENDED |
| `submitted` | `number` | 是 | 待审 |
| `approved` | `number` | 是 | 已通过 |
| `rejected` | `number` | 是 | 已驳回 |


#### GET `/ops/promotion/platform-activities/{activityNo}/enrollments`

一个平台活动的报名（s30）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `activityNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`OpsEnrollment`](#opsenrollment)\[\]


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

这张流水表只增不改、金额又是运营当场填的 —— 重复提交会实打实记两笔，而后端漏传时直接 400（不静默放行）

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
| `status` | [`PlanStatus`](#planstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`PlanQuotaSource`](#planquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |


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
| `status` | [`PlanStatus`](#planstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`PlanQuotaSource`](#planquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |


#### GET `/ops/merchant-plans/upgrade-signals`

升档信号：一个人名下多个主体 = 他已经在多店经营，只是绕过了额度

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`PlanUpgradeSignal`](#planupgradesignal)\[\]


#### POST `/ops/merchant/{entityNo}/nudge`

主动触达商家（M2）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `entityNo` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`MerchantNudgeResult`](#merchantnudgeresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sent` | `number` | 是 | 实际发出几条（按收件人计） |
| `alreadySentToday` | `boolean` | 是 | 今天已就同一事由提醒过。不是失败，但必须说出来 |
| `noRecipient` | `boolean` | 是 | 这家店一个能收消息的人都没有 —— 该做的是去给这家店配人 |


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


#### GET `/ops/merchant/chain`

商家链条画像（M1）：一家一行，建品 → 提审 → 上架 → 建账 → 首次进货 → 持续记账

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`MerchantChainRow`](#merchantchainrow)\[\]


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


#### GET `/ops/merchants/{merchantNo}/store-modes`

storeModes

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`StoreMode`](#storemode)\[\]


#### POST `/ops/merchants/{merchantNo}/stores`

给**平台自营主体**再开一家门店

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：[`SelfOperatedStore`](#selfoperatedstore)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店业务键 |
| `merchantNo` | `string` | 是 | 它挂在哪个主体下 |
| `name` | `string` | 是 | 门店名。与主体名可以不同（分店） |
| `address` | `string,null` | 否 | 门店地址，可空 |
| `businessMode` | `string` | 是 | 回读值，应为 `SELF_OPERATED` |
| `payMerchantNo` | `string,null` | 否 | 收款号。**为空是正常的** —— 自营门店不进件，钱先进平台户； 只有第三方模式下它为空才是硬阻塞。 |


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


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
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |


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
| `type` | [`ViolationType`](#violationtype) | 是 | 违规类型。**只有 `BREACH` 计入 breachCount** |
| `action` | [`ViolationAction`](#violationaction) | 是 | 处置动作。`SUSPEND` 会真的把商家状态推到 SUSPENDED |
| `storeNo` | `string,null` | 否 | 门店级处置的对象门店。**`STORE_OFFLINE` 必有、其余动作必空** —— 主体级处置带上门店号会让人以为只压了那一家。 |
| `detail` | `string` | 是 | 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 |
| `operator` | `string` | 是 | 处置人（STAFF 账号） |
| `at` | `string` | 是 | 处置时间 |


#### POST `/ops/merchants/apply-on-behalf`

**代商家提交入驻申请**（三期）

**入参**

_无字段_

**出参**（`data`）

类型：[`ApplyOnBehalfResult`](#applyonbehalfresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 落库的申请单号。队列里就是这一张 |
| `ownerUserNo` | `string` | 是 | 商户本人的 userNo。**可能是这一刻新建的** —— 界面上要据此提示「这个号是新开的，本人还不知道」。 |


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


#### POST `/ops/merchants/self-operated`

建**平台自营商家**（跳过进件与审核）

**入参**

_无字段_

**出参**（`data`）

类型：[`SelfOperatedResult`](#selfoperatedresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 建出来（或幂等命中）的主体业务键 |
| `storeNo` | `string` | 是 | 随主体一并建出来的默认门店 |
| `ownerUserNo` | `string` | 是 | 手机号对应的账号；没有就按登录那条路新建一个 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 是 | 回读值，应为 `AGGREGATED` |
| `businessMode` | `string` | 是 | 回读值，应为 `SELF_OPERATED` |
| `serviceScope` | `string` | 是 | 回读值：COMMUNITY / CITY / PLATFORM |
| `selfOperated` | `boolean` | 是 | 回读值，应为 true。它是**免证件与运营建店的唯一判据** —— `fundsMode` 认不出平台自己（归集同时盖着代销）， `businessMode` 也认不出（门店级，且建表默认值就是自营）。 |
| `reachableCommunities` | `number` | 是 | **这家店现在对多少个小区可见。** 不是装饰：ADR-009 的「必须勾社区」只拦得住「一个都没勾」， 而可见性最终一律展开成小区号 —— 库里一个小区都没有时 CITY 档同样是 0 （区划表里有深圳，不代表深圳有小区）。建完是 0 就是「建好了，谁也看不到」。 |
| `created` | `boolean` | 是 | 本次是否**真的新建**了主体。false = 这个手机号名下已经有主体，原样返回它。 界面上要分开说：运营连点两次时，「又建了一个」与「就是刚才那个」是不同的事实。 |


#### GET `/ops/merchants/violations`

违规记录

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Violation`](#violation)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


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
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 是 | 自营 / 第三方；空 = 尚未设置 |
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
| `channel` | [`MsgChannel`](#msgchannel) | 是 | 触达渠道 |
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
| `status` | [`TicketStatus`](#ticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
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
| `status` | [`TicketStatus`](#ticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
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
| `status` | [`TicketStatus`](#ticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
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
| `status` | [`TicketStatus`](#ticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
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
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式 |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


#### GET `/ops/orders/{orderNo}/cancel-impact`

整笔取消影响，权限不足时不返回隐藏明细

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`CancellationImpact`](#cancellationimpact)

_无字段_


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
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式 |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行 |
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
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式 |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行 |
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

createProxyOrder

**入参**

_无字段_

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 |
| `parentNo` | `string` | 是 | 父单号（同一次结算拆出的子订单共享） |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式 |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |


#### GET `/ops/orders/proxy-limit`

代客下单的限额

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ProxyLimit`](#proxylimit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `maxAmountMinor` | `number` | 是 | 单笔上限（分）。按订单**实际应付额**判，不按商品估算 |
| `maxPerDay` | `number` | 是 | 每个客服每天最多几笔。按自然日算 |
| `updatedAt` | `string,null` | 是 | 最后修改时间 |
| `updatedBy` | `string,null` | 是 | 最后修改人 |


#### POST `/ops/orders/proxy-limit`

改限额

**入参**

_无字段_

**出参**（`data`）

类型：[`ProxyLimit`](#proxylimit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `maxAmountMinor` | `number` | 是 | 单笔上限（分）。按订单**实际应付额**判，不按商品估算 |
| `maxPerDay` | `number` | 是 | 每个客服每天最多几笔。按自然日算 |
| `updatedAt` | `string,null` | 是 | 最后修改时间 |
| `updatedBy` | `string,null` | 是 | 最后修改人 |


### payment

#### GET `/ops/channel-messages`

渠道报文查询（O1）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ChannelMessagePage`](#channelmessagepage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ChannelMessage`](#channelmessage)\[\] | 是 | 本页记录 |
| `total` | `number` | 是 | 符合条件的记录总数 |
| `pageNo` | `number` | 是 | 页码，从一开始 |
| `size` | `number` | 是 | 本页最大记录数 |
| `note` | `string` | 是 | 服务端补充说明 |


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
| `channel` | [`PayChannel`](#paychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`ReconDiffType`](#recondifftype) | 是 | 差异类型。退款异常仅用于人工核对，处置不自动解冻或付款，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`ReconStatus`](#reconstatus) | 是 | 处置状态 |
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
| `channel` | [`PayChannel`](#paychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`ReconDiffType`](#recondifftype) | 是 | 差异类型。退款异常仅用于人工核对，处置不自动解冻或付款，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`ReconStatus`](#reconstatus) | 是 | 处置状态 |
| `resolution` | `string,null` | 否 | 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 |
| `recoveredOrderNo` | `string,null` | 否 | 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） |
| `createdAt` | `string` | 是 | 差异产生时间 |
| `resolvedAt` | `string,null` | 否 | 处置时间。未处置为 null |
| `resolvedBy` | `string,null` | 否 | 处置人（STAFF 账号）。未处置为 null |


### product

#### GET `/ops/banned-word`

平台禁售词（商品①）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`BannedWord`](#bannedword)\[\]


#### POST `/ops/banned-word`

addBannedWord

**入参**

_无字段_

**出参**（`data`）

类型：[`BannedWord`](#bannedword)\[\]


#### POST `/ops/banned-word/{id}/remove`

removeBannedWord

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `id` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`BannedWord`](#bannedword)\[\]


#### GET `/ops/categories`

类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Category`](#category)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


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
| `template` | [`CategoryTemplate`](#categorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`I18nText`](#i18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
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
| `template` | [`CategoryTemplate`](#categorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`I18nText`](#i18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
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
| `template` | [`CategoryTemplate`](#categorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`I18nText`](#i18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
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
| `titleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`GoodsDetailSku`](#goodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | 业务对象显示名称 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 业务对象显示名称 |
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
| `name` | `string` | 是 | 业务对象显示名称 |


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
| `titleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`GoodsDetailSku`](#goodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | 业务对象显示名称 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 业务对象显示名称 |
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


#### GET `/ops/product/{goodsNo}/chain`

单商品全链路状态（M5）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`GoodsChain`](#goodschain)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品编号 |
| `title` | `string,null` | 是 | 商品或规格展示标题 |
| `entityNo` | `string` | 是 | 商家主体编号 |
| `auditStatus` | `string,null` | 是 | 商品审核状态 |
| `onSale` | `boolean` | 是 | 是否上架销售 |
| `skuCount` | `number` | 是 | SKU 数量 |
| `bookedSkus` | `number` | 是 | 其中在进销存里建了账的。**少于 skuCount 就是投影没搬全** —— 商家端的表现是「有些规格盘得着、有些盘不着」，极难自查 |
| `onHand` | `number` | 是 | 账面库存数量 |
| `available` | `number` | 是 | 当前可售库存数量 |
| `soldCount` | `number` | 是 | 累计销售数量 |
| `stuckAt` | [`MerchantChainStuck`](#merchantchainstuck) \| `null` | 是 | 与链条画像用同一套词（）—— 两处分叉就是两套结论 |


#### GET `/ops/product/policy`

建品规则（商品①）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ProductPolicy`](#productpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requireCover` | `boolean` | 是 | 提审前必须有主图 |
| `titleMinLength` | `number` | 是 | 标题最少几个字，0 = 不限 |
| `titleMaxLength` | `number` | 是 | 标题最多几个字，0 = 不限 |


#### POST `/ops/product/policy`

saveProductPolicy

**入参**

_无字段_

**出参**（`data`）

类型：[`ProductPolicy`](#productpolicy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requireCover` | `boolean` | 是 | 提审前必须有主图 |
| `titleMinLength` | `number` | 是 | 标题最少几个字，0 = 不限 |
| `titleMaxLength` | `number` | 是 | 标题最多几个字，0 = 不限 |


#### GET `/ops/product/stats`

商品域平台统计（M4）

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`ProductStats`](#productstats)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categories` | `number` | 是 | 分类数量 |
| `categoriesUsed` | `number` | 是 | 至少被一个商品用过的类目数 |
| `skus` | `number` | 是 | SKU 数量 |
| `skusWithBarcode` | `number` | 是 | 填了条码的。**扫码功能的天花板就是这个数** |
| `skusWithCode` | `number` | 是 | 填了商家货号的 |
| `specDims` | `number` | 是 | 规格维度数量 |
| `specDimsBound` | `number` | 是 | 至少挂到一个类目上的维度数。规格库只增不减，没挂上的是清理依据 |
| `auditApproved` | `number` | 是 | 审核通过商品数量 |
| `auditRejected` | `number` | 是 | 审核驳回商品数量 |
| `auditPending` | `number` | 是 | 待审核商品数量 |
| `auditActions` | `number` | 是 | 最近 N 天的审核动作数 —— **吞吐**，与上面三个累计数不是一回事 |
| `auditDays` | `number` | 是 | 审核统计窗口天数 |


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
| `title` | [`I18nText`](#i18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`SkuStatus`](#skustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`Partial<Record<Market,number>>`](#partialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
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
| `title` | [`I18nText`](#i18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`SkuStatus`](#skustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`Partial<Record<Market,number>>`](#partialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
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
| `title` | [`I18nText`](#i18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`SkuStatus`](#skustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`Partial<Record<Market,number>>`](#partialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
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
| `values` | [`SpecValue`](#specvalue)\[\] | 是 | 取值列表 |


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
| `values` | [`SpecValue`](#specvalue)\[\] | 是 | 取值列表 |


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
| `values` | [`SpecValue`](#specvalue)\[\] | 是 | 取值列表 |


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
| `categoryType` | [`CategoryTemplate`](#categorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecTemplateOption`](#spectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
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
| `categoryType` | [`CategoryTemplate`](#categorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecTemplateOption`](#spectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
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
| `categoryType` | [`CategoryTemplate`](#categorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecTemplateOption`](#spectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
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
| `titleI18n` | [`Record<string,string>`](#recordstringstring) | 否 | 标题的多语言版本 |
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
| `name` | `string` | 是 | 业务对象显示名称 |
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
| `titleI18n` | [`Record<string,string>`](#recordstringstring) | 否 | 标题的多语言版本 |
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
| `name` | `string` | 是 | 业务对象显示名称 |
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
| `titleI18n` | [`Record<string,string>`](#recordstringstring) | 否 | 标题的多语言版本 |
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
| `name` | `string` | 是 | 业务对象显示名称 |
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
| `status` | [`AppealStatus`](#appealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），REJECTED = 驳回申诉（差评保留） |
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
| `status` | [`ReviewStatus`](#reviewstatus) | 是 | 审核状态 |
| `riskFlags` | [`RiskFlag`](#riskflag)\[\] | 是 | 命中的刷评信号。**是线索不是结论** —— 命中不等于判定 |
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
| `subjectType` | [`SubjectType`](#subjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`BlacklistAppealStatus`](#blacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
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
| `subjectType` | [`SubjectType`](#subjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`BlacklistAppealStatus`](#blacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
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
| `type` | [`RiskType`](#risktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | [`SubjectType`](#subjecttype) | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
| `signals` | `string`\[\] | 是 | 命中的信号。**不给分值** —— 分值口径要等有真实样本后由风控定， 现在编一个看起来很准的分数，只会让人照着它做决定。 |
| `refs` | `string`\[\] | 是 | 关联证据：订单号 / 归因链路号 |
| `status` | [`RiskStatus`](#riskstatus) | 是 | 处置状态 |
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
| `type` | [`RiskType`](#risktype) | 是 | 适用的风险类型，一类一条 |
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


#### POST `/ops/stores/{merchantNo}/qrcode/issue`

给这家门店发码

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/stores/{merchantNo}/qrcode/print`

登记一次店铺码印刷量（线下事实，系统无从自动知道）

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

_无字段_

**出参**（`data`）

类型：`object`


#### POST `/ops/stores/{merchantNo}/qrcode/reissue`

<b>换码：旧码当场失效</b>，已经贴在店里的物料全部变成死链

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

类型：[`StoreGovernDetail`](#storegoverndetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `store` | [`StoreGovern`](#storegovern) | 是 | 门店档案本身（与列表行同一份形状） |
| `coverage` | [`MerchantCoverage`](#merchantcoverage) | 是 | 经营范围与它的投影结果。挂在**主体**上 —— 同主体的门店看到同一份，界面别写成「本店覆盖」 |
| `pickupNames` | `string`\[\] | 是 | 这家店挂靠的取货点名。空数组 = 没挂，不是没查到 |
| `scanCount30d` | `number` | 是 | 近 30 天店铺码扫码次数。与获客看板同一个数据源，不另算一份 |


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
| `status` | [`StoreGovernStatus`](#storegovernstatus) | 是 | 经营状态，见  {@link  StoreGovernStatus } |
| `businessMode` | [`BusinessMode`](#businessmode) | 是 | 自营 / 第三方。决定这家店的钱怎么走、票怎么开 |
| `payMerchantNo` | `string,null` | 是 | 本店专属收款商户号。 **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。 |
| `rating` | `number,null` | 是 | 门店评分，**×10 的整数**（85 = 8.5 分）。与主体那几列同口径 |
| `ratingCount` | `number,null` | 是 | 评价条数。 ⚠️ **0 = 暂无评价，不是 0 分** —— 新店与还没重算过的店都是这个形状。 判空要按**条数**，按分值判会把「没人评过」显示成「0 分」。 |
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
| `toAfterSale` | `number` | 是 | 待处理售后单数（P-11.2.1d）。 **只含还压着人的两态**（APPLIED / ARBITRATING）：已退款/已驳回/已关闭是了结的事实， 算进「待办堆积」会让处理得快的店看起来积压严重 —— 而运营正是拿这个数判断「这家店是不是没人管了」。 |


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
| `kind` | [`StoreAuditKind`](#storeauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL、公告文本，或 `DISTRICT:330106` 这样的覆盖项定位串 |
| `display` | `string` | 否 | 人话版的 content。`SERVICE_AREA` 时是「浙江省 / 杭州市 / 西湖区」，其余与 content 相同。 **列表与详情一律显示它**：让运营对着 `DISTRICT:330106` 判断 「这家菜摊该不该覆盖整个西湖区」，等于让他去别处查一次再回来。 |
| `status` | [`StoreAuditStatus`](#storeauditstatus) | 是 | 审核状态 |
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


#### GET `/ops/stores/qrcodes/export`

导出用：列表那几列 + <b>可直接印的码图</b>

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：`object`


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
| `layout` | [`SectionLayout`](#sectionlayout) | 是 | 商品区排布 |
| `sections` | [`TemplateSection`](#templatesection)\[\] | 是 | 板块开关列表 |
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
| `layout` | [`SectionLayout`](#sectionlayout) | 是 | 商品区排布 |
| `sections` | [`TemplateSection`](#templatesection)\[\] | 是 | 板块开关列表 |
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
| `defaultSkin` | [`ThemeKey`](#themekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`ThemeKey`](#themekey) | 否 | 节日皮肤：留空表示不启用 |
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
| `defaultSkin` | [`ThemeKey`](#themekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`ThemeKey`](#themekey) | 否 | 节日皮肤：留空表示不启用 |
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

类型：`object`


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

类型：`object`


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
| `batch` | [`MediaPurgeBatch`](#mediapurgebatch) | 是 | 批次本身 |
| `items` | [`MediaReclaimable`](#mediareclaimable)\[\] | 是 | 这一批里的每一张 |


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


#### GET `/ops/test-phones`

全量，**含停用的**

> 查询参数见 lib/api/query.ts 中对应的 *Q 类型。

**入参**：无

**出参**（`data`）

类型：[`OtpTestPhone`](#otptestphone)\[\]


#### POST `/ops/test-phones`

录一条或改一条（按手机号认，不按 id）

**入参**

_无字段_

**出参**（`data`）

类型：[`OtpTestPhone`](#otptestphone)\[\]


#### POST `/ops/test-phones/{id}/enabled`

开 / 关

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `id` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`OtpTestPhone`](#otptestphone)\[\]


#### POST `/ops/test-phones/{id}/remove`

删

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `id` | path | `string` | 是 | — |

_无字段_

**出参**（`data`）

类型：[`OtpTestPhone`](#otptestphone)\[\]


---

## 数据模型

### AdmissionPolicy

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `legalForm` | [`LegalForm`](#legalform) | 是 | 主体档位，三档锁定 |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `refundExecution` | [`RefundExecutionProgress`](#refundexecutionprogress) \| `null` | 否 | 原资金执行与独立资源恢复进度。 |
| `afterSaleNo` | `string` | 是 | 售后单号 |
| `subOrderNo` | `string` | 是 | 关联的子订单 |
| `orderNo` | `string` | 是 | 关联的主订单 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `buyerNickname` | `string` | 是 | 申请人昵称 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 / 换货 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` |
| `refundMinor` | `number` | 是 | 申请退款金额（分）。裁决只决定退不退，不改这个数 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证材料（照片） |
| `liability` | [`Liability`](#liability) | 否 | 裁定的责任方。平台介入后才有值 |
| `share` | [`LiabilityShare`](#liabilityshare) | 否 | 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） |
| `verdict` | `string` | 否 | 裁决说明：用户与商家都会看到 |
| `refundSplitPending` | `boolean` | 否 | E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段， 真实后端未接（见上方说明），售后本身的裁决流程不读写它。 |
| `createdAt` | `string` | 是 | 售后发起时间 |

### AfterSaleStatus

枚举取值：

- `APPLIED`
- `WAIT_RETURN`
- `RETURNING`
- `REFUNDING`
- `WAIT_RECEIPT`
- `ARBITRATING`
- `REJECTED`
- `REFUNDED`
- `CLOSED`

### AfterSaleType

枚举取值：

- `REFUND_ONLY`
- `RETURN_REFUND`
- `EXCHANGE`

### AppealStatus

评价申诉的裁决结果。 ⚠️ 不成立用 `REJECTED` 不用 `DISMISSED` —— 同一个业务动作在 shared 的 `ReviewAppealStatus` 里一直是 `REJECTED`，两个词指一件事。 词典 §11 已把「拒绝/驳回/申诉不成立」统一到 `REJECTED`。

枚举取值：

- `PENDING`
- `UPHELD`
- `REJECTED`

### AppearanceConfig

全局外观与语言（P-17.1.1 / 17.1.2）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `defaultSkin` | [`ThemeKey`](#themekey) | 是 | C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 |
| `festivalSkin` | [`ThemeKey`](#themekey) | 否 | 节日皮肤：留空表示不启用 |
| `festivalFrom` | `string` | 否 | 节日皮肤生效开始时间。启用节日皮肤时必填 |
| `festivalTo` | `string` | 否 | 节日皮肤生效结束时间 |
| `fallbackLang` | `string` | 是 | 语言回落规则（R9）：缺译时回落到哪个语言 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### ApplyOnBehalfResult

代商家进件的回执（三期）。**不是主体** —— 主体要等审核通过才存在。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 落库的申请单号。队列里就是这一张 |
| `ownerUserNo` | `string` | 是 | 商户本人的 userNo。**可能是这一刻新建的** —— 界面上要据此提示「这个号是新开的，本人还不知道」。 |

### ApplyStatus

入驻申请状态。 **它不是商家的状态** —— 申请与主体是两个东西：通过之前商家<b>还不存在</b>。 曾经把审核建模成「商家的一个状态」，于是驳回的申请会在商家表里留下一行 从没开过张的「僵尸商家」，出现在每一处按主体聚合的地方（结算、积分、报表）。

枚举取值：

- `PENDING`
- `REVIEWING`
- `APPROVED`
- `REJECTED`

### ArrivalBatch

到货批次与配车（P-5.1.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batchNo` | `string` | 是 | 批次单号 |
| `status` | [`BatchStatus`](#batchstatus) | 是 | 批次状态。**有序推进不允许跳步**，见 `BATCH_TRANSITIONS` |
| `communityNo` | `string` | 是 | 目的社区 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 是 | 目的自提点 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `planArriveAt` | `string` | 是 | 计划到货时间 |
| `vehicle` | `string` | 是 | 车次/司机标识；一期人肉填，二期接运力系统 |
| `itemCount` | `number` | 是 | 本批件数 |
| `merchantCount` | `number` | 是 | 涉及的商家数（跨商家拆单后，一个批次会混装多家的货） |

### AttrSource

归因来源。优先级是**全序**，不是三个独立开关。

枚举取值：

- `STORE_CODE`
- `INVITER`
- `CHANNEL`

### AttributionRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priority` | [`AttrSource`](#attrsource)\[\] | 是 | 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 |
| `windowDays` | `number` | 是 | 归因窗口期（天），1–90 |
| `conflictPolicy` | [`ConflictPolicy`](#conflictpolicy) | 是 | 归因冲突处置策略（矩阵 B1 未拍板，故可配） |
| `newUserFactors` | [`NewUserFactor`](#newuserfactor)\[\] | 是 | 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### AttributionTrace

一条归因链路（P-9.1.3）。风控从这里看"这个人是怎么被带进来的"。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `traceNo` | `string` | 是 | 归因链路单号 |
| `userNo` | `string` | 是 | 被归因的用户号。**后端下发的是它**（`MktAttributionLog.userNo`）。 |
| `userNickname` | `string` | 否 | 用户昵称。**后端目前不下发** —— 它要连 usr_account 才拿得到。 页面回落显示 userNo：空着一列比显示用户号更难查。 |
| `source` | [`AttrSource`](#attrsource) | 是 | 归因来源 |
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

### BankFlowImportFailure

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `line` | `number` | 是 | 原始文件里的行号（1 起，含表头） |
| `reason` | `string` | 是 | 为什么没认出来。给的是可执行的原因：看不懂的日期 / 金额 / 看不出方向 |

### BankFlowImportResult

银行流水导入的结果（TDD-供应商结算与双轨资金 §10）。 **三个计数分开给，不合成一句「成功 N 条」**：它们对应三种完全不同的处置 —— 入库的不用管；跳过的说明这份传过了（正常，不是错）；失败的要对着原始文件去看那几行。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 文件里认出来的行数 = imported + skipped + failed |
| `imported` | `number` | 是 | 真正入库的 |
| `skipped` | `number` | 是 | 流水号已存在、跳过的。**重复上传是常态，不是错误** |
| `failed` | `number` | 是 | 没解析成功的行数 |
| `failures` | [`BankFlowImportFailure`](#bankflowimportfailure)\[\] | 是 | 失败明细。`line` 是原始文件里的行号 —— 财务要对着原文件看 |

### BannedWord

平台禁售词（商品①）。商家提审商品时前置校验标题。 <p>**此前只有事后驳回**：带违禁词的标题会进审核队列、占一个审核员的时间、 再被驳回，而商家隔几天才知道要改哪个字。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `word` | `string` | 是 | 词。**存的是小写**，匹配时两边都转小写 |
| `reason` | `string,null` | 是 | 为什么禁。**会原样出现在给商家的报错里**，所以要写成他看得懂的一句话 |
| `enabled` | `boolean` | 是 | 是否启用该配置 |

### BatchStatus

枚举取值：

- `PLANNED`
- `DISPATCHED`
- `ARRIVED`
- `SIGNED`

### Benefit

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `kind` | [`BenefitKind`](#benefitkind) | 是 | 权益类型。**决定 `value` 的口径**，见上方注释 |
| `value` | `number` | 是 | 权益数值，口径随 kind 变化 |
| `couponNo` | `string,null` | 否 | 仅 COUPON_PACK：绑定的券模板 |

### BenefitKind

权益类型。 四类的 `value` 口径不同，所以必须分开而不是塞一个通用数字： - `DISCOUNT` 万分比（9500 = 95 折） - `FREE_SHIPPING` 每月免运费次数 - `COUPON_PACK` 每月赠券张数（要绑 `couponNo`） - `POINTS_BOOST` 积分倍率万分比（10000 = 1 倍）

枚举取值：

- `DISCOUNT`
- `FREE_SHIPPING`
- `COUPON_PACK`
- `POINTS_BOOST`

### BindState

微信 token：`NA` 不适用（线下单）· `WAITING` 等揽收后换 · `DONE` 已换上 · `FATAL` 换不了

枚举取值：

- `NA`
- `WAITING`
- `DONE`
- `FATAL`

### BlacklistAppealStatus

黑名单申诉状态。 ⚠️ 刻意不叫 `AppealStatus` —— 评价域（P-13.1.3 恶意差评申诉）已经有一个同名类型， 两者是完全不同的东西（一个是商家申诉差评，一个是被拉黑者申诉解禁）。 同名会在 `lib/types` 的总出口上直接冲突，也会让读代码的人以为它们是一回事。

枚举取值：

- `NONE`
- `PENDING`
- `UPHELD`
- `REJECTED`

### BlacklistEntry

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `blackNo` | `string` | 是 | 黑名单单号 |
| `subjectType` | [`SubjectType`](#subjecttype) | 是 | 主体类型 |
| `subject` | `string` | 是 | 主体标识：用户昵称 / 商家名 / 设备号 |
| `reason` | `string` | 是 | 拉黑原因，必填 |
| `until` | `string` | 是 | 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 |
| `appealStatus` | [`BlacklistAppealStatus`](#blacklistappealstatus) | 是 | 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 |
| `appealReason` | `string` | 否 | 被拉黑者提交的申诉理由 |
| `appealVerdict` | `string` | 否 | 申诉裁决说明 |
| `active` | `boolean` | 是 | 是否生效中。到期或申诉通过后置 false，记录保留 |
| `createdAt` | `string` | 是 | 拉黑时间 |

### BusinessMode

经营模式。与  {@link  FeeRuleVersion }  一起构成费率的第一个维度。 自营：平台是销售主体，这个费率是**进销差价（毛利）**。 第三方：平台是撮合方，这个费率是**服务收入（佣金）**。 算法一样，记账口径不同 —— 口径由结算单上的 `businessMode` 快照决定。

枚举取值：

- `SELF_OPERATED`
- `THIRD_PARTY`

### BuyerInvoiceRequest

买家的开票申请（`/ops/invoice-requests`）。 ⚠️ **这个域里有三张不同的「票」，名字很近，别混：** \| 类型 \| 谁开给谁 \| 决定什么 \| 端点 \| \|---\|---\|---\|---\| \|  {@link  PurchaseInvoice }  进项票 \| 供应商 → 平台 \| 平台能不能付款（票到付款）\| `/ops/purchase-invoices` \| \|  {@link  InvoiceRequest }  商家开票申请 \| 平台 → 商家 \| 商家的服务费发票 \| `/ops/finance/invoices` \| \| 本类型 买家开票申请 \| 平台 → 买家 \| 买家能不能报销 \| `/ops/invoice-requests` \| 前两个此前已有类型，本类型是补的 —— 它按订单走（`orderNo`），前两个按主体/账期走。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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

### CancellationAffectedOrder

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `subOrderNo` | `string` | 是 | Child order identifier affected by cancellation. |
| `merchantNo` | `string` | 是 | Merchant owning the affected child order. |
| `merchantName` | `string` | 是 | Display name of the affected merchant. |
| `storeNo` | `string,null` | 是 | Store fulfilling the order; null when fulfillment has no store assignment. |
| `storeName` | `string,null` | 是 | Display name of the fulfillment store; null when there is no store. |
| `payableMinor` | `number` | 是 | Amount payable for this child order in the order currency's minor units. |
| `status` | [`OrderStatus`](#orderstatus) | 是 | Current business state of the affected child order. |

### CancellationImpact

只有完整授权且可取消时才含主单金额和影响明细。

类型：`object`（见下） \| `object`（见下）

### Captcha

图形验证码挑战。`imageBase64` 不带 data: 前缀，端上自己拼

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `captchaId` | `string` | 是 | 验证码会话号，校验时要带回来 |
| `imageBase64` | `string` | 是 | 图形验证码的图，base64 |

### Carrier

承运商。一期只接这三家，改枚举比改结构便宜。

枚举取值：

- `SF`
- `JD`
- `YTO`

### CarrierConfig

一家承运商的接入配置。 ⚠️ 这一页配错的后果不是"显示不对"，而是**订单发不出去**： 全停、启用没配密钥的、或者停掉还有在途单的那家，都会让快递链路当场断掉。 所以规则全部落在 mock 层，页面写不出违规配置。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `carrier` | [`Carrier`](#carrier) | 是 | 承运商标识 |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 |
| `priority` | `number` | 是 | 优先级，数字越小越优先。 **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。 |
| `accountMasked` | `string` | 是 | 接入账号，展示一律脱敏 |
| `apiKeyConfigured` | `boolean` | 是 | 密钥是否已配置。 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。 |
| `pickupCutoff` | `string` | 是 | 每日截单时间 HH:mm，过点的单顺延到次日 |
| `slaHours` | `number` | 是 | 承诺时效（小时） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |
| `codes` | [`Record<string,string>`](#recordstringstring) | 否 | 这家承运商在各物流渠道里叫什么（`{ kuaidi100: "shentong", wx: "STO" }`）。 某个渠道没有这一项 = 那家渠道不覆盖它，订阅时会跳过 |

### Category

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `categoryNo` | `string` | 是 | 类目单号 |
| `name` | `string` | 是 | 类目名（运营侧展示用中文名） |
| `parentNo` | `string` | 否 | 顶级为空 |
| `level` | `number` | 是 | 1–3，见 MAX_CATEGORY_LEVEL |
| `template` | [`CategoryTemplate`](#categorytemplate) | 是 | 品类属性模板。**决定商家录入时看到哪些字段** |
| `qualifications` | `string`\[\] | 是 | 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。 ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。 |
| `requiredCode` | `string` | 否 | 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。 空 = 无门槛。 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的， 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 —— 看起来在校验，实际上几乎总是通过。 ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身； 证件校验要等 B-11.1.2 资质上传落地后再收紧。 |
| `i18n` | [`I18nText`](#i18ntext) | 是 | 类目名的三语文案，下发给 C 端展示 |
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

### CategorySpec

类目 × 规格总览的一行（规格库 V195，`GET /ops/category-specs`）。 **一条规格都没绑的类目也会返回**：这张表真正要回答的是「哪些类目还没配」—— 只列已配的，缺口就永远看不见，而缺口的代价是那一类商家建品只能手打， 手打的选项没有 code，跨店聚合就此断掉。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目号 |
| `categoryName` | `string` | 是 | 类目名 |
| `parentName` | `string` | 是 | 一级类目名，用来分组 |
| `categoryType` | [`CategoryTemplate`](#categorytemplate) \| `null` | 否 | 类目形态 |
| `dimCount` | `number` | 是 | 已绑维度数。0 就是缺口 |
| `dims` | [`CategorySpecDim`](#categoryspecdim)\[\] | 是 | 这个类目能用的规格维度 |

### CategorySpecDim

类目下的一个规格维度。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 规格维度号 |
| `code` | `string` | 是 | 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀 |
| `name` | `string` | 是 | 名称 |
| `valueType` | `string` | 是 | ENUM 枚举 / QUANT 数值+单位 |
| `unit` | `string,null` | 否 | 单位 |
| `usage` | `string` | 是 | SALE 进 SKU 笛卡尔积 / PROP 只是描述，不生成规格 |
| `universal` | `boolean` | 是 | 通用维度：值的含义跨类目一致（颜色、重量）。与只在本类目成立的专用维度相对 |
| `primary` | `boolean` | 是 | 主维度：商家建品选完类目，自动预填的就是它。每个类目至多一个 |
| `valueCount` | `number` | 是 | 有几个取值 |
| `values` | [`CategorySpecValue`](#categoryspecvalue)\[\] | 是 | 取值列表 |

### CategorySpecValue

维度下的一个取值。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | 规格取值号 |
| `code` | `string` | 是 | 码值 |
| `label` | `string` | 是 | 该类目下的展示文案，可能被类目级换名换过（500g → 约1斤） |
| `numericValue` | `number,null` | 否 | 归一量：500g / 半斤 / 0.5kg 都是 500。排序与同规格比价靠它 |
| `numericUnit` | `string,null` | 否 | 归一量的单位。与 numericValue 一起才有意义 |

### CategoryTemplate

品类属性模板（P-3.1.2）：五品类的字段结构不同，模板决定商家录入时看到哪些字段。

枚举取值：

- `STANDARD`
- `FRESH`
- `SERVICE`
- `VIRTUAL`
- `VOUCHER`

### ChannelMessage

渠道报文一条（`stl_channel_message`，V286 起在落数据）。 ⚠️ **报文已脱敏**：签名、证书序列号、Authorization 都不入库， 所以它<b>不能拿去重放验签</b>。列表响应里带着这句话（`note`）， 页面必须显示 —— 不显示的话，第一个拿它去核签名的人会得出 「我们的验签实现有 bug」这个结论。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 通道报文编号 |
| `payChannel` | `string` | 是 | 支付通道编码 |
| `msgType` | `string` | 是 | CALLBACK 通道推给我方 / SEND 我方发给通道 |
| `api` | `string` | 是 | 回调是端点路径，发送是接口坐标 |
| `bizNo` | `string,null` | 否 | 我方单号。**验签失败时为空** —— 那种行只能按时间和通道翻， 所以按单号筛会把最该看的那几行滤掉 |
| `paymentNo` | `string,null` | 否 | 原支付指令编号 |
| `outcome` | `string` | 是 | RECEIVED / ACCEPTED / REJECTED / OK / FAILED |
| `reason` | `string,null` | 否 | 拒绝或失败的原因，直接显示 |
| `payload` | `string,null` | 否 | 脱敏并截断后的报文 |
| `headers` | `string,null` | 否 | 按接口权限返回的报文头信息 |
| `createdAt` | `number,null` | 否 | 服务端创建时间，Unix 毫秒 |

### ChannelMessagePage

报文分页。`note` 是固定口径，**端上必须显示**

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`ChannelMessage`](#channelmessage)\[\] | 是 | 本页记录 |
| `total` | `number` | 是 | 符合条件的记录总数 |
| `pageNo` | `number` | 是 | 页码，从一开始 |
| `size` | `number` | 是 | 本页最大记录数 |
| `note` | `string` | 是 | 服务端补充说明 |

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
| `parentNo` | `string,null` | 否 | 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 |
| `kind` | `string,null` | 否 | ESTATE / VILLAGE / BUILDING |
| `source` | `string,null` | 否 | `MAP` 地图导入 / `OFFICIAL` 官方名录 / `MERCHANT` 商家提报 / `OPS` 运营手建。 **批量导入之后一个区有几千条**，不标出来源就没法把「机器扒进来的」 与「人一条条维护的」分开核对 —— 而前者是要抽查的，后者不是。 |
| `pickupCount` | `number` | 是 | 本社区的自提点数量（列表直接给，避免逐行再查一次） |
| `createdAt` | `string` | 是 | 建档时间 |
| `regionCode` | `string` | 否 | 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。 **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区 悄悄出现在别人的经营范围里。 |
| `regionPath` | `string` | 否 | 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。 **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字， 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。 |
| `latE6` | `number,null` | 否 | 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** —— 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样， 必须分开说：前者是待办，后者是事实。 |
| `lngE6` | `number,null` | 否 | 经度乘以一百万的整数 |

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
| `kind` | [`SettlementKind`](#settlementkind) | 否 | ESTATE 小区 / VILLAGE 村。裁决的人要一眼看出这是哪种聚落 |
| `originCode` | `string` | 否 | 关联的官方村码；非空 = 从词典选的，重复开通会被后端拦 |
| `located` | `boolean` | 否 | 带没带定位。**没带的要显眼** —— 通过后聚落没有坐标， 买家用定位永远找不到它，运营得先补坐标再通过。 |
| `latE6` | `number,null` | 否 | 商家提报时带的坐标（gcj02，E6）。**要看得见具体值** —— 只给一个「有/无」，落点偏到隔壁区也照样显示「有定位」，判不出对错。 |
| `lngE6` | `number,null` | 否 | 经度 ×1e6（gcj02） |
| `fallbackLatE6` | `number,null` | 否 | 官方村码在区划表里的坐标（高德批量补录）。没带定位时后端通过这条提报会自动用它兜底 —— 两个都空，才是真的「通过后无坐标、买家搜不到」。 |
| `fallbackLngE6` | `number,null` | 否 | 兜底经度：商家没选点时用提交那一刻的位置。**多半不在那个小区里**，裁决要留意 |
| `status` | [`CommunityApplyStatus`](#communityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。**只有 PENDING 能裁**：裁完就是终态，再裁一次意味着同一条提报有两个结论 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因。**原样出现在商家 B 端**，所以驳回必须填 |
| `submittedAt` | `number` | 是 | 提报时间 |

### CommunityApplyStatus

提报单的状态。 与覆盖项的 PENDING **同名不同物**：那个说「这一条覆盖算不算数」， 这个说「这张提报单走到哪了」—— `APPROVED` 意味着平台已经建出了社区。

枚举取值：

- `PENDING`
- `APPROVED`
- `REJECTED`

### CommunityDuplicate

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `left` | [`Community`](#community) | 是 | 疑似重复的一方 |
| `right` | [`Community`](#community) | 是 | 另一方 |
| `reason` | [`DuplicateReason`](#duplicatereason) | 是 | 原因 |
| `distanceM` | `number,null` | 否 | 两点直线距离（米）。有一方没坐标时为空 |

### CommunityImportResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `received` | `number` | 是 | 收到多少条 |
| `created` | `number` | 是 | 新建了多少条 |
| `updated` | `number` | 是 | 更新了多少条（按幂等键命中既有行） |
| `skipped` | `number` | 是 | 跳过多少条。**没坐标的一律跳** —— 建出来买家永远搜不到它，而这件事没有任何报错 |
| `deduped` | `number` | 是 | 认出是「同一个小区的另一个 POI」而没有建档的条数（名字完全相同且相距 ≤300m）。 与 `skipped`（数据不合格）分开计：导一个区时这个数就是 「地图给的 POI 比小区多出来多少」，是判断导入质量的唯一依据。 |
| `dryRun` | `boolean` | 是 | 试算。**默认就是它** —— 一次动几百行的接口，默认值要在安全那一边 |

### ConflictPolicy

店铺码归因冲突处置（P-9.1.5，矩阵 **B1 未拍板**）： 用户已归属 A 店，又扫了 B 店的码，算谁的？

枚举取值：

- `KEEP_FIRST`
- `OVERWRITE`
- `ASK_USER`

### ContentSlot

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `slotNo` | `string` | 是 | 内容位单号 |
| `title` | `string` | 是 | 内容位标题 |
| `kind` | [`SlotKind`](#slotkind) | 是 | 内容位形态：首页楼层 / 轮播 / 频道 |
| `sort` | `number` | 是 | 同一 kind 内的展示顺序，小的在前 |
| `communityNos` | `string`\[\] | 是 | 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） |
| `onlineAt` | `string` | 是 | 上线时间 |
| `offlineAt` | `string` | 是 | 下线时间 |
| `enabled` | `boolean` | 是 | 是否启用。关掉即刻不再展示，不等下线时间 |
| `goodsNos` | `string`\[\] | 是 | 楼层里的商品，**有序** —— 数组顺序就是首页里的展示顺序。 只有 `HOME_FLOOR` 有内容：BANNER 要「图 + 跳转目标」、CHANNEL 要频道页， 而 C 端两样都还没有，没有承接位就定不了那个模型。后端对这两种一律存空。 |

### Coupon

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `couponNo` | `string` | 是 | 券模板单号 |
| `name` | `string` | 是 | 券名，展示给用户 |
| `type` | [`CouponType`](#coupontype) | 是 | 券类型，决定 `value` 的口径 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** |
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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `issueNo` | `string` | 是 | 发放记录单号 |
| `couponNo` | `string` | 是 | 发放的券模板 |
| `couponName` | `string` | 是 | 券名快照 |
| `target` | [`IssueTarget`](#issuetarget) | 是 | 发放对象类型 |
| `targetDesc` | `string` | 是 | 定向说明：社区名 / 用户昵称 / 人群名 |
| `count` | `number` | 是 | 本次发放张数 |
| `amount` | `number` | 是 | 本次发放占用的预算（分） |
| `operator` | `string` | 是 | 操作人（STAFF 账号）。**客服也持有发券权限**，留痕不能省 |
| `createdAt` | `string` | 是 | 发放时间 |

### CouponStatus

DRAFT 可改可删；ACTIVE ⇄ PAUSED 之间可来回；ENDED 是终态（券已发出去的仍然有效）。

枚举取值：

- `DRAFT`
- `ACTIVE`
- `PAUSED`
- `ENDED`

### CouponType

券类型。**与 `mkt_coupon.type` 的库列注释逐字一致**（`FULL_CUT 满减 / DISCOUNT 折扣`）。 ⚠️ **2026-09-09：这里原来还有 `NEWCOMER` / `TARGETED`，已删。** 三条都指向同一个结论：库列只允许两个值；`discountFor` 从未处理过它们 （下面那条注释原本就写着「没有折扣算法撑着，建券表单不给选」）； 而 `TARGETED` 在后端根本是**另一个字段**的值 —— `PmtCoupon.ISSUE_TARGETED` 是发放方式（issueMode），不是券类型。 留着的代价不是多两个枚举值：它们有三语标签、有 mock 数据、 界面上还有「按券类型筛选」——**筛「新人」永远是空列表，而且不报错**。

枚举取值：

- `FULL_CUT`
- `DISCOUNT`

### CoverageArea

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `level` | `string` | 是 | COMMUNITY / STREET / DISTRICT / CITY / PROVINCE |
| `refCode` | `string` | 是 | 范围所引用的行政或业务编码 |
| `name` | `string` | 是 | 取不到名就给号，**不留空** —— 空会被读成「没有这一条」 |
| `status` | `string` | 是 | ACTIVE / PENDING |

### CoverageDistribution

位置分布：聚落 × 买家 × 商家 × 商品。 **最要紧的不是那几行，是 `unattributable`。** 把算不了的静默丢掉， 这张表就会把「缺数据」说成「缺需求」—— 而运营会据此去撤一个其实有人的片区的商家。 分母写错的分析比没有分析更危险：没有分析时人会去查，有一张看起来完整的表时，人会直接照着做。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regions` | [`RegionRow`](#regionrow)\[\] | 是 | 区县概览：一区县一行。两万多聚落不平铺，点开区县走 communitiesInRegion 下钻 |
| `supplyGaps` | [`DistributionRow`](#distributionrow)\[\] | 是 | 招商清单：有买家、无商家覆盖的聚落（全局小集合，可行动到小区） |
| `totals` | [`DistributionTotals`](#distributiontotals) | 是 | 全局四桶计数 + 买家总数 |
| `unattributable` | [`Unattributable`](#unattributable) | 是 | 无法归属覆盖片区的数量 |

### CoverageHealth

坐标健康度 —— **整个位置模块的分母**。 门店没标点时后端那条自送半径的闸**直接放行**（缺数据不该拦正常订单，这是对的）。 代价是商家以为自己限了三公里、实际多远的单都进来，等他要送货才发现送不到， 那时钱已经收了。而这件事此前在任何界面上都看不见 —— 商家看不见，运营也看不见。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `stores` | `object`（见下） | 是 | 门店统计摘要 |
| `addresses` | `object`（见下） | 是 | 地址**只给聚合数**：那是个人信息，看总数就够判断分母有多脏 |
| `communities` | `object`（见下） | 是 | 社区统计摘要 |

`stores` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |
| `missing` | `object`（见下）\[\] | 是 | 没标点的那些。**给明细不只给数字** —— 只给一个数，运营下一步无从做起 |

`stores.missing[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店编号 |
| `storeName` | `string` | 是 | 门店显示名称 |
| `merchantNo` | `string` | 是 | 从这里跳到商家去催他标点。**刻意不带商家名**（取名字要绕数据域） |
| `deliveryRadiusM` | `number,null` | 是 | 他以为自己限了多少米，而实际一米都没限 —— 后果有多大就看这个数 |

`addresses` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |

`communities` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `withCoords` | `number` | 是 | — |
| `missing` | `object`（见下）\[\] | 是 | 没坐标的聚落**谁也匹配不到** —— 而它看起来一切正常：建档成功、列表里有 |

`communities.missing[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | — |
| `name` | `string` | 是 | 业务对象显示名称 |

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
| `pendingGoodsAudit` | `number` | 是 | 待审商品数（P-3.2 商品审核队列） |
| `goodsAuditOldestDays` | `number` | 是 | 最早那件待审商品等了几天。**与数量成对出现才有意义** —— 「194 件待审」既可能是今天涌进来的一批，也可能是积了两周没人管， 而这两件事该做的反应完全不同。没有待审时为 0 |

### DebtTxn

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `txnNo` | `string` | 是 | 流水号 |
| `txnType` | [`DebtTxnType`](#debttxntype) | 是 | 这一笔是怎么来的 |
| `amountMinor` | `number` | 是 | **有符号**：产生为正、偿还为负。靠 txnType 推方向等于把方向表达两遍 |
| `balanceAfterMinor` | `number` | 是 | 变动后余额。对账时逐笔回放用 |
| `sourceType` | `string,null` | 是 | 源单类型，如 REFUND。空 = 存量数据 |
| `sourceNo` | `string,null` | 是 | 源单号。**指不出源头的欠款没法向商家解释** |
| `batchNo` | `string,null` | 是 | OFFSET 时记从哪一批扣的 |
| `reason` | `string,null` | 是 | 记这一笔的理由，**给人看的原话** |
| `at` | `number` | 是 | 发生时刻（毫秒） |

### DebtTxnType

欠款流水类型。<b>方向不由它推</b> —— amountMinor 自带符号，靠类型再推一次方向等于把同一件事表达两遍， 而两处迟早会不一致。

枚举取值：

- `INCUR`
- `OFFSET`
- `DEPOSIT`
- `WRITE_OFF`

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
| `status` | [`DemandStatus`](#demandstatus) | 是 | 需求单状态 |
| `quoteCount` | `number` | 是 | 已收到的报价数 |
| `createdAt` | `string` | 是 | 发起时间 |

### DemandStatus

枚举取值：

- `OPEN`
- `QUOTING`
- `CHOSEN`
- `CLOSED`

### DepositTxn

保证金流水。**只有余额字段的账户是不可审计的** —— 说不清这笔钱什么时候少的、谁扣的。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `txnNo` | `string` | 是 | 流水号 |
| `txnType` | [`DepositTxnType`](#deposittxntype) | 是 | 变动类型 |
| `amountMinor` | `number` | 是 | 有符号：扣划为负 |
| `balanceAfterMinor` | `number` | 是 | 变动后实缴余额（分），对账用 |
| `reason` | `string,null` | 否 | 变动原因 |
| `operator` | `string,null` | 否 | 操作人 |
| `createdAt` | `string,null` | 否 | 发生时间 |

### DepositTxnType

枚举取值：

- `PAY`
- `REFUND`
- `FREEZE`
- `UNFREEZE`
- `DEDUCT`

### DistributionRow

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 聚落编号 |
| `name` | `string` | 是 | 聚落名 |
| `kind` | `string` | 是 | ESTATE / VILLAGE / BUILDING |
| `regionPath` | `string,null` | 否 | 归属区划路径（省/市/区/街道）；没挂区划时为空 |
| `buyerCount` | `number` | 是 | 围栏内有坐标的收货地址数 |
| `merchantCount` | `number` | 是 | 现算在这儿有货的主体数 —— 是「买家真搜得到」，不是「谁框了这儿」 |
| `goodsCount` | `number` | 是 | 这个聚落现算能买到的商品数（买家真搜得到） |

### DistributionTotals

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communities` | `number` | 是 | 全平台已开通聚落总数 |
| `buyers` | `number` | 是 | 全平台能定位到聚落的买家总数（不含「算不了的」） |
| `okCount` | `number` | 是 | 供需都有的聚落数（有买家且有商家） |
| `supplyGapCount` | `number` | 是 | 缺供给的聚落数（有买家、没商家）——招商目标 |
| `demandGapCount` | `number` | 是 | 缺需求的聚落数（有商家、没买家） |
| `emptyCount` | `number` | 是 | 两头空的聚落数 |

### DuplicateReason

判重的依据。SAME_NAME 同名 / NEARBY 坐标相近 —— 运营据此决定合不合

枚举取值：

- `SAME_NAME`
- `NEARBY`

### EarningCalculation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `object`（见下） | 是 | 业务类型编码或类型摘要，按当前接口定义解释 |
| `matched` | `object`（见下） | 是 | 是否命中可用赠送规则 |
| `cashMinor` | `number` | 是 | 参与计算的现金金额，货币最小单位 |
| `points` | `number` | 是 | 按当前规则计算的积分数量 |

`type` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `name` | `string` | 是 | 业务对象显示名称 |
| `market` | `string` | 是 | — |
| `currency` | `string` | 是 | ISO货币代码，金额必须结合此字段解释 |
| `scale` | `number` | 是 | — |
| `ratePoints` | `number` | 是 | — |
| `rateMinor` | `number` | 是 | — |

`matched` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scope` | `string` | 是 | 规则适用范围编码 |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |
| `rule` | `object`（见下） | 是 | — |

`matched.rule` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |

### EarningCategory

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 商品分类编号 |
| `parentNo` | `string,null` | 是 | 父分类编号，根节点可为空 |
| `name` | `string` | 是 | 业务对象显示名称 |
| `path` | `string` | 是 | 分类层级路径 |

### EarningContext

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`PointsTypeConfig`](#pointstypeconfig) \| `null` | 是 | 业务类型编码或类型摘要，按当前接口定义解释 |
| `categories` | [`EarningCategory`](#earningcategory)\[\] | 是 | 可用商品分类列表 |
| `types` | [`PointsTypeConfig`](#pointstypeconfig)\[\] | 是 | 可用积分类型列表 |

### EarningRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `scope` | `string` | 是 | 规则适用范围编码 |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |
| `activeRevision` | `number,null` | 是 | 当前生效版本号 |
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |
| `activeMode` | [`PointsEarningRuleMode`](#pointsearningrulemode) \| `null` | 是 | 已生效规则模式 |
| `activeValue` | `number,null` | 是 | 已生效规则值 |
| `actor` | `string` | 是 | 操作人标识 |
| `remark` | `string` | 是 | 操作备注 |
| `at` | `number` | 是 | 业务发生时间 |
| `action` | [`PointsEarningRuleAction`](#pointsearningruleaction) | 否 | 本次执行的业务动作 |

### EarningRulePage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`EarningRule`](#earningrule)\[\] | 是 | 当前页数据行 |
| `total` | `number` | 是 | 匹配条件的总记录数量 |
| `page` | `number` | 是 | 当前页码，从1开始 |
| `size` | `number` | 是 | 请求的每页记录数量 |

### EffectiveFeeRates

某时刻实际生效的费率表，键为 `${businessMode}\|${trafficSource}`。

类型：`Record<string,number>`

### ExceptionKind

异常单的成因。 ⚠️ 异常单**不是一张表**，而是对订单表的一个视图 —— 一旦落成记录就会过期： 订单已经推进了，异常记录还挂在那里，运营会去处理一个不存在的问题。 所以 `OrderException` 由 `orders` 实时算出来，不存。

枚举取值：

- `STUCK`
- `PAY_TIMEOUT`

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

售后规则（P-6.1.2）：极速退的门槛 + 各环节时限。 端点路径仍是 `/ops/after-sales/fast-refund-rule` —— 名字留着，内容长大了。 **这一屏此前是悬空的**：写进参数表之后没有任何业务代码读它，于是页面显示 「关闭 · ¥20 · 24 小时」，线上真正在跑的是「无条件 · ¥100 · 不限时」。现在它真的生效了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enabled` | `boolean` | 是 | 总开关。关掉后所有小额售后都走人工 |
| `maxAmount` | `number` | 是 | 金额上限（分），必须 > 0 |
| `withinHours` | `number` | 是 | 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） |
| `categories` | `string`\[\] | 是 | 适用品类编码，空 = 全品类。**目前只存不判**（后端注释里记着这条账） |
| `replyHours` | `number` | 是 | 商家响应时限（小时）：超时系统替他同意并退款 |
| `shipBackDays` | `number` | 是 | 买家寄回时限（天）：逾期未寄出则关闭本次申请 |
| `confirmHours` | `number` | 是 | 商家确认收货时限（小时）：超时系统退款 |
| `interveneWorkDays` | `number` | 是 | 平台介入承诺时限（工作日）：仅展示与超期提醒，不自动裁决 |
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
| `businessMode` | [`BusinessMode`](#businessmode) | 是 | 经营模式，费率的第一个维度 |
| `trafficSource` | [`FeeTrafficSource`](#feetrafficsource) | 是 | 适用的流量来源，费率的第二个维度 |
| `rateBp` | `number` | 是 | 万分比。500 = 5% |
| `effectiveFrom` | `number` | 是 | 生效时刻（毫秒）。**填未来时刻 = 预约生效** |
| `enabled` | `number` | 是 | 1 = 该版本生效；0 = 已停用（回退到上一版） |
| `remark` | `string,null` | 否 | 为什么调这一次 —— 回查时这句话比数字更有用 |
| `createdAt` | `string` | 否 | 创建时间 |
| `createdBy` | `string` | 否 | 创建人 |

### FeeTrafficSource

费率适用的流量来源。 **只有两档**，比订单上的 `TrafficSource`（还有 INVITE / CHANNEL）窄 —— 后端的费率表只认这两个。写成独立类型而不是在接口里内联字面量联合， 是因为内联的枚举不会被登记表扫到，等于绕过了枚举雷达（规范 §D5）。

枚举取值：

- `MERCHANT_OWNED`
- `PLATFORM`

### FenceImpact

围栏改动的影响预览。 只给「当前半径」没用 —— 运营要回答的是「改成 1500 会多进来几户」， 而这件事此前在任何界面上都算不出来，只能改完再等有人投诉。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `currentRadiusM` | `number` | 是 | 当前服务半径，米 |
| `previewRadiusM` | `number` | 是 | 预览服务半径，米 |
| `currentInside` | `number` | 是 | 当前半径内对象数 |
| `previewInside` | `number` | 是 | 预览半径内对象数 |
| `addressesWithCoords` | `number` | 是 | 有坐标的收货地址总数。**分母要给** —— 「多进来 0 户」在一个没几条地址有坐标的库里说明不了任何事 |

### FissionCampaign

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `fissionNo` | `string` | 是 | 活动单号 |
| `name` | `string` | 是 | 活动名 |
| `rewardType` | [`RewardType`](#rewardtype) | 是 | 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 |
| `couponNo` | `string` | 是 | 奖励券模板号（对应营销域的 Coupon） |
| `inviterCount` | `number` | 是 | 邀请人得几张 |
| `inviteeCount` | `number` | 是 | 被邀请人得几张 |
| `enabled` | `boolean` | 是 | 是否启用 |
| `invitedCount` | `number` | 是 | 累计邀请人数 |
| `convertedCount` | `number` | 是 | 其中转化（完成首单）的人数 |
| `createdAt` | `string` | 是 | 创建时间 |

### FreightDraft

从快递100 报价生成的运费模板草稿（TDD-快递100商家寄件 §8 AC17）。**不落库** —— 运营核对、改过之后走 saveFreightTemplate。首重 / 续重取多数省份那一档，贵的省份写成加收，查不到价的省份不配送。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 建议的模板名，如「运城市发 · 中通快递」 |
| `firstWeightGram` | `number` | 是 | 首重（克） |
| `firstFee` | `number` | 是 | 首重费（分） |
| `addWeightGram` | `number` | 是 | 续重单位（克） |
| `addFee` | `number` | 是 | 每个续重单位的费用（分） |
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 地区规则：贵的省份加收、查不到价的省份不配送 |
| `rows` | [`FreightProvincePrice`](#freightprovinceprice)\[\] | 是 | 31 个省的原始报价，运营据此核对 |
| `unquoted` | `number` | 是 | 查不到价的省份数 |

### FreightProvincePrice

发货城市 → 快递100 报价生成模板时，每个省的原始报价（分）。查不到价为 null

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `region` | `string` | 是 | 省份简称，写进模板的地区名（按收货地址开头匹配） |
| `firstFee` | `number,null` | 是 | 首重价（分） |
| `addFee` | `number,null` | 是 | 一个续重单位的价（分） |

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
| `outOfRange` | [`OutOfRangeRule`](#outofrangerule)\[\] | 是 | 超区规则 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### FulfillmentType

履约方式。**取值与后端 `ord_sub_order.fulfillment` 一致**。 ⚠️ 这里曾经写成 `PICKUP_STORE` / `PICKUP_NEIGHBOR` —— 同一个概念、词序反了， 于是按它筛后端一条也匹配不上。ops-web 只跑 mock，所以从没被真实响应打脸过。

枚举取值：

- `STORE_PICKUP`
- `NEIGHBOR_PICKUP`
- `MERCHANT_DELIVERY`
- `EXPRESS`
- `STORE_VERIFY`
- `APPOINTMENT`

### FundsMode

资金路径。**能走哪条由商户类型决定，不是平台自选**（ADR-017 §3.2）： 自然人开不出票 → 平台按全额确认收入而成本不可税前扣除 → 禁归集； 自产农产品例外。

枚举取值：

- `AGGREGATED`
- `DIRECT`

### FunnelRow

获客漏斗的一行（P-16.1.4 扫码→进店→注册→首单）。 ⚠️ 此前 interface 与环节枚举撞名叫 FunnelStep，字段写成 `step: FunnelStep` —— 自我引用

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `step` | [`FunnelStep`](#funnelstep) | 是 | 漏斗环节：扫码 → 进店 → 注册 → 首单 |
| `count` | `number` | 是 | 该环节人数 |

### FunnelStep

增长漏斗的四段。扫码 → 进店 → 注册 → 首单

枚举取值：

- `SCAN`
- `ENTER_STORE`
- `REGISTER`
- `FIRST_ORDER`

### GeoPlace

固定地址库的一条：**某个坐标格子叫什么名字，上次核对是什么时候**。 它同时是答案库、是防止频繁调地图的挡板、也是逐步长大的自有资产 —— 这三样本来就该是同一份数据。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `geoKey` | `string` | 是 | geohash 精度 8（约 38m×19m）—— 同一栋楼里所有人命中同一行 |
| `name` | `string` | 是 | 最具体的那个名字：建筑 > 小区/楼盘 > 街道门牌 |
| `kind` | `string` | 是 | POI/AOI/STREET/REGION —— 说清这个名字是哪一档 |
| `address` | `string,null` | 是 | 带门牌的标准地址。取不到就 null |
| `latE6` | `number,null` | 是 | 这个格子里**首次落库的那个点**（gcj02 ×1e6），不是格子中心 |
| `lngE6` | `number,null` | 是 | 与 latE6 成对 |
| `hitCount` | `number` | 是 | 沉淀成聚落的依据：用得最多的那些地方值得我们自己认识 |
| `verifiedAt` | `string,null` | 是 | 上次核对的时刻。超期不等于作废 —— 它只说「该回头核一次了」 |
| `promotedNo` | `string,null` | 是 | 已经升级成聚落的话指过去 |

### GeoPlacePage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`GeoPlace`](#geoplace)\[\] | 是 | 这一页的行。按命中次数降序 —— 最该沉淀的排最前 |
| `total` | `number` | 是 | **全量的那个数**，不是这一页的 —— 只给一页的条数，「还要依赖地图多久」就判不出来 |
| `mapStatus` | `string` | 是 | CLOSED / OPEN / QUOTA_EXHAUSTED。这一行是唯一能提前发现「地图快不行了」的地方 |

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
| `name` | `string` | 是 | 业务对象显示名称 |

### GoodsChain

单商品全链路状态（M5）。 <p>「审核到哪了、建账了吗、有库存吗、卖了多少」此前要在四个页面之间跳着看， 而它们各自的主键还不一样。卡点用词与链条画像同一套 —— 分叉就是两套结论。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品编号 |
| `title` | `string,null` | 是 | 商品或规格展示标题 |
| `entityNo` | `string` | 是 | 商家主体编号 |
| `auditStatus` | `string,null` | 是 | 商品审核状态 |
| `onSale` | `boolean` | 是 | 是否上架销售 |
| `skuCount` | `number` | 是 | SKU 数量 |
| `bookedSkus` | `number` | 是 | 其中在进销存里建了账的。**少于 skuCount 就是投影没搬全** —— 商家端的表现是「有些规格盘得着、有些盘不着」，极难自查 |
| `onHand` | `number` | 是 | 账面库存数量 |
| `available` | `number` | 是 | 当前可售库存数量 |
| `soldCount` | `number` | 是 | 累计销售数量 |
| `stuckAt` | [`MerchantChainStuck`](#merchantchainstuck) \| `null` | 是 | 与链条画像用同一套词（）—— 两处分叉就是两套结论 |

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
| `titleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语标题原文（`prd_goods.title_i18n`）。 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。 |
| `subtitleI18n` | [`Partial<Record<("zh"|"en"|"ar"),string>>`](#partialrecordzhenarstring) | 否 | 三语副标题原文，同  {@link  titleI18n } |
| `specGroups` | `object`（见下）\[\] | 是 | 规格组（如「重量」→「500g / 1kg」）。后端必发 |
| `skus` | [`GoodsDetailSku`](#goodsdetailsku)\[\] | 是 | SKU 矩阵。后端必发 |
| `fulfillments` | `string`\[\] | 是 | 支持的履约方式（自提 / 配送 …）。后端必发 |
| `price` | `number` | 否 | 展示价 = 最低 SKU 价（分） |
| `status` | `string` | 否 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `auditReason` | `string,null` | 否 | 最近一次驳回 / 强制下架的原因。 **它是商家能看到的那半边** —— 审计日志只有运营看得到， 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。 |

`merchant` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | — |
| `name` | `string` | 是 | 业务对象显示名称 |

`specGroups[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 业务对象显示名称 |
| `options` | `string`\[\] | 是 | — |

### GoodsDetailSku

{@link  GoodsDetail }  里的一条 SKU。价格是**单一价**（分），不按市场分列 —— 后端 `SkuVO` 就这一份。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 规格单号 |
| `optionValues` | `string`\[\] | 是 | 各规格组上选中的值，顺序与 `specGroups` 一致 |
| `spec` | `string` | 否 | 规格展示串（如「500g / 红」）。后端拼好下发，前端不再自己拼 |
| `price` | `number` | 是 | 售价（分） |
| `originPrice` | `number,null` | 否 | 划线价（分）。没有 = 不划线 |
| `stock` | `number` | 是 | 可售库存 |

### GoodsSkuRow

商品池里一个商品下的某个规格——**故意不是**  {@link  Sku } 。 <p>{@code Sku} 那份（预售额度/已售/截单时间）是「库存与预售」tab 的形状， 而那几个字段在真实后端压根不存在（`prd_sku` 没有这些列，见该 tab 的说明）。 商品池要的是"这个规格在每个市场卖多少钱、还有多少库存"，直接照实定义， 不要为了复用一个类型就在这里塞几个"这次数据源永远给不出来"的字段。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | SKU 号 |
| `optionValues` | `string`\[\] | 是 | 这条 SKU 的规格取值组合 |
| `spec` | `string` | 否 | 规格描述，人读的 |
| `prices` | [`Partial<Record<Market,number>>`](#partialrecordmarketnumber) | 是 | 按市场分别定价（分）。缺某个市场 = 没这个市场的价，不是 0 元 |
| `stock` | `number` | 是 | 可售库存 |
| `storeStock` | `number,null` | 否 | 门店投影（列表查询带 `storeNo` 时才有值）：该店的可用库存。 `null`/缺失 = 该 SKU 未启用分店库存，`stock` 就是它的数； 启用了但那家店没有行 = 0（**不回退总量**，与后端 V13 语义一致）。 |

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
| `status` | [`GroupStatus`](#groupstatus) | 是 | 团状态。允许的流转见 `GROUP_TRANSITIONS` |
| `endAt` | `number` | 是 | 成团截止时间（毫秒时间戳） |
| `createdAt` | `number` | 是 | 开团时间（毫秒时间戳） |

### GroupStatus

团的状态。**与后端逐字一致**（`MktGroupBuy` 的常量）。 此前这里是 `PENDING/RUNNING/SUCCESS/FAILED` —— 一套只存在于运营端 mock 里的词汇， 而后端是 `PENDING/OPEN/FORMED/FAILED/CLOSED`。两套词汇对不上， 接真后端那天每一行的状态都会渲染成「未知」，而 mock 上一直是对的。 - `PENDING` 待审核（**开关 `group.audit` 打开时才会有**；关着时建团直接进 OPEN） - `OPEN` 开团中（C 端可见、可参团）· `FORMED` 已成团 - `FAILED` 未成团/被驳回 · `CLOSED` 已关闭

枚举取值：

- `PENDING`
- `OPEN`
- `FORMED`
- `FAILED`
- `CLOSED`

### I18nText

三语文案。zh 是基准，en/ar 缺失时按 R9 回落规则展示 zh。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `zh` | `string` | 是 | 中文，**基准语言，必填** |
| `en` | `string` | 否 | 英文。缺失时按 R9 回落规则展示 zh |
| `ar` | `string` | 否 | 阿拉伯语。缺失时回落 zh |

### InAppLog

站内信的平台侧记录（发送记录页第二个 tab）。 <p><b>没有 status</b>：站内信入库即到达，不存在「发送中/失败」—— 这正是它与 NotifyLog 不能合成一张表的原因。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 消息号 |
| `receiverType` | `string` | 是 | USER / STAFF / OPS |
| `receiverNo` | `string` | 是 | 收件人编号。**不掩码**：它是平台内部标识（userNo），不是手机号邮箱 |
| `type` | [`InboxMessageType`](#inboxmessagetype) | 是 | 类型 |
| `title` | `string` | 是 | 标题 |
| `templateNo` | `string,null` | 否 | 模板号 |
| `read` | `boolean` | 是 | 已读 |
| `at` | `number` | 是 | 发生时刻 |

### InboxMessage

运营自己的通知收件箱（顶栏铃铛）。 与 NotifyLog 是两回事：这个是**发给运营的待办**（新工单/待审核/告警）， 那个是**平台发给用户**的触达留痕。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 消息号 |
| `type` | [`InboxMessageType`](#inboxmessagetype) | 是 | 类型 |
| `title` | `string` | 是 | 标题 |
| `body` | `string` | 是 | 正文 |
| `link` | `string,null` | 否 | 点开跳哪儿。空 = 只是一条通知，点不动 |
| `read` | `boolean` | 是 | 已读 |
| `at` | `number` | 是 | 发生时刻 |

### InboxMessageType

与后端 MsgMessage 的三个常量逐字一致 —— 内联在 interface 里的话对登记表不可见（规范 §D5）

枚举取值：

- `TRADE`
- `MARKETING`
- `SYSTEM`

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

### InvDocKind

台账方向。IN 入库 / OUT 出库 —— 与 shared 的 StockDocKind 同义，归一属另一批

枚举取值：

- `IN`
- `OUT`

### InvHealthKind

不健康库存的类型。**它决定这一行怎么念，也决定该找谁**

枚举取值：

- `NEGATIVE`
- `ZERO_ON_SALE`
- `STALE`

### InvHealthRow

一条不健康的库存。`kind` 决定这一行要怎么念，也决定该找谁

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `kind` | [`InvHealthKind`](#invhealthkind) | 是 | NEGATIVE 负库存 · ZERO_ON_SALE 零库存仍在架 · STALE 长期未动销 |
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
| `entries` | [`InvLedgerRow`](#invledgerrow)\[\] | 是 | 本页的台账行 |
| `nextCursor` | `number,null` | 否 | null = 没有下一页 |

### InvLedgerRow

台账一行。**不可变** —— 这里永远只有查看，没有编辑

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 行号。台账不可变，它只用来分页定位 |
| `docKind` | [`InvDocKind`](#invdockind) | 是 | `IN` 入库 / `OUT` 出库 |
| `docNo` | `string` | 是 | 单号 |
| `reasonCode` | `string` | 是 | 变动原因码 |
| `qtyDelta` | `number` | 是 | 变动量。**有符号**：入库为正、出库为负 |
| `balanceAfter` | `number` | 是 | 这一行之后的结存。台账靠它自证连续，断一行就看得出来 |
| `unitCostMinor` | `number` | 否 | 单位成本（分） |
| `occurredAt` | `string` | 是 | 业务发生时刻。**不是落库时刻** |
| `operator` | `string` | 否 | 经手人 |

### InvLinkChannel

平台 → 进销存（建品投影、上架变更）· 进销存 → 平台（库存镜像回写）

枚举取值：

- `PLATFORM_TO_INVENTORY`
- `INVENTORY_TO_PLATFORM`

### InvLinkHealth

一条投影方向的健康度（M3）。 <p>与  {@link  InvReconReport }  **不是同一件事**：那一页读的是数据（账上有多少、 实际有多少），这一条读的是链路（事件投出去了没有）。2026-09-02 的教训正是 它们被混成了一个数 —— 「待搬 1 个」看起来像一条数据要补， 实际是整条投递链停了六个小时。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | [`InvLinkChannel`](#invlinkchannel) | 是 | 通道编码 |
| `pending` | `number` | 是 | 积压总数 |
| `neverTried` | `number` | 是 | 其中一次都没被投递过的（retry = 0）—— 投递任务没在跑 |
| `retrying` | `number` | 是 | 其中投过且失败过的（retry > 0）—— 消费者在抛异常 |
| `oldestPendingAt` | `string,null` | 是 | 最老一条积压的产生时间。**这是判据，不是 pending 的条数** —— 积压 1 条可能只是正在处理的那一瞬，最老的躺了六小时才是断了 |
| `lastSentAt` | `string,null` | 是 | 最近一次成功投递。null = 从没成功过 |
| `maxRetry` | `number` | 是 | 允许的最大重试次数 |
| `lastError` | `string,null` | 是 | 最近一条错误摘要，只在 retrying > 0 时有意义 |
| `verdict` | [`InvLinkVerdict`](#invlinkverdict) | 是 | 服务端健康检查结论 |
| `delivery` | `object`（见下） \| `null` | 否 | Unified source progress; legacy mode returns null. |

### InvLinkVerdict

每一档指向不同的人 —— 这正是它比「积压 N 条」多出来的那点信息

枚举取值：

- `OK`
- `BACKLOG`
- `DISPATCHER_STALLED`
- `CONSUMER_FAILING`

### InvOrderTrace

Order-to-inventory audit chain; all quantities and source locations come from durable inventory rows.

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | Order identifier whose inventory lifecycle is being audited. |
| `reservations` | [`InvTraceReservation`](#invtracereservation)\[\] | 是 | Physical stock reservations associated with this order. |
| `syncEvents` | [`InvTraceSyncEvent`](#invtracesyncevent)\[\] | 是 | Inventory synchronization events associated with this order. |

### InvPolicy

进销存平台规则（M7）。今天只有一条 —— 见后端那个控制器的类注释： 其余几条查下来多数不该按原样做（有的本来就在拦，有的会拆掉模块边界）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reconCleanStreakRequired` | `number` | 是 | 对差要连续几轮为零，才算够格切换真相源（G3）。 **此前这个 N 根本不存在** —— 判据写的是「连续为零」，而连续多少是空的， 于是「够了没有」谁都答不了。 |

### InvReconDiff

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 哪家商家的票 |
| `storeNo` | `string` | 否 | 门店号 |
| `skuNo` | `string` | 是 | 商城侧的 SKU 号 |
| `platformQty` | `number` | 是 | 平台侧的实存（prd_sku / prd_store_stock） |
| `inventoryQty` | `number` | 是 | 进销存侧的实存（inv_stock_balance） |
| `platformHeld` | `number` | 是 | 平台侧的预留（`locked_stock`）。 **实存一样、预留不一样也是差异** —— 只比实存的话， 「已被下单占住的货」在两边对不上会被报成干净， 而切过去那些货就重新变成可售了。 |
| `inventoryHeld` | `number` | 是 | 进销存侧的预留 |

### InvReconReport

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scannedSkus` | `number` | 是 | 扫了多少个 SKU |
| `moved` | `number` | 是 | 本轮搬动了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |
| `pending` | `number` | 是 | 扫到了但**还没搬**的。**它必须是 0 才准切真相源** —— 没搬的那些在进销存侧余额是 0，切过去就是「全都卖不了」。 这一列原本不存在：`moveOne` 只算不写时故意不把没搬过的算成差异， `doRun` 又把它们计成既不 moved 也不 skipped，于是它们在报告里一个字都不出现， 而 `clean` 只看 diffs —— 闸门守着一个它没在看的东西。 |
| `clean` | `boolean` | 是 | 没有差异**且**没有待搬的。两者缺一都不算干净 |
| `diffs` | [`InvReconDiff`](#invrecondiff)\[\] | 是 | 对不上的行 |

### InvRepairResult

手动补投影的结果（M2）。字段与 `InvReconReport` 同源（后端同一个 Report）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scannedSkus` | `number` | 是 | 本次扫描 SKU 数量 |
| `moved` | `number` | 是 | 真搬了几条。**试算时恒为 0** —— 不传 apply 就一条都不该动 |
| `skipped` | `number` | 是 | 本次跳过的记录数量 |
| `pending` | `number` | 是 | 仍待搬的 |
| `clean` | `boolean` | 是 | 未发现投影差异的数量 |

### InvTraceLedger

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | Inventory ledger row identifier. |
| `docNo` | `string` | 是 | Inventory document that recorded the quantity change. |
| `reasonCode` | `string` | 是 | Business reason code for the quantity change. |
| `qtyDelta` | `number` | 是 | Signed quantity change recorded by the ledger. |
| `balanceBefore` | `number` | 是 | Stock balance before applying this ledger entry. |
| `balanceAfter` | `number` | 是 | Stock balance after applying this ledger entry. |
| `operator` | `string,null` | 是 | Human-readable operator name, if available. |
| `createdBy` | `string,null` | 是 | Creator identifier, if available. |
| `occurredAt` | `string` | 是 | Business occurrence time of the ledger entry. |

### InvTraceLine

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `lineNo` | `number` | 是 | Line number within the reservation. |
| `fulfillmentRef` | `string,null` | 是 | Fulfillment reference linked to this reservation line, if present. |
| `itemId` | `string` | 是 | Inventory item identifier. |
| `locationId` | `string` | 是 | Physical location identifier. |
| `reservedQty` | `number` | 是 | Quantity originally reserved. |
| `issuedQty` | `number` | 是 | Quantity issued to fulfillment. |
| `releasedQty` | `number` | 是 | Quantity released without being issued. |
| `outboundNo` | `string,null` | 是 | Outbound document identifier for the issued quantity. |
| `issuedAt` | `string,null` | 是 | Time the reservation line was issued, if issued. |
| `updatedBy` | `string,null` | 是 | Staff or process that last updated the issue record. |
| `issueLedger` | [`InvTraceLedger`](#invtraceledger)\[\] | 是 | Immutable inventory ledger rows generated by issuing this line. |
| `returns` | [`InvTraceReturn`](#invtracereturn)\[\] | 是 | Returns received against this reservation line. |

### InvTraceReservation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reservationId` | `string` | 是 | Inventory reservation identifier. |
| `ownerId` | `string` | 是 | Business owner identifier for this reservation. |
| `status` | `string` | 是 | Current lifecycle state of the reservation. |
| `lifecycleVersion` | `number,null` | 是 | Version used to detect concurrent reservation lifecycle updates. |
| `outboundNo` | `string,null` | 是 | Outbound document that issued the reserved stock, if issued. |
| `createdAt` | `string,null` | 是 | Reservation creation time. |
| `lines` | [`InvTraceLine`](#invtraceline)\[\] | 是 | Per-item quantities and issue/return history for this reservation. |

### InvTraceReturn

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `receiptNo` | `string` | 是 | Inventory receipt identifier for the returned quantity. |
| `afterSaleNo` | `string` | 是 | After-sale case associated with the return. |
| `qty` | `number` | 是 | Returned quantity received into inventory. |
| `quality` | `string` | 是 | Quality classification recorded at receipt. |
| `locationId` | `string` | 是 | Physical location that received the return. |
| `inboundNo` | `string` | 是 | Inbound document generated for the return. |
| `createdBy` | `string,null` | 是 | Staff or process that created the receipt. |
| `createdAt` | `string` | 是 | Receipt creation time. |

### InvTraceSyncEvent

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `aggregateId` | `string` | 是 | Identifier of the event aggregate that produced this synchronization. |
| `eventType` | `string` | 是 | Event type emitted by the inventory or order lifecycle. |
| `source` | `string` | 是 | System that requested or handled the synchronization. |
| `status` | [`InventoryTraceEventStatus`](#inventorytraceeventstatus) | 是 | Delivery state of the synchronization event. |
| `outboxMode` | [`InventoryTraceOutboxMode`](#inventorytraceoutboxmode) | 是 | Outbox implementation that persisted the event. |

### InventoryTraceEventStatus

Delivery state of an inventory trace event.

枚举取值：

- `COMPLETED`
- `PENDING`
- `FAILED`
- `WARNING`
- `SKIPPED`
- `ARCHIVED`

### InventoryTraceOutboxMode

Outbox implementation supplying the inventory trace.

枚举取值：

- `UNIFIED`
- `LEGACY`

### InvoiceRequest

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `invoiceNo` | `string` | 是 | 开票申请单号 |
| `merchantNo` | `string` | 是 | 申请商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `period` | `string` | 是 | 开票周期，与结算周期同口径 |
| `amount` | `number` | 是 | 申请开票金额（分） |
| `settledAmount` | `number` | 是 | 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 |
| `title` | `string` | 是 | 发票抬头（公司全称或个人姓名） |
| `taxNo` | `string,null` | 否 | 纳税人识别号。企业抬头必填 |
| `status` | [`InvoiceStatus`](#invoicestatus) | 是 | 开票状态 |
| `serialNo` | `string,null` | 否 | 开票后的发票流水号 |
| `appliedAt` | `string` | 是 | 申请时间 |
| `decidedAt` | `string,null` | 否 | 处理时间。未处理为 null |
| `remark` | `string,null` | 否 | 驳回原因。原样回商家 B 端 |

### InvoiceStatus

枚举取值：

- `PENDING`
- `ISSUED`
- `REJECTED`

### InvoiceTitle

平台开票抬头（P0-11）。**供应商照着它给平台开票** —— 缺公司全称或税号，票就开不出来。 <p>五个字段都是字符串，后端存成一条扁平 JSON 配置（`finance.invoice-title`）； **默认值是五项全空而不是编一份假的** —— 空着能让人立刻发现「还没配」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `companyName` | `string` | 是 | 公司全称。**必填** |
| `taxNo` | `string` | 是 | 纳税人识别号。**必填** |
| `address` | `string` | 是 | 注册地址 |
| `phone` | `string` | 是 | 注册电话 |
| `bankAccount` | `string` | 是 | 开户行与账号 |

### InvoiceTitleType

抬头类型。企业抬头必须有税号，个人抬头没有 —— 这是两条不同的校验路径。

枚举取值：

- `COMPANY`
- `PERSONAL`

### IssueTarget

发放对象类型（P-7.1.2 发放留痕）。

枚举取值：

- `ALL`
- `NEW_USER`
- `COMMUNITY`
- `SINGLE_USER`

### JobLogRow

执行日志一行。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `runId` | `string` | 是 | 这一轮的编号 |
| `jobName` | `string` | 是 | 任务的锁名（与 shedlock 同一个键）。**页面不显示它**，显示 displayName |
| `triggerType` | [`JobTriggerType`](#jobtriggertype) | 是 | 这一轮是被什么触发的。**排障第一个看它** —— 定时跑失败和人手动补跑失败要找的人不同 |
| `bizDate` | `string,null` | 是 | 业务日期。**补数跑的是历史某一天，与执行时刻不是一回事** |
| `startedAt` | `string` | 是 | 开始时刻 |
| `finishedAt` | `string,null` | 是 | 结束时刻。空 = 还没回（可能仍在跑，也可能超时了） |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `status` | [`JobStatus`](#jobstatus) | 是 | 这一轮的结局 |
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
| `lastStatus` | [`JobStatus`](#jobstatus) \| `null` | 是 | 上一轮的结局 |
| `durationMs` | `number,null` | 是 | 耗时（毫秒） |
| `detail` | `string,null` | 是 | 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 |
| `error` | `string,null` | 是 | 错误信息。**与 detail 分开**：detail 是业务说的话，这里是异常 |
| `consecutiveFailures` | `number` | 是 | **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 |
| `runCount` | `number` | 是 | 累计执行轮次 |
| `nextRunAt` | `string,null` | 是 | 下一次预计执行时刻。任务停用或已消失时为空 |
| `running` | `boolean` | 是 | 此刻正在跑 |
| `triggerPending` | `boolean` | 是 | 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 |
| `updatedBy` | `string,null` | 是 | 上次改配置的人 |

### JobStatus

一轮执行的结局。**分组比取值本身重要** —— 它决定页面上要提示人做什么。

枚举取值：

- `SUCCESS`
- `FAILED`
- `SKIPPED`
- `UNREACHABLE`
- `TIMEOUT`
- `RUNNING`

### JobTriggerType

这一轮是被什么触发的。**排障时第一个要看的就是它** —— 同一个任务，定时跑失败和人手动补跑失败，要找的人不是同一个。

枚举取值：

- `CRON`
- `MANUAL`
- `RETRY`
- `BACKFILL`

### LegalForm

主体档位 —— 与后端 `mch_entity.legal_form` 同一套取值。 **三档锁定，不再增删**：S1/S2/S3 是对这三个值的读法，不是新枚举。 V87 起 `MICRO` 改名 `NATURAL_PERSON` —— 那是通道发明的收款档位，不是法律形态， 且与法规「小微企业（有照）」重名含义相反。通道档留在 `mch_payment_merchant` 上。

枚举取值：

- `NATURAL_PERSON`
- `INDIVIDUAL`
- `ENTERPRISE`

### LevelRecomputeRun

一次会员分层重算的结果

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `at` | `number` | 是 | 按哪个时刻算的（毫秒） |
| `scanned` | `number` | 是 | 扫描了多少会员 |
| `changed` | `number` | 是 | 分层或近 90 天单数变了的人数 |
| `newlySleeping` | `number` | 是 | 其中这一轮新变成沉睡的人数。第一次上线会很大 —— 那是历史欠账一次性显形，不是事故 |
| `tookMs` | `number` | 是 | 用时（毫秒） |

### Liability

责任方（P-6.1.4）。三方之外没有第四种可能：货是商家的、点是自提点的、规则是平台的。

枚举取值：

- `PLATFORM`
- `MERCHANT`
- `PICKUP`

### LiabilityShare

赔付出资比例，百分比，**三者之和必须为 100**。 ⚠️ 矩阵 M4「售后责任归属与出资方比例」口径未定，真实后端从没实现过这个概念—— `ord_after_sale` 上没有存它的列，裁决台曾经收集这三个数字一起提交， 点「确认裁决」时被后端静默丢弃，运营以为填了就生效。**只有 finance 域的 「退款回退分账」mock 队列还在用这两个字段**（`share`/`refundSplitPending`）， 而那条队列本身也是 mock-only——`/ops/refund-split-backs` 后端不存在。 M4 有结论、这两块真正接上后端之后再把类型收紧。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `platform` | `number` | 是 | 平台 |
| `merchant` | `number` | 是 | 商家承担的份额 |
| `pickup` | `number` | 是 | 自提点承担的份额 |

### LoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `username` | `string` | 是 | 登录名 |
| `role` | [`Role`](#role) | 是 | 角色。**权限判定以后端为准**，前端只做菜单裁剪 |
| `token` | `string` | 是 | 访问令牌。STAFF 池，与 C 端、B 端账号不通用 |
| `perms` | `string`\[\] | 是 | **后端下发的权限码**（`staff.perms`）。判权以它为准。 `["*"]` = 超管通配。前端的 UI 码要先经 `UI_PERM_MAP` 翻译成后端码 再来这里查 —— 两边的粒度不同（前端 45 个、后端 14 个）， 直接比会全判 false。 |
| `merchantNo` | `string` | 否 | 商家运营（BD）等受限角色的数据域；平台全量角色为空 |
| `communityNo` | `string` | 否 | 受限角色的社区数据域 |

### LogisticsCapability

渠道的一种能力

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `capability` | [`LogisticsCapabilityKind`](#logisticscapabilitykind) | 是 | 能力种类 |
| `available` | `boolean` | 是 | 可不可用 |
| `reason` | `string,null` | 是 | 不可用的原因（「凭据没配」「推送不可用，订阅随之不可用」） |

### LogisticsCapabilityKind

物流渠道的能力种类：`SUBSCRIBE` 订阅 · `PUSH` 收推送 · `PROBE` 主动查 · `BIND` 换微信 token

枚举取值：

- `SUBSCRIBE`
- `PUSH`
- `PROBE`
- `BIND`

### LogisticsChannel

物流渠道总览的一行（`GET /ops/logistics/channels`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 渠道名：`kuaidi100` / `yto` / `wx` / `stub` |
| `enabled` | `boolean` | 是 | 配置里启用没有 |
| `capabilities` | [`LogisticsCapability`](#logisticscapability)\[\] | 是 | 每种能力可不可用 |
| `carriers` | `string`\[\] | 是 | 覆盖的承运商码；`*` = 全覆盖 |
| `routes` | `string`\[\] | 是 | 出现在哪些路由链里（`subscribe.default#1` 这种） |

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
| `kind` | [`MaterialKind`](#materialkind) | 是 | 素材形态 |
| `content` | `string` | 是 | 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） |
| `scope` | [`MaterialScope`](#materialscope) | 是 | 可见范围。**投给谁和素材本身是一件事** |
| `scopeRefs` | `string`\[\] | 是 | scope=COMMUNITY 时的社区列表；=MERCHANT 时的商家列表。ALL 时为空 |
| `langs` | `string`\[\] | 是 | 适用语言，空 = 不限 |
| `published` | `boolean` | 是 | 是否已发布。未发布的素材商家看不到 |
| `downloads` | `number` | 是 | 被下载次数，衡量素材有没有人用 |
| `createdAt` | `string` | 是 | 创建时间 |

### MaterialKind

枚举取值：

- `COPY`
- `IMAGE`
- `POSTER`
- `VIDEO`

### MaterialScope

可见范围（P-15.1.3 / 15.1.4）：一份素材投给谁，和素材本身是一件事。

枚举取值：

- `ALL`
- `COMMUNITY`
- `MERCHANT`

### MediaBackfillResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scanned` | `number` | 是 | 扫了多少个对象 |
| `inserted` | `number` | 是 | 补录了多少条 |
| `skipped` | `number` | 是 | 跳过多少个 |

### MediaBatchDetail

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `batch` | [`MediaPurgeBatch`](#mediapurgebatch) | 是 | 批次本身 |
| `items` | [`MediaReclaimable`](#mediareclaimable)\[\] | 是 | 这一批里的每一张 |

### MediaBizType

这张图当初是为什么传的。运营靠它判断「这张能不能删」

枚举取值：

- `GOODS`
- `QUAL`
- `AFTERSALE`

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
| `status` | [`MediaPurgeStatus`](#mediapurgestatus) | 是 | 状态 |
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

### MediaPurgeStatus

清理批次的状态。**PARTIAL 不是失败** —— 部分对象删成功、部分没删掉 （多半是已经不在了），归进 DONE 会让人以为清干净了，归进失败会让人重跑一遍。

枚举取值：

- `QUEUED`
- `RUNNING`
- `DONE`
- `PARTIAL`

### MediaReclaimable

待回收的一行。`reason` 是这一列的全部意义 —— 运营靠它判断「这张能不能删」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `assetKey` | `string` | 是 | 对象存储里的键。**删的就是它** —— 删错一张图，引用它的页面从此是破图 |
| `entityNo` | `string` | 是 | 上传方商家 |
| `storeNo` | `string` | 是 | 上传方门店 |
| `bizType` | [`MediaBizType`](#mediabiztype) | 是 | 这张图当初是为什么传的。运营靠它判断能不能删 |
| `bytes` | `number` | 是 | 占用字节数。回收的价值全在这个数上 |
| `width` | `number,null` | 否 | 像素宽 |
| `height` | `number,null` | 否 | 像素高 |
| `uploadedBy` | `string,null` | 否 | 上传人 |
| `createdAt` | `string,null` | 否 | 上传时刻 |
| `markedAt` | `string,null` | 否 | 被标记为可回收的时刻。**与 createdAt 分开**：刚失去引用就删，容易删掉正在编辑的东西 |
| `reason` | `string` | 是 | 「从未被引用」或「曾被『商品 G0012 · 主图』引用，… 后失去引用」 |
| `status` | `string` | 是 | 状态 |
| `thumbUrl` | `string,null` | 否 | 这一行的缩略图地址，由后端给：COS 下公开图是缩略图、私有图是 10 分钟签名地址； 本地盘下是站内相对路径。用 `mediaThumbSrc()` 取，别自己拼。 |

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
| `benefits` | [`Benefit`](#benefit)\[\] | 是 | 卡内权益列表 |
| `status` | [`MemberCardStatus`](#membercardstatus) | 是 | 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 |
| `holderCount` | `number` | 是 | 持卡人数（只读）。 ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。 |
| `createdAt` | `string` | 是 | 创建时间 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### MemberCardStatus

会员卡状态。沿用券的那套语义：停售 = ENDED，不影响已持卡人。

枚举取值：

- `DRAFT`
- `ACTIVE`
- `PAUSED`
- `ENDED`

### MemberLevelPolicy

会员分层口径（全平台统一，商家只读）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sleepDays` | `number` | 是 | 超过这么多天没下单算沉睡 |
| `loyalD90Orders` | `number` | 是 | 近 90 天至少这么多单算熟客 |
| `regularD90Orders` | `number` | 是 | 近 90 天至少这么多单算常客；再少是新客 |
| `lastRun` | [`LevelRecomputeRun`](#levelrecomputerun) \| `null` | 否 | 上一次重算。从没跑过为 null —— 页面要说「还没跑过」，而不是显示一排 0 |

### MenuFunction

服务端下发的菜单分区（`GET /ops/menu`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `functionCode` | `string` | 是 | 功能点编码，菜单树的一级节点 |
| `name` | `string` | 是 | 菜单显示名 |
| `icon` | `string,null` | 否 | 图标名；为空由前端按 functionCode 兜底 |
| `href` | `string,null` | 否 | 一级节点自身的落地路径；为空表示它只是个分组 |
| `sort` | `number` | 是 | 同级排序，小的在前 |
| `points` | [`MenuPoint`](#menupoint)\[\] | 是 | 这个功能点下的二级菜单/按钮 |

### MenuPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pointCode` | `string` | 是 | 功能点下的点编码，菜单树的二级节点 |
| `name` | `string` | 是 | 菜单显示名 |
| `groupName` | `string,null` | 否 | 侧栏里的分组标题；为空表示不分组 |
| `href` | `string,null` | 否 | 落地路径；为空表示还没有页面 |
| `uiPermCode` | `string,null` | 否 | 前端页面自己判的码。与 permCode 不同：**它管的是能不能看见按钮，不是能不能调接口** |
| `permCode` | `string,null` | 否 | 后端权限码。**null = 不受权限约束** —— 与 NOT_IMPLEMENTED 是两回事 |
| `backendStatus` | `string` | 是 | IMPLEMENTED / NOT_IMPLEMENTED / UNMAPPED |
| `uiReady` | `boolean` | 是 | 后端通了但前端页面还没做完 |
| `matrixCode` | `string,null` | 否 | 对应需求矩阵里的编号（如 `P-11.1`），用于回溯这个菜单项是哪条需求 |
| `pointType` | `string` | 是 | MENU 菜单项 / ACTION 页面内按钮级授权（菜单不渲染 ACTION） |
| `sort` | `number` | 是 | 同级排序，小的在前 |

### Merchant

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层，为引入大商家预留 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` |
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
| `legalForm` | [`LegalForm`](#legalform) \| `null` | 否 | 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」， 于是只会来问为什么。 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径（轴②）：钱先进谁的账户。 **与经营模式（`StoreMode.businessMode`，轴③）是两件事** —— 这个说钱先进谁的账户，那个说谁是销售主体。两者正交： 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。 |
| `agriProducer` | `boolean` | 否 | 农业生产者。**无照主体走归集的唯一例外** —— 平台可自开农产品收购发票，成本有合法凭证。 |
| `selfOperated` | `boolean` | 否 | 这个主体是不是**平台自己**（V329）。类目授权那几个勾选框据此决定 要不要因为「缺证件」而禁用 —— 自营不问证件，它的证件就是平台自己的证件。 **不能用 `fundsMode` 代替**：归集（AGGREGATED）同时盖着平台自营与 代销第三方，而代销那一档仍然要核验（ADR-017 §3.4：平台先担责、再向商家追偿）。 |

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
| `referrerPhone` | `string` | 否 | 推荐人手机号（V353，选填）。**奖励靠它人工发** —— 小程序里不能出现「邀请商家入驻得 X 元」（拉人头 + 奖励会被判平台型经营）， 所以规则只在官网与企微里，运营在这一栏看到号之后自己去发券 （TDD-C 端裂变与商家招募 §8.3）。 可选：绝大多数申请没有推荐人，那时这一栏整个不渲染。 |
| `category` | `string` | 是 | 主营类目。**商家自己的说法**（「食品」），不是权威码 |
| `categoryCodes` | `string`\[\] | 否 | 审核通过时授予的经营类目码 —— **平台的裁定**，与 `category` 并存。 <p>两者分开是为了留痕：追溯时要的恰恰是这两者的差 （「他说卖食品，我们批的是预包装食品」）。 |
| `desc` | `string` | 是 | 店铺简介。通过后会写进主体档案，C 端门店页读的就是它 |
| `industry` | `string` | 否 | 行业。**决定这家店能不能以小微进件** —— 审核页要看得到它， 否则运营批了一个行业不允许小微的小微商家，通道那边才会拒。 |
| `industryNote` | `string` | 否 | 商家**自己写的**行业（V360）。只在 `industry === "OTHER"` 时非空。 审核台必须显示它：意向表收这一句的全部目的就是知道 「他想做的是平台还接不了的哪一类」。不展示等于收了没收。 |
| `serviceScope` | `string` | 否 | 期望服务范围。**商家可以留空，但通过时必须确定** —— 空的后果是商家上着架却对谁都不可见，且没有任何报错。 |
| `communityNos` | `string`\[\] | 否 | 覆盖的小区。scope=COMMUNITY 时**空 = 通过之后对谁都不可见** |
| `licenses` | `string`\[\] | 否 | 已传的资质图。个体户/企业必传，自然人免 —— 缺它正是驳回的主因 |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质（V79）。**审核台看的是这一份** —— 上面的 licenses 只有图片 URL，审核员看不出「这是执照还是食品证」「什么时候过期」。 而通过之后转存进 mch_qualification 的正是它。 |
| `asPickupPoint` | `boolean` | 是 | 是否愿意承接自提点（ADR-005）。**只是意愿，不代表点已建立** |
| `status` | [`ApplyStatus`](#applystatus) | 是 | 审核状态 |
| `rejectReason` | `string` | 否 | 驳回原因。**驳回必写** —— 不写对方只能猜着改 |
| `createdAt` | `number` | 是 | 提交时间 |
| `auditedAt` | `number` | 否 | 审核完成时间。待审期间为空 |
| `onBehalf` | `boolean` | 否 | <b>这张单是运营代填的</b>（三期）。审核台要看到它 —— 资料不是商户自己录的，核验那一步该更仔细。 <p>⚠️ 看到这个标记<b>不等于你审得动</b>：后端还拦「代填的人审自己填的那一张」 （403）。BD 同时持有审核码与代填码，光靠角色配置挡不住，所以分离钉在数据上。 |
| `agreedAt` | `number` | 否 | 商户本人同意《商家服务协议》的时刻（毫秒）；<b>0 / 空 = 尚未同意</b>。 <p>代填的单子这一栏一律为空 —— 运营不能替商户勾，等他首次登录自己补。 <b>存量单子同样是空的</b>（协议勾选此前从没落过库），所以「空」不等于 「他拒绝了」，也不等于「这是代填单」：要分开看  {@link  onBehalf } 。 |

### MerchantCampaign

**商家自建的店铺活动**（`GET /ops/campaigns` 真正返回的东西）。 <p>平台对它只有治理权：看得见、能停、能归档，**不能建也不能改内容** —— 那是商家自己的经营决定（矩阵 §2.3「平台停券与停活动」）。 <p>字段对齐后端 `CampaignVO`。与  {@link  PlatformSlot }  是两个领域对象， 曾经被一根 HTTP 路径连着，见 `docs/technical/运营端营销列表契约错配.md`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `campaignNo` | `string` | 是 | 活动号。跨端唯一，平台治理与商家自己看到的是同一个 |
| `merchantNo` | `string` | 是 | 所属商家（主体号）。平台视角要按它归堆 |
| `name` | `string` | 是 | 活动名，商家自己填的。C 端会原样展示，平台治理时也按它认人 |
| `type` | [`MerchantCampaignType`](#merchantcampaigntype) | 是 | COUPON / FULL_CUT / FLASH / BUY_GIFT —— 商家能建的四种 |
| `status` | `string` | 是 | RUNNING / ENDED / PAUSED |
| `startAt` | `number` | 是 | 开始时间（毫秒时间戳） |
| `endAt` | `number` | 是 | 结束时间（毫秒时间戳） |
| `goodsNos` | `string`\[\] \| `null` | 否 | 参与的商品号。**列表上只显示条数**，明细进详情看 |

### MerchantCampaignType

商家能建的活动类型。与 shared 的 `CampaignType` 同源（b-app 建的就是它）

枚举取值：

- `COUPON`
- `FULL_CUT`
- `FLASH`
- `BUY_GIFT`

### MerchantChainRow

商家链条画像的一行（M1）。 <p>六个数字沿着「建品 → 提审 → 上架 → 建账 → 首次进货 → 持续记账」排开， 而**真正能拿去做事的是 `stuckAt`** —— 数字自己不指向任何人。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 商家主体编号 |
| `merchantName` | `string,null` | 是 | 商家展示名称 |
| `goods` | `number` | 是 | 建了几个 SPU |
| `pendingAudit` | `number` | 是 | 其中待审几个 |
| `onSale` | `number` | 是 | 其中上架几个 |
| `items` | `number` | 是 | 进销存里建了几条账 |
| `firstInbound` | `string,null` | 是 | 第一笔入库时间。null = 一次都没进过货 |
| `lastLedger` | `string,null` | 是 | 最近一笔流水时间。null = 从没记过账 |
| `stuckAt` | [`MerchantChainStuck`](#merchantchainstuck) \| `null` | 是 | 卡在哪一层。null = 这条链是通的 |

### MerchantChainStuck

卡点。**取第一个断掉的环** —— 后面几列的 0 是它的后果，不是几个独立问题

枚举取值：

- `NO_GOODS`
- `IN_AUDIT`
- `NOT_ON_SALE`
- `NO_ACCOUNT`
- `NO_INBOUND`
- `STALE_LEDGER`

### MerchantCoverage

一个主体的经营范围明细。 **三块分开，缺一块都会被读反：** - `includes` 他框了哪些地方 - `excludes` 他**明确排除**的地方 —— 混在上面一起列，运营会读成「他做这儿」，而事实正好相反 - `reachableCount` 展开之后**实际覆盖几个聚落** —— 框了什么与覆盖到什么不是一回事：   框一个街道可能展开成 30 个聚落，也可能一个都没有（那条街道下还没开通任何聚落），   而后者在只看「他框了什么」的界面上完全看不出来

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `includes` | [`CoverageArea`](#coveragearea)\[\] | 是 | 明确纳入的服务范围 |
| `excludes` | [`CoverageArea`](#coveragearea)\[\] | 是 | 明确排除的服务范围 |
| `reachableCount` | `number` | 是 | 当前可触达对象数量 |
| `reachableSample` | `string`\[\] | 是 | 覆盖到的聚落名，最多几条。只给一个数字的话，30 与 3 一样让人无从判断展开对不对 |

### MerchantDebt

商家欠款：退款追不回来时先记在账上，从后续货款里扣。 ⚠️ **与保证金方向相反**：保证金是商家的钱（平台代管、将来要退还）， 欠款是商家欠平台的。两者不能合成一个数看。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 欠款主体号 |
| `balanceMinor` | `number` | 是 | 当前欠款（分），恒 >= 0。0 = 没有欠款 |
| `txns` | [`DebtTxn`](#debttxn)\[\] | 是 | 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 |

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

### MerchantNudgeResult

主动触达商家的结果（M2）。 <p>三种结局要**分开告诉运营**：发出去了 / 今天已经提醒过了 / 这家店没人收 —— 混成一个「成功」，运营看不出区别就会一直点，而商家那头什么也没多收到。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sent` | `number` | 是 | 实际发出几条（按收件人计） |
| `alreadySentToday` | `boolean` | 是 | 今天已就同一事由提醒过。不是失败，但必须说出来 |
| `noRecipient` | `boolean` | 是 | 这家店一个能收消息的人都没有 —— 该做的是去给这家店配人 |

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
| `status` | [`PlanStatus`](#planstatus) | 是 | 状态 |
| `startAt` | `number,null` | 否 | 生效时刻 |
| `expireAt` | `number,null` | 否 | 到期时刻 |
| `grantedBy` | `string,null` | 否 | PLATFORM（运营授予）/ SELF（一期没有这条路） |
| `trialUsed` | `boolean` | 是 | 试用额度用过了 |
| `downgradedAt` | `number,null` | 否 | 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） |
| `quotaSource` | [`PlanQuotaSource`](#planquotasource) | 是 | 生效额度是哪来的。**运营必须看得出来** —— 否则「这家怎么是 5 家」只能翻审计日志 |

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
| `storeNo` | `string` | 是 | 门店编号 |
| `storeName` | `string` | 是 | 门店显示名称 |
| `role` | `string` | 是 | — |

### MerchantStatus

商家的**经营状态**。取值与后端 `mch_entity.status` 一致。 ⚠️ 它**不是入驻审核状态** —— 审核状态在申请单上（ {@link  ApplyStatus } ， PENDING/REVIEWING/APPROVED/REJECTED），后端刻意分成两张表： `mch_entity_apply` 记「这次申请审到哪了」，`mch_entity` 记「这家店现在能不能做生意」。 这里曾经把两者揉成一个字段（DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED/SUSPENDED）， 于是一家已经在正常经营、又提交了第二张执照的商家，status 该填什么无解 —— 而这正是「一人多主体」的常见情形。

枚举取值：

- `ACTIVE`
- `SUSPENDED`
- `FROZEN`

### MerchantTier

商家**分层**（规模）。对应 `mch_entity.tier`，与 shared 的 `MerchantTier` 同名同值。 ⚠️ 这里此前是 `PERSONAL \| INDIVIDUAL \| COMPANY` —— 那是**主体类型**的旧取值 （权威码见 shared 的 `MerchantSubject`：NATURAL_PERSON / INDIVIDUAL / ENTERPRISE）。 名字对得上后端字段，取值却来自另一个概念，于是这一列**永远显示不出东西**： 后端下发的 tier 要么是 null（一期没启用分层），要么是 SMALL， 而 SMALL 不在这个联合类型里，i18n 也查不到词条。 <p>如果运营界面真正想看的是**主体类型**（小微/个体户/企业），那要后端在 ops 商家 VO 里补一个字段 —— `mch_entity` 上没有 subject 列， 它在申请单与进件表上。这是另一件事，不能靠改端上的类型变出来。

枚举取值：

- `SMALL`
- `MEDIUM`
- `LARGE`

### ModeRisk

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
| `riskType` | [`ModeRiskType`](#moderisktype) | 否 | 这一行属于哪一档 —— 老后端不发时按「无票成本」处理（那是本表原本的唯一一档） |

### ModeRiskType

这一行在说哪种问题（后端 ModeRiskVO.riskType，TDD-门店经营模式与结算口径 §7）。 <p>`UNLICENSED_SELF_OPERATED` 无照主体 + 自营门店 —— 税：无进项票，成本不可税前扣除。 <p>`MODE_NOT_SET` 有照主体 + 自营门店 + 有可用收款号 —— 结算口径：本该按第三方算。 <p>**两档的处置方式不同**（前者找财务与档位，后者运营切一下就好），所以界面要分开标。

枚举取值：

- `UNLICENSED_SELF_OPERATED`
- `MODE_NOT_SET`

### MsgChannel

模板所属通道。**与后端 `SysNotifyLog` 的四个常量 + INAPP 一致**。 <p>旧值 `SUBSCRIBE/PUSH/INBOX` 来自 V20 的建表注释，而代码与种子（V141） 用的一直是这一套 —— 两套名字并存时，模板列表的通道列会显示成空白。

枚举取值：

- `SMS`
- `MAIL`
- `WXSUB`
- `PUSH`
- `INAPP`

### MsgTemplate

订阅消息模板（P-14.1.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `channel` | [`MsgChannel`](#msgchannel) | 是 | 触达渠道 |
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

### NewUserFactor

新客判定因子（P-9.2.3）。至少选一个 —— 一个都不选等于所有人都是新客。

枚举取值：

- `DEVICE`
- `PHONE`

### NotifyChannel

外发渠道。**与后端 `SysNotifyLog` 的四个常量逐字一致**。 <p>WXSUB = 微信小程序订阅消息（服务通知），target 是掩码后的 openid； PUSH = App 推送（个推/uni-push，ADR-018），target 是掩码后的 clientId。 <p><b>站内信（INAPP）不在这里</b>：它不进 `sys_notify_log`， 它自己就是一张可查的表（`notify_message`）。混进来会让人以为能在发送记录里查到它。

枚举取值：

- `SMS`
- `MAIL`
- `WXSUB`
- `PUSH`

### NotifyChannelHealth

通道体检（TDD-运营端触达中心 §4.1）。 <p><b>凭据只有「配没配」，没有值</b>：一个能在 Web 上读出生产短信密钥的接口， 泄漏一次就是全平台可群发。要改密钥去改环境变量并重启，不在这个页面上改。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | [`NotifyChannel`](#notifychannel) | 是 | 通道 |
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
| `value` | `string` | 是 | 当前规则配置值 |

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
| `channel` | [`NotifyChannel`](#notifychannel) | 是 | 通道 |
| `provider` | `string,null` | 否 | 供应商 ALI/SMTP/WECHAT/GETUI/FCM/APNS（N3）；旧行与单供应商推出为空 |
| `bizType` | `string` | 是 | OTP / OPS_INIT_PASSWORD / OPS_RESET_PASSWORD / TEST |
| `target` | `string` | 是 | 发给谁。**已脱敏** —— 这张表运营都看得到 |
| `templateCode` | `string,null` | 否 | 短信是阿里云模板号；邮件是主题 |
| `status` | [`NotifyStatus`](#notifystatus) | 是 | 状态 |
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

### NotifyStatus

发送结果。**失败也记**——只记成功的话，这张表回答不了「他为什么没收到」。

枚举取值：

- `SENT`
- `FAILED`

### OnboardingRow

进件看板的一行（`GET /ops/onboarding`）。**每主体每通道一条**。 它补的是入驻审核与收款进件两条链之间的盲区：审核通过 = 能上架卖货， 进件通过 = 能收钱。审核过了但进件没走完的商家「货照上、单照来、钱收不到」， 此前运营端没有一个跨商家的地方能看见 —— 这份看板就是那个地方。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 主体号。进件挂在主体上，一个主体下多家店共用同一条主体级进件 |
| `merchantName` | `string` | 是 | 商家名，展示用 |
| `storeNo` | `string` | 是 | 为哪家门店进的件；**空串 = 主体级默认号** |
| `payChannel` | `string` | 是 | WECHAT / ALIPAY |
| `applyStatus` | [`OnboardingStatus`](#onboardingstatus) | 是 | 进件状态。**APPLYING 有两种含义**：入驻通过时派生的占位（商家还没填过东西）， 与已发给通道等回执。两者要靠进件详情里的 `submitted` 才分得开 —— 只看这一列 会把「球在商家脚下」误读成「在等通道」。 |
| `rejectReason` | `string,null` | 是 | 被拒原因，原样给商家看；null = 没被拒 |
| `settleAccountType` | `string,null` | 是 | PERSONAL_BANK / CORPORATE_BANK；null = 还没填 |
| `settleAccountMasked` | `string,null` | 是 | 结算账号掩码 —— 真实账号只在后端 |
| `subMchid` | `string,null` | 是 | 通道侧二级商户号；null = 还没开出来 |
| `payMerchantNo` | `string,null` | 是 | 进件成功才生成的收款号业务键；**空/null = 还收不了钱** |
| `appliedAt` | `number,null` | 是 | 提交进件的时间（毫秒）；null = 还没提交（占位记录） |
| `ageMs` | `number,null` | 是 | 从提交到现在的停留时长（毫秒）；null = 还没提交。越大越该有人去问 |
| `canReceiveMoney` | `boolean` | 是 | applyStatus === "ACTIVE" |

### OnboardingStatus

进件（收款开户）状态：占位 / 审核中 / 已开通 / 被拒 / 冻结。

枚举取值：

- `NONE`
- `APPLYING`
- `ACTIVE`
- `REJECTED`
- `FROZEN`

### OpsEnrollment

一份报名（s30 审核表的一行）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `enrollmentNo` | `string` | 是 | 报名单号 |
| `activityNo` | `string` | 是 | 平台活动 |
| `entityNo` | `string` | 是 | 商家 |
| `merchantName` | `string` | 是 | 商家名 |
| `goodsNos` | `string`\[\] | 是 | 报名的货 |
| `quota` | `number` | 是 | 报的份数 |
| `quotaUsed` | `number` | 是 | 已用份数 |
| `platformMaxMinor` | `number` | 是 | 最多平台出资（分） |
| `merchantMaxMinor` | `number` | 是 | 最多商家承担（分） |
| `rating` | `number` | 是 | 商家评分 |
| `status` | `string` | 是 | SUBMITTED / APPROVED / REJECTED / WITHDRAWN |
| `rejectReason` | `string,null` | 是 | 驳回理由 |
| `reviewedAt` | `number,null` | 是 | 审核时间 |
| `createdAt` | `number` | 是 | 提交时间 |

### OpsMember

运营侧看到的一条会员（P8）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberNo` | `string` | 是 | 会员号（某商家下的一条会员关系） |
| `personNo` | `string` | 是 | 平台人档号。**一份人档串起几家商家的会员关系** —— 跨商家查同一个人靠它 |
| `phoneTail` | `string,null` | 是 | 手机号后四位。**只有后四位** —— 要完整号得走 revealMemberPhone：单独权限码、必填理由、每次留痕 |
| `entityNo` | `string` | 是 | 所属商家 |
| `entityName` | `string` | 是 | 商家名 |
| `status` | `string` | 是 | 状态 |
| `source` | `string` | 是 | 这个会员是怎么来的 |
| `level` | `string,null` | 是 | 会员等级。空 = 商家没开分层 |
| `orderCount` | `number` | 是 | 累计下单数 |
| `totalSpentMinor` | `number` | 是 | 累计消费（分） |
| `reachOptOut` | `boolean` | 是 | 已退订。**退订的人不进任何受众** —— 运营排查「怎么没收到」第一个看它 |
| `joinedAt` | `number` | 是 | 成为会员的时刻 |

### OpsPerson

人档：一份人档串起几家商家的会员关系 —— 这正是它存在的理由

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `personNo` | `string` | 是 | 平台人档号 |
| `phoneTail` | `string,null` | 是 | 手机号后四位。**永远不给完整号** |
| `userNo` | `string,null` | 是 | 用户号 |
| `memberships` | [`OpsMember`](#opsmember)\[\] | 是 | 他在各商家的会员关系。**一份人档串起几家** —— 这正是人档存在的理由 |
| `merges` | `string`\[\] | 是 | 合并过的人档号。合并不可逆，留痕是唯一的回溯手段 |

### OpsPlatformActivity

平台活动（原型 s29 · s30）。与商家活动同一个模型；多出来的只有出资、预算、报名。 出资比例用万分比：10000 全额 / 5000 一半 / 0 不出。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `activityNo` | `string` | 是 | 活动号 |
| `name` | `string` | 是 | 名称 |
| `triggerType` | `string` | 是 | 触发：NONE 立减 / AMOUNT 满额 / QTY 满件 |
| `triggerAmountMinor` | `number,null` | 是 | 满多少（分） |
| `triggerQty` | `number,null` | 是 | 满几件 |
| `benefitType` | `string` | 是 | 优惠方式（现只有 CUT） |
| `benefitAmountMinor` | `number,null` | 是 | 减多少（分） |
| `startAt` | `number,null` | 是 | 活动开始 |
| `endAt` | `number,null` | 是 | 活动结束 |
| `enrollDeadline` | `number,null` | 是 | 报名截止 |
| `platformShareBp` | `number` | 是 | 平台出资万分比 |
| `budgetMinor` | `number,null` | 是 | 平台预算（分） |
| `reservedMinor` | `number` | 是 | 已通过的报名占掉的预算（分） |
| `perOrderPlatformMinor` | `number` | 是 | 每单平台最多补贴（分） |
| `perOrderMerchantMinor` | `number` | 是 | 每单商家最多承担（分） |
| `enrollRule` | [`PlatformEnrollRule`](#platformenrollrule) | 是 | 报名门槛 |
| `status` | `string` | 是 | DRAFT / RUNNING / ENDED |
| `submitted` | `number` | 是 | 待审 |
| `approved` | `number` | 是 | 已通过 |
| `rejected` | `number` | 是 | 已驳回 |

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
| `quotaReleased` | `number` | 否 | 被关单退回的份数（待办设计 P4）。`quotaUsed` 已经不含它们 —— 这个数让运营知道「真实卖出」与「曾经被占过」差多少 |
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
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。允许的流转见 `ORDER_TRANSITIONS` |
| `merchantNo` | `string` | 是 | 归属商家。一个子订单只属于一个商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `communityNo` | `string` | 是 | 归属社区。运营按社区做数据域隔离 |
| `communityName` | `string` | 是 | 社区名快照 |
| `pickupNo` | `string` | 否 | 自提点编号；配送/快递单为空 |
| `fulfillType` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式 |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 是 | 流量来源。**决定平台费率档**（P-12.1.7） |
| `buyerNickname` | `string` | 是 | 买家昵称 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行 |
| `payAmount` | `number` | 是 | 实付，最小货币单位（分） |
| `createdAt` | `string` | 是 | 下单时间（ISO 8601 字符串） |
| `paidAt` | `string,null` | 否 | 支付时间。未支付为 null |
| `statusAt` | `string` | 否 | 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 |

### OrderEarningInspection

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单内部业务编号 |
| `subOrderNo` | `string` | 是 | 子订单内部业务编号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `currency` | `string` | 是 | ISO货币代码，金额必须结合此字段解释 |
| `snapshot` | `object`（见下） | 是 | 业务处理时冻结的权威快照 |
| `ledger` | `object`（见下） \| `null` | 是 | 权威账务查询结果 |

`snapshot` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `schemaVersion` | `number` | 是 | — |
| `ruleTraces` | `object`（见下）\[\] | 是 | — |
| `lines` | `object`（见下）\[\] | 是 | — |

`snapshot.ruleTraces[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `categoryNo` | `string,null` | 是 | 商品分类编号 |
| `matchedScope` | `string` | 是 | — |
| `revision` | `number` | 是 | 当前记录版本号，用于并发控制 |

`snapshot.lines[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `goodsNo` | `string` | 是 | — |
| `categoryNo` | `string,null` | 是 | 商品分类编号 |
| `quantity` | `number` | 是 | — |
| `goodsCashMinor` | `number` | 是 | — |
| `rule` | `object`（见下） | 是 | — |

`snapshot.lines[].rule` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mode` | [`PointsEarningRuleMode`](#pointsearningrulemode) | 是 | 规则模式编码 |
| `value` | `number` | 是 | 当前规则配置值 |

### OrderException

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `order` | [`Order`](#order) | 是 | 关联的订单快照。异常单是**实时算出来的视图**，不落表 |
| `kind` | [`ExceptionKind`](#exceptionkind) | 是 | 异常成因 |
| `stuckMinutes` | `number` | 是 | 已经卡了多少分钟（从进入当前状态算起，mock 用 createdAt/paidAt 近似） |
| `thresholdMinutes` | `number` | 是 | 该状态允许卡多久（分钟），用于在界面上说明"为什么它算异常" |

### OrderIntervention

人工干预的留痕。改状态这件事必须留下是谁、为什么。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 被干预的订单 |
| `from` | [`OrderStatus`](#orderstatus) | 是 | 原状态 |
| `to` | [`OrderStatus`](#orderstatus) | 是 | 改为的状态。**必须是 `ORDER_TRANSITIONS` 允许的迁移** |
| `remark` | `string` | 是 | 干预原因，必填 —— 改状态这件事必须说清楚为什么 |
| `operator` | `string` | 是 | 操作人（STAFF 账号） |
| `at` | `string` | 是 | 操作时间 |

### OrderItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | SKU 单号 |
| `title` | `string` | 是 | 下单时的商品标题快照 |
| `qty` | `number` | 是 | 件数 |
| `price` | `number` | 是 | 最小货币单位（分） |

### OrderPointsInspection

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `bindingPhase` | `string,null` | 是 | 源绑定当前阶段，无绑定为空 |
| `bindingVersion` | `number` | 是 | 源绑定版本，无绑定为空 |
| `placementPhase` | `string,null` | 是 | 下单工作流阶段，无记录为空 |
| `placementVersion` | `number` | 是 | 下单工作流版本，无记录为空 |
| `originalHoldNo` | `string,null` | 是 | 原积分占用编号，不生成新编号替换 |
| `refundPhases` | `string`\[\] | 是 | 该子单所有售后执行阶段 |
| `pendingSourceEvents` | `number` | 是 | 尚未确认送达的源事实数量 |
| `paymentVerified` | `boolean` | 是 | 原支付证据是否核验成功 |
| `differences` | `string`\[\] | 是 | 只读一致性差异项，不返回敏感正文 |

### OrderPointsIntegrationPolicy

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `scene` | [`OrderPointsIntegrationQScene`](#orderpointsintegrationqscene) | 是 | 线上或线下交易场景 |
| `mode` | [`OrderPointsIntegrationPolicyMode`](#orderpointsintegrationpolicymode) | 是 | 服务端参与模式或已发布回退规则模式 |
| `earnEnabled` | `boolean` | 是 | 是否接收新的返分承诺 |
| `redeemEnabled` | `boolean` | 是 | 是否接收新的抵扣承诺 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `actor` | `string,null` | 是 | 服务端取得的操作人编号 |
| `reason` | `string,null` | 是 | 服务端不可用原因或操作审计原因 |
| `changedAt` | `number` | 是 | 服务端配置变更时间，Unix 毫秒 |

### OrderPointsIntegrationPolicyMode

OrderPointsIntegrationPolicy 的 mode 取值域，按服务端契约校验。

枚举取值：

- `DETACHED`
- `ACTIVE`
- `DRAINING`

### OrderPointsIntegrationQScene

OrderPointsIntegrationQ 的 scene 取值域，按服务端契约校验。

枚举取值：

- `ONLINE`
- `OFFLINE`

### OrderPointsManualRefundPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `string,null` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `createdAt` | `number` | 是 | 服务端创建时间，Unix 毫秒 |

### OrderPointsRefundActionResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `status` | `string` | 是 | 服务端业务状态 |

### OrderPointsStoreObligations

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `known` | `boolean` | 是 | 核验是否完整可用；false 时禁止按零义务判断 |
| `activePolicies` | `number` | 是 | 仍处于 ACTIVE 的场景策略数量 |
| `openBindings` | `number` | 是 | 尚未关闭的源订单绑定数量 |
| `sourceSettled` | `boolean` | 是 | 源端义务是否全部结清 |
| `pay` | `object`（见下） | 是 | 支付侧义务摘要，不可用时为 null |

`pay` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `known` | `boolean` | 是 | 核验是否完整可用；false 时禁止按零义务判断 |
| `openHolds` | `number` | 是 | 未释放的原占用数量 |
| `pendingPromises` | `number` | 是 | 未完成的原承诺数量 |
| `openRefunds` | `number` | 是 | 未完成的原退款数量 |
| `openStatements` | `number` | 是 | 未完成的门店结算单数量 |
| `unknownFunds` | `number` | 是 | 资金结果未知的原指令数量 |
| `pendingEvents` | `number` | 是 | 未处理完的原事件数量 |

### OrderStatus

订单状态。**抽象状态，与履约方式无关**，三端同一套。 ⚠️ 这里曾经是另一套：`PENDING_PAY` / `PREPARING` / `DELIVERING` / `AFTER_SALE`。 同一条订单，运营端叫一个名字、C/B 端叫另一个、库里又是第三个 —— 而 ops-web 只跑 mock，所以三套并存了很久没人发现。 ⚠️ 后来又有过 `SHIPPED` / `ARRIVED` 两个值。它们**不是状态**， 是「状态 × 履约方式」的组合冒充状态 —— 代价是每加一种履约就要加一批状态。 现在下发抽象状态 + 履约方式，显示成什么由展示层决定 （见《订单状态-统一整理》与 shared 的 `orderView`）。

枚举取值：

- `POINTS_PROCESSING`
- `WAIT_PAY`
- `WAIT_OFFLINE_PAY`
- `PAID`
- `FULFILLING`
- `COMPLETED`
- `CANCELLED`
- `REFUNDED`

### OtpTestPhone

测试号固定验证码白名单的一条（TDD-测试号固定验证码）。 命中的号请求验证码时**不发真实短信**，码恒为 `code`。唯一用途是苹果审核 —— 审核员在美国，收不到中国短信，而 App 的登录是手机号 + 验证码。 **这里的每一行都是一把能登进那个手机号账号的钥匙。** 真正拦住「拿它登进别人的店」 的不是权限码，是后端那条「拒绝录入已存在账号的手机号」—— 页面上要把这件事说清楚， 否则运营会以为它只是一个普通的配置项。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 库内自增 id。启停与删除按它定位（手机号会被改，id 不会） |
| `phone` | `string` | 是 | 11 位大陆手机号 |
| `code` | `string` | 是 | 固定验证码。**明文显示** —— 不显示的话就没法填进苹果审核资料，而那是这张表的全部意义 |
| `enabled` | `boolean` | 是 | 启用中。**停用即时生效**，不等缓存过期、不等重启 |
| `remark` | `string` | 否 | 这一条为什么存在。空 = 没写（不是「没有原因」） |

### OutOfRangeAction

超区处置：不配送，或加价配送。

枚举取值：

- `REJECT`
- `SURCHARGE`

### OutOfRangeRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `region` | `string` | 是 | 省或直辖市名 |
| `action` | [`OutOfRangeAction`](#outofrangeaction) | 是 | 处置方式：不配送 or 加价配送 |
| `surcharge` | `number` | 是 | 加价金额（分）。REJECT 时必须为 0 —— 不配送就没有"加多少钱"这回事 |

### OverdueAction

逾期处置方式（P-5.1.4）。

枚举取值：

- `POSTPONE`
- `VOID`

### OverdueRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `action` | [`OverdueAction`](#overdueaction) | 是 | 逾期处置方式：顺延 or 作废 |
| `graceHours` | `number` | 是 | 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。 校验在 mock/后端两侧都有，不只是表单提示。 |
| `maxPostpone` | `number` | 是 | 顺延次数上限（action=POSTPONE 时有意义） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### Partial<Record<("zh"|"en"|"ar"),string>>

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `zh` | `string` | 否 | — |
| `en` | `string` | 否 | — |
| `ar` | `string` | 否 | — |

### Partial<Record<Market,number>>

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `CN` | `number` | 否 | — |
| `SG` | `number` | 否 | — |

### Partial<Record<PointsBucket,number>>

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ISSUED` | `number` | 否 | — |
| `ISSUABLE` | `number` | 否 | — |
| `PENDING` | `number` | 否 | — |
| `AVAILABLE` | `number` | 否 | — |
| `HELD` | `number` | 否 | — |
| `REFUND_RESERVED` | `number` | 否 | — |
| `RECIRCULATABLE` | `number` | 否 | — |

### PayChannel

支付渠道。一期只接微信支付，枚举先留出位置（改枚举比改结构便宜）。

枚举取值：

- `WECHAT`
- `ALIPAY`
- `BALANCE`

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
| `currentRate` | [`PayChannelRateVersion`](#paychannelrateversion) \| `null` | 是 | 此刻生效的那一版；**一条都没配时为 null**，要显示成「未配置」而不是 0 |
| `rates` | [`PayChannelRateVersion`](#paychannelrateversion)\[\] | 是 | 全部版本，按生效时间倒序 |

### PayQuota

一个收款号的额度。主体级一条 + 每个已进件门店一条。 <p>**空列表 ≠ 额度为零**：空表示这家还没进过件，界面上必须画成两样东西 —— 读成「额度为零」的运营会去调大额度，而实际该做的是先走进件。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 空串 = 主体级默认收款号 |
| `payChannel` | `string,null` | 否 | WECHAT / ALIPAY |
| `applyStatus` | `string,null` | 否 | 进件状态；未 ACTIVE 时额度设了也不生效 |
| `limitMinor` | `number` | 是 | 上限（分）；**0 = 未设置，不拦**，不是「额度为零」 |
| `usedMinor` | `number` | 是 | 已用（分）。支付累加出来的事实，运营改不了 |

### Payout

放款记录（V391 / TDD-账期推进与放款记录）：**账期批次 × 收款号，一笔网银转账一条**。 凭证号与银行流水挂在它上面，不再挂在逐张结算单上。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payoutNo` | `string` | 是 | 放款单号（PO…） |
| `batchNo` | `string` | 是 | 所属账期批次 |
| `entityNo` | `string` | 是 | 收款主体业务键 |
| `payMerchantNo` | `string,null` | 是 | 收款号（分组键）。自营一主体一账户时为空 |
| `accountName` | `string,null` | 是 | 付款时快照：户名。银行要户名，而账号会改 |
| `bankName` | `string,null` | 是 | 付款时快照：开户行 |
| `bankBranch` | `string,null` | 是 | 付款时快照：支行 |
| `accountNoMasked` | `string,null` | 是 | 付款时快照：账号掩码。明文只在导出付款清单那一刻存在 |
| `amountMinor` | `number` | 是 | 本笔金额（分）= 组内结算单 net 之和 |
| `billCount` | `number` | 是 | 本笔包含几张结算单 |
| `currency` | `string` | 是 | 币种 |
| `status` | [`PayoutStatus`](#payoutstatus) | 是 | 放款状态。只有一个方向，见 PayoutStatus |
| `channel` | [`PayoutChannel`](#payoutchannel) | 是 | 放款通道。一期全是 MANUAL（网银手工） |
| `paymentRef` | `string,null` | 是 | 凭证号（网银流水号）。登记付款时必填 |
| `bankFlowNo` | `string,null` | 是 | 对上的银行流水号。出款对账勾上时写 |
| `exportedAt` | `number,null` | 是 | 导出进付款清单的时刻（毫秒） |
| `paidAt` | `number,null` | 是 | 登记凭证的时刻（毫秒） |
| `paidBy` | `string,null` | 是 | 登记凭证的操作人 |
| `matchedAt` | `number,null` | 是 | 银行流水勾上的时刻（毫秒） |
| `failReason` | `string,null` | 是 | 退回原因。给运营看，也给商家看 |
| `settleNos` | `string`\[\] | 是 | 本笔包含的结算单 |

### PayoutAccount

供应商收款账户（V358，ADR-011 自营供应商模式）。 **没有明文账号字段** —— 后端只回掩码。审核这件事本身是资金重定向： 通过之后这个主体下一期的货款就打到这张卡。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `accountNo` | `string` | 是 | 平台内部单号，审核时按它定位 |
| `entityNo` | `string` | 是 | 供应商主体。**账户挂主体不挂门店** —— 收款是主体的事，门店只是统计维度 |
| `accountType` | `string` | 是 | PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公 |
| `accountName` | `string` | 是 | 户名。**必须等于营业执照主体名** —— 后端硬校验，对不上提交就被拒 |
| `accountMasked` | `string` | 是 | 账号掩码，只留尾四位 |
| `bankName` | `string,null` | 否 | 开户银行。商家可不填，所以可能为空 |
| `bankBranch` | `string,null` | 否 | 开户支行。同上，转账时财务据它核对 |
| `status` | `string` | 是 | PENDING 待审 / ACTIVE 生效中 / REJECTED 已驳回 / DISABLED 已被新卡顶替 |
| `auditRemark` | `string,null` | 否 | 驳回原因，原样回商家 |
| `auditedAt` | `number,null` | 否 | 审核时刻（毫秒）。未审为空 —— 与「审过但没写原因」是两回事 |

### PayoutBlockedRow

本该付、但缺条件的一行。**必须显示** —— 不然财务看不出这一期怎么少了一家

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | 供应商主体号 |
| `merchantName` | `string` | 是 | 商家名 |
| `amountMinor` | `number` | 是 | 这一期因此没付出去多少（分） |
| `billCount` | `number` | 是 | 涉及几张结算单 |
| `reason` | `string` | 是 | 为什么没进清单。给的是可执行的原因：缺票 / 未对账 / 无收款账户 |

### PayoutChannel

放款通道。一期全是网银手工；BANK_API 预留，接口来了只换 EXPORTED→PAID 那一步

枚举取值：

- `MANUAL`
- `BANK_API`

### PayoutList

付款清单。`totalMinor` **不含 blocked** —— 那笔钱这次付不出去

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PayoutRow`](#payoutrow)\[\] | 是 | 可付的 |
| `blocked` | [`PayoutBlockedRow`](#payoutblockedrow)\[\] | 是 | 被挡下的 |
| `totalMinor` | `number` | 是 | rows 的合计（分） |

### PayoutRow

付款清单的一行（ADR-011 · P2）。财务拿它去网银转账。 ⚠️ **这是全站唯一带明文账号的响应**。拿到之后立刻写进导出文件， 不要存进任何全局 state、不要打日志。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleDocumentNos` | `string`\[\] | 否 | 关联结算单的展示单号列表 |
| `payoutNo` | `string,null` | 否 | 放款单号（V391）。**有号的按号回填凭证**（`payPayout`）， 没号的是存量老路（逐张 `payPayable`）。两条路并存到存量清零。 |
| `entityNo` | `string` | 是 | 供应商主体号 |
| `merchantName` | `string` | 是 | 商家名。查不到时回落成主体号 |
| `accountType` | `string` | 是 | PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公 |
| `accountName` | `string` | 是 | 户名。已经过「必须等于营业执照主体名」的硬校验 |
| `accountNumber` | `string` | 是 | **明文账号**。只在这一次响应里存在 |
| `bankName` | `string,null` | 是 | 开户银行，可能为空 |
| `bankBranch` | `string,null` | 是 | 开户支行，可能为空 |
| `amountMinor` | `number` | 是 | 本次该付多少（分） |
| `billCount` | `number` | 是 | 这一行覆盖了几张结算单 |
| `remark` | `string` | 是 | 银行附言。**回读银行流水时靠它勾对**，所以不是一句人话 |
| `settleNos` | `string`\[\] | 是 | 覆盖的结算单号。回填凭证号时按它逐张登记 |

### PayoutStatus

只有一个方向。MATCHED 只能由出款对账轴写（银行流水勾上），不给人工入口

枚举取值：

- `PENDING`
- `EXPORTED`
- `PAID`
- `MATCHED`
- `FAILED`

### PickupFeeMode

计费口径。**目前只有 PLATFORM 点有值** —— STORE 与 NEIGHBOR 恒为 NONE。 ⚠️ 这与需求矩阵里「常驻点收履约服务费」看起来矛盾，其实是同一件未决事情的两个阶段： **B9（履约服务费口径：按单/按件/保底）在待完成清单里明确标着「未定」**， 所以 STORE 的费率还没开启；PLATFORM 点是线下逐点协商的，先落了字段。 B9 定了之后，STORE 才会有 PER_ITEM 或 RATE。

枚举取值：

- `NONE`
- `PER_ITEM`
- `RATE`

### PickupPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称 |
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。 ⚠️ 这里此前只有 STORE\|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点， 而它的费率规则与常驻点完全不同。 |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 |
| `status` | [`PickupStatus`](#pickupstatus) | 是 | 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 |
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

### PickupPointType

自提点类型。**与 shared 的 `PickupPointType` 同名同值** —— 此前这里叫 `PickupType`，是同一个概念的第二个名字。 一个领域概念只能有一个词（见 docs/requirements/项目词典.md）。

枚举取值：

- `STORE`
- `NEIGHBOR`
- `PLATFORM`

### PickupStatus

PENDING/REJECTED（P1）：商家自建点的审核态 —— 先审后用，地址要印在买家取货页上

枚举取值：

- `ACTIVE`
- `SUSPENDED`
- `MIGRATING`
- `PENDING`
- `REJECTED`

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

### PlanQuotaSource

生效额度的来源。运营必须看得出这个数是哪来的 —— 否则「这家怎么是 5 家？」只能靠翻审计日志回答。

枚举取值：

- `PLAN`
- `OVERRIDE`
- `CONFIG`

### PlanStatus

订阅状态。 `GRACE`（宽限期，7 天）**能力全保留** —— 到期当天就压店的话， 一次忘记续费等于让他的店在客户面前消失，而他往往正在门店里忙。 宽限期是给「人」的缓冲，不是给系统的。

枚举取值：

- `ACTIVE`
- `GRACE`
- `EXPIRED`

### PlanUpgradeSignal

升档信号的一行（`GET /ops/merchant-plans/upgrade-signals`）。 **按 owner 分组而不是按主体**：「同一个人开了两个主体」正是要找的人 —— 他已经在多店经营，只是绕过了额度。主体表上没有联系电话（那在申请单上）， 所以这里只给 owner 号，销售拿它去后台查人。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ownerUserNo` | `string` | 是 | 店主的用户号。要联系他升档时用 |
| `entityNos` | `string`\[\] | 是 | 命中的商家号 |
| `entityNames` | `string`\[\] | 是 | 命中的商家名 |
| `entityCount` | `number` | 是 | 命中几家 |

### PlatformEnrollRule

平台活动的报名门槛（原型 s29「报名门槛」「类目」「城市」）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minRating` | `number,null` | 是 | 评分下限；空 = 不限 |
| `noViolation` | `boolean` | 是 | 是否要求无违规 |
| `categoryNos` | `string`\[\] | 是 | 只收这些类目的货 |
| `cityCodes` | `string`\[\] | 是 | 只收这些城市的店（暂只展示、不校验） |

### PointsAccountView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `snapshot` | `object`（见下） | 是 | 服务端冻结的账务或准备快照 |
| `exists` | `boolean` | 是 | 账户是否已经创建，未创建不代表查询失败 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

`snapshot` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `buckets` | [`Partial<Record<PointsBucket,number>>`](#partialrecordpointsbucketnumber) | 是 | 按账本分桶保存的积分余额 |
| `frozen` | `boolean` | 是 | 是否冻结该账户新增使用 |
| `expireAt` | `number,null` | 是 | 服务端计算的过期时间，Unix 毫秒；无则为空 |
| `expiryRevision` | `number` | 是 | 账户失效计划修订号 |
| `lastActiveAt` | `number` | 是 | 最近有效账务活动时间，Unix 毫秒 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `holderType` | [`PointsHolder`](#pointsholder) | 是 | 账本持有人类型 |
| `holderNo` | `string` | 是 | 账本持有人编号，与持有人类型联合定位 |

### PointsAgreement

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `agreementNo` | `string` | 是 | 门店接受协议编号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `merchantNo` | `string` | 是 | 门店归属主体编号，不作为积分隔离维度 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `signedBy` | `string` | 是 | 签署该门店协议的主体属主 |
| `termsVersion` | `string` | 是 | 接受的条款版本 |
| `termsHash` | `string` | 是 | 接受条款的内容摘要 |
| `termsText` | `string` | 是 | 接受时冻结的条款正文 |
| `settleMode` | `string` | 是 | 门店按单或按期结算方式 |
| `path` | `string` | 是 | 冻结的直连或归集资金路径 |
| `channel` | `string` | 是 | 冻结的支付通道编码 |
| `accountVersion` | `string` | 是 | 冻结的收款账户版本 |
| `state` | `string` | 是 | 服务端处理状态 |

### PointsAgreementPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `agreement` | [`PointsAgreement`](#pointsagreement) | 是 | 服务端冻结的门店协议快照 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsChangeResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `action` | `string` | 是 | 请求执行的业务动作 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |

### PointsEarningRuleAction

Rule history action, including imported revisions.

枚举取值：

- `IMPORT`
- `SAVE`
- `PUBLISH`
- `DISABLE`

### PointsEarningRuleMode

Points earning rule calculation mode.

枚举取值：

- `RATIO`
- `FIXED`

### PointsEventPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsEventView`](#pointseventview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

### PointsEventView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `subOrderNo` | `string,null` | 否 | 子订单内部业务编号 |
| `reason` | `string,null` | 否 | 业务原因或阻断说明 |
| `id` | `number` | 是 | 持久记录主键 |
| `eventNo` | `string` | 是 | 源事实事件编号 |
| `kind` | `string` | 是 | 原业务持仓或事件种类 |
| `state` | `string` | 是 | 服务端处理状态 |
| `attempt` | `number` | 是 | 原事件投递次数 |
| `nextAttempt` | `number` | 是 | 下一允许重试时间，Unix 毫秒 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsExecution

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 持久资金指令编号 |
| `status` | [`PointsExecutionStatus`](#pointsexecutionstatus) | 是 | 服务端业务状态 |
| `confirmedMinor` | `number` | 是 | 已核验入账金额，最小货币单位 |
| `replayed` | `boolean` | 是 | 是否返回原请求的幂等结果 |

### PointsExecutionStatus

PointsExecution 的 status 取值域，按服务端契约校验。

枚举取值：

- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

### PointsFallback

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `revision` | `number` | 是 | 不可变修订版本 |
| `mode` | [`PointsFallbackMode`](#pointsfallbackmode) | 是 | 服务端参与模式或已发布回退规则模式 |
| `value` | `number` | 是 | 回退规则配置值 |

### PointsFallbackMode

PointsFallback 的 mode 取值域，按服务端契约校验。

枚举取值：

- `FIXED`
- `RATIO`

### PointsFlow

枚举取值：

- `EARN`
- `REDEEM`
- `BOTH`

### PointsFundsView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `operation` | [`PointsRecoveryOperation`](#pointsrecoveryoperation) | 是 | 服务端接受的操作类型 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `grossMinor` | `number` | 是 | 原退款金额，最小货币单位 |
| `offsetMinor` | `number` | 是 | 已抵销金额，最小货币单位 |
| `cashMinor` | `number` | 是 | 实际现金退款金额，最小货币单位 |

### PointsHolder

枚举取值：

- `MINT`
- `ISSUE`
- `USER`
- `STORE`
- `RECEIVE`

### PointsOperation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestId` | `string` | 是 | 幂等操作请求号，重试必须复用 |
| `operation` | `string` | 是 | 服务端接受的操作类型 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |

### PointsOverview

Four independent funding buckets, with non-cash store receivables reported separately.

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型，不按不同类型合并余额 |
| `market` | `string` | 是 | 市场 |
| `currency` | `string` | 是 | ISO货币代码 |
| `scale` | `number` | 是 | 金额最小单位的小数位数 |
| `ratePoints` | `number` | 是 | 兑换比例的积分单位 |
| `rateMinor` | `number` | 是 | 兑换比例的最小货币单位 |
| `uMinor` | `number` | 是 | 发行备款 U，最小货币单位 |
| `cMinor` | `number` | 是 | 积分负债对应资金 C，最小货币单位 |
| `dMinor` | `number` | 是 | 待兑付资金 D，最小货币单位 |
| `bMinor` | `number` | 是 | 退款备付 B，最小货币单位 |
| `reservedBMinor` | `number` | 是 | 已承诺退款的 B 预留 |
| `lockedUMinor` | `number` | 是 | 待决提取占用的 U |
| `lockedBMinor` | `number` | 是 | 待决提取占用的 B |
| `receivableMinor` | `number` | 是 | 门店未回收应收 R，不属于现金 |

### PointsPlanAction

PointsPlan 的 action 取值域，按服务端契约校验。

枚举取值：

- `TOPUP_U`
- `TOPUP_B`
- `TRANSFER_U_TO_B`

### PointsPlanPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsPlanView`](#pointsplanview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

### PointsPlanView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planNo` | `string` | 是 | 周期计划编号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `action` | [`PointsPlanAction`](#pointsplanaction) | 是 | 请求执行的业务动作 |
| `amountMinor` | `number` | 是 | 本次资金金额，最小货币单位 |
| `anchor` | `string` | 是 | 计划锚点时间，Unix 毫秒 |
| `everyDays` | `number` | 是 | 周期天数 |
| `enabled` | `boolean` | 是 | 是否允许新增对应业务 |
| `id` | `number` | 是 | 持久记录主键 |
| `period` | `string` | 是 | 本次生成的周期序号 |
| `nextPeriod` | `string,null` | 是 | 下一待生成周期序号 |
| `state` | `string` | 是 | 服务端处理状态 |
| `requestNo` | `string,null` | 是 | 原资金或申请请求号 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsPoolAction

枚举取值：

- `TOPUP_U`
- `TOPUP_B`
- `TRANSFER_U_TO_B`
- `WITHDRAW_U`
- `WITHDRAW_B`

### PointsPositionKind

枚举取值：

- `GRANT`
- `REDEEM`
- `REFUND`
- `PAYOUT`
- `STATEMENT`
- `TRANSFER`

### PointsPositionView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `kind` | [`PointsPositionKind`](#pointspositionkind) | 是 | 原业务持仓或事件种类 |
| `reference` | `string` | 是 | 原业务引用编号 |
| `state` | `string` | 是 | 服务端处理状态 |
| `originalPoints` | `number` | 是 | 原持仓积分总数 |
| `processedPoints` | `number` | 是 | 该持仓已处理积分数 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsReceivable

Explicit receivable fields; no original bank instructions or posting payloads.

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 分页游标 ID |
| `receivableNo` | `string` | 是 | 应收编号 |
| `storeNo` | `string` | 是 | 门店编号 |
| `state` | `string` | 是 | 应收状态 |
| `originalMinor` | `number` | 是 | 原始应收金额 |
| `recoveredMinor` | `number` | 是 | 已回收金额 |
| `lockedMinor` | `number` | 是 | 待决抵销占用，仍包含在未回收金额中 |
| `outstandingMinor` | `number` | 是 | 未回收金额，原始减已回收 |
| `version` | `number` | 是 | 当前版本 |

### PointsReceivablePage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型，不按不同类型合并余额 |
| `currency` | `string` | 是 | ISO货币代码 |
| `scale` | `number` | 是 | 金额最小单位的小数位数 |
| `rows` | [`PointsReceivable`](#pointsreceivable)\[\] | 是 | 当前门店的应收记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否可能还有下一页 |

### PointsRecoveryExecution

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reference` | `string` | 是 | 原业务引用编号 |
| `status` | [`PointsExecutionStatus`](#pointsexecutionstatus) | 是 | 服务端业务状态 |

### PointsRecoveryOperation

枚举取值：

- `PAYOUT`
- `TRANSFER`
- `REFUND_COLLECTION`
- `REFUND_COMPENSATION`

### PointsRefundPreparation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `preparationNo` | `string` | 是 | 退款准备编号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `revision` | `number` | 是 | 不可变修订版本 |
| `inputHash` | `string` | 是 | 原准备输入的不可变摘要 |
| `returnedPoints` | `number` | 是 | 本次退回已使用积分 |
| `reclaimedPending` | `number` | 是 | 本次收回待激活积分 |
| `reclaimedAvailable` | `number` | 是 | 本次收回可用积分 |
| `expectedClawbackPoints` | `number` | 是 | 原承诺要求收回的积分总数 |
| `cashOffsetMinor` | `number` | 是 | 现金退款中抵销的金额，最小货币单位 |
| `netCashMinor` | `number` | 是 | 最终需退现金金额，最小货币单位 |
| `waivedPoints` | `number` | 是 | 按授权豁免的补收积分 |
| `collectionTicket` | `string,null` | 是 | 原补收资金指令，未生成时为空 |
| `state` | `string` | 是 | 服务端处理状态 |

### PointsRefundView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `afterSaleNo` | `string` | 是 | 原售后尝试编号 |
| `subOrderNo` | `string` | 是 | 门店子订单编号 |
| `state` | `string` | 是 | 服务端处理状态 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |
| `preparation` | [`PointsRefundPreparation`](#pointsrefundpreparation) | 是 | 本次售后冻结的退款准备 |

### PointsRequestPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsRequestView`](#pointsrequestview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

### PointsRequestView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `requestNo` | `string` | 是 | 原资金或申请请求号 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `action` | [`PointsPoolAction`](#pointspoolaction) | 是 | 请求执行的业务动作 |
| `amountMinor` | `number` | 是 | 本次资金金额，最小货币单位 |
| `state` | `string` | 是 | 服务端处理状态 |
| `submittedBy` | `string` | 是 | 原申请人编号 |
| `approvedBy` | `string,null` | 是 | 服务端审核人编号 |
| `hasEvidence` | `boolean` | 是 | 是否已有合格资金证据 |
| `ticketNo` | `string,null` | 是 | 持久资金指令编号 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsScope

枚举取值：

- `GLOBAL`
- `REGION`
- `TYPE`
- `SCENE`
- `CLIENT`
- `MERCHANT`
- `STORE`
- `USER`

### PointsStatementPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | `object`（见下）\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

`rows[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | 持久记录主键 |
| `snapshot` | [`PointsStatementSnapshot`](#pointsstatementsnapshot) | 是 | 服务端冻结的账务或准备快照 |
| `version` | `number` | 是 | 当前版本号，变更按版本校验 |

### PointsStatementSnapshot

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `statementNo` | `string` | 是 | 门店结算单编号 |
| `period` | `object`（见下） | 是 | 本次生成的周期序号 |
| `positionIds` | `string`\[\] | 是 | 本次结算引用的原持仓编号集合 |
| `revisions` | `object`（见下）\[\] | 是 | 结算单不可变修订记录 |
| `state` | `string` | 是 | 服务端处理状态 |
| `disputeReason` | `string,null` | 是 | 争议原因，未争议时为空 |

`period` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `storeNo` | `string` | 是 | 积分业务隔离的门店编号 |
| `fromMillis` | `number` | 是 | 统计或结算起始时间，Unix 毫秒 |
| `toMillis` | `number` | 是 | 统计或结算截止时间，Unix 毫秒 |
| `routeKey` | `string,null` | 是 | 原资金路由唯一键 |

`revisions[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `number` | `number` | 是 | 修订序号 |
| `lines` | `object`（见下）\[\] | 是 | 本修订包含的原持仓结算行 |
| `points` | `number` | 是 | 积分数量 |
| `actor` | `string` | 是 | 服务端取得的操作人编号 |
| `reason` | `string,null` | 是 | 服务端不可用原因或操作审计原因 |

`revisions[].lines[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `positionNo` | `string` | 是 | 原持仓编号 |
| `sourceVersion` | `number` | 是 | 引用的原持仓版本 |
| `points` | `number` | 是 | 积分数量 |

### PointsStatsPeriod

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码。 |
| `from` | `string` | 是 | 统计周期起始日期，YYYY-MM-DD。 |
| `to` | `string` | 是 | 统计周期结束日期，YYYY-MM-DD。 |
| `metrics` | [`Record<string,number>`](#recordstringnumber) | 是 | 周期内由服务端聚合的指标。 |
| `availableDays` | `number` | 是 | 统计数据可用的日期数量。 |
| `missingDays` | `number` | 是 | 统计数据缺失的日期数量。 |

### PointsSwitchPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `rows` | [`PointsSwitchView`](#pointsswitchview)\[\] | 是 | 本页记录 |
| `next` | `number` | 是 | 下一页游标 |
| `hasMore` | `boolean` | 是 | 是否还有后续记录 |

### PointsSwitchView

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `scope` | [`PointsScope`](#pointsscope) | 是 | 开关或统计的作用范围 |
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `subjectNo` | `string` | 是 | 范围对象编号，门店范围使用 storeNo |
| `enabled` | `boolean` | 是 | 是否允许新增对应业务 |
| `flow` | [`PointsFlow`](#pointsflow) | 是 | 控制积分发放、核销或两个方向 |
| `reason` | `string` | 是 | 服务端不可用原因或操作审计原因 |
| `id` | `number` | 是 | 持久记录主键 |
| `actor` | `string` | 是 | 服务端取得的操作人编号 |
| `at` | `number` | 是 | 服务端事件时间，Unix 毫秒 |

### PointsTypeConfig

Points configuration commands carry stable intent IDs; identity and time come from the server.

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `typeCode` | `string` | 是 | 积分类型唯一编码 |
| `name` | `string` | 是 | 积分名称，运营端展示名称 |
| `market` | `string` | 是 | 市场代码 |
| `currency` | `string` | 是 | ISO 货币代码 |
| `scale` | `number` | 是 | 货币最小单位精度 |
| `rate` | `object`（见下） | 是 | 积分与最小货币单位的换算比例 |
| `deductRatioBp` | `number` | 是 | 最大抵扣比例，万分比 |
| `idleDays` | `number` | 是 | 账户无活动失效天数 |
| `status` | [`PointsTypeConfigStatus`](#pointstypeconfigstatus) | 是 | 服务端业务状态 |
| `earningPaused` | `boolean` | 否 | 是否暂停该积分类型的订单赠送 |
| `orderDefault` | `boolean` | 否 | 是否作为订单默认积分类型 |

`rate` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pointsUnit` | `number` | 是 | 换算比例中的积分数量 |
| `minorUnit` | `number` | 是 | 换算比例中的最小货币单位数量 |

### PointsTypeConfigStatus

PointsTypeConfig 的 status 取值域，按服务端契约校验。

枚举取值：

- `DRAFT`
- `ACTIVE`
- `DRAINING`
- `RETIRED`

### Post

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `postNo` | `string` | 是 | 内容单号 |
| `authorType` | [`PostAuthorType`](#postauthortype) | 是 | 作者类型：普通用户 or 商家。商家发的内容审核标准更严 |
| `authorName` | `string` | 是 | 作者昵称/店名 |
| `title` | `string` | 是 | 内容标题 |
| `content` | `string` | 是 | 正文 |
| `communityNo` | `string` | 是 | 归属社区。内容只在本社区露出 |
| `communityName` | `string` | 是 | 社区名快照 |
| `skuNo` | `string,null` | 否 | 关联商品；纯分享贴可以没有 |
| `riskHits` | `string`\[\] | 是 | 命中的风险词。 ⚠️ 命中的内容**不进批量通过** —— 批量 + 风险内容 = 事故，必须逐条看。 |
| `status` | [`PostStatus`](#poststatus) | 是 | 审核状态。允许的流转见 `POST_TRANSITIONS`（`PASSED → OFFLINE` 是单独一条路） |
| `auditRemark` | `string,null` | 否 | 审核意见 / 下架原因。原样回作者 |
| `likeCount` | `number` | 是 | 点赞数 |
| `createdAt` | `string` | 是 | 发布时间 |
| `decidedAt` | `string,null` | 否 | 审核完成时间。未审为 null |
| `decidedBy` | `string,null` | 否 | 审核人（STAFF 账号）。未审为 null |

### PostAuthorType

枚举取值：

- `USER`
- `MERCHANT`

### PostStatus

种草内容状态。 ⚠️ `PASSED → OFFLINE` 是**单独一条路**，而不是"改回待审"： 内容已经露出过、被人看过、可能已经被引用，把它退回待审等于假装没发生过。 下架同样要写原因。

枚举取值：

- `PENDING`
- `PASSED`
- `REJECTED`
- `OFFLINE`

### ProductGoods

商品池里的一行（goods 粒度，SKU 收在 `skus` 里）——**不是**  {@link  Sku }  的复数形式。 <p>后端 `prd_goods`/`prd_sku` 本来就是一对多：标题、图、类目、审核状态都在 goods 上， 价格/库存/规格才是 sku 的。商品池按 goods 展示、审核/强制下架/预售这几个动作 仍然打在具体某个 sku 上（见 `skus[].skuNo`）——两者granularity 不同，别混用。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | [`I18nText`](#i18ntext) | 是 | 标题（三语） |
| `cover` | `string` | 否 | 封面图 |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 否 | 归属类目 |
| `categoryName` | `string` | 否 | 类目名快照 |
| `status` | `string` | 是 | 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED |
| `skus` | [`GoodsSkuRow`](#goodsskurow)\[\] | 是 | 这件商品下的所有规格 |
| `storeOnSale` | `boolean,null` | 否 | 门店投影（列表查询带 `storeNo` 时才有值）：这件商品在**那家店**上不上架。 `null`/缺失 = 未按店管理，跟随主体级 `status` —— 与「在那家店下架了」是两回事， 显示成同一个「否」会让运营去催商家上架一件其实全店都在卖的商品。 |

### ProductPolicy

建品规则（商品①）。提审那一刻校验，**拦在进审核队列之前**。 <p>**三条默认全关**（等于今天的行为）：一旦打开，命中的存量商品下次提审全会被拦， 而平台上有 200 个 SPU、194 个正卡在审核里。默认打开等于在没人预告的情况下 让一批商家的提交突然失败。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requireCover` | `boolean` | 是 | 提审前必须有主图 |
| `titleMinLength` | `number` | 是 | 标题最少几个字，0 = 不限 |
| `titleMaxLength` | `number` | 是 | 标题最多几个字，0 = 不限 |

### ProductStats

商品域平台统计（M4）。 <p>此前这个域**一个统计数字都没有**，而商品是这个平台的主体。 四个数各自对应一个能做的事，不是四个摆着看的指标。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categories` | `number` | 是 | 分类数量 |
| `categoriesUsed` | `number` | 是 | 至少被一个商品用过的类目数 |
| `skus` | `number` | 是 | SKU 数量 |
| `skusWithBarcode` | `number` | 是 | 填了条码的。**扫码功能的天花板就是这个数** |
| `skusWithCode` | `number` | 是 | 填了商家货号的 |
| `specDims` | `number` | 是 | 规格维度数量 |
| `specDimsBound` | `number` | 是 | 至少挂到一个类目上的维度数。规格库只增不减，没挂上的是清理依据 |
| `auditApproved` | `number` | 是 | 审核通过商品数量 |
| `auditRejected` | `number` | 是 | 审核驳回商品数量 |
| `auditPending` | `number` | 是 | 待审核商品数量 |
| `auditActions` | `number` | 是 | 最近 N 天的审核动作数 —— **吞吐**，与上面三个累计数不是一回事 |
| `auditDays` | `number` | 是 | 审核统计窗口天数 |

### ProxyLimit

代客下单的限额（M6：客服代客操作的权限边界与金额阈值）。 <p>此前只有留痕没有闸门：客服能替任何人下任意金额的单，事后查得到、当时拦不住。 留痕回答「谁干的」，闸门回答「能干多大」—— 两件事。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `maxAmountMinor` | `number` | 是 | 单笔上限（分）。按订单**实际应付额**判，不按商品估算 |
| `maxPerDay` | `number` | 是 | 每个客服每天最多几笔。按自然日算 |
| `updatedAt` | `string,null` | 是 | 最后修改时间 |
| `updatedBy` | `string,null` | 是 | 最后修改人 |

### PurchaseInvoice

进项票（供应商开给平台的）。自营链路专用 —— **票到才付款**。 `titleMatched` 是后端算好的：抬头与主体名对不上时不给核验通过， 而这一条**在界面上必须显示原因** —— 财务看到「不能核验」而不知道为什么， 只会去问开票的人，而对方也不知道。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleDocumentNos` | `string`\[\] | 否 | 关联结算单的展示单号列表 |
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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

### QualificationItem

申请单上提交的一条结构化资质。`expireAt` 为 null = 长期有效

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`QualificationType`](#qualificationtype) | 是 | 资质类型，如营业执照 / 食品经营许可证 |
| `code` | `string` | 是 | 证照编号，与证面一致 |
| `imageUrl` | `string` | 是 | 图片地址 |
| `expireAt` | `number,null` | 是 | 到期时刻 |
| `issuer` | `string` | 否 | 发证机关 |

### QualificationType

资质证件类型。与后端 MchQualification 的四个常量逐字一致

枚举取值：

- `BUSINESS_LICENSE`
- `FOOD_PERMIT`
- `FOOD_WORKSHOP`
- `OTHER`

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
| `status` | [`QuestionStatus`](#questionstatus) | 是 | 问答状态 |
| `createdAt` | `string` | 是 | 提问时间 |
| `hideReason` | `string,null` | 否 | 隐藏原因。隐藏也要写清为什么，否则用户来问时没人说得清 |

### QuestionStatus

枚举取值：

- `PENDING`
- `ANSWERED`
- `OFFLINE`

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
| `kind` | [`RankingKind`](#rankingkind) | 是 | 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** |
| `size` | `number` | 是 | 取前 N 名 |
| `manualSkus` | `string`\[\] | 是 | 仅 MANUAL：人工指定的商品，顺序即榜位 |
| `enabled` | `boolean` | 是 | 是否启用。停用后 C 端不再展示该榜 |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### RankingKind

榜单口径。 `MANUAL` 与其余三类是两种东西：前三类由数据算出来，`MANUAL` 由人指定。 混在一个结构里但**校验路径完全不同** —— 算出来的榜带 `manualSkus` 就是调用方理解错了。

枚举取值：

- `SALES`
- `RATING`
- `NEW`
- `MANUAL`

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
| `tagCount` | `number` | 是 | 标签**个数**。只有个数：标签名是商家的经营判断，运营这一页用不到（AC-15） |
| `segmentCount` | `number` | 是 | 人群**个数**。同上，没有人群条件 |
| `tasks` | `number` | 是 | 近 30 天触达次数（批次数） |
| `skipped` | `number` | 是 | 被频次闸等拦下的人次 |
| `skipRate` | `number` | 是 | 跳过率（%）= 拦下的 / 命中的。高 = 在反复给同一批人发 |

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

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channelBillConnected` | `boolean` | 是 | 渠道账单是否已接入。false 时 note 必须显示给运营 |
| `note` | `string` | 是 | 说明 |

### ReconDiff

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `diffNo` | `string` | 是 | 差异单号 |
| `billDate` | `string` | 是 | 对账日期（渠道账单的账期），YYYY-MM-DD |
| `channel` | [`PayChannel`](#paychannel) | 是 | 支付渠道 |
| `channelTxnNo` | `string,null` | 否 | 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） |
| `orderNo` | `string,null` | 否 | 平台订单号；CHANNEL_ONLY 时为空（平台没落库） |
| `type` | [`ReconDiffType`](#recondifftype) | 是 | 差异类型。退款异常仅用于人工核对，处置不自动解冻或付款，见上方注释 |
| `channelAmount` | `number` | 是 | 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 |
| `platformAmount` | `number` | 是 | 平台侧金额（分）。CHANNEL_ONLY 为 0 |
| `status` | [`ReconStatus`](#reconstatus) | 是 | 处置状态 |
| `resolution` | `string,null` | 否 | 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 |
| `recoveredOrderNo` | `string,null` | 否 | 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） |
| `createdAt` | `string` | 是 | 差异产生时间 |
| `resolvedAt` | `string,null` | 否 | 处置时间。未处置为 null |
| `resolvedBy` | `string,null` | 否 | 处置人（STAFF 账号）。未处置为 null |

### ReconDiffType

对账差异类型。 ⚠️ 这三类的**处置方式完全不同**，所以必须分开而不是笼统叫"异常"： - `CHANNEL_ONLY`：渠道有、平台无 —— 用户付了钱但订单没落库，要**补单或退款** - `PLATFORM_ONLY`：平台有、渠道无 —— 平台记了收款但渠道查无此笔，多半是误记，要**冲正** - `AMOUNT_DIFF`：两边都有但金额不符 —— 要人去查是折扣算错还是重复扣款

枚举取值：

- `CHANNEL_ONLY`
- `PLATFORM_ONLY`
- `AMOUNT_DIFF`
- `REFUND_ABNORMAL`
- `REFUND_LATE_SUCCESS`
- `REFUND_CLOSED`

### ReconScope

对账覆盖面。<b>SELF_ONLY 不等于「对完了」</b> —— 它只是我方账内自洽，通道账单还没比过。界面要如实标注， 显示成「已对账」是一句自证的话。

枚举取值：

- `SELF_ONLY`
- `BOTH`

### ReconStatus

枚举取值：

- `PENDING`
- `RESOLVED`
- `IGNORED`

### Record<string,number>

类型：`object`

### Record<string,string>

类型：`object`

### Record<string,unknown>

类型：`object`

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

### RefundExecutionProgress

后端持久化的退款执行事实，资源恢复独立于资金结果。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `closureVersion` | `number,null` | 否 | Persisted protocol version; absent means legacy recovery semantics. |
| `closureStatus` | `LEGACY` \| `PENDING` \| `DONE` \| `null` | 否 | State of order and resource closure for this refund. |
| `cashStatus` | `string,null` | 否 | Cash refund state reported by the payment execution. |
| `benefitsStatus` | `PENDING` \| `SETTLED` \| `null` | 否 | Whether points, coupons and other benefits have been restored. |
| `executionNo` | `string` | 是 | 原退款执行号 |
| `paymentNo` | `string,null` | 否 | 资金退款单号 |
| `status` | `string` | 是 | 资金执行状态 |
| `phase` | `string` | 是 | 分账回退或现金退款阶段 |
| `errorCode` | `string,null` | 否 | 资金执行错误码 |
| `resourceStatus` | `string` | 是 | 库存及优惠恢复状态 |
| `resourceErrorCode` | `string,null` | 否 | 资源恢复错误码 |
| `succeededAt` | `number,null` | 否 | 可信现金成功时刻，毫秒 |
| `nextAttemptAt` | `number,null` | 否 | 原执行下次可恢复时刻，毫秒 |

### Region

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath |
| `enabled` | `boolean` | 是 | 开城开关：停用只影响新的选择，存量商家不动 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 |

### RegionMatchSource

行政区划是怎么定出来的。ADDRESS 靠地址串匹配 / COORDS 靠坐标反查

枚举取值：

- `ADDRESS`
- `COORDS`

### RegionRow

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string,null` | 是 | 国标 6 位区县码；下钻用它 |
| `regionName` | `string,null` | 是 | 区县名（省/市/区）；regionCode 查不到名时为 null |
| `communityCount` | `number` | 是 | 这个区县里已开通的聚落数 |
| `buyerCount` | `number` | 是 | 这个区县里的买家数（生效地址落在其中任一聚落） |
| `buyerCommunityCount` | `number` | 是 | 有买家的聚落数 |
| `merchantCommunityCount` | `number` | 是 | 有商家覆盖的聚落数（「买家真搜得到」，不是「谁框了这儿」） |
| `supplyGapCount` | `number` | 是 | 有人没商家 |
| `demandGapCount` | `number` | 是 | 有商家没人 |
| `emptyCount` | `number` | 是 | 两头空 |

### RegionSuggestion

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 国标区划码 |
| `level` | `string` | 是 | 层级 |
| `name` | `string` | 是 | 名称 |
| `path` | `string` | 是 | 「广东省 / 深圳市 / 龙华区 / 福城街道」 |
| `source` | [`RegionMatchSource`](#regionmatchsource) | 是 | 来源 |
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
| `status` | [`ReviewStatus`](#reviewstatus) | 是 | 审核状态 |
| `riskFlags` | [`RiskFlag`](#riskflag)\[\] | 是 | 命中的刷评信号。**是线索不是结论** —— 命中不等于判定 |
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
| `status` | [`AppealStatus`](#appealstatus) | 是 | 裁决状态。UPHELD = 支持商家（差评下架），REJECTED = 驳回申诉（差评保留） |
| `submittedAt` | `string` | 是 | 申诉提交时间 |
| `verdict` | `string` | 否 | 裁决说明：无论支持还是驳回都必须写，商家会看到 |

### ReviewStatus

枚举取值：

- `PENDING`
- `PASSED`
- `REJECTED`

### RewardType

邀请有礼（P-9.2.1）。 ⚠️ 奖励**只能是券**：ADR-004 去团长化后不存在现金激励 —— 一旦发现金，职业薅羊毛立刻回来，且归因作弊有了直接变现路径。

类型：`string`

### RiskEvent

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `eventNo` | `string` | 是 | 风险事件单号 |
| `type` | [`RiskType`](#risktype) | 是 | 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 |
| `subject` | `string` | 是 | 主体：用户昵称 / 商家名 / 设备号 |
| `subjectType` | [`SubjectType`](#subjecttype) | 是 | 主体类型，决定 `subject` 是昵称、店名还是设备号 |
| `signals` | `string`\[\] | 是 | 命中的信号。**不给分值** —— 分值口径要等有真实样本后由风控定， 现在编一个看起来很准的分数，只会让人照着它做决定。 |
| `refs` | `string`\[\] | 是 | 关联证据：订单号 / 归因链路号 |
| `status` | [`RiskStatus`](#riskstatus) | 是 | 处置状态 |
| `createdAt` | `string` | 是 | 事件产生时间 |
| `verdict` | `string` | 否 | 处置结论 |

### RiskFlag

刷评信号（P-13.1.5）。不是结论，是**给人审的线索** —— 命中不等于判定。

枚举取值：

- `SAME_DEVICE`
- `SAME_IP`
- `TEXT_DUP`
- `BURST`

### RiskRule

拦截规则（P-16.2.5）：各类型的触发阈值 + 是否自动拦截。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`RiskType`](#risktype) | 是 | 适用的风险类型，一类一条 |
| `threshold` | `number` | 是 | 触发阈值（如同设备下单数、同 IP 邀请数、30 天退款次数），必须 > 0 |
| `autoBlock` | `boolean` | 是 | 命中后是否自动拦截；false = 只记事件等人工 |
| `updatedAt` | `string` | 是 | 最后修改时间 |

### RiskStatus

枚举取值：

- `PENDING`
- `CONFIRMED`
- `DISMISSED`

### RiskType

枚举取值：

- `FAKE_ORDER`
- `ABNORMAL_FISSION`
- `MALICIOUS_REFUND`

### Role

枚举取值：

- `SUPER_ADMIN`
- `PRODUCT_OPS`
- `CAMPAIGN_OPS`
- `COMMUNITY_OPS`
- `MERCHANT_BD`
- `AUDITOR`
- `CS`
- `FINANCE`
- `RISK`
- `ANALYST`
- `TECH_OPS`
- `ELEC_ADMIN`

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

### SectionKey

店铺主页板块。 `BANNER`（店招）是 `required` 的：关掉之后店铺页没有头部，等于一张裸列表。 其余板块商家可以按模板取舍。

枚举取值：

- `BANNER`
- `NOTICE`
- `HOT`
- `CATEGORY`
- `COUPON`
- `GROUP`

### SectionLayout

店铺装修区块的排布方式

枚举取值：

- `GRID`
- `LIST`
- `FEATURE`

### SelfOperatedResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 建出来（或幂等命中）的主体业务键 |
| `storeNo` | `string` | 是 | 随主体一并建出来的默认门店 |
| `ownerUserNo` | `string` | 是 | 手机号对应的账号；没有就按登录那条路新建一个 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 是 | 回读值，应为 `AGGREGATED` |
| `businessMode` | `string` | 是 | 回读值，应为 `SELF_OPERATED` |
| `serviceScope` | `string` | 是 | 回读值：COMMUNITY / CITY / PLATFORM |
| `selfOperated` | `boolean` | 是 | 回读值，应为 true。它是**免证件与运营建店的唯一判据** —— `fundsMode` 认不出平台自己（归集同时盖着代销）， `businessMode` 也认不出（门店级，且建表默认值就是自营）。 |
| `reachableCommunities` | `number` | 是 | **这家店现在对多少个小区可见。** 不是装饰：ADR-009 的「必须勾社区」只拦得住「一个都没勾」， 而可见性最终一律展开成小区号 —— 库里一个小区都没有时 CITY 档同样是 0 （区划表里有深圳，不代表深圳有小区）。建完是 0 就是「建好了，谁也看不到」。 |
| `created` | `boolean` | 是 | 本次是否**真的新建**了主体。false = 这个手机号名下已经有主体，原样返回它。 界面上要分开说：运营连点两次时，「又建了一个」与「就是刚才那个」是不同的事实。 |

### SelfOperatedStore

运营给平台自营主体开出来的门店。 `payMerchantNo` 为空是**正常的** —— 自营门店不进件，钱先进平台户。 只有第三方模式下它为空才是硬阻塞。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店业务键 |
| `merchantNo` | `string` | 是 | 它挂在哪个主体下 |
| `name` | `string` | 是 | 门店名。与主体名可以不同（分店） |
| `address` | `string,null` | 否 | 门店地址，可空 |
| `businessMode` | `string` | 是 | 回读值，应为 `SELF_OPERATED` |
| `payMerchantNo` | `string,null` | 否 | 收款号。**为空是正常的** —— 自营门店不进件，钱先进平台户； 只有第三方模式下它为空才是硬阻塞。 |

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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `batchNo` | `string` | 是 | 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 |
| `entityNo` | `string` | 是 | 收款主体号 |
| `payChannel` | `string` | 是 | 支付通道码。**不同通道账期不同，所以不能合批** |
| `settleCycle` | `string` | 是 | 本批采用的账期规则快照，如 T+1 / WEEKLY |
| `periodFrom` | `number` | 是 | 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 |
| `dueAt` | `number` | 是 | T3 应结日 |
| `releasedAt` | `number,null` | 是 | 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 |
| `freezeExpireAt` | `number,null` | 是 | Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** —— 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警 |
| `status` | [`SettleBatchStatus`](#settlebatchstatus) | 是 | DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED |
| `billCount` | `number` | 是 | 本批单据数 |
| `grossMinor` | `number` | 是 | 本批结算基数合计（分）。与单据上同名字段一个口径 —— **不含运费** |
| `netMinor` | `number` | 是 | 本批应放款合计（分）。**放行时按这个数下发** |
| `reconScope` | [`ReconScope`](#reconscope) | 是 | 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**， 不能显示成「已对账」—— 没有对方账单时那是一句自证的话 |
| `blockedReason` | `string,null` | 是 | 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） |
| `blockedAt` | `number,null` | 是 | 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 |
| `blockExpireAt` | `number,null` | 是 | 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 |
| `decidedBy` | `string,null` | 是 | 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 |
| `decideRemark` | `string,null` | 是 | 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 |
| `payoutStatus` | [`PayoutStatus`](#payoutstatus) \| `null` | 否 | 本批最近一笔放款的摘要（V391）。空 = 还没放款 |
| `paymentRef` | `string,null` | 否 | 凭证号（网银流水号） |
| `paidAt` | `number,null` | 否 | 财务登记凭证的时刻（毫秒） |

### SettleBatchStatus

枚举取值：

- `DRAFT`
- `COLLECTED`
- `RECONCILING`
- `BLOCKED`
- `RECONCILED`
- `RELEASED`

### SettleStatRow

结算口径的经营统计一行（TDD-供应商结算与双轨资金 §2.1）。 **与门店经营排行不是一回事**：那个读订单（GMV、退款率）、是最近 N 天 Top N； 这里读结算单、按区间全量、三维可切。GMV 没扣佣金与手续费，两个数对不上是正常的。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimKey` | `string` | 是 | 维度值：门店号 / 主体号 / 收款商户号。空门店为 __UNASSIGNED__ |
| `dimName` | `string` | 是 | 展示名。查不到时回落成 dimKey 本身，**不会是空串** |
| `grossMinor` | `number` | 是 | 成交额（分）。**不含退款**（退款走售后与分账回退），**也不含运费**（见结算单那份的说明） |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 服务费（分） |
| `channelFeeMinor` | `number` | 是 | 渠道手续费（分） |
| `freightIncomeMinor` | `number` | 是 | 代收的运费（分）。**不在 grossMinor 里** |
| `freightCostMinor` | `number` | 是 | 平台代付出去的快递费（分）。 与 `freightIncomeMinor` 的差额是**平台自己在快递上的盈亏** —— 这个数此前没有任何地方能看到，而平台一直在垫这笔钱。 |
| `netMinor` | `number` | 是 | 商家净额（分） |
| `billCount` | `number` | 是 | 结算单数。只给金额看不出「一笔大的还是很多笔」 |

### SettleStatus

⚠️ **`SPLIT` 曾经同时表示「指令已发出」与「钱已到」**，而底下调的是桩实现 —— 账面显示已分账而一分钱没动。2026-08-26 拆开，到账另立 `SPLIT_CONFIRMED`。 ⚠️ 说明写在这里而不是夹在值之间：`enum-registry` 的扫描正则只认 「值行 + 行尾注释」，**整行注释会把 union 打断** —— 打断之后这个枚举在登记表里会被报成「代码里已不存在」。

枚举取值：

- `PENDING`
- `SPLITTING`
- `SPLIT`
- `SPLIT_CONFIRMED`
- `OFFLINE_SETTLED`
- `RETRYING`
- `MANUAL`
- `REVERSED`
- `PENDING_RECON`
- `CONFIRMED`
- `PAID`

### Settlement

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单，**一条 = 一个子订单** |
| `orderNo` | `string` | 是 | 所属主单 |
| `merchantNo` | `string` | 是 | 结算对象商家 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 实付 + 平台补贴 + 积分抵扣。 **不含运费**（V367 / TDD-快递100商家寄件 §9 AC21，2026-09-29 改的口径）： 运费是代收代付的钱，进了这里等于让商家为平台代收的运费付佣金。 它单列在结算单的 `freight_income_minor`。 口径改之前这个数是「货款 + 运费」，按它做过的报表要重对一遍。 |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分） |
| `freightIncomeMinor` | `number` | 是 | 代收的运费（分）。**不在 grossMinor 里**，所以不进佣金基数。 非快递单为 0。 |
| `freightCostMinor` | `number` | 是 | 平台实付给快递公司、从商家收款里扣回的快递费（分）。 **只有平台代寄才有**；商家自己填单号发货是他自付，这里是 0。 判据是 `freightShipMode`，不是「这个数是不是 0」—— 平台代寄但还没称重回传时它也是 0。 |
| `freightShipMode` | `string` | 否 | PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为空 |
| `freightDiffReason` | `string` | 否 | 实付高于代收时的原因：OVERWEIGHT / REGION_SURCHARGE / OVER_CAP。没有差额时为空 |
| `netMinor` | `number` | 是 | 实付商家（分）= 基数 − 佣金 − 服务费 + 运费收入 − 实付快递费 |
| `trafficSource` | `string` | 是 | 该单的流量来源，决定适用哪一档费率 |
| `commissionRate` | `number` | 是 | 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** |
| `status` | [`SettleStatus`](#settlestatus) | 是 | 结算状态，两条轨道各走各的 |
| `createdAt` | `number` | 是 | 生成时刻（毫秒） |
| `splitAt` | `number,null` | 否 | 分账成功时刻；空 = 未分账 |
| `storeNo` | `string,null` | 否 | 哪家店挣的（统计维度） |
| `payMerchantNo` | `string,null` | 否 | 打给哪个收款号（结算维度） |
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 否 | 自营 / 第三方 |
| `invoiceStatus` | `string,null` | 否 | 自营：进项票状态。第三方恒为 NO_INVOICE |
| `paymentRef` | `string,null` | 否 | 自营：付款凭证号。空 = 尚未付款 |

### SettlementKind

聚落类型。裁决的人要一眼看出这是小区还是村 —— 两者的重复判据不一样

枚举取值：

- `ESTATE`
- `VILLAGE`

### Shipment

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `shipmentNo` | `string` | 是 | 运单记录单号（平台侧主键，不是快递单号） |
| `orderNo` | `string` | 是 | 关联的子订单 |
| `carrier` | [`Carrier`](#carrier) | 是 | 承运商 |
| `waybillNo` | `string` | 是 | 承运商的快递单号 |
| `status` | [`ShipmentStatus`](#shipmentstatus) | 是 | 快递状态。**`EXCEPTION` 不是终态**，疑难件可能之后又派送成功 |
| `receiver` | `string` | 是 | 收件人姓名 |
| `region` | `string` | 是 | 收件地区（省/市），超区判断看的就是它 |
| `createdAt` | `string` | 是 | 建单时间 |
| `updatedAt` | `string` | 是 | 最后一次轨迹更新时间 |
| `traces` | [`ShipmentTrace`](#shipmenttrace)\[\] | 是 | 轨迹节点，按时间正序 |
| `displayChannel` | `string,null` | 否 | 这一单备好的展示渠道（`wx-plugin` / `self-map`）。**运营要看见它** —— 买家说「看不到物流」时，第一个要回答的就是「走的哪条链、哪一环断了」 |
| `displayFailReason` | `string,null` | 否 | 最近一次备载荷失败的原因，只给运营看，不给买家看 |
| `profile` | [`ShipmentProfile`](#shipmentprofile) | 否 | `WX` 微信支付单（可用微信物流全套）/ `SELF` 线下付款等（不调任何微信物流接口） |
| `storeNo` | `string,null` | 否 | 门店号（登记时快照） |
| `entityNo` | `string,null` | 否 | 商家主体号（登记时快照） |
| `subState` | [`SubscribeState`](#subscribestate) | 否 | 订阅状态 |
| `subChannel` | `string,null` | 否 | 受理订阅的渠道（`kuaidi100` / `yto` …） |
| `subError` | `string,null` | 否 | 订阅最后一次失败：渠道 + 码 + 原文 |
| `subAttempts` | `number` | 否 | 订阅累计尝试次数 |
| `subscriptions` | `object`（见下）\[\] | 否 | Current shipment identity only; WeChat follow and Kuaidi100 are independent. |
| `bindState` | [`BindState`](#bindstate) | 否 | 微信 token 状态 |
| `bindError` | `string,null` | 否 | 换微信 token 最后一次失败的原因 |
| `signedAt` | `string,null` | 否 | 签收时间 |
| `lastEventAt` | `string,null` | 否 | 最近一次有新进展的时刻 |
| `atLocker` | `boolean` | 否 | 已放到驿站或快递柜 |
| `carrierCorrectedFrom` | `string,null` | 否 | 渠道纠正过承运商时的原值 |
| `receiverPhoneLast4` | `string,null` | 否 | 收件人手机号后四位（完整号只在物流模块里加密存，签收后清空） |

`subscriptions[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | `string` | 是 | 消息或支付通道编码 |
| `state` | `string` | 是 | 服务端处理状态编码 |
| `error` | `string,null` | 否 | — |
| `lastAttemptAt` | `number,null` | 否 | — |
| `nextRetryAt` | `number,null` | 否 | — |

### ShipmentProfile

运单走哪套：`WX` 微信支付单 / `SELF` 线下付款等

枚举取值：

- `WX`
- `SELF`

### ShipmentStatus

DELIVERING（派件中）与 CANCELLED（已作废）2026-10-09 加（TDD-物流模块 批 3），与 packages/shared 的同名类型对齐

枚举取值：

- `CREATED`
- `PICKED_UP`
- `IN_TRANSIT`
- `DELIVERING`
- `DELIVERED`
- `EXCEPTION`
- `CANCELLED`

### ShipmentTrace

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `at` | `string` | 是 | 轨迹时间 |
| `text` | `string` | 是 | 轨迹节点描述，原样来自承运商 |
| `location` | `string` | 否 | 所在城市/网点 |

### Sku

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 商品单号 |
| `title` | [`I18nText`](#i18ntext) | 是 | 商品标题（三语） |
| `merchantNo` | `string` | 是 | 归属商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `categoryNo` | `string` | 是 | 归属类目 |
| `categoryName` | `string` | 是 | 类目名快照 |
| `status` | [`SkuStatus`](#skustatus) | 是 | 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` |
| `prices` | [`Partial<Record<Market,number>>`](#partialrecordmarketnumber) | 是 | 各市场价格（分）。**缺任一市场价格不予通过**（B6） |
| `stock` | `number` | 是 | 现货库存 |
| `presaleQuota` | `number` | 是 | 预售额度（P-3.3.1）。0 = 不做预售 |
| `soldCount` | `number` | 是 | 已售（预售期内） |
| `cutoffAt` | `string` | 否 | 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 |
| `arriveAt` | `string` | 否 | 到货时间（与履约批次对齐） |
| `createdAt` | `string` | 是 | 创建时间 |
| `reason` | `string` | 否 | 驳回/强制下架原因，原样进商家 B 端 |

### SkuStatus

REJECTED 不是终态：商家改完可以重新提审。

枚举取值：

- `DRAFT`
- `PENDING`
- `ON_SALE`
- `OFF_SALE`
- `REJECTED`

### SlotKind

枚举取值：

- `HOME_FLOOR`
- `BANNER`
- `CHANNEL`

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
| `values` | [`SpecValue`](#specvalue)\[\] | 是 | 取值列表 |

### SpecTemplate

平台规格模板（P-3.4 / E27，后端 `prd_spec_template` 里 `scope=PLATFORM` 的那些）。 <p>B-4.4 商家建品时能选它，而平台端此前**没有维护入口** —— 表里只有初始化时 塞进去的几行，谁也改不了、加不了。三端联动表把这条记成「❌ 断裂：模板是死的」。 <p>与商家自存的模板（`scope=MERCHANT`）不是同一批数据：那些归商家， 平台端一条都不该列出来，更不该改 —— 改了那家店的历史规格就对不上了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `archivedAt` | `string,null` | 否 | 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。 |
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `string` | 是 | 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 |
| `categoryType` | [`CategoryTemplate`](#categorytemplate) \| `null` | 否 | 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecTemplateOption`](#spectemplateoption)\[\] | 是 | 选项。整体替换，不做逐项 diff |
| `createdAt` | `string` | 否 | 创建时刻 |

### SpecTemplateOption

{@link  SpecTemplate }  的一个选项。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 聚合键。**平台模板必填** —— 这是平台模板存在的唯一理由（B-4.5）： 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」， 聚合、比价、搜索全部对不上。没有 code 的平台模板与商家手输的没有区别。 |
| `label` | `string` | 是 | 展示文案 |

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
| `documentNo` | `string,null` | 否 | 面向用户的展示单号，不能代替内部业务编号 |
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
| `titleI18n` | [`Record<string,string>`](#recordstringstring) | 否 | 标题的多语言版本 |
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
| `name` | `string` | 是 | 业务对象显示名称 |
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
| `storeNo` | `string` | 是 | 哪家门店（S1，**一行一店**）。 历史数据没有门店号，后端已并入该主体的默认店；主体连默认店都没有时这里是主体号。 |
| `storeName` | `string,null` | 是 | 门店名；**null = 查不到**，端上显示门店号 —— 别拿主体名冒充店名 |
| `scan` | `number` | 是 | 扫码次数（PV）。同一个人扫三次算三次 |
| `scanUv` | `number` | 是 | 扫码人数（UV）。匿名访客按设备号去重 —— 他还没有账号 |
| `enter` | `number` | 是 | 进店人数：归因到本店的去重用户数 |
| `register` | `number` | 是 | **首次归因人数**（后端 `decision=CREATED`）。 ⚠️ **不等于「平台新注册」**：一个注册了很久的老用户，第一次扫这家店的码 也会计入。字段名沿用 `register` 是为了不动既有契约，口径以这句为准。 |
| `firstOrder` | `number` | 是 | 其中已产生首单的人数 |
| `convRate` | `number` | 是 | 首单转化率 = firstOrder / **scanUv**，0–1。 分母用 UV 不用 PV：同一个人扫三次不该把转化率摊薄成三分之一。 |

### StoreAuditKind

待审内容的种类。 前两种审的是**单据自己带的 content**（店招图 / 公告文本）； `SERVICE_AREA` 审的是**另一张表里的一行** —— 商家框的区、街道覆盖（ADR-013 阶段三）。 它进同一个队列是有意的：运营的工作台上不该有两个长得一样、入口不同的待审列表。

枚举取值：

- `BANNER`
- `NOTICE`
- `SERVICE_AREA`

### StoreAuditStatus

枚举取值：

- `PENDING`
- `PASSED`
- `REJECTED`

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
| `channel` | `string` | 是 | 消息或支付通道编码 |
| `enabled` | `boolean` | 是 | — |
| `denied` | `boolean` | 是 | 准入矩阵不允许（按主体类型） |
| `templateNo` | `string,null` | 否 | 仅 EXPRESS：运费模板号 |
| `locked` | `boolean` | 否 | 运营锁路（P2）：买家侧不可选、商家侧置灰。解锁只能运营 |
| `scopeMode` | `string` | 否 | ALL / SUBSET（P2 范围子集） |
| `areaNos` | `string`\[\] | 否 | — |

### StoreGovern

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 是 | 门店地址 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `isDefault` | `boolean` | 是 | 是否主体的默认门店。默认店承接「没指定门店」的那些流量 |
| `status` | [`StoreGovernStatus`](#storegovernstatus) | 是 | 经营状态，见  {@link  StoreGovernStatus } |
| `businessMode` | [`BusinessMode`](#businessmode) | 是 | 自营 / 第三方。决定这家店的钱怎么走、票怎么开 |
| `payMerchantNo` | `string,null` | 是 | 本店专属收款商户号。 **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。 |
| `rating` | `number,null` | 是 | 门店评分，**×10 的整数**（85 = 8.5 分）。与主体那几列同口径 |
| `ratingCount` | `number,null` | 是 | 评价条数。 ⚠️ **0 = 暂无评价，不是 0 分** —— 新店与还没重算过的店都是这个形状。 判空要按**条数**，按分值判会把「没人评过」显示成「0 分」。 |
| `announcement` | `string` | 是 | 门店公告（走 P-10.1 的机审 + 人审） |
| `openHours` | `string` | 是 | 营业时间，展示串 |
| `deliveryRadiusM` | `number` | 是 | 配送半径（米） |
| `deliveryMinOrderMinor` | `number` | 是 | 起送价（分） |
| `deliveryFeeMinor` | `number` | 是 | 配送费（分） |
| `deliveryFreeThresholdMinor` | `number` | 是 | 免配送费门槛（分） |

### StoreGovernDetail

门店详情（P-11.2.1c）：档案 + 三样**只有详情才算**的东西。 不把这三项塞进  {@link  StoreGovern } ：列表一屏几十行，每行再查社区/自提点/扫码数 就是三次 N+1；而给它们留 null 又会让「列表不算这一项」与「这家店没有」长得一样。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `store` | [`StoreGovern`](#storegovern) | 是 | 门店档案本身（与列表行同一份形状） |
| `coverage` | [`MerchantCoverage`](#merchantcoverage) | 是 | 经营范围与它的投影结果。挂在**主体**上 —— 同主体的门店看到同一份，界面别写成「本店覆盖」 |
| `pickupNames` | `string`\[\] | 是 | 这家店挂靠的取货点名。空数组 = 没挂，不是没查到 |
| `scanCount30d` | `number` | 是 | 近 30 天店铺码扫码次数。与获客看板同一个数据源，不另算一份 |

### StoreGovernStatus

门店经营状态。三档**不是同一个人在管**，所以不能压成一个 `enabled` 布尔： - `ACTIVE`    正常营业； - `READONLY`  商家自助停用（自己关的，自己能开回来）； - `SUSPENDED` 平台强制下线 —— **只有平台解得开**，商家侧的启停对它一律拒绝。 压成布尔的后果是运营看不出「这家店是自己歇业还是被我们压下去的」， 而这两件事该做的动作完全相反。

枚举取值：

- `ACTIVE`
- `READONLY`
- `SUSPENDED`

### StoreMode

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string` | 是 | 门店名，展示用 |
| `merchantNo` | `string` | 是 | 所属商家主体 |
| `businessMode` | [`BusinessMode`](#businessmode) \| `null` | 是 | 自营 / 第三方；空 = 尚未设置 |
| `payMerchantNo` | `string,null` | 是 | 该店实际可用的收款号（本店专属号优先，回落到主体默认号）。**空 = 不能切第三方** |

### StorePageAudit

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `auditNo` | `string` | 是 | 审核单号 |
| `merchantNo` | `string` | 是 | 提审商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `storeName` | `string,null` | 否 | 这条内容发给哪家店。存量单（后端 V214 之前）没记，为空。 多店商家只看商家名判断不了「南门店今天停电」该不该放行 —— 而通过之后正是写回那家店。 |
| `kind` | [`StoreAuditKind`](#storeauditkind) | 是 | 待审内容类型：店招图 / 公告文本 |
| `content` | `string` | 是 | 待审内容：店招图 URL、公告文本，或 `DISTRICT:330106` 这样的覆盖项定位串 |
| `display` | `string` | 否 | 人话版的 content。`SERVICE_AREA` 时是「浙江省 / 杭州市 / 西湖区」，其余与 content 相同。 **列表与详情一律显示它**：让运营对着 `DISTRICT:330106` 判断 「这家菜摊该不该覆盖整个西湖区」，等于让他去别处查一次再回来。 |
| `status` | [`StoreAuditStatus`](#storeauditstatus) | 是 | 审核状态 |
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
| `storeNo` | `string` | 是 | 哪家门店（V298 一店一码，一行一店） |
| `storeName` | `string,null` | 是 | 门店名，可与主体名不同（「张记粮油·文三路店」） |
| `code` | `string,null` | 是 | 码值（C 端扫码进店的深链参数），导出时给 BD 去印刷。 ⚠️ **null = 这家分店还没发过码**，不是空串。它是运营要动手的那一行 —— 显示成空白的话，与「有码但没印」看起来一模一样。 |
| `size` | `string,null` | 是 | 最近一次印刷的尺寸规格，如 "10x10cm"；**从没印过是 null**（尺寸属于那一次印刷，不是门店属性） |
| `printed` | `number,null` | 是 | 累计已印数量，用于对账印刷成本。 ⚠️ **null = 还没人登记，不是「印了 0 张」**。两者在界面上必须分开显示 —— 混成一个数之后，运营没法知道该去催谁登记。 |
| `scanCount` | `number` | 是 | 区间内扫码次数。**这个 0 是真的 0**（埋点一直在记），与 printed 的 null 不同 |

### StoreRankRow

门店经营排行的一行（门店③）。 <p>经营看板早就有商家排行，**没有门店维度** —— 而多门店商家的货、单、码 都挂在门店上：商家排行会把「一家很好、一家半死」平均成「还行」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店编号 |
| `storeName` | `string,null` | 是 | 门店展示名称 |
| `merchantNo` | `string,null` | 是 | 归属商家主体编号 |
| `merchantName` | `string,null` | 是 | 门店属于谁。**两家都在垫底时，是不是同一个老板该做的事完全不同** |
| `gmv` | `number` | 是 | 成交金额，最小货币单位 |
| `orderCount` | `number` | 是 | 订单数量 |
| `avgOrderValue` | `number` | 是 | 平均订单金额，最小货币单位 |
| `refundedCount` | `number` | 是 | 已退款订单数量 |
| `refundedRate` | `number` | 是 | 退款率 0–1。分母是总成交单数（在售的 + 已退的） |

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
| `toAfterSale` | `number` | 是 | 待处理售后单数（P-11.2.1d）。 **只含还压着人的两态**（APPLIED / ARBITRATING）：已退款/已驳回/已关闭是了结的事实， 算进「待办堆积」会让处理得快的店看起来积压严重 —— 而运营正是拿这个数判断「这家店是不是没人管了」。 |

### StoreTemplate

店铺主页模板。 ⚠️ `usedByCount` 是**只读的引用计数**，不是配置项 —— 它存在的唯一理由是 拦住"停用一个正在被 12 家店用着的模板"这件事。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `name` | `string` | 是 | 模板名 |
| `layout` | [`SectionLayout`](#sectionlayout) | 是 | 商品区排布 |
| `sections` | [`TemplateSection`](#templatesection)\[\] | 是 | 板块开关列表 |
| `enabled` | `boolean` | 是 | 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 |
| `isDefault` | `boolean` | 是 | 默认模板：新店开出来就用它，所以停用不了 |
| `usedByCount` | `number` | 是 | 正在使用该模板的店铺数（只读） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### SubjectType

枚举取值：

- `USER`
- `MERCHANT`
- `DEVICE`

### SubscribeState

订阅状态：`PENDING` 待订阅（总开关关着时就停在这）· `DONE` 已订上 · `FATAL` 判死（换了所有渠道都不成，要人处理）· `ENDED` 渠道说跟踪结束 · `NA` 不需要（已终态）

枚举取值：

- `PENDING`
- `DONE`
- `FATAL`
- `ENDED`
- `NA`

### TaxRule

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `threshold` | `number` | 是 | 起征点（分）：单期收入低于它不代扣 |
| `rate` | `number` | 是 | 代扣税率（万分比） |
| `updatedAt` | `string` | 是 | 最后修改时间 |
| `updatedBy` | `string` | 是 | 最后修改人（STAFF 账号） |

### TemplateSection

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `key` | [`SectionKey`](#sectionkey) | 是 | 板块标识 |
| `enabled` | `boolean` | 是 | 是否启用。`required=true` 的板块不能停用 |
| `required` | `boolean` | 是 | 必选板块不能停用 |

### ThemeKey

枚举取值：

- `mono`
- `business`
- `fresh`
- `promo`
- `blue`

### Ticket

客服工单（P-14.2.1）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `ticketNo` | `string` | 是 | 工单号 |
| `title` | `string` | 是 | 工单标题 |
| `userNickname` | `string` | 是 | 提单用户昵称 |
| `orderNo` | `string` | 否 | 关联订单，可空 |
| `status` | [`TicketStatus`](#ticketstatus) | 是 | 工单状态。允许的流转见 `TICKET_TRANSITIONS` |
| `assignee` | `string` | 否 | 处理人（员工登录名）；未分派为空 |
| `proxyActions` | `string`\[\] | 否 | 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。 **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段 （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt）， 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。 |
| `createdAt` | `string` | 是 | 提单时间 |
| `reply` | `string` | 否 | 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。 此前它在三层上各缺一处：后端 `notify_ticket` 建表就留了 `reply`/`replied_at`/`replied_by` 且注释写明「代客操作要能追到人」，但没有任何代码写过它们； 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。 |
| `repliedAt` | `string` | 否 | 回复时间；未回复为空 |
| `repliedBy` | `string` | 否 | 回复人（员工登录名）。回复署的是平台的名，必须能追到人 |

### TicketStatus

枚举取值：

- `PENDING`
- `ASSIGNED`
- `RESOLVED`
- `CLOSED`

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

### TrafficSource

客流来源（矩阵 P-12.1.7 按 trafficSource 分档计费）。**与 `ord_sub_order.traffic_source` 的库列注释逐字一致** （`MERCHANT_OWNED 自带客流 / PLATFORM 平台客流`，下单时固化）。 ⚠️ **2026-09-10：这里原来还有 `INVITE` / `CHANNEL`，已删。** 后端那一列只有两个值、下单时固化；shared 的同名类型也只有两个； 而 ops-web **自己已经在绕开这个宽类型** —— `finance.ts` 专门定义了 `FeeTrafficSource`，注释写着「只有两档，比订单上的 TrafficSource （还有 INVITE / CHANNEL）窄」。多出来的那两个更像归因来源 （本端另有 `AttrSource = STORE_CODE \| INVITER \| CHANNEL`），是两个域串了。 留着的代价与券类型那次一样：它有标签、有 mock 数据、订单列表按它画徽章 —— 对着真后端永远不会出现，而开发机上看着一切正常。

枚举取值：

- `MERCHANT_OWNED`
- `PLATFORM`

### TrendPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `date` | `string` | 是 | 日期 YYYY-MM-DD |
| `gmv` | `number` | 是 | 当日成交额（最小货币单位整数） |
| `orderCount` | `number` | 是 | 当日订单数 |

### Unattributable

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `addressesWithoutCoords` | `number` | 是 | 没坐标的收货地址：推不出聚落，**不是没人** |
| `addressesOutsideFences` | `number` | 是 | 有坐标但不落在任何围栏里：那儿真的有人，只是还没在那儿开聚落 —— **这是开城线索** |
| `storesWithoutCoords` | `number` | 是 | 没标点的门店：自送半径对它形同虚设 |
| `communitiesClosed` | `number` | 是 | 已关闭的聚落：不在 rows 里，但历史数据还在 |

### Violation

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `violationNo` | `string` | 是 | 违规记录单号 |
| `merchantNo` | `string` | 是 | 涉事商家 |
| `merchantName` | `string` | 是 | 商家名快照 |
| `type` | [`ViolationType`](#violationtype) | 是 | 违规类型。**只有 `BREACH` 计入 breachCount** |
| `action` | [`ViolationAction`](#violationaction) | 是 | 处置动作。`SUSPEND` 会真的把商家状态推到 SUSPENDED |
| `storeNo` | `string,null` | 否 | 门店级处置的对象门店。**`STORE_OFFLINE` 必有、其余动作必空** —— 主体级处置带上门店号会让人以为只压了那一家。 |
| `detail` | `string` | 是 | 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 |
| `operator` | `string` | 是 | 处置人（STAFF 账号） |
| `at` | `string` | 是 | 处置时间 |

### ViolationAction

处置动作。**两个动作有真副作用**，不是记一笔就完： `SUSPEND` 把整个商家推到 SUSPENDED，`STORE_OFFLINE` 把**指定的那一家门店** 压到 SUSPENDED 并撤下它的货架行。 门店级独立成一个 action 而不是「SUSPEND + 可选 storeNo」：后者的语义 由一个可空字段决定，读处置记录的人分不出「封了整个商家」和「只压了一家店」。

枚举取值：

- `WARN`
- `LIMIT`
- `SUSPEND`
- `STORE_OFFLINE`

### ViolationType

违规类型。分开是因为**处置尺度不同**，笼统记成"违规"之后没法按类型统计。

枚举取值：

- `FAKE_GOODS`
- `BREACH`
- `PRICE_FRAUD`
- `SERVICE`

### WxTemplates

微信订阅消息的模板号映射。**唯一一项开放到运营端的通道参数**（模板号不是凭据）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderArrived` | `string` | 是 | 「订单已送达」用的微信模板 id |
| `refunded` | `string` | 是 | 「退款成功」用的微信模板 id |
