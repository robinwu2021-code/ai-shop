# TDD-商户 App 快递公司

状态：草稿（未实现，未拍板）
档位：1（新增端点 `GET /biz/express/companies`；`ExpressCompanies` 按市场分表，影响 `ship` / 发货设置的校验口径；`LogisticsPort` 加一个只读方法；无库表变更）
关联需求：
- 交接文档 `docs/交接-商户App功能矩阵与API.md` 第 12 模块「订单与履约」12-05（填运单发货）、12-17（发货设置的默认承运商）及该模块「待决与注意」第 2 条；附录 B「`/biz/express/companies` · 没有时可先用叫快递」
- 已有设计：`docs/technical/TDD-物流模块.md`（承运商码与渠道映射 `lgs_carrier_code`）、`docs/technical/TDD-快递100商家寄件.md`
创建：2026-11-08 · 最后更新：2026-11-08

> **一句话**：给商户 App 一份「这家店能选的快递公司」清单，**和 `ship()` 校验用的是同一份表**；
> 现有那份表只有大陆快递（微信 `delivery_id`），台湾门店一家都选不到，所以这次同时把表按市场分开、补上台湾的承运商。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| E1 | 选了门店的成员读快递公司清单，得到 `[{code, name, traceSupported}]`，顺序即发货界面的显示顺序 | `GET /biz/express/companies` → 新 `BizExpressCompanyController#list` |
| E2 | 清单按**门店所属主体的经营市场**给：台湾门店给台湾承运商，大陆门店给现有 14 家 | `ExpressCompanies#forMarket(market)`；市场取 `MerchantQueryPort#storeProfileOf(storeNo).market()`，为空按部署默认 `shop.merchant.default-market` |
| E3 | 清单里的每一个 `code`，拿去 `POST /biz/order/{orderNo}/ship`、`POST /biz/order/{orderNo}/waybill` 与 `PUT /biz/store/{storeNo}/ship-setting` 都会被接受；清单外的码被 10400 拒 | 三处校验统一为 `ExpressCompanies#isValidFor(market, code)`（改运单原先不校验，见 §1） |
| E4 | 大陆的微信发货上报口径不变：只认微信 `delivery_id` | `WxShippingUploadResolver` 改用 `isValidFor("CN", code)`，台湾码进不了微信上报 |
| E5 | `traceSupported=false` 的承运商照样能发货，只是不能查轨迹；App 据此提示「这家快递暂不支持查询轨迹」 | `LogisticsPort#traceCovers(carrier)`（新默认方法，实现查快递100 是否覆盖） |
| E6 | 店主、店长、店员——凡是能发货或能看发货设置的人都能读；与门店无关的人读不到 | `@perm.canAnyBiz(SHIP, STORE)`；路由登记 `ShopStoreMemberRoutes` |
| E7 | 新模型门店不被 70006 拒 | `ShopStoreMemberRoutes` 加 `GET /biz/express/companies` |
| E8 | 端上（uni-app b-app / c-app）共用的快递表与后端逐项一致 | `packages/shared/src/utils/express-companies.ts` 与 `packages/shared/tests/express-companies.test.ts` 同步改 |

**孤立项**

- 没落点的 AC：
  - **台湾承运商的轨迹查询**——快递100 是否覆盖、编码是什么，本仓库里没有核验记录（`Kuaidi100TraceProvider.CODES` 与 `lgs_carrier_code` 只有大陆 14 家）。首期台湾承运商 `traceSupported=false`，轨迹另开（见待决 X3）。
  - **台湾「叫快递上门」**——叫快递走快递100 商家寄件（`Kuaidi100PickupGateway`），承运商只有大陆快递，台湾门店报价会是空的。交接文档说的「没有承运商列表时可先用叫快递顶上」**在台湾门店不成立**。不在本 TDD 范围。
  - **超商取货（7-11 交貨便、全家店到店）**——是「寄到门市、买家到门市取」的另一种履约，要收件门市，不是填一个承运商码能表达的。不在本 TDD 范围（见待决 X2）。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面

### 后端已有（逐条带出处）

| 能力 | 出处 | 用法 |
|---|---|---|
| 快递公司内置表（14 家大陆快递，码 = 微信 `delivery_id`，名 = 微信 `delivery_name`，顺序 = 发货界面顺序） | `backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java`（`all()`、`isValid(code)`、`nameOf(code)`） | **直接作为清单的真源**；本次按市场分表 |
| 发货时校验承运商码 | `OrderShippingServiceImpl#ship`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/impl/OrderShippingServiceImpl.java` 第 96 行 `ExpressCompanies.isValid`）；非快递履约走 `MerchantOrderServiceImpl#shipLegacy`（第 335 行） | 改为按门店市场校验 |
| 发货接口与运单预检 | `BizOrderController`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOrderController.java` 第 200–230 行，`/biz/order/{subOrderNo}/ship`、`/ship/precheck`，`BizPerms.SHIP`） | 不改接口 |
| 预检对不认识的承运商回 `UNKNOWN`（放行发货，不拦） | `LogisticsPortImpl#precheck`（`backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsPortImpl.java` 第 272 行起；只有快递100 覆盖的承运商才去查，否则第 310 行回 `UNKNOWN`） | 台湾承运商发货时预检不会误拦 |
| 发货设置的默认承运商校验 | `StoreShipSettingServiceImpl`（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreShipSettingServiceImpl.java` 第 53 行 `ExpressCompanies.isValid(carrier)`）；接口 `BizStoreShipController`（读 `BizPerms.STORE`、写 `BizPerms.STORE_ADMIN`） | 改为按门店市场校验 |
| 微信发货上报读承运商码 | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/WxShippingUploadResolver.java` 第 57、71 行 | 收紧为只认大陆表 |
| 叫快递报价的承运商 | `ExpressPickupServiceImpl#quotes`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ExpressPickupServiceImpl.java` 第 95 行起）取 `ExpressPickupPort#carriers()`，实现 `backend/shop-channel/src/main/java/ai/neargo/shop/channel/express/port/Kuaidi100PickupGateway.java` 第 103 行（快递100 编码表的 key） | 不改；说明它顶不了台湾 |
| 快递100 轨迹覆盖的承运商 | `Kuaidi100TraceProvider.CODES`（`backend/logistics/logistics-channel/src/main/java/ai/neargo/shop/logistics/channel/kuaidi100/Kuaidi100TraceProvider.java` 第 62 行）；渠道编码表 `lgs_carrier_code`（`V387__logistics_module.sql` 第 70–100 行种子，`CarrierCodeBook` 只读缓存） | E5 的 `traceSupported` 从这里来 |
| 门店 → 主体市场 | `MerchantQueryPort#storeProfileOf(storeNo)` 的 `market`（`backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantQueryPort.java` 第 383–388 行，取 `mch_entity.market` 原值、为空不回落） | E2 |
| 部署默认市场 | `shop.merchant.default-market`（`backend/shop-app/src/main/resources/application.yml` 第 219 行，见 `TDD-B端建店默认经营市场.md`） | 主体市场为空时的回落 |
| 「任一权限即可」判权 | `@perm.canAnyBiz(...)`（`BizDashboardController` 第 78 行在用）；`BizEndpointPermTest` 的 `ANY_OF` 表 | E6 |
| 门店范围路由登记 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | E7 |
| 端上共用快递表 + 与 Java 对账的测试 | `packages/shared/src/utils/express-companies.ts`；`packages/shared/tests/express-companies.test.ts`（用正则 `new Company\("…",\s*"…"\)` 从 Java 源码抠序对） | E8 |

### 后端没有

- `/biz/express/companies` 或任何「快递公司清单」读接口：后端没有（`backend` 下搜不到）。b-app 与 c-app 一直是编译期常量 `EXPRESS_COMPANIES`。
- 台湾承运商：后端没有。`ExpressCompanies` 只有大陆 14 家；`ship()` 只认这 14 个码，**台湾门店现在「填运单发货」不可能成功**（App 默认填的是「黑貓宅急便」这几个字，后端会 10400）。
- 「承运商属于哪个市场」：后端没有这个维度。
- 注：`ExpressCompanies` 类注释提到的 `ExpressCompaniesFreshnessTest` 在 `backend` 里找不到；实际在跑的对账是 `packages/shared/tests/express-companies.test.ts`。

### 不要混淆的另一张表

`lgs_carrier`（原 `ful_carrier`，实体 `backend/shop-core/src/main/java/ai/neargo/shop/fulfillment/entity/FulCarrier.java`，运营端 `/ops/fulfillment/carriers`、菜单「第三方运力配置」）是**运力接入配置**（账号是否配好、截单时间、SLA），不是商户可选的快递公司清单。两者编码碰巧重合，但那张是运营随手可改的；清单若从它读，填错一个码就会让发货静默报不上去（`packages/shared/src/utils/express-companies.ts` 文件头记过这个坑）。本 TDD **不用它**。

### App 现状（ai-shop-bapp，供对照）

- App **从不实际调用** `/biz/express/companies`，只用 `controller.api.permits('GET', '/biz/express/companies')` 当开关（`features/store_orders/store_orders_pages.dart` 第 419 行、`features/goods/goods_pages.dart` 第 1757 行）。开关关着时，发货页的承运商是一个**自由文本框**、默认值「黑貓宅急便」。
- 交接文档里这个接口**没有给出建议的请求 / 响应形状**，所以下面的契约全部是后端提的，App 要按它改。

### 会被改到的（已在跑的功能）

- `POST /biz/order/{orderNo}/ship`：校验口径从「14 家大陆码」变为「本店市场的码」。**大陆门店行为不变**（大陆表原样保留、顺序不变）。`/ship/precheck` 本身不校验码（不认识的回 `UNKNOWN`），不改。
- `POST /biz/order/{orderNo}/waybill`（改运单，`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizWaybillCorrectionController.java`）：**现在完全不校验承运商码**——`LogisticsAdminPortImpl#changeWaybill`（`backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsAdminPortImpl.java` 第 128 行起）只要求单号和原因非空，任意字符串都会写进运单。本次补上与 `ship` 同口径的校验；这是对现有行为的收紧（大陆门店原来能写进去的非法码，以后会 10400）。
- `PUT /biz/store/{storeNo}/ship-setting`：同上。
- 微信发货上报：口径从 `isValid` 改成 `isValidFor("CN", …)`，对现有大陆数据零差异。
- b-app / c-app：共用常量文件改成按市场导出，大陆那份逐项不变（对账测试保证）。

### 明确不受影响的

- 叫快递（报价、下单、取消）与快递100 商家寄件：不改。
- 物流轨迹订阅、推送、`lgs_carrier_code` 与 `CarrierCodeBook`：不改（台湾轨迹另开）。
- 运营端「第三方运力配置」与 `lgs_carrier`：不改。

---

## §2 方案

### 契约变更总览

| 项 | 内容 |
|---|---|
| 端点（新增） | `GET /biz/express/companies` |
| 端点（口径变化） | `ship` / `ship-setting` 的承运商码按门店市场校验；`waybill` 从「不校验」改为同口径校验 |
| 库表 | **无**。清单是代码里的内置表（与 `ExpressCompanies` 现有取舍一致：十几家、改了要发版、换来的是每个码都核验过） |
| 迁移 | **无**（V6xx 不需要）。若待决 X4 选「运营端可配」，才需要新表与迁移 |
| 权限码 | 不新增；用 `biz:ship`、`biz:store` 任一 |
| i18n | 无（承运商名是专有名词，按市场原文给，见 X5） |
| 配置项 | 无（复用 `shop.merchant.default-market`） |
| SPI | `LogisticsPort` 加默认方法 `boolean traceCovers(String carrier)` |

### 接口 · `GET /biz/express/companies`

| 项 | 契约 |
|---|---|
| 范围 | **门店范围**。必须带 `X-Store-No`（与 `X-Entity-No`）；清单取决于这家店的市场 |
| 鉴权 | `Authorization: Bearer btk_…`（`merchantChain`） |
| 权限 | `@PreAuthorize("@perm.canAnyBiz('" + BizPerms.SHIP + "','" + BizPerms.STORE + "')")`——发货页要它（`biz:ship`），发货设置页也要它（读发货设置是 `biz:store`）；只给其中一个码，另一个页面的人就读不到 |
| 路由登记 | `ShopStoreMemberRoutes.ALL` 加 `HostRoute.of("GET", "/biz/express/companies")`（放在「订单与售后」那组）；它不匹配已有的 `/biz/order(?:/.*)?`，不登记就是 70006 |
| 判权登记 | `BizEndpointPermTest` 的 `ANY_OF` 加 `/biz/express/companies` |
| 请求 | 无参数 |

响应 `data`（数组，顺序即显示顺序）：

```json
[
  { "code": "TCAT",  "name": "黑貓宅急便", "traceSupported": false },
  { "code": "HCT",   "name": "新竹物流",   "traceSupported": false },
  { "code": "TWPOST","name": "中華郵政",   "traceSupported": false }
]
```

大陆门店得到的就是现有 14 家，`traceSupported` 按快递100 覆盖情况为 `true`（现有 14 家都在 `Kuaidi100TraceProvider.CODES` 里）。

错误：10403 / 70006（不是该店成员、角色没有这两个码之一）· 70006 + `SELECT_STORE`（没带店号）· 401。

**为什么返回裸数组而不是 `{market, companies}`**：与同模块的 `GET /biz/order/{orderNo}/express/quotes` 一致（交接文档第 12 模块该接口返回数组）；市场 App 已经从 `/biz/stores/mine` 与 `/biz/stores/{storeNo}/context` 拿到了，不必重复。

**App 要改的**：发货页（R03）与发货设置页（S01）的承运商从自由文本框改为从这份清单选，提交 `code` 而不是名字；`expressCompany` / `carrier` 字段传 `code`。冻结后删 `features/v5/contracts.dart` 里 `^/biz/express/companies$` 那一行。

### `ExpressCompanies` 按市场分表

```java
public final class ExpressCompanies {
    public record Company(String code, String name) { }          // 形状不变：对账测试的正则依赖它
    private static final List<Company> CN = List.of(/* 现有 14 家，原样、原顺序 */);
    private static final List<Company> TW = List.of(/* 见待决 X1 */);
    private static final Map<String, List<Company>> BY_MARKET = Map.of("CN", CN, "TW", TW);

    public static List<Company> forMarket(String market);         // 不认得的市场 → 空表（不回落 CN）
    public static boolean isValidFor(String market, String code);
    public static String nameOf(String code);                     // 跨市场查名字（展示用，码全局唯一）
    @Deprecated public static boolean isValid(String code);       // = isValidFor("CN", code)，过渡期保留
}
```

规则与防住什么：

- **码全局唯一**（台湾不复用大陆的 `SF`）：订单上只存一个码，`nameOf` 不带市场也能反查。若台湾也上顺丰，用独立码（如 `SF_TW`），因为大陆 `SF` 的语义是「微信 `delivery_id`」，台湾单永远不会上报微信，混用会让 `WxShippingUploadResolver` 把台湾单当成能报的。
- **不认得的市场给空表**而不是回落大陆：回落的话，阿联酋门店会看到一排大陆快递，选了以后发货成功、买家查不到件——比「一家都没有」更难发现。
- **`Company` 记录形状不变**：`express-companies.test.ts` 用正则抠 `new Company("…", "…")`，改成三参会让正则一条都抠不到，而那个测试里「真的抠到了东西」的对照量只要求 `> 10`——大陆 14 家仍在，**台湾那几条会被静默漏掉**。所以对账测试要同步改成按市场分组比对（E8）。

### 校验口径（E3、E4）

| 调用点 | 现在 | 改为 | 市场从哪来 |
|---|---|---|---|
| `OrderShippingServiceImpl#ship` | `isValid(carrier)` | `isValidFor(market, carrier)` | `OrderActor.storeNo` → `MerchantQueryPort#storeProfileOf` |
| `MerchantOrderServiceImpl#shipLegacy` | `isValid(expressCompany)` | 同上 | `storeNo` 参数 |
| `BizWaybillCorrectionController#correct`（改运单） | **不校验** | `expressCompany` 非空时 `isValidFor(market, …)`，不过 → 10400 | `BizContext.currentStoreNo()` |
| `StoreShipSettingServiceImpl` | `isValid(carrier)` | 同上 | 路径里的 `storeNo` |
| `WxShippingUploadResolver` | `isValid(...)` | `isValidFor("CN", ...)` | 固定大陆：微信只认 `delivery_id` |
| `ExpressPickupServiceImpl`、`NotificationConsumer`、`FreightDraftServiceImpl`、`Kuaidi100TraceProvider` | 只用 `nameOf` / 编码映射 | 不改 | — |

市场为空时按 `shop.merchant.default-market` 回落——这和 E2「不认得的市场给空表」不矛盾：空是「存量主体没写过市场」（存量大陆数据大多如此），不认得是「写了一个我们没有快递表的市场」。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java` | 按市场分表，加 `forMarket` / `isValidFor`；类注释补「台湾码不是微信码」 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizExpressCompanyController.java` | 唯一的新端点；与 `BizExpressController` 同包 |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/logistics/LogisticsPort.java` | 加 `default boolean traceCovers(String carrier) { return false; }` |
| 修改 | `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsPortImpl.java` | 实现 `traceCovers`：有可用的轨迹渠道覆盖该码即为 true（与 `precheck` 里判「快递100 覆盖」同一口径） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/impl/OrderShippingServiceImpl.java` | 按市场校验 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java` | 按市场校验 |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreShipSettingServiceImpl.java` | 按市场校验 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizWaybillCorrectionController.java` | 补承运商码校验（原先没有） |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/WxShippingUploadResolver.java` | 固定大陆口径 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 登记 `GET /biz/express/companies` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | `ANY_OF` 加一行 |
| 修改 | `packages/shared/src/utils/express-companies.ts` | 按市场导出；`EXPRESS_COMPANIES` 保留为大陆表（b-app / c-app 现有引用不动） |
| 修改 | `packages/shared/tests/express-companies.test.ts` | 按市场分组对账 |
| 运营端 | — | **不做页面**（清单是代码内置表）。若 X4 选「运营可配」再另开 |

> 注：若 `codex/biz-host-routes-70006`（`56d729544`，App 第二批门店路由登记）先合进 `codex/ai-shop-codex`，`ShopStoreMemberRoutes.java` 会先有一轮改动；本次只追加一行，合并时按行并入即可。当前分支（基于 `codex/ai-shop-codex`）里还没有那一批登记。

---

## §5 对账三 · 实现 → 需求（测试，计划，未跑）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| E1 | `BizExpressCompanyFlowTest#member_reads_companies_in_display_order` | 未跑 | |
| E2 | `ExpressCompaniesTest#tw_market_lists_tw_carriers_only`、`#cn_market_keeps_the_14_wechat_codes_in_order`、`#unknown_market_is_empty`；`BizExpressCompanyFlowTest#entity_without_market_falls_back_to_deploy_default` | 未跑 | 计划：`forMarket` 改成恒返回大陆表 → TW 用例变红 |
| E3 | `BizExpressCompanyFlowTest#every_listed_code_is_accepted_by_ship_and_ship_setting`（逐个码打 `ship` 与 `ship-setting`）、`OrderShippingServiceTest#tw_store_rejects_cn_only_code`、`BizWaybillCorrectionFlowTest#unknown_carrier_code_is_rejected` | 未跑 | 计划：`ship` 仍用旧 `isValid` → TW 码被拒、变红；改运单去掉新校验 → 最后一条变红 |
| E4 | `WxShippingUploadResolverTest#tw_code_is_never_reported_to_wechat` | 未跑 | |
| E5 | `LogisticsPortImplTest#trace_covers_only_kuaidi100_mapped_carriers`；`BizExpressCompanyFlowTest#tw_carriers_report_trace_unsupported` | 未跑 | |
| E6 | `BizEndpointPermTest#everyBizEndpointHasADecision`（已有，加登记后通过）、`BizExpressCompanyFlowTest#clerk_without_ship_or_store_is_forbidden` | 未跑 | |
| E7 | `StoreScopedDailyRoutesFlowTest#express_companies_pass_admission_for_new_model_store` | 未跑 | 计划：删登记行 → 70006 变红 |
| E8 | `packages/shared/tests/express-companies.test.ts`「按市场逐项一致」 | 未跑 | 计划：TS 侧少一家台湾承运商 → 变红 |
| 闸门 | `ArchitectureTest`、`BizEndpointPermTest`、`npm test`（packages/shared） | 未跑 | |

---

## 待决（要产品 / 用户拍板）

| # | 问题 | 建议 |
|---|---|---|
| X1 | 台湾首批承运商清单与显示顺序 | 建议：黑貓宅急便 `TCAT`、新竹物流 `HCT`、中華郵政 `TWPOST`、嘉里大榮物流 `KERRYTJ`、宅配通 `PELICAN`。**码是我方自定义的**（台湾承运商不在微信表里），名称用承运商官方繁体名；要产品按首批商户实际在用的快递增删，并确认顺序 |
| X2 | 超商取货（7-11 交貨便、全家店到店）要不要进这份清单 | **不进**。它要买家选取件门市、走另一套单号与状态，硬塞成「承运商」会让买家拿着超商单号去宅配网站查不到；另立履约方式另开 |
| X3 | 台湾承运商要不要首期就能查轨迹 | 首期不要（`traceSupported=false`，发货照常）。要做时先核验快递100 对这几家的覆盖与编码，再往 `lgs_carrier_code` 与 `Kuaidi100TraceProvider.CODES` 各补一行（届时才有迁移，V6xx 待定） |
| X4 | 清单是代码内置还是运营端可配 | **代码内置**（与 `ExpressCompanies` 现有取舍一致：十几家、每个码核验过；运营可配的话填错一个码就是发货静默失败）。若业务要求不发版加快递，再另开「运营可配 + 核验」方案 |
| X5 | 承运商名要不要随 `Accept-Language` 翻译 | 不翻译，按市场原文（台湾繁体、大陆简体）：名字要与包裹面单、承运商官网一致，买家才对得上 |
| X6 | 大陆快递是否也给台湾门店（如跨境寄大陆） | 首期不给：台湾一期只做岛内；跨境寄件的运费模板、清关都没有 |
| X7 | 交接文档写「没有清单时先用叫快递顶上」 | 台湾门店顶不上（叫快递只有大陆承运商）。建议 App 在台湾门店**隐藏**叫快递入口，直到台湾寄件渠道接入；本接口上线后「填运单发货」就是台湾门店唯一的发货方式 |
