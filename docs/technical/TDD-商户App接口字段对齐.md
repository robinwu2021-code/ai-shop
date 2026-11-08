# TDD-商户App接口字段对齐

状态：已实现（2026-11-08；「已拍板 2」履约方式因新发现重开，未实现，见 §6）
档位：1（已有端点补字段；不新增端点、不改权限码；只有一条数据回填迁移，不改表结构）
关联需求：商户 App 交接文档《功能矩阵与API交接文档》附录 A（ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md`，main `31a56c1`）；ADR-034（可见范围模型）；TDD-双地图提供方与门店选点（AC5 坐标系）
创建：2026-11-08 · 最后更新：2026-11-08

## §0 对账一 · 需求 → 设计

附录 A 里标「后端补」或「后端确认」的 6 项。标「App 改」的（列印请求、考勤代码、发券、积分、自提点判断）不在本文。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| A1 | Given 店主或店长在某门店 When `GET /biz/store` Then 响应带 `name`、`slug`、`status`、`isDefault`、`planSuspended`，取值与 `/biz/stores/mine` 里同一门店那一行一致；`status` 与 `planSuspended` 出自**同一次读取的同一行**（商家自己暂停与方案压店在 `status` 里都是 `READONLY`，App 只能靠 `planSuspended` 区分） | `StoreProfileVO` 加 5 个字段；`MerchantStoreService#profile` 从同一个 `MchStore` 取 |
| A2 | Given 商家画了范围 When `POST /biz/store/scope-preview` 带 `areas[{level: POLYGON, mode, geometry}]` Then 预估把这片范围算进去（现在被忽略）；`mode=EXCLUDE` 的多边形在预估里扣除，与保存后的可见性判定一致 | `ScopePreviewReq.AreaItem` 加 `geometry`；预估复用 `GeoPolygon.parse` 与保存时同一条判点路径 |
| A3 | Given 自画范围 When 保存、预估或回读 Then `geometry` 的格式与坐标系只有一种说法：请求与 `GET/POST /biz/store` 响应里的 `serviceAreas[].geometry` **同一格式**；图形不合法（顶点不足、超过 200 个、越界、退化）统一回 **10473** | 格式保持 ADR-034 的 `[[lngE6,latE6],…]` JSON 字符串；保存已用 10473（`MerchantStoreServiceImpl.java:431`），预估同样用它；坐标系见「已拍板 1」与 §2「坐标系回填」 |
| A4 | Given 主体方案降级、有门店被压成只读 When `GET /biz/plan` Then 返回每家受影响门店的 `storeNo`、`name`、`openOrders`（该店状态不在 `COMPLETED / CANCELLED / REFUNDED` 的子订单数），以及宽限期截止 `graceUntil`（`ACTIVE` / `GRACE` 有值，`EXPIRED` 为 null）；旧字段 `suspendedStores`（门店名数组）不变 | `MinePlanVO` 加 `suspendedStoreDetails[]` 与 `graceUntil` |
| A5 | Given 员工管理 When `POST /biz/staff/{no}/store` 的 `role` 是商家自定义角色码 Then 授予成功；OpenAPI 不再把 `role` 限成 5 个预置值 | 后端**已支持**（见 §1），只改 OpenAPI 描述 |
| A6 | Given 门店订阅 When App 要显示方案到期日 Then 读 `currentPlan.planEndsAt` | 后端**已有**，App 改读法（见 §1），本文不改代码 |

**孤立项**
- 没落点的 AC：「进件签约 `signRequired` / `signUrl`」（附录 A 第 4 模块）。后端没有签约环节的数据，也没有渠道签约链接的来源，补不出来；放到 §2「待决 3」。
- 挂不上 AC 的设计：无。

## §1 现状与影响面

逐项核对 `origin/codex/ai-shop-codex`（`fc070b2d4`）：

| 项 | 后端现状（出处） | 结论 |
|---|---|---|
| `GET /biz/store` 字段 | `StoreProfileVO`（`backend/shop-merchant/.../dto/StoreProfileVO.java:27`）只有公告、营业时间、地址、履约、服务范围、坐标、横幅，**没有** `name / slug / status / isDefault` | 补（A1） |
| 门店名、状态从哪来 | `/biz/stores/mine` 的门店行 `StoreVO`（`backend/shop-merchant/.../dto/StoreVO.java:23`）已有 `name, isDefault, status, slug, planSuspended` | A1 照同一来源取，不另算一遍 |
| `fulfillmentReach` | 单个字符串，`PICKUP / ONSITE / SHIPPING`，为空按 PICKUP（ADR-013，`MerchantStoreService.java:109`）；存放在 `MchEntity.fulfillmentReach`（`MchEntity.java:166`，主体级字段） | App 收发数组是 App 自拟的；见「待决 2」 |
| `geometry` | `GeoPolygon`（`backend/shop-base/.../geo/GeoPolygon.java:20`）：JSON 字符串 `[[lngE6,latE6],…]`，**经度在前**，整数 E6；JTS 判点，边界算在内 | 格式不改（A3） |
| 坐标系 | 门店坐标有 `coordSys` 列（V630，`MchStore.java:241`）：大陆高德 `gcj02`，台湾 / 阿联酋 Google `wgs84`，null 按 gcj02。**服务范围多边形没有坐标系字段**。V630 只加列、**没有回填**（`V630__store_coord_sys.sql`），V630 之前建的台湾门店 `coord_sys` 都是 null，按现有口径会被当成 gcj02 | 见「已拍板 1」与 §2「坐标系回填」 |
| 多边形回读 | `GET /biz/store` 的 `serviceAreas[].geometry` 直接回库里存的字符串（`MerchantStoreServiceImpl.java:628–632`），与请求同一格式 `[[lngE6,latE6],…]` | 写进 OpenAPI，App 按同一格式解析 |
| 图形校验 | 保存时 `GeoPolygon.parse` 失败回 `SERVICE_AREA_POLYGON_INVALID(10473)`（`MerchantStoreServiceImpl.java:431`）；顶点上限 `shop.reach.polygon-max-vertices` 默认 200（`ReachGeoProps.java:37`） | 预估沿用 10473 与同一上限 |
| 未完成订单 | 买家维度的「未了结」口径：子订单状态不在 `COMPLETED / CANCELLED / REFUNDED`（`OpenOrderPortImpl.java:23`）；**没有按门店计数的现成方法** | A4 按门店计数，沿用同一组终态 |
| 谁能改履约方式 | `POST /biz/store` 判 `biz:store`（`BizMerchantController.java:621`），店长模板含此码（`BizPerms.java:195`）；而 `fulfillmentReach` 存在主体上 | 店长改一家店会影响同主体所有门店，见「已拍板 2」 |
| `scope-preview` | `ScopePreviewReq.AreaItem(level, refCode, mode)`（`BizMerchantController.java:675`），不收 `geometry` | 补（A2） |
| `/biz/plan` 停用门店 | `MinePlanVO.suspendedStores` 是 `List<String>`，**内容是门店名**（`MerchantPlanService.java:186` 注释），不是门店号 | 不能改类型：老 uni-app B 端 `b-app/src/pages/plan/index.vue:163` 直接 `suspendedStores.join("、")` 显示。新增字段（A4） |
| 宽限期 | `PlanExpiryJob`：ACTIVE → GRACE（7 天）→ EXPIRED；`MerchantPlanServiceImpl.GRACE_DAYS = 7`（`:136`） | `graceUntil = expireAt + 7 天`，后端算好下发，App 不再自己推算 |
| 授权自定义角色 | `MerchantStaffServiceImpl#grantStore`（`:290–307`）：注释写明此前判写死的五元组，**V71 起改按 `mch_role` 有无该行判**；只拒空值与 `OWNER`。`MchStoreRole.GRANTABLE`（`:50`）注释同样说明它已不是全集 | 已支持；`docs/api/openapi-b.yaml` 仍写「MANAGER 店长 / CLERK 店员」，过时 |
| 订阅到期日 | 门店上下文与订阅的 `currentPlan` 都是 `FoundationQueryFacade.Source`（`commerce-api/.../FoundationQueryFacade.java:62`），字段名是 `planEndsAt`；从来没有 `effectiveUntil` | App 改读 `planEndsAt` |
| 签约 | 全后端搜 `signUrl / signRequired / 签约`：只有运营端方案控制器的一句注释提到「通道签约是独立项目」；`PaymentApplymentVO`（`dto/PaymentApplymentVO.java:36`）无签约字段 | 后端没有 |

**会被改到的**：`GET /biz/store` 与 `POST /biz/store` 的响应（同一个 VO）、`POST /biz/store/scope-preview` 的请求、`GET /biz/plan` 的响应。三者都只**加字段**，旧客户端（uni-app B 端）忽略未知字段，不受影响。

**明确不受影响的**：`/biz/stores/mine`、门店上下文 / 订阅、员工授权逻辑、范围保存逻辑（只加预估）、`fulfillmentReach` 存储。

## §2 方案

### 契约变更
- **端点**：无新增。
- **响应字段（加）**：
  - `GET|POST /biz/store`：`name`、`slug`、`status`（`ACTIVE | READONLY | SUSPENDED`，与 `/biz/stores/mine` 同义）、`isDefault`、`planSuspended`。
  - `GET /biz/plan`：`suspendedStoreDetails: [{storeNo, name, openOrders}]`、`graceUntil`（毫秒；仅 `status` 为 `ACTIVE` 或 `GRACE` 且有 `expireAt` 时给，否则 null）。
- **请求字段（加）**：`POST /biz/store/scope-preview` 的 `areas[]` 加 `geometry`（仅 `level=POLYGON`，格式同保存）。响应字段不变：`currentCommunities, currentBuyers, nextCommunities, nextBuyers`。
- **格式写明（不改行为）**：`serviceAreas[].geometry` 在请求与 `GET|POST /biz/store` 响应里都是 JSON 字符串 `[[lngE6,latE6],…]`，经度在前、整数 E6；图形不合法回 10473。
- **库表 / 迁移**：一条数据回填（只改数据，不改表结构）`V6xx__store_coord_sys_backfill.sql`：`coord_sys` 为 null、且门店所属市场是台湾或阿联酋的，填 `wgs84`。依据：这两个市场建店选点只用 Google 地图（TDD-双地图提供方与门店选点 AC5），null 只可能来自 V630 之前的存量。实现前先在 166 统计受影响行数，并核对 `coord_sys` 在可见范围判定里实际被谁读取。
- **权限码**：无变化。
- **i18n**：无。
- **配置**：无。
- **OpenAPI**（`docs/api/openapi-b.yaml`、`docs/api/openapi.yaml`）：补上述字段；`geometry` 写明格式与坐标系；`POST /biz/staff/{mchAccountNo}/store` 的 `role` 说明改为「预置角色码或本商户自定义角色码，不能是 OWNER」。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-merchant/.../dto/StoreProfileVO.java` | 加 5 个字段；保留现有构造器给其他调用方 |
| 修改 | `backend/shop-merchant/.../service/impl/MerchantStoreServiceImpl.java`（或 `profile` 实际所在类） | 组装时从 `MchStore` 填入；`status` / `planSuspended` 与 `/biz/stores/mine` 共用同一个推导方法，不复制逻辑 |
| 修改 | `backend/shop-app/.../portal/biz/BizMerchantController.java` | `ScopePreviewReq.AreaItem` 加 `geometry` 并传入预估 |
| 修改 | 预估服务（`MerchantGovernService` 的范围预估入口） | POLYGON 项用 `GeoPolygon.parse` 判买家 / 社区落点；与保存同一个顶点上限配置 `shop.reach.polygon-max-vertices` |
| 修改 | `backend/shop-merchant/.../service/MerchantPlanService.java`、`impl/MerchantPlanServiceImpl.java` | `MinePlanVO` 加 `suspendedStoreDetails`、`graceUntil`；`openOrders` 按门店数未完成订单（与停店拦截用同一个口径的计数口子） |
| 修改 | `docs/api/openapi-b.yaml`、`docs/api/openapi.yaml` 及生成文档 | 见上 |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__store_coord_sys_backfill.sql` | 台湾 / 阿联酋存量门店 `coord_sys` 回填 `wgs84`；只改数据，166 应用账号可直接执行 |
| 修改 | 对应测试类 | 见 §5 |

### 已拍板

> 2026-11-08 用户按建议全部接受：下列各条一律按「建议」执行；「选项」与「后果」保留作决策记录。

1. **范围多边形的坐标系**（A3）
   - 现状：门店坐标记了坐标系（`gcj02` / `wgs84`），多边形顶点没记。大陆用高德地图（gcj02），台湾、阿联酋用 Google 地图（wgs84）。同一地点两种坐标在大陆相差约 300–600 米；台湾用的 Google 本来就是 wgs84，不存在偏移。
   - 风险：多边形和门店、买家坐标如果不在同一坐标系，判「买家在不在范围里」会整体偏几百米，边界附近的买家会被误放或误拒。
   - **建议**：多边形顶点**跟随门店的 `coordSys`**，不单独记；后端保存和预估都按门店坐标系解释。对 App 成立的理由（按评审更正）：App 的 W06（`PolygonDrawPage`）不是地图，而是以门店坐标为中心的空白画布，顶点由门店 `latE6/lngE6` 推出，天然与门店同一坐标系。原型 W06 画的是真地图，以后 App 换成地图组件时要重新核对这一条。前提是台湾存量门店的 `coord_sys` 先回填为 `wgs84`（§2「库表 / 迁移」）。
   - 备选：在 `serviceAreas[]` 上加 `coordSys` 字段让 App 显式声明。灵活，但多一个可能填错的字段。
2. **履约方式：单选还是多选、主体级还是门店级、谁能改**
   - 现状：后端 `fulfillmentReach` 是三个值（`PICKUP / ONSITE / SHIPPING`）里选一个，存在**主体**上；原型 W05 是四项多选（到店自取 / 外送 / 到府服务 / 宅配，至少开一种），「外送」没有对应值，单选也满足不了最常见的「自取 + 宅配」并存。改它的权限是 `biz:store`，店长也有，店长改一家店会影响同主体所有门店。
   - 选项：
     - A（本次不改后端）：App 单选，提示「主体级设置，会同时改同主体所有门店」；可选再把权限收紧到 `biz:store:admin`（只有店主能改）。
     - B（改成门店级多选）：按 2 档另起 TDD，改存储层级与 C 端可见范围判定；原型的「外送」要先定义对应哪个值。
   - **建议**：一期选 A，并把改 `fulfillmentReach` 收紧到店主；同时请产品确认「自取 + 宅配」是不是一期必须，必须就走 B。
3. **进件签约 `signRequired` / `signUrl`**
   - 后端没有签约环节的数据。渠道签约（如蓝新的线上签约页）如果存在，应由支付服务在进件回执里给出，ai-shop 只透传。
   - **建议**：先确认一期接入的通道（蓝新）进件后是否真有「商家点链接签约」这一步。有，则在支付服务侧补，ai-shop 在 `PaymentApplymentVO` 加两个透传字段（另起一份小 TDD）；没有，App 删掉「待签约」状态和 `signUrl` 按钮。

### 关键接口

```text
GET /biz/store → 现有字段 + {
  name: string, slug: string|null, status: "ACTIVE"|"READONLY"|"SUSPENDED",
  isDefault: boolean, planSuspended: boolean }

POST /biz/store/scope-preview
  areas[]: { level, refCode?, mode, geometry?: string /* "[[lngE6,latE6],…]"，仅 POLYGON */ }

GET /biz/plan → 现有字段 + {
  suspendedStoreDetails: [{ storeNo: string, name: string, openOrders: int }],
  graceUntil: long|null }
```

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| A1 | `BizPlanFlowTest#suspendedListNamesOnlyPlatformPressedStores`（追加断言）：同一主体下方案压店的门店 `GET /biz/store` 回 `status=READONLY`、`planSuspended=true`、`isDefault=false`、`name` 与套餐页一致、带 `slug` 键；商家自停的门店 `status=READONLY`、`planSuspended=false` | ✅ 8/8 | 实现前字段缺失变红（`get("graceUntil")` 为 null 抛 NPE，先于 A1 断言） |
| A2 | `ScopePreviewFlowTest#polygonIsCountedInPreviewAndMatchesSave`：多边形框住的社区进入预估、框外的不进；保存后真查一次可见范围，与预估逐一相等 | ✅ 6/6 | 实现前红：「画了范围预览却是空的」 |
| A3 | `ScopePreviewFlowTest#invalidPolygonInPreviewIs10473`：纬度在前、只有 2 个顶点，预估都回 10473；保存侧既有 `ServiceAreaPolygonFlowTest`（8/8）不变 | ✅ | 实现前红：没有抛异常 |
| A4 | `BizPlanFlowTest#suspendedListNamesOnlyPlatformPressedStores`（追加）：`suspendedStoreDetails` 一条，门店号是被压那家、名称与 `suspendedStores` 一致、`openOrders=0`；EXPIRED 的 `graceUntil` 为 null；`suspendedStores` 仍是名称数组。`BizPlanFlowTest#activePlanCarriesGraceUntil`：ACTIVE 时 `graceUntil = expireAt + 7 天`、无压店时为空数组 | ✅ 8/8 | 实现前红：两条都 NPE |
| A5 | 引用既有 `MerchantRoleFlowTest#customRoleTakesEffectImmediately`：经 `POST /biz/staff/{no}/store` 授予自定义角色码并判权生效 | ✅ | 后端行为未改，只改契约描述 |
| 回填 | 无自动化测试：测试库按 `schema-test.sql` 建表、不跑 Flyway，V636 执行不到。SQL 方言闸门对它零命中；上线前在 166 先 `SELECT COUNT(*)` 同条件确认受影响行数 | — | — |
| 回归 | `ServiceAreaPolygonFlowTest` 8、`ProfileAndPasswordTest` 9（`OpenOrderPort` 加方法后的 mock）、`CommerceStoreCreationFlowTest` 4（跳过 1，手工构造 `MerchantPlanServiceImpl`）、`BizStoreSetupTest` 12；`ArchitectureTest`、`BizEndpointPermTest` 与基线（`408da00ee`）报告逐字相同（基线已有 5 + 1 条失败，本次未新增）；`MerchantRoleFlowTest#assignablePermsComeFromBackend` 基线即失败（权限点清单），与本次无关；b-app / c-app `vue-tsc`、ops-web `tsc` 通过；生成文档 26 个全最新 | ✅ | |

## §6 对账二 · 设计 → 实现

```
     b-app/src/api/requests.ts                          |  8 ++-
     .../shop/portal/biz/BizMerchantController.java     | 10 +++-
     .../migration/V636__store_coord_sys_backfill.sql   | 12 ++++
     .../ai/neargo/shop/scenario/BizPlanFlowTest.java   | 45 ++++++++++++++
     .../neargo/shop/scenario/ScopePreviewFlowTest.java | 36 ++++++++++++
     .../ai/neargo/shop/spi/trade/OpenOrderPort.java    |  8 +++
     .../ai/neargo/shop/spi/user/MerchantQueryPort.java |  6 +-
     .../neargo/shop/trade/port/OpenOrderPortImpl.java  | 13 +++++
     .../neargo/shop/merchant/dto/StoreProfileVO.java   | 10 +++-
     .../shop/merchant/port/MerchantPortImpl.java       | 24 +++++++-
     .../shop/merchant/service/MerchantPlanService.java | 15 ++++-
     .../service/impl/MerchantPlanServiceImpl.java      | 33 +++++++++--
     .../service/impl/MerchantStoreServiceImpl.java     |  7 ++-
     .../API\350\257\246\346\203\205-B\347\253\257.md"  | 62 ++++++++++++++++----
     .../API\350\257\246\346\203\205-C\347\253\257.md"  |  3 +-
     docs/api/openapi-b.yaml                            | 68 ++++++++++++++++++++--
     docs/api/openapi.yaml                              | 62 ++++++++++++++++++--
     docs/technical/reference/glossary.json             |  3 +-
     ...275\223\344\270\216\345\255\227\345\205\270.md" |  2 +-
     packages/shared/src/types/merchant.ts              | 19 ++++++
     packages/shared/src/types/region.ts                |  2 +
     packages/shared/src/types/store.ts                 | 24 ++++++--
     22 files changed, 428 insertions(+), 44 deletions(-)
```

| 差异 | 说明 |
|---|---|
| 改了 `OpenOrderPort`、`OpenOrderPortImpl`（§2 未列） | A4 的 `openOrders` 要按门店计数，商户域不能直接读订单表，经 spi 端口加 `countOpenByStore`，口径复用同一组终态 `SETTLED` |
| 改了 `MerchantQueryPort` 注释（§2 未列） | 预估项由 `[level, refCode, mode]` 扩为 `[level, refCode, mode, geometry?]` |
| 改了 `packages/shared` 类型与 `b-app/src/api/requests.ts`，并重新生成 `openapi-b.yaml`、`openapi.yaml`、两份 API 详情与术语表 | OpenAPI 由 TS 类型生成，§2 写的「改 openapi-b.yaml」实际落在类型上 |
| `StoreStatus` 加了 `SUSPENDED` | 后端 `MchStore.SUSPENDED`（平台强制下线）早已会出现在 `/biz/stores/mine`，共享类型漏了这个值；本次 `GET /biz/store` 也会回它 |
| `GrantStoreReq.role` 放宽为字符串 | A5：OpenAPI 里不再只有 5 个预置枚举 |
| 测试落在既有测试类里，名字与 §5 原计划不同 | `BizPlanFlowTest` 已能造出「自停 / 压店」两种只读，`ScopePreviewFlowTest` 已有「预估 = 保存后实查」的框架，复用比新建三个类更能测到真实路径 |
| 回填迁移没有自动化测试 | 见 §5「回填」行 |

### 偏差说明

- **「已拍板 2」履约方式重开，本次没有实现「收紧到店主」**。实现时发现后端已有门店级、多选的履约配置 `GET|PUT /biz/stores/{storeNo}/fulfillment`（`BizStoreFulfillmentController`，四路：到店自取 / 邻里自提 / 商家自送 / 快递，共享类型注释写明它「取代商家级 fulfillmentReach 单选」），正对应原型 W05 的多选。拍板时的信息（只有主体级单选）不完整。这条路由在新模型门店上会被 `CommerceRoutePolicy` 拒（归为旧复数路由、DENY），用前要登记。等用户重新拍板：App 改用门店级接口并登记路由，还是维持单选。
- 迁移号 V636 取的是本分支当前最大号 +1；合并前要确认 `codex/ai-shop-codex` 上没有别人占用。
- V636 只改数据，166 的应用账号可直接执行，不需要 root。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 草稿，等待用户与商户 App 确认待决 1–3 |
| 2026-11-08 | 按商户 App 评审（ai-shop-bapp `docs/implementation/TDD评审-商户App.md`）修订：写明回读 `geometry` 与请求同格式；图形不合法统一 10473、上限 200；`status` 与 `planSuspended` 同一次读取；`openOrders` 口径；`graceUntil` 的有无；EXCLUDE 多边形在预估里扣除；坐标系理由按 App 实际画布更正，并补台湾存量门店坐标系回填；履约方式扩成「单选 / 门店级 / 谁能改」一并拍板 |
| 2026-11-08 | 用户按「已拍板」各条建议全部接受，状态改为已确认 |
| 2026-11-08 | 已实现 A1–A5 与坐标系回填（闸门见 §5「回归」）；履约方式一条重开，见 §6 偏差说明 |
