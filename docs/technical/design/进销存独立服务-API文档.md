# 进销存独立服务 · API 文档

> 状态：v1（2026-10-30）。本文同时是接口说明和「组织边界」能力的契约。
>
> 依据：
> - [TDD-进销存独立服务与多业态.md](TDD-进销存独立服务与多业态.md)（§2.13 契约、§2.19 组织边界与成对单据）；
> - [进销存-库存组织边界与出库对象.md](进销存-库存组织边界与出库对象.md)。
>
> 标「**新增**」的是本轮补充的能力，其余为现状。客户端：Java 宿主用 `inventory-client`；其他宿主（如 ShareHub）自写 HTTP 客户端。

## 0. 模型速览

库存服务只认四样东西：

| 对象 | 含义 | 由谁定义 |
|---|---|---|
| **系统** | 调用方（SHOP / SHAREHUB / FOOD …），租户边界 | 平台开户 |
| **账本（业主 Owner）** | 独立持有、独立核算、可以一起预留的一份库存 | 宿主选定粒度：门店、连锁、主体、平台运营方 |
| **库位（Location）** | 货在哪：仓 / 门店 / 站点 / 在途 / 虚拟 | 账本内维护 |
| **出库对象（Target）** | 货出去给谁：商家、站点、人、供应商、兄弟门店 | 宿主同步 |

账户、组织层级、授权、资金都不在库存服务里。宿主把它们翻译成「哪本账、哪个库位、哪个出库对象」再来调用。

---

## 1. 通用约定

### 1.1 入口与鉴权

| 入口 | 路径前缀 | 鉴权 | 谁用 |
|---|---|---|---|
| 宿主接口 | `/api/inventory/v1/**` | `Authorization: Bearer <appKey>:<appSecret>` | 宿主后端 |
| 系统级接口 | `/api/inventory/v1/system/**` | **只认系统凭证**，不指定账本 | 多账本宿主（商城） |
| 商家后台转发 | `/api/inventory/v1/console/**` | 同宿主接口 | 商城把已切流商家的后台请求原样转发 |
| 平台运维 | `/api/inventory/admin/v1/**` | `X-Inventory-Admin-Token` | 平台运维 |

**两种凭证**：

| 凭证 | 绑定 | 怎样确定账本 |
|---|---|---|
| 账本凭证 | 一本账 | 凭证本身决定，不需要额外的头 |
| 系统凭证 | 一个系统 | 请求头 `X-Inventory-Owner-Ref` 指定本系统的账本；**新增** `X-Inventory-Owner-Type` 可选，见 §1.2 |

无论读写，系统凭证指定的账本都必须是**写入方已翻到独立服务（SVC）**的账本。

### 1.2 请求头

| 头 | 必填 | 说明 |
|---|---|---|
| `Authorization` | 是 | `Bearer <appKey>:<appSecret>` |
| `X-Inventory-Owner-Ref` | 系统凭证时必填 | 账本在宿主里的编号（`externalRef`） |
| `X-Inventory-Owner-Type` | **新增**，可选 | 账本的外部类型（`externalType`）。同一系统里同一个编号可能对应不同类型的账本（门店和连锁撞号），这时必须带上；只有一本时可以省略 |
| `X-Inventory-Actor` | 建议 | 操作人，写入审计，≤ 64 字符 |
| `Idempotency-Key` | 写操作建议带 | 幂等键，按 `(系统, 账本, 键)` 唯一。同键同参返回原结果；同键不同参返回 `IDEMPOTENCY_CONFLICT` |
| `X-Trace-Id` | 否 | 链路号，没有就生成 |

### 1.2a 标识：内部号或宿主编号（M12）

宿主接口里凡是要物料或库位的地方，都可以给内部号（`itemId` / `locationId`），也可以给宿主自己的编号（`itemRef` / `locationRef`）。两者都给时以内部号为准。
- 编号不存在：404，`args` 为 `itemRef:<编号>` 或 `locationRef:<编号>`；
- 必填却一个都没给：400，`args` 为 `itemId` 或 `locationId`。

| 适用 | 字段 |
|---|---|
| 入库 / 出库建单与追加 | `locationRef`；行上 `itemRef` |
| 调拨 | `fromLocationRef`、`toLocationRef`；行上 `itemRef` |
| 成对收货 | 行上 `itemRef`：用宿主物料号，与发货方同一个，不需要知道收货方账本里的内部号 |
| 冻结 | `itemRef`、`locationRef` |
| 开盘点 | `locationRef`、`itemRefs` |
| 余额 / 在仓 SN / 流水查询 | 查询参数 `itemRef`、`locationRef`；余额与 SN 的响应同时带内部号和编号 |

- 物料编号：先认物料自己的 `externalRef`，再认商城的 SKU 映射。
- 库位编号：仓库或门店的 `externalRef`，精确匹配，不会被换成发货源库位。

尚未支持编号的：单据详情、盘点行（路径里的 `{itemId}`）。

### 1.3 错误

HTTP 状态码 + 统一结构：

```json
{"code": "inventory.stock.short", "message": "STOCK_SHORT", "data": {"args": ["ITEM-1"]}}
```

| 错误 | HTTP | 含义 |
|---|---|---|
| `BAD_REQUEST` | 400 | 参数不合法；`args` 指出字段 |
| `UNAUTHORIZED` / `FORBIDDEN` | 401 / 403 | 凭证无效或无权 |
| `NOT_FOUND` | 404 | 账本、单据、预留等不存在 |
| `CONFLICT` | 409 | 状态不允许（单据已过账等） |
| `STOCK_SHORT` | 409 | 可用量不足 |
| `NOT_WRITER` | 409 | 这本账的写入方不是独立服务（过渡期） |
| `IDEMPOTENCY_CONFLICT` | 409 | 同幂等键不同参数 |
| `CAPABILITY_DISABLED` | 403 | 档案没开这个能力包 |
| `PROFILE_INVALID` | 400 | 档案不合法 |
| `SERIAL_CONFLICT` | 409 | SN 状态冲突，`args` 逐个列出 |
| `COUNT_FROZEN` / `COUNT_ALREADY_OPEN` | 409 | 盘点冻结中 / 已有盘点 |
| `DISPATCH_*` / `ISSUE_VOID_FORBIDDEN` | 409 | 出库池状态冲突 |
| `EVENTS_CURSOR_EXPIRED` | 410 | 事件游标早于清理点 |
| `SYSTEM_NOT_SERVED` | 403 | 本套部署不服务这个系统 |
| `UNAVAILABLE` / `DEPENDENCY_UNAVAILABLE` | 503 | 服务或依赖不可用；客户端熔断即拒单 |

### 1.4 命令查询

所有写操作都按幂等键记录命令。结果未知时，宿主用原键查询：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/commands/{requestId}` | 查询某个幂等键对应命令的结果 |

**保留期（契约）**：命令记录至少保留 **90 天**。现状不清理；将来若加清理任务，保留期不少于 90 天，并提前公告。宿主的补偿窗口按它定：超过 90 天的命令不要再按原键查结果、重发。

---

## 2. 平台运维（admin）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/admin/v1/systems/{systemCode}/credentials` | 签发**系统凭证**（secret 只返回一次） |
| POST | `/api/inventory/admin/v1/owners` | 开账本。**新增 `externalType`**，见下 |
| POST | `/api/inventory/admin/v1/owners/{ownerId}/credentials` | 签发账本凭证 |
| PUT | `/api/inventory/admin/v1/owners/{ownerId}/profile` | 安装 / 更新业态档案（追加新版本） |
| POST | `/api/inventory/admin/v1/owners/{ownerId}/write-authority/flip` | 灰度切换写入方：置翻转中 → 等在途写入 → 对账 → 平则翻，不平则翻回 |
| PUT | `/api/inventory/admin/v1/owners/{ownerId}/write-authority` | 强制改写写入方（不对账，只用于排障） |
| GET | `/api/inventory/admin/v1/owners/{ownerId}/reconciliation` | 只对账不翻 |
| POST | `/api/inventory/admin/v1/owners/{ownerId}/imports?apply=` | 旧数据导入：先出差异报告，确认后执行。**新增** `pool`：已发出、未签收的件，见下 |
| POST | `/api/inventory/admin/v1/owners/{ownerId}/events/prune` | 清理已确认事件 |

### 开账本

```http
POST /api/inventory/admin/v1/owners
X-Inventory-Admin-Token: …
X-Inventory-Actor: OPS:alice

{"systemCode": "SHOP", "externalType": "CHAIN", "externalRef": "CH1001", "name": "某某连锁"}
```

响应 `{"ownerId": "OW…"}`。

`externalType` 是宿主组织的类型（例如 `STORE` / `CHAIN` / `ENTITY` / `PLATFORM`）：
- 库存服务**不解释**它，只用于 `(systemCode, externalType, externalRef)` 唯一；
- 可以不给。不给时按系统取缺省：SHOP 为 `ENTITY`，其他系统为 `PLATFORM`。V26 的存量回填用的是同一条规则。

同一 `(系统, 类型, 编号)` 重复开账本返回 `CONFLICT`。

---

### 导入「在出库池」的件（M11）

```json
{"batchId": "MIG-1", "warehouses": [...], "items": [...], "targets": [...], "stock": [...],
 "pool": [{"warehouseRef": "WH-SZ", "purpose": "ISSUE",
           "targets": {"MERCHANT": "AG001", "SITE": "ST01", "PERSON": "E1001"},
           "serials": [{"itemRef": "CAB", "serialNo": "C9", "gateRef": "QC-C9"},
                       {"itemRef": "PB",  "serialNo": "P9", "parentSerialNo": "C9"}]}]}
```

- 每个 `pool` 元素对应宿主的一张发货单。执行时先以 `OPENING` 入库（带组装关系与放行凭证；`gateRef` 为空时取 `MIGRATION:<批次号>`），再按 `purpose`（缺省 `ISSUE`）出库进池，待签收、不领取。入了又出，所以余额不变。
- 已经在池里（待签收或已签收）的 SN 跳过，所以重跑结果为空。
- SN 当前还在某个仓里报冲突 `<sn>:in_stock`；池行没有 SN 报冲突 `<仓号>:pool.serials_required`。有冲突时执行整批 409。
- 报告新增 `poolToIssue`（要进池的 SN）、`outboundNos`（生成的出库单）。

## 3. 主数据

### 3.1 档案与物料

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/profile` | 当前账本的业态档案（只读） |
| PUT | `/api/inventory/v1/items/by-ref/{externalRef}` | 按宿主编号新增 / 更新物料（型号、规格、是否 SN 管理） |
| GET | `/api/inventory/v1/items` | 物料列表 |

### 3.2 仓库（库位）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/warehouses?holderRef=` | 仓库列表；**新增**按持有方过滤 |
| POST | `/api/inventory/v1/warehouses` | 新建仓库；**新增** `externalRef`、`holderRef` |
| PATCH | `/api/inventory/v1/warehouses/{locationId}` | 改名、区域；**新增** `holderRef`。补丁语义：没给的字段不动 |
| POST | `/api/inventory/v1/warehouses/{locationId}/disable` | 停用：有余额、未完成单据或冻结时拒绝，一次列全 |

```json
POST /api/inventory/v1/warehouses
{"name": "文三路店", "externalRef": "S2001", "holderRef": "E100"}

GET /api/inventory/v1/warehouses
[{"locationId": "LC…", "name": "文三路店", "externalRef": "S2001", "regionRef": null,
  "holderRef": "E100", "status": "ACTIVE"}]
```

- `externalRef`：库位在宿主里的编号（门店号、站点号），本账本内唯一。成对单据靠它找到收货库位（§5）。
- `holderRef`：宿主的「持有方」标记，例如一本连锁账本里每个门店所属的主体。库存服务只存储和过滤，不解释。

### 3.3 出库对象

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/api/inventory/v1/external-targets/{type}/{ref}` | 同步一个出库对象（幂等；旧版本到达时忽略） |
| GET | `/api/inventory/v1/external-targets?type=&parentRef=` | 列表 |

```json
PUT /api/inventory/v1/external-targets/STORE/S3001
{"name": "西湖店（加盟）", "parentRef": null, "status": "ACTIVE", "sourceVersion": 7,
 "linkedOwner": {"type": "STORE", "ref": "S3001"}, "linkedLocationRef": "S3001"}
```

| 字段 | 说明 |
|---|---|
| `type` | 档案 `targetTypes` 声明过的类型；没声明返回 `BAD_REQUEST type` |
| `parentRef` | 类型声明了上级时必填，且上级必须已同步（站点 → 商家） |
| `status` | `ACTIVE` / `DISABLED`。停用后不能作为新出库单的对象 |
| `sourceVersion` | 宿主侧版本，只增不减；小于库里的版本时忽略本次推送 |
| **`linkedOwner`** | **新增**。这个对象在**同一系统**里也有自己的账本时，填对方账本的外部类型和编号。服务端解析为对方账本，找不到返回 `NOT_FOUND linkedOwner` |
| **`linkedLocationRef`** | **新增**，与 `linkedOwner` 同时出现。对方账本中的收货库位（库位 `externalRef`），找不到返回 `NOT_FOUND linkedLocation` |

响应和列表中带出解析后的 `linkedOwnerId`、`linkedLocationId`。每次同步按整份状态处理：不带 `linkedOwner` 就是解除关联。

---

## 4. 入库、出库、调拨、盘点

### 4.1 入库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/inbounds` | 建草稿：库位、用途（档案 `inboundPurposes`）、来源、依据、附件、行 |
| PATCH | `/api/inventory/v1/inbounds/{no}` | 追加行 |
| POST | `/api/inventory/v1/inbounds/{no}/post` | 过账。**成对入库单不能走这里**，返回 `CONFLICT paired_use_receive` |
| POST | `/api/inventory/v1/inbounds/{no}/cancel` | 作废草稿。**成对入库单不能作废**，返回 `CONFLICT paired` |
| POST | `/api/inventory/v1/inbounds/{no}/business-clearance` | 业务放行（档案带门禁时） |
| POST | `/api/inventory/v1/inbounds/{no}/receive` | **新增**：确认收货，只用于成对入库单，见 §5 |
| GET | `/api/inventory/v1/inbounds/{no}` | 详情 |

### 4.2 出库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/outbounds` | 建草稿：库位、用途（档案 `outboundPurposes`）、出库对象 `targets{type: ref}`、行、SN |
| PATCH | `/api/inventory/v1/outbounds/{no}` | 追加行或 SN |
| POST | `/api/inventory/v1/outbounds/{no}/post` | 过账：扣在手。交接方式为 `POOL` 时生成出库池记录；**新增**交接方式为 `RECEIPT` 时生成对方账本的待收入库单 |
| POST | `/api/inventory/v1/outbounds/{no}/cancel` | 作废草稿 |
| GET | `/api/inventory/v1/outbounds/{no}` | 详情 |

### 4.3 调拨（账本内）与在途

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/transfers` | **新增**（M11）：建调拨单。`{fromLocationId, toLocationId, lines:[{itemId, qty}], serialNos:[…], remark}`；扫上级件带出它挂着的下级件；数量行和 SN 至少有一样 |
| GET | `/api/inventory/v1/transfers/{no}` | 详情：状态、出入库单号、逐行物料与 SN |
| POST | `/api/inventory/v1/transfers/{no}/ship` | 发运：调出仓出库，同一事务进本账本的在途库位。SN 的放行凭证沿用原值，组装关系不拆 |
| POST | `/api/inventory/v1/transfers/{no}/receive` | 收货：在途出库、进调入仓；组装关系与凭证原样带到调入仓 |
| POST | `/api/inventory/v1/transfers/{no}/cancel` | 作废，只限草稿；已发运的要收回，开一张反向调拨 |
| — | `/api/inventory/v1/console/docs/transfers` | 商家后台转发的调拨（商城结构，不支持 SN）。同一张单只应由建它的那一侧推进 |

**跨账本不走调拨**，见 §5。调出、调入的系统用途 `TRANSFER_OUT / TRANSFER_IN` 不需要在档案里登记。
| GET | `/api/inventory/v1/in-transit` | 「货在外面」的统一视图，见下 |

**`GET /in-transit` 响应**（V27 / M10 起合并三种来源；不再要求出库池能力包，没开就没有 POOL 行）：

```json
[{"kind": "POOL",     "rootTargetType": "MERCHANT", "rootTargetRef": "AG001", "itemId": "ITM…", "qty": 9,  "docNo": null},
 {"kind": "PAIRED",   "rootTargetType": "STORE",    "rootTargetRef": "S3001", "itemId": "ITM…", "qty": 3,  "docNo": "OUT…"},
 {"kind": "TRANSFER", "rootTargetType": "LOCATION", "rootTargetRef": "LOC…",  "itemId": "ITM…", "qty": 1,  "docNo": "TRF…"}]
```

| `kind` | 来源 | 目标 | 粒度 |
|---|---|---|---|
| `POOL` | 出库池待签收 | 顶层目标（如商家） | 按目标 + 物料汇总 |
| `PAIRED` | 成对单据已发未收 | 收货方账本的宿主类型与编号 | 每张单每个物料一行，`docNo` 为出库单号 |
| `TRANSFER` | 账内调拨已发运未收货 | `LOCATION` + 调入库位 | 每张单每个物料一行，`docNo` 为调拨单号 |

### 4.4 盘点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/counts` | 开盘点（冻结范围） |
| GET | `/api/inventory/v1/counts/{no}` | 详情 |
| POST | `/api/inventory/v1/counts/{no}/scans` | 扫码计数 |
| PUT | `/api/inventory/v1/counts/{no}/lines/{itemId}` | 录入实盘数 |
| POST | `/api/inventory/v1/counts/{no}/submit` / `reject` / `approve` | 复核流（档案 `countReview`） |
| POST | `/api/inventory/v1/counts/{no}/post` | 过账：生成盘盈、盘亏单 |
| POST | `/api/inventory/v1/counts/{no}/cancel` | 取消，解冻 |

### 4.5 在手直改

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/stock/on-hand` | 把在手数改到目标值（自动生成盘盈或盘亏单） |

---

## 5. 跨账本成对单据（**新增**）

货发给**关联了账本**的出库对象（兄弟门店、加盟店）时，一出一入两张单：

```
A 账本：POST /outbounds  (用途的 handover = RECEIPT，对象 = STORE:S3001)
        POST /outbounds/{no}/post ──▶ 扣 A 在手；同一事务内在 B 账本生成待收入库单（DRAFT）
                                       并产生成对记录 SHIPPED，B 收到事件 PairedInboundCreated
B 账本：POST /inbounds/{no}/receive {lines:[实收]} ──▶ 入 B 在手；成对记录 RECEIVED
                                       A 收到事件 PairedTransferReceived（带差异）
```

### 5.1 档案配置

```json
"targetTypes": [{"code": "STORE", "nameKey": "inventory.target.store", "parentType": null}],
"inboundPurposes": [ …, {"code": "STORE_IN", …}, {"code": "SUPPLY_IN", …} ],
"outboundPurposes": [
  {"code": "STORE_OUT", "requiredTargets": ["STORE"], "optionalTargets": [],
   "handover": "RECEIPT", "receiptPurpose": "STORE_IN"},
  {"code": "SUPPLY_OUT", "requiredTargets": ["STORE"], "optionalTargets": [],
   "handover": "RECEIPT", "receiptPurpose": "SUPPLY_IN"}
]
```

用途码最长 32 个字符（档案校验、单据表、流水三处一致；V27 起单据表从 16 放宽到 32）。

档案校验规则：
- `handover = RECEIPT` 必须**恰好一个**必填对象类型，并给出 `receiptPurpose`；
- `receiptPurpose` 必须是本档案登记过的入库用途。对方账本也要登记同名入库用途，过账时校验。

### 5.2 规则

| 规则 | 说明 |
|---|---|
| 前提 | 出库对象必须带 `linkedOwner`，否则建单时返回 `BAD_REQUEST target.not_linked`。收货方账本的写入方必须是 SVC，否则过账时返回 `NOT_WRITER`（判的是过账那一刻的事实），什么都不落 |
| SN | 本版只支持数量物料。出库单含 SN 时返回 `BAD_REQUEST paired.serial_unsupported` |
| 原子性 | A 的出库过账、B 的待收入库单、成对记录在同一事务内 |
| 在途 | 出库过账后、收货前，货已离开 A 的在手、尚未进入 B 的在手；通过成对记录的 `SHIPPED` 状态查询「已发未收」 |
| 收货 | B 按实收确认：未列出的物料算 0；实收可以多于或少于发货数；数量全为 0 也可以确认，这时 B 的入库单作废，成对记录照样记为已收 |
| 差异 | 成对记录保存每行的发、收、差，并发给 A 事件；差异如何处理（补发、索赔、内部往来）由宿主决定 |
| 不可撤 | B 的待收入库单不能作废，也不能直接 `post`，只能 `receive` |
| 成本 | 本版 B 入库不带单价（按 B 的估值方式处理）；按供货价入账留待下一版 |

### 5.3 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/inbounds/{no}/receive` | B 确认收货 |
| GET | `/api/inventory/v1/paired-transfers?direction=OUT\|IN&status=SHIPPED\|RECEIVED` | 本账本作为发货方（OUT）或收货方（IN）的成对记录 |
| GET | `/api/inventory/v1/paired-transfers/{pairNo}` | 详情，含逐行的发、收、差 |

```json
POST /api/inventory/v1/inbounds/IN20261030001/receive
{"lines": [{"itemId": "IT1", "qty": 9}, {"itemId": "IT2", "qty": 5}]}

GET /api/inventory/v1/paired-transfers/PT…
{"pairNo": "PT…", "status": "RECEIVED",
 "from": {"ownerId": "OWN…", "ownerType": "CHAIN", "ownerRef": "CH1001", "docNo": "OUT…", "locationId": "LOC…"},
 "to":   {"ownerId": "OWN…", "ownerType": "STORE", "ownerRef": "S3001", "docNo": "IN…", "locationId": "LOC…"},
 "shippedAt": "…", "receivedAt": "…",
 "lines": [{"itemRef": "SKU-1", "fromItemId": "ITM…", "toItemId": "ITM…", "shipped": 10, "received": 9},
           {"itemRef": "SKU-2", "fromItemId": "ITM…", "toItemId": "ITM…", "shipped": 5,  "received": 5}]}
```

两本账的物料各自独立，按**宿主物料编号**（物料的 `externalRef`）对应；A 的物料没有宿主编号时，过账返回 `BAD_REQUEST paired.item_ref:<itemId>`。B 账本里没有对应物料时，生成待收入库单时会自动建档：名称、单位、属性取自 A 的物料，按数量管理；放行策略不随过去，为 `NONE`。同一物料在出库单上的多行合成一行，收货按物料确认（`itemId` 是 B 账本的物料号，取自成对记录行的 `toItemId`）。

---

## 6. 预留（电商下单）

账本接口（系统凭证 + 账本头，或账本凭证）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/reservations` | 预留（按宿主单号 `ref`，带生命周期版本） |
| GET | `/api/inventory/v1/reservations/{ref}` | 查询 |
| POST | `/api/inventory/v1/reservations/{ref}/accept-unpaid` · `allocate-paid` · `issue` · `cancel` · `release-allocated-to` | 生命周期推进 |
| POST | `/api/inventory/v1/reservations/restores` | 退货按原来源恢复（需原单号） |

系统级接口（只有单号、没有账本时）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/inventory/v1/system/reservations/{ref}/release` · `commit` · `allocate-paid` | 按单号跨本系统的 SVC 账本操作 |
| POST | `/api/inventory/v1/system/fulfillments/{fulfillmentRef}/issue` · `release-allocated` | 按履约单操作 |

---

## 7. 查询

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/balances?locationId=&itemId=` | 本账本余额：在手、预留、不可用、可用 |
| POST | `/api/inventory/v1/holds` · `/holds/{holdId}/release` | 手工冻结 / 解冻 |
| GET | `/api/inventory/v1/serials` | 在库 SN |
| POST | `/api/inventory/v1/serials/{serialNo}/assemble` · `disassemble` | 组装 / 拆解 |
| GET | `/api/inventory/v1/ledger` | 流水 |
| GET | `/api/inventory/v1/reports/period-summary` | 期间汇总 |
| POST | `/api/inventory/v1/system/balances/query` | **新增**：多账本批量余额，见下 |

### 7.1 多账本批量余额（**新增**）

用途：宿主在业务侧做「连锁看所有门店库存」这类汇总。库存服务不知道这些账本为什么被一起查。

```http
POST /api/inventory/v1/system/balances/query
Authorization: Bearer <系统凭证>

{"owners": [{"type": "STORE", "ref": "S2001"}, {"type": "STORE", "ref": "S3001"}],
 "itemRefs": ["SKU-1", "SKU-2"]}
```

```json
[{"ownerType": "STORE", "ownerRef": "S2001", "error": null,
  "balances": [{"itemRef": "SKU-1", "itemId": "ITM…", "locationId": "LOC…", "locationRef": "S2001",
                "onHand": 10, "reserved": 2, "unavailable": 0, "available": 8}]},
 {"ownerType": "STORE", "ownerRef": "S3001", "error": "NOT_WRITER", "balances": []}]
```

| 规则 | 说明 |
|---|---|
| 鉴权 | 只认系统凭证，账本凭证调用返回 403。`owners` 必须属于本系统且写入方为 SVC，否则该项的 `error` 为 `NOT_FOUND` 或 `NOT_WRITER`，不影响其他项 |
| 上限 | `owners` ≤ 50，`itemRefs` ≤ 200；`itemRefs` 为空表示全部物料 |
| `type` | 可省略；省略时按编号匹配，同号多本时该项的 `error` 为 `owner_type_required` |

---

## 8. 出库池（SHAREHUB 等交接业态）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/dispatch-pool` | 待领用 |
| POST | `/api/inventory/v1/dispatch-pool/claims` | 领用 |
| GET | `/api/inventory/v1/dispatch-pool/claims/by-request/{requestId}` | 按原请求恢复领用结果 |
| POST | `/api/inventory/v1/dispatch-pool/claims/{claimNo}/release` | 退回 |
| POST | `/api/inventory/v1/dispatch-pool/{dispatchId}/redirect` · `revoke` | 改派 / 撤回 |

---

## 9. 事件

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory/v1/events?cursor=&limit=` | 拉取本账本事件（至少一次，按游标） |
| POST | `/api/inventory/v1/event-acks` | 确认到某个游标 |

| 事件 | 发给 | 载荷 |
|---|---|---|
| `InboundPosted` / `OutboundPosted` | 本账本 | 单号、用途、库位 |
| `CountPosted`、出库池事件等 | 本账本 | 见 TDD §2.12 |
| **`PairedInboundCreated`** | 收货方账本 | `pairNo`、`inboundNo`、发货方 `ownerRef/ownerType`、`outboundNo` |
| **`PairedTransferReceived`** | 发货方账本 | `pairNo`、`outboundNo`、逐行 `itemRef/shipped/received/diff` |
| **`TransferShipped`** / **`TransferReceived`** | 本账本 | 调拨单号；发运带调出、调入库位，收货带在途出库单号、调入入库单号 |

---

## 10. 其他

| 方法 | 路径 | 说明 |
|---|---|---|
| `/api/inventory/v1/stock-projections/**` | — | 餐饮库存投影（FOOD），见 FOOD 文档 |
| `/api/inventory/v1/console/**` | — | 商城商家后台转发，返回结构与商城 `BizStockController` 等逐个对应，见 TDD §2.3 |

---

## 11. 宿主接入清单

| 宿主 | 账本 | 凭证 | 出库对象 | 用到的新增能力 |
|---|---|---|---|---|
| ai-shop | 门店 / 连锁 / 主体（存量） | 系统凭证 + 账本头（+ 类型头） | STORE（兄弟门店、加盟店，关联账本）、SUPPLIER | 外部类型、关联账本、成对单据、批量余额、持有方 |
| ShareHub | 平台运营方（`PLATFORM`） | 账本凭证 | MERCHANT / SITE / PERSON | 无（存量回填为 `PLATFORM`，开账本不给类型也取这个缺省） |

---

## 12. 电商（ai-shop）接入要点

ShareHub 的接入见 [进销存独立服务-ShareHub对接方案](./进销存独立服务-ShareHub对接方案.md) §七。

| 事项 | 做法 |
|---|---|
| 凭证 | 一把**系统凭证**（`SHOP`），每次请求用 `X-Inventory-Owner-Ref` 指定账本；同号有多本时加 `X-Inventory-Owner-Type` |
| 账本粒度 | 存量：一个经营主体一本（`ENTITY`），不迁移。直营连锁共用总仓：连锁一本（`CHAIN`），门店是库位，库位的 `holderRef` 标出门店所属主体。加盟店、独立进货的门店：一店一本（`STORE`） |
| 下单 | 预留 → 付款分配 → 按履约单出库（自动生成 `SALE` 出库单）；取消时释放，退货按原单回补。只有单号、不知道账本时，用 `/system/**` 系统级接口 |
| 门店之间调货 | 同一本账：账内调拨。不同账本：同步 `STORE` 出库对象（带 `linkedOwner` + `linkedLocationRef`），用 `STORE_OUT` / `SUPPLY_OUT` 出库，对方用 `receive` 确认收货 |
| 连锁看各店 | 由商城按连锁成员组织账本列表，调 `POST /system/balances/query` 后在商城侧汇总 |
| 在外 | `GET /in-transit`：`PAIRED`（发往兄弟门店、加盟店，未收）和 `TRANSFER`（账内调拨在途） |
| 商家后台 | 已切到服务的商家，后台请求原样转发到 `/console/**`，返回结构与商城一致 |

**尚未具备**（见 [评审](./进销存-统一逻辑与设计评审.md) §6）：
- 商城侧「门店 → 账本 + 库位」的统一映射入口（C8）。新的连锁或门店粒度要等它上线才能开账，在那之前只能是主体一本账；
- 商家后台转发的单据不按档案执行，也不发宿主事件（K1）。

