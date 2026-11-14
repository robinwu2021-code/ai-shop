# TDD-商户 App 快递公司

状态：已实现（2026-11-08：用户按「已拍板」一节各条的建议全部接受；实现与测试见 §5、§6）
档位：1（新增端点 `GET /biz/express/companies`；`ExpressCompanies` 按市场分表，影响 `ship` / 发货设置的校验口径；`LogisticsPort` 加一个只读方法；无库表变更）
关联需求：
- 交接文档（ai-shop-bapp 仓库 `docs/implementation/功能矩阵与API交接文档.md`）第 12 模块「订单与履约」12-05（填运单发货）、12-17（发货设置的默认承运商）及该模块「待决与注意」第 2 条；附录 B「`/biz/express/companies` · 没有时可先用叫快递」
- 已有设计：`docs/technical/TDD-物流模块.md`（承运商码与渠道映射 `lgs_carrier_code`）、`docs/technical/TDD-快递100商家寄件.md`
- 评审：ai-shop-bapp 仓库 `docs/implementation/TDD评审-商户App.md`「最需要先定的」第 10 条与「第一组 · 二」（本轮修订逐条见文末「评审修订说明」）
创建：2026-11-08 · 最后更新：2026-11-08

> **一句话**：给商户 App 一份「这家店能选的快递公司」清单，**和 `ship()` 校验用的是同一份表**；
> 现有那份表只有大陆快递（微信 `delivery_id`），台湾门店一家都选不到，所以这次同时把表按市场分开、补上台湾的承运商。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| E1 | 选了门店的成员读快递公司清单，得到 `{market, courierPickupAvailable, companies: [{code, name, traceSupported}]}`，`companies` 的顺序即发货界面的显示顺序 | `GET /biz/express/companies` → 新 `BizExpressCompanyController#list` |
| E2 | 清单按**门店所属主体的经营市场**给：台湾门店给台湾承运商，大陆门店给现有 14 家 | `ExpressCompanies#forMarket(market)`；市场取 `MerchantQueryPort#storeProfileOf(storeNo).market()`，为空按部署默认 `shop.merchant.default-market` |
| E3 | 清单里的每一个 `code`（以及本市场清单里的承运商**名称**，归一成码再存），拿去 `POST /biz/order/{orderNo}/ship`、`POST /biz/order/{orderNo}/waybill` 与 `PUT /biz/store/{storeNo}/ship-setting` 都会被接受；`ship` 与 `ship-setting` 对清单外的值回 10400 | 统一为 `ExpressCompanies#resolve(market, codeOrName)`；**市场取订单 / 门店自己的门店号**，不取调用方上下文（§2 校验口径） |
| E4 | 大陆的微信发货上报口径不变：只认微信 `delivery_id` | `WxShippingUploadResolver` 改用 `isValidFor("CN", code)`，台湾码进不了微信上报 |
| E5 | `traceSupported=false` 的承运商照样能发货，只是不能查轨迹；App 据此提示「这家快递暂不支持查询轨迹」 | `LogisticsPort#traceCovers(carrier)`（新默认方法，实现查快递100 是否覆盖） |
| E6 | 店主、店长、店员——凡是能发货或能看发货设置的人都能读；与门店无关的人读不到 | `@perm.canAnyBiz(SHIP, STORE)`；路由登记 `ShopStoreMemberRoutes` |
| E7 | 新模型门店不被 70006 拒 | `ShopStoreMemberRoutes` 加 `GET /biz/express/companies` |
| E8 | 端上（uni-app b-app / c-app）共用的快递表与后端逐项一致 | `packages/shared/src/utils/express-companies.ts` 与 `packages/shared/tests/express-companies.test.ts` 同步改 |
| E9 | **改运单不因承运商校验而失败**：不改承运商（不传、或传的与运单当前承运商相同）一律放行；改成清单内的码或名称 → 归一后写入；改成清单外的值 → 首期仍写入（与现在一致），只记 WARN | `BizWaybillCorrectionController#correct`（§2 校验口径「改运单」一行） |
| E10 | 响应里的 `market` 是**已回落**的经营市场；`courierPickupAvailable` 告诉 App 这家店能不能「叫快递」，App 不再自己按市场判断 | `BizExpressCompanyController#list`：`market` 为空按 `shop.merchant.default-market`；`courierPickupAvailable = ExpressPickupPort#enabled() && carriers() 与本市场清单有交集` |

**孤立项**

- 没落点的 AC：
  - **台湾承运商的轨迹查询**——快递100 是否覆盖、编码是什么，本仓库里没有核验记录（`Kuaidi100TraceProvider.CODES` 与 `lgs_carrier_code` 只有大陆 14 家）。首期台湾承运商 `traceSupported=false`，轨迹另开（见已拍板 X3）。
  - **台湾「叫快递上门」**——叫快递走快递100 商家寄件（`Kuaidi100PickupGateway`），承运商只有大陆快递，台湾门店报价会是空的。交接文档说的「没有承运商列表时可先用叫快递顶上」**在台湾门店不成立**。不在本 TDD 范围（入口怎么处理见已拍板 X7）。
  - **超商取货（7-11 交貨便、全家店到店）**——是「寄到门市、买家到门市取」的另一种履约，要收件门市，不是填一个承运商码能表达的。不在本 TDD 范围（见已拍板 X2）。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面

### 后端已有（逐条带出处）

| 能力 | 出处 | 用法 |
|---|---|---|
| 快递公司内置表（14 家大陆快递，码 = 微信 `delivery_id`，名 = 微信 `delivery_name`，顺序 = 发货界面顺序） | `backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java`（`all()`、`isValid(code)`、`nameOf(code)`） | **直接作为清单的真源**；本次按市场分表 |
| 发货时校验承运商码 | `OrderShippingServiceImpl#ship`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/impl/OrderShippingServiceImpl.java` 第 96 行 `ExpressCompanies.isValid`）；非快递履约走 `MerchantOrderServiceImpl#shipLegacy`（第 335 行） | 改为按门店市场校验 |
| 发货调用方的门店号**可能为空** | `OrderActor.owns` 在 `storeNo` 为空时不比门店（`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/command/OrderActor.java` 第 24 行）；叫快递取件回填运单时就是空门店号调用 `MerchantOrderService#ship(entityNo, null, …)`（`ExpressPickupServiceImpl` 第 360 行） | 市场必须取**订单自己的** `OrdSubOrder.storeNo`，不能取调用方的 |
| 叫快递回写到订单上的承运商码 | 回写的是 `OrdExpressPickup.carrier`，即 `Kuaidi100PickupGateway.CODES` 的 key（第 52–64 行：`ZTO YTO YD STO JTSD JD DBL EMS`，注释写明「微信 delivery_id → 快递100 公司码」），**全部在大陆 14 家里**；快递100 自己的公司码（`zhongtong` 等）只发给快递100，不回写 | 回答评审「回写的是哪一套码」：与清单同一套，叫过快递的订单改运单不受影响 |
| 改运单不传承运商时沿用原值 | `LogisticsAdminPortImpl#changeWaybill` 第 142 行：`newCarrier = notBlank(carrier) ? carrier.trim() : w.getCarrier()` | E9「不改承运商一律放行」的依据 |
| 发货接口与运单预检 | `BizOrderController`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOrderController.java` 第 200–230 行，`/biz/order/{subOrderNo}/ship`、`/ship/precheck`，`BizPerms.SHIP`） | 不改接口 |
| 预检对不认识的承运商回 `UNKNOWN`（放行发货，不拦） | `LogisticsPortImpl#precheck`（`backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsPortImpl.java` 第 272 行起；只有快递100 覆盖的承运商才去查，否则第 310 行回 `UNKNOWN`） | 台湾承运商发货时预检不会误拦 |
| 发货设置的默认承运商校验 | `StoreShipSettingServiceImpl`（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreShipSettingServiceImpl.java` 第 53 行 `ExpressCompanies.isValid(carrier)`）；接口 `BizStoreShipController`（读 `BizPerms.STORE`、写 `BizPerms.STORE_ADMIN`） | 改为按门店市场校验 |
| 微信发货上报读承运商码 | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/WxShippingUploadResolver.java` 第 57、71 行 | 收紧为只认大陆表 |
| 叫快递报价的承运商 | `ExpressPickupServiceImpl#quotes`（`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ExpressPickupServiceImpl.java` 第 95 行起）取 `ExpressPickupPort#carriers()`，实现 `backend/shop-channel/src/main/java/ai/neargo/shop/channel/express/port/Kuaidi100PickupGateway.java` 第 103 行（快递100 编码表的 key） | 不改；说明它顶不了台湾 |
| 快递100 轨迹覆盖的承运商 | `Kuaidi100TraceProvider.CODES`（`backend/logistics/logistics-channel/src/main/java/ai/neargo/shop/logistics/channel/kuaidi100/Kuaidi100TraceProvider.java` 第 62 行）；渠道编码表 `lgs_carrier_code`（`V387__logistics_module.sql` 第 70–100 行种子，`CarrierCodeBook` 只读缓存） | E5 的 `traceSupported` 从这里来 |
| 门店 → 主体市场 | `MerchantQueryPort#storeProfileOf(storeNo)` 的 `market`（`backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantQueryPort.java` 第 383–388 行，取 `mch_entity.market` 原值、为空不回落）；`GET /biz/stores/{storeNo}/context` 给 App 的也是这个原值（`BizFoundationController#context` 第 140–152 行） | E2；也说明 App 拿不到回落后的市场（E10） |
| 叫快递通道开关与承运商 | `ExpressPickupPort#enabled()`、`#carriers()`（`backend/shop-base/src/main/java/ai/neargo/shop/spi/fulfillment/ExpressPickupPort.java` 第 15、18 行）；报价前 `requireChannel()` 不通回 70070（`ExpressPickupServiceImpl` 第 398–402 行）；`quotes` 本身**不按市场拦**（第 95–119 行），台湾地址交给快递100 报价，结果未验证 | E10 的 `courierPickupAvailable` |
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

- `POST /biz/order/{orderNo}/ship`：校验口径从「14 家大陆码」变为「本店市场的码或名称」。**大陆门店只放宽不收紧**（大陆表原样保留、顺序不变；多接受「顺丰速运」这类本市场名称，归一成码再存）。`/ship/precheck` 本身不校验码（不认识的回 `UNKNOWN`），不改。
- `POST /biz/order/{orderNo}/waybill`（改运单，`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizWaybillCorrectionController.java`）：**现在完全不校验承运商码**——`LogisticsAdminPortImpl#changeWaybill`（`backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsAdminPortImpl.java` 第 128 行起）只要求单号和原因非空，任意字符串都会写进运单。**（评审修订）本次不收紧成 10400**：App 的改运单（R06）是不受开关控制的自由文本框、预填当前承运商（ai-shop-bapp `features/store_orders/store_orders_pages.dart` 第 666–668 行），草稿原定「补上与 `ship` 同口径校验」会让台湾门店改运单被 10400 拒。改为 E9：不改承运商放行、改成清单内的值归一、清单外的值照旧写入并记 WARN。收紧另开，等 App 清单单选版本普及后再定。
- `PUT /biz/store/{storeNo}/ship-setting`：同 `ship`。
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
| 端点（口径变化） | `ship` / `ship-setting` 的承运商按订单 / 门店所属市场校验（码或本市场名称，名称归一成码）；`waybill` 不改承运商一律放行、改成清单内的值归一、清单外的值照旧写入（E9，**不收紧**） |
| 库表 | **无**。清单是代码里的内置表（与 `ExpressCompanies` 现有取舍一致：十几家、改了要发版、换来的是每个码都核验过） |
| 迁移 | **无**（V6xx 不需要）。若已拍板 X4 选「运营端可配」，才需要新表与迁移 |
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

响应 `data`（对象；`companies` 的顺序即显示顺序）：

```json
{
  "market": "TW",
  "courierPickupAvailable": false,
  "companies": [
    { "code": "TCAT",  "name": "黑貓宅急便", "traceSupported": false },
    { "code": "HCT",   "name": "新竹物流",   "traceSupported": false },
    { "code": "TWPOST","name": "中華郵政",   "traceSupported": false }
  ]
}
```

- `market`：**已按 `shop.merchant.default-market` 回落**的经营市场。
- `courierPickupAvailable`：这家店能不能「叫快递」= `ExpressPickupPort#enabled()`，且 `carriers()` 与本市场清单有交集。台湾门店恒为 `false`（叫快递的承运商全是大陆码）；大陆门店在快递100 桩环境下也是 `false`。App 据此决定显示不显示叫快递入口；**台湾门店在接口为 `false` 时要不要隐藏入口，是产品决定（已拍板 X7）**，这个字段只如实报告能力。
- 大陆门店得到的就是现有 14 家，`traceSupported` 按快递100 覆盖情况为 `true`（现有 14 家都在 `Kuaidi100TraceProvider.CODES` 里）。

错误：10403 / 70006（不是该店成员、角色没有这两个码之一）· 70006 + `SELECT_STORE`（没带店号）· 401。

**为什么从裸数组改成对象（评审修订）**：草稿原理由是「市场 App 已经从 `/biz/stores/mine` 与 `/biz/stores/{storeNo}/context` 拿到了」——核对后不成立：这两处给的是 `mch_entity.market` **原值**，为空时不回落（§1），App 不知道部署默认市场是什么，会把存量主体判错。叫快递能不能用也不该由 App 按市场猜。两个字段都放在 App 进发货页本来就要调的这个接口里，不另动 `/context`（那个响应归《TDD-商户App接口字段对齐》管）。与 `quotes` 返回数组不一致，换来的是 App 不用再自己推断。

**清单的稳定性**：清单是代码内置表，只随后端发版变化。App 按门店缓存到切店或冷启动即可，不用每次进发货页都拉。

**App 要改的**：发货页（R03）、改运单（R06）与发货设置页（S01）的承运商从自由文本框改为从这份清单选，提交 `code` 而不是名字；R06 预选当前单的承运商；`expressCompany` / `carrier` 字段传 `code`。清单为空（不认得的市场）时禁用发货、提示「本市场暂无可选快递」，不要退回自由文本框。冻结后删 `features/v5/contracts.dart` 里 `^/biz/express/companies$` 那一行。**服务端的名称归一与改运单放行（E3、E9）是给 App 改版前那段时间兜底的**，App 不应依赖它们。

### `ExpressCompanies` 按市场分表

```java
public final class ExpressCompanies {
    public record Company(String code, String name) { }          // 形状不变：对账测试的正则依赖它
    private static final List<Company> CN = List.of(/* 现有 14 家，原样、原顺序 */);
    private static final List<Company> TW = List.of(/* 见已拍板 X1 */);
    private static final Map<String, List<Company>> BY_MARKET = Map.of("CN", CN, "TW", TW);

    public static List<Company> forMarket(String market);         // 不认得的市场 → 空表（不回落 CN）
    public static boolean isValidFor(String market, String code);
    public static Optional<String> resolve(String market, String codeOrName); // 码原样返回；本市场名称（去首尾空白、精确匹配）→ 码；都不是 → empty
    public static String nameOf(String code);                     // 跨市场查名字（展示用，码全局唯一）
    @Deprecated public static boolean isValid(String code);       // = isValidFor("CN", code)，过渡期保留
}
```

规则与防住什么：

- **码全局唯一**（台湾不复用大陆的 `SF`）：订单上只存一个码，`nameOf` 不带市场也能反查。若台湾也上顺丰，用独立码（如 `SF_TW`），因为大陆 `SF` 的语义是「微信 `delivery_id`」，台湾单永远不会上报微信，混用会让 `WxShippingUploadResolver` 把台湾单当成能报的。
- **不认得的市场给空表**而不是回落大陆：回落的话，阿联酋门店会看到一排大陆快递，选了以后发货成功、买家查不到件——比「一家都没有」更难发现。
- **名称归一只在本市场的表里找**（评审修订新增）：App 改版前，发货页是自由文本框、默认值「黑貓宅急便」（ai-shop-bapp `features/store_orders/store_orders_pages.dart` 第 421 行）。台湾表的 `TCAT` 名称就是「黑貓宅急便」，归一后**现有 App 不改也能在台湾门店发货**；只在本市场找，防止台湾门店写「顺丰速运」被归成大陆的 `SF`、再被当成能报微信的单。
- **`Company` 记录形状不变**：`express-companies.test.ts` 用正则抠 `new Company("…", "…")`，改成三参会让正则一条都抠不到，而那个测试里「真的抠到了东西」的对照量只要求 `> 10`——大陆 14 家仍在，**台湾那几条会被静默漏掉**。所以对账测试要同步改成按市场分组比对（E8）。

### 校验口径（E3、E4）

| 调用点 | 现在 | 改为 | 市场从哪来 |
|---|---|---|---|
| `OrderShippingServiceImpl#ship` | `isValid(carrier)` | `resolve(market, carrier)`，empty → 10400；有值则存归一后的码 | **订单自己的 `OrdSubOrder.storeNo`** → `MerchantQueryPort#storeProfileOf`（草稿原写 `OrderActor.storeNo`，它可能为空，见 §1） |
| `MerchantOrderServiceImpl#shipLegacy` | `isValid(expressCompany)` | 同上 | 订单自己的 `OrdSubOrder.storeNo`（草稿原写「`storeNo` 参数」；叫快递回填时这个参数是 null） |
| `BizWaybillCorrectionController#correct`（改运单） | **不校验** | E9：`expressCompany` 为空、或去空白后等于订单当前的 `expressCompany` → 原样交给 `changeWaybill`（空则沿用原值）；`resolve` 有值 → 传归一后的码；`resolve` 为 empty → **照旧原样写入**，记 WARN（订单号、门店、原值），不拒 | 订单所属门店（`orders.detail` 返回的 `OrderVO.store`），取不到用 `BizContext.currentStoreNo()` |
| `StoreShipSettingServiceImpl` | `isValid(carrier)` | 同 `ship` | 路径里的 `storeNo` |
| `WxShippingUploadResolver` | `isValid(...)` | `isValidFor("CN", ...)` | 固定大陆：微信只认 `delivery_id` |
| `ExpressPickupServiceImpl`、`NotificationConsumer`、`FreightDraftServiceImpl`、`Kuaidi100TraceProvider` | 只用 `nameOf` / 编码映射 | 不改 | — |

改运单为什么不拒（E9）：App 现在的 R06 预填当前承运商、商户通常只改单号，「与当前相同就放行」覆盖了绝大多数；叫快递回写的码都在大陆表里（§1），也不受影响。清单外的新值照旧写入，是因为拒了就是**已发货的订单改不了运单**，而写进去的后果和今天一样（查不到轨迹、报不了微信——`WxShippingUploadResolver` 只认大陆码，挡在那一层）。WARN 日志用来看 App 改版后还有没有人在写自由文本，再决定何时收紧。

市场为空时按 `shop.merchant.default-market` 回落——这和 E2「不认得的市场给空表」不矛盾：空是「存量主体没写过市场」（存量大陆数据大多如此），不认得是「写了一个我们没有快递表的市场」。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java` | 按市场分表，加 `forMarket` / `isValidFor` / `resolve`；类注释补「台湾码不是微信码」 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizExpressCompanyController.java` | 唯一的新端点；与 `BizExpressController` 同包；读 `ExpressPickupPort` 算 `courierPickupAvailable` |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/logistics/LogisticsPort.java` | 加 `default boolean traceCovers(String carrier) { return false; }` |
| 修改 | `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsPortImpl.java` | 实现 `traceCovers`：有可用的轨迹渠道覆盖该码即为 true（与 `precheck` 里判「快递100 覆盖」同一口径） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/impl/OrderShippingServiceImpl.java` | 按订单所属市场校验并归一 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java` | 按订单所属市场校验并归一 |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreShipSettingServiceImpl.java` | 按门店市场校验并归一 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizWaybillCorrectionController.java` | E9：承运商归一 + 清单外记 WARN，**不拒** |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/WxShippingUploadResolver.java` | 固定大陆口径 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 登记 `GET /biz/express/companies` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | `ANY_OF` 加一行 |
| 修改 | `packages/shared/src/utils/express-companies.ts` | 按市场导出；`EXPRESS_COMPANIES` 保留为大陆表（b-app / c-app 现有引用不动） |
| 修改 | `packages/shared/tests/express-companies.test.ts` | 按市场分组对账 |
| 运营端 | — | **不做页面**（清单是代码内置表）。若 X4 选「运营可配」再另开 |

### 评审提问的答复（不改契约的部分）

| 评审提问 | 答复 |
|---|---|
| `OrderVO` 与 `/trace` 能否顺带返回 `expressCompanyName` | **本 TDD 不加**。`OrderVO` 是 C 端、商户端、运营端共用的 record，构造器有七八个重载（`backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java` 第 131–587 行），加一个字段要改遍所有构造点；App 用清单把码映射成名称即可，映射不到就显示原值。若 App 确有大量跨市场或历史订单要显示名称，另开一份只改 `OrderVO` 的 TDD |
| 台湾门店调 `/biz/order/{no}/express/quotes` 现在回什么 | 代码里不按市场拦（`ExpressPickupServiceImpl#quotes` 第 95–119 行），会拿台湾地址去问快递100 的八家大陆承运商，结果未验证（多半是空数组，且要等各家超时）。App 以 `courierPickupAvailable` 为准，为 `false` 就不调 |
| 存量订单的 `expressCompany` 不在清单里，改运单怎么办 | 服务端放行原值（E9「与当前承运商相同即放行」）。App 端建议：原值在清单里就预选；不在就显示原值、默认不改承运商，商户主动换才从清单选 |
| 清单会不会因发版变化，能否缓存 | 只随后端发版变；按门店缓存到切店或冷启动即可（见接口一节） |

> 注：若 `codex/biz-host-routes-70006`（`56d729544`，App 第二批门店路由登记）先合进 `codex/ai-shop-codex`，`ShopStoreMemberRoutes.java` 会先有一轮改动；本次只追加一行，合并时按行并入即可。当前分支（基于 `codex/ai-shop-codex`）里还没有那一批登记。

---

## §5 对账三 · 实现 → 需求（测试，已跑）

先写测试跑出红（实现前，同一分支 `54eb737ab`）：`BizExpressCompanyFlowTest` 8 条全红（端点不存在、台湾码被 10400、
空门店号回填 `BAD_REQUEST`），`StoreScopedDailyRoutesFlowTest#express_companies_pass_admission_for_new_model_store` 回 70006，
`express-companies.test.ts` 8/9 红；其余新测试引用的类 / 方法尚不存在（编译红）。实现后全部转绿：

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| E1 | `BizExpressCompanyFlowTest#member_reads_companies_in_display_order` | ✅ | 实现前红（端点不存在） |
| E10 | `BizExpressCompanyFlowTest#entity_without_market_falls_back_to_deploy_default`、`#tw_store_lists_tw_carriers_without_trace_or_pickup`；`BizExpressCompanyControllerTest#market_is_resolved_with_deploy_default`、`#tw_store_reports_courier_pickup_unavailable`、`#cn_store_reports_pickup_available_only_when_channel_enabled` | ✅ | 流程两条实现前红 |
| E2 | `ExpressCompaniesTest#tw_market_lists_tw_carriers_only`、`#cn_market_keeps_the_14_wechat_codes_in_order`、`#unknown_market_is_empty`；`BizExpressCompanyFlowTest#entity_without_market_falls_back_to_deploy_default` | ✅ | — |
| E3 | `BizExpressCompanyFlowTest#every_listed_code_is_accepted_by_ship_and_ship_setting`（大陆 14 + 台湾 5 个码逐个打 `ship` 与 `ship-setting`）、`#tw_store_validates_by_its_own_market`（大陆码 10400、「黑貓宅急便」存 `TCAT`、「顺丰速运」10400，发货设置同口径）、`#cn_store_normalizes_name_and_rejects_tw_code`、`#pickup_write_back_uses_order_store_market`（`MerchantOrderService#ship(entityNo, null, …)`，即叫快递回填的那条调用）；`ExpressCompaniesTest#resolve_normalizes_names_within_market_only` | ✅ | 实现前红（`ship` 用旧 `isValid` → 台湾码 10400；回填那条 `BAD_REQUEST`） |
| E9 | `BizWaybillCorrectionControllerTest#unchanged_carrier_passes_even_if_not_in_list`、`#blank_carrier_keeps_current`、`#listed_name_is_normalized`、`#unknown_new_carrier_is_written_and_warned_not_rejected`（断言 WARN 带订单号、门店号、原值） | ✅ | 编译红（新构造器） |
| E4 | `WxShippingUploadResolverTest#tw_code_is_never_reported_to_wechat`（存量单路径与冻结快照路径各一次） | ✅ | 守的是「`isValid` 不得放宽成跨市场」：基线上台湾码不在任何表里，本条在基线即绿 |
| E5 | `LogisticsPortImplTest#trace_covers_only_kuaidi100_mapped_carriers`；`BizExpressCompanyControllerTest#trace_supported_comes_from_logistics_coverage`；`BizExpressCompanyFlowTest#tw_store_lists_tw_carriers_without_trace_or_pickup` | ✅ | — |
| E6 | `BizEndpointPermTest`（`ANY_OF` 登记后 `/biz/express/companies` 不在未决清单里）、`BizExpressCompanyFlowTest#clerk_without_ship_or_store_is_forbidden`（CS 角色 70006；COURIER 只有 `biz:ship` 也读得到） | ✅ | 实现前红 |
| E7 | `StoreScopedDailyRoutesFlowTest#express_companies_pass_admission_for_new_model_store` | ✅ | 实现前红（70006 `CONTACT_ADMIN`） |
| E8 | `packages/shared/tests/express-companies.test.ts`「按市场逐项一致」+「对照量：每个市场都真的抠到了东西，且没有一条 `new Company` 落在市场之外」（台湾必须恰好 5 条） | ✅ | 做过一次：TS 侧删掉 `PELICAN` → 「按市场逐项一致」变红 |
| 闸门 | `ArchitectureTest`、`BizEndpointPermTest`、`packages/shared` vitest、`b-app` / `c-app` `vue-tsc`、`check-generated-docs` | 见下 | — |

**真实输出**（`cd backend && mvn -q -o -pl shop-app -am test -Dtest=<上表各类与发货 / 改运单 / 发货设置 / 微信上报相关既有类>`，2026-11-08）：

```
Tests run: 5,  Failures: 0 -- in ai.neargo.shop.common.ExpressCompaniesTest
Tests run: 4,  Failures: 0 -- in ai.neargo.shop.trade.api.biz.BizExpressCompanyControllerTest
Tests run: 8,  Failures: 0 -- in ai.neargo.shop.scenario.BizExpressCompanyFlowTest
Tests run: 4,  Failures: 0 -- in ai.neargo.shop.portal.biz.BizWaybillCorrectionControllerTest
Tests run: 1,  Failures: 0 -- in ai.neargo.shop.paybridge.WxShippingUploadResolverTest
Tests run: 7,  Failures: 0 -- in ai.neargo.shop.logistics.port.LogisticsPortImplTest
Tests run: 14, Failures: 0 -- in ai.neargo.shop.commercebridge.StoreScopedDailyRoutesFlowTest
Tests run: 9,  Failures: 0 -- in ai.neargo.shop.logistics.channel.kuaidi100.Kuaidi100TraceProviderTest
Tests run: 7,  Failures: 0 -- in ai.neargo.shop.channel.express.port.Kuaidi100PickupGatewayTest
Tests run: 9,  Failures: 0 -- in ai.neargo.shop.scenario.OrderShipWiringFlowTest
Tests run: 1,  Failures: 0 -- in ai.neargo.shop.scenario.OrderShippingOperatorFlowTest
Tests run: 15, Failures: 0 -- in ai.neargo.shop.scenario.BizOrderFulfillFlowTest
Tests run: 12, Failures: 0 -- in ai.neargo.shop.scenario.ExpressPickupFlowTest
Tests run: 5,  Failures: 0 -- in ai.neargo.shop.scenario.WxShippingSourceIsolationFlowTest
Tests run: 3,  Failures: 0 -- in ai.neargo.shop.scenario.ShippingRefundSnapshotFlowTest
Tests run: 3,  Failures: 0 -- in ai.neargo.shop.scenario.OrderLogisticsFinalFlowTest
Tests run: 11, Failures: 0 -- in ai.neargo.shop.scenario.OpsLogisticsFlowTest
Tests run: 9,  Failures: 0 -- in ai.neargo.shop.scenario.OrderMappedOpsLogisticsFlowTest
Tests run: 1,  Failures: 0 -- in ai.neargo.shop.unit.WxShippingUploadImmediateConsumerTest
Tests run: 4,  Failures: 0 -- in ai.neargo.shop.trade.GenericIndustryFulfillmentGuardTest
Tests run: 22, Failures: 5, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
Tests run: 12, Failures: 1 -- in ai.neargo.shop.arch.BizEndpointPermTest
```

`ArchitectureTest` 5 条（`implsMustLiveInDedicatedPackage`、`noFullyQualifiedReferences`、`everyScheduledMethodMustBeLocked`、
`serviceMustBeInterface`、`controllersMustNotTouchMappers`）与 `BizEndpointPermTest.everyBizEndpointHasADecision` 是基线已知失败：
实现前在同一分支跑过同一组类，两份 surefire 报告去掉耗时与堆栈后**逐字相同**；未决清单里没有 `/biz/express/companies`。

前端：`b-app` / `c-app` `npx vue-tsc --noEmit` 均 0 退出；`packages/shared` `vitest run` 712 条 687 过 25 红 ——
红的 27 项（25 条用例 + 2 个整文件加载失败）与 `54eb737ab` 干净副本逐条相同（基线既有，与本次无关），`express-companies.test.ts` 9/9 过。
`node scripts/check-generated-docs.mjs --check`：`✓ 26 个生成器的产物都是最新的`（重跑了 b-app OpenAPI、API 清单、B 端 API 详情、
B 端功能点矩阵、三端权限矩阵、后端分层清单）。

---

## §6 对账二 · 设计 → 实现

改动文件（提交 `git show --stat`，生成物另列）：

| 动作 | 文件 | 对 §2 模块设计 |
|---|---|---|
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java` | ✅ 按市场分表；`forMarket` / `isValidFor` / `resolve` / `nameOf`（跨市场）/ `@Deprecated isValid` |
| 新增 | `backend/shop-base/src/main/java/ai/neargo/shop/common/StoreMarkets.java` | ⚠️ 偏差 1 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizExpressCompanyController.java` | ✅ |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/logistics/LogisticsPort.java` | ✅ `default boolean traceCovers` |
| 修改 | `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/port/LogisticsPortImpl.java` | ✅ 实现 `traceCovers`，原 `hasK100` 改为调它（同一口径） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/impl/OrderShippingServiceImpl.java` | ✅（另见偏差 3） |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java` | ✅ `shipLegacy` |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreShipSettingServiceImpl.java` | ✅ |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizWaybillCorrectionController.java` | ✅ E9 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/WxShippingUploadResolver.java` | ✅ |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | ✅ 「订单与售后」组 |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | ✅ `ANY_OF` |
| 修改 | `packages/shared/src/utils/express-companies.ts`、`packages/shared/tests/express-companies.test.ts` | ✅（另见偏差 4） |
| 修改 | `b-app/src/api/{contract,endpoints,http}.ts`、`b-app/src/api/mocks/order.ts`、`b-app/scripts/gen-openapi.mjs` | ⚠️ 偏差 5 |
| 修改 | `backend/logistics/logistics-channel/.../Kuaidi100TraceProviderTest.java`、`backend/shop-channel/.../Kuaidi100PickupGatewayTest.java` | ⚠️ 偏差 2 |
| 新增 / 修改（测试） | `ExpressCompaniesTest`、`BizExpressCompanyControllerTest`、`BizExpressCompanyFlowTest`、`BizWaybillCorrectionControllerTest`、`WxShippingUploadResolverTest`、`LogisticsPortImplTest`、`StoreScopedDailyRoutesFlowTest` | §5 |
| 生成物 | `docs/api/openapi-b.yaml`、`docs/api/API清单.md`、`docs/api/API详情-B端.md`、`docs/technical/reference/{B端功能点-权限码-页面,三端权限矩阵-按业务域,后端分层清单}.md` | 重新生成 |

库表、迁移、权限码、i18n、配置项：均未动（与 §2 一致，没有 V6xx）。

### 偏差说明

1. **新增 `StoreMarkets`（`ai.neargo.shop.common`，`@Component`）**。§2 只写了「市场取订单 / 门店自己的门店号，为空按
   `shop.merchant.default-market` 回落」，没说放哪。清单接口、`ship`、`shipLegacy`、改运单、发货设置五处都要同一口径，
   散写五遍迟早有一处忘了回落（存量大陆主体大多没写市场，忘了的那处会拒掉所有发货）。它只读 `MerchantQueryPort#storeProfileOf`，
   不加 Port 方法。另：`mch_entity.market` 列非空，「没写市场」在库里是空串，`storeProfileOf` 已把空串当空处理。
2. **删掉 `ExpressCompanies#all()`**（§2 草图没列它）。「全部」在分表之后语义不清；唯一调用方 `Kuaidi100TraceProviderTest`
   改为 `forMarket("CN")`（它验的是「大陆能选的轨迹都查得到」，台湾首期本就不查）；`Kuaidi100PickupGatewayTest` 改用
   `isValidFor("CN", …)`，不再调已废弃的 `isValid`。
3. **`OrderShippingServiceImpl#correctByPlatform`（运营端改运单）也随之按订单市场校验并归一**：它与 `ship` 共用
   `requireEligible`，§2 只列了 `ship`。大陆单零差异（大陆表原样）；台湾单此前任何码都过不了这里，现在能用台湾码更正。
4. **端上共用文件多导出了 `EXPRESS_COMPANIES_BY_MARKET`、`expressCompaniesOf(market)`、`expressCompanyName(code)`**；
   `EXPRESS_COMPANIES` 仍是大陆表，b-app / c-app 现有引用一行没动。对账测试改为先去掉 Java 注释再抠（类注释里举例的
   `new Company("…", "…")` 会被误算），并按 `BY_MARKET` 映射分组、要求「分组后的条数 = 全文件 `new Company` 条数」。
5. **b-app 契约补了端点**（`mExpressCompanies` → `GET /biz/express/companies`，响应类型 `ExpressCompanyList` /
   `ExpressCompanyOption` 写在 `contract.ts`）：§2 只说「App 要改」，没有列 uni-app b-app 的契约文件；不补的话 OpenAPI 与
   API 清单里没有这个端点。mock 只有大陆门店，给大陆 14 家、`courierPickupAvailable: true`。b-app 页面仍用编译期常量
   （它只服务大陆单），没有改成调接口。
6. **测试类名与 §5 计划不同**：`OrderShippingServiceTest#…` / `ExpressPickupServiceTest#…` 的几条并进了
   `BizExpressCompanyFlowTest`（走真实 HTTP 与真实 `MerchantOrderService`，比替身更接近线上）；`BizWaybillCorrectionFlowTest`
   改成 `BizWaybillCorrectionControllerTest` 单测 —— 商户改运单要先有已登记的物流运单（outbox → 物流域登记），
   流程夹具代价与要验的分支不相称，四条分支只看「交给 `changeWaybill` 的承运商」与 WARN，单测即可断言；
   能不能叫快递的两个分支（通道开 / 关）同理放在 `BizExpressCompanyControllerTest`。

---

## 评审修订说明（2026-11-08，按 ai-shop-bapp `docs/implementation/TDD评审-商户App.md`）

技术问题核对属实的，已直接改进 §0 / §1 / §2 / §5；产品问题合进下方「已拍板」。

| 评审条目 | 改了什么 | 为什么 |
|---|---|---|
| 「最需要先定的」10 · 改运单（R06）是自由文本，加校验后台湾门店改运单被 10400 拒 | 新 E9：改运单**不收紧**——不改承运商（空或与当前相同）放行、改成清单内的码或名称归一、清单外照旧写入并记 WARN；§1「会被改到的」与校验口径表同步改 | 核对属实：App R06 是不受开关控制的自由文本框（`store_orders_pages.dart` 第 666–668 行）；`changeWaybill` 本来就允许不传承运商（第 142 行）。拒了就是已发货订单改不了运单 |
| 同上（延伸）· 现有 App 在台湾门店发货必失败 | E3 加「本市场名称归一成码」，`ExpressCompanies#resolve` | App 默认值「黑貓宅急便」正是台湾表 `TCAT` 的名称，归一后 App 改版前也能发货；只在本市场找，台湾门店的「顺丰速运」不会被归成大陆 `SF` |
| 二 · 叫快递回写的是哪一套码 | §1 新增一行出处 | 回写的是 `Kuaidi100PickupGateway.CODES` 的 key，八个全在大陆 14 家里，与清单同一套；叫过快递的订单改运单不受影响 |
| 二 · 只按 `market` 判断，门店 `market` 为空时 App 判错；建议给能力位 | E1 响应从裸数组改成 `{market, courierPickupAvailable, companies}`；新 E10 | 核对属实：`/context` 与 `storeProfileOf` 给的都是原值、为空不回落。能力位放在发货页本来就调的这个接口里，不动 `/context` |
| （评审未提，核对时发现）· 发货校验的市场取调用方门店号 | 校验口径表改为取订单自己的 `OrdSubOrder.storeNo` | 叫快递取件回填运单时以空门店号调 `ship`（`ExpressPickupServiceImpl` 第 360 行），按调用方取市场会取不到 |
| 二 · 关联需求里的交接文档路径不对 | 改为 ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md` | 原路径在本仓库不存在 |
| 二 · `OrderVO` / `/trace` 带 `expressCompanyName` | **没有照改**，答复见 §2「评审提问的答复」 | `OrderVO` 共用、构造点多，改动面与本 TDD 不相称；App 用清单映射即可 |
| 二 · X7 台湾隐藏叫快递、X3 台湾首期不查轨迹与原型冲突 | 不自己定，合进「已拍板」X7、X3，写明与原型 v15 R10、v6 R03 / R06 的冲突 | 这是砍原型功能，要产品决定并同步改原型 |
| 二 · X1 首批名单谁拍板、何时定 | 合进「已拍板」X1 | 同上 |

---

## 已拍板

> 2026-11-08 用户按建议全部接受：下列各条一律按「建议」执行；「选项」与「后果」保留作决策记录。

| # | 问题 | 选项与各自后果 | 建议 |
|---|---|---|---|
| X7 | 台湾门店的「叫快递」入口怎么办。原型 v15 R10 画了台湾门店叫「黑貓 NT$130 / 新竹 NT$110」上门取件，v6 R03 也有「改为预约上门取件」；但后端叫快递只有大陆承运商（`Kuaidi100PickupGateway`），台湾门店 `courierPickupAvailable=false` | **A 首期隐藏**：App 按 `courierPickupAvailable=false` 隐藏 R03 与 R13 待办里的叫快递，「填运单发货」是台湾唯一发货方式；原型 R10 与 R03 那一句要同步改或标「二期」。**B 保留入口、点了说明「本市场暂未开通」**：原型不用改，但商户看得到用不了，会来问。**C 首期就做台湾叫快递**：要先接台湾寄件渠道（本仓库没有），排期另算，本 TDD 不覆盖 | **A**，并请产品同步改原型；台湾寄件渠道接入时只要 `courierPickupAvailable` 变 `true`，App 不用再改 |
| X3 | 台湾承运商首期查不查轨迹。原型 v6 R06 画了「黑猫 9012…7890」的轨迹节点；后端快递100 对台湾这几家的覆盖与编码没有核验记录 | **A 首期不查**：`traceSupported=false`，发货照常，R06 显示「这家快递暂不支持查询轨迹」；原型 R06 要改。**B 首期就查**：先核验快递100 对台湾承运商的覆盖与编码，再往 `lgs_carrier_code` 与 `Kuaidi100TraceProvider.CODES` 补行，**多一次迁移（V6xx）**、排期加核验时间 | **A**，轨迹另开；核验通过后只改服务端，`traceSupported` 变 `true` 即可 |
| X1 | 台湾首批承运商清单与显示顺序，由谁拍板、何时定。App 默认选中第一项 | 建议名单：黑貓宅急便 `TCAT`、新竹物流 `HCT`、中華郵政 `TWPOST`、嘉里大榮物流 `KERRYTJ`、宅配通 `PELICAN`（**码是我方自定义的**，名称用官方繁体名）。**A 按建议名单上**：首批商户用的其它快递要等下一次发版。**B 先问首批商户实际在用哪些再定**：名单准，但接口冻结要等 | 由产品在接口冻结前拍板；没有新信息就按 **A**，黑貓放第一（与 App 现有默认值一致） |
| X2 | 超商取货（7-11 交貨便、全家店到店）要不要进这份清单 | **A 不进**：另立履约方式另开。**B 进**：硬塞成「承运商」，买家拿着超商单号去宅配网站查不到，也没有收件门市 | **A** |
| X4 | 清单是代码内置还是运营端可配 | **A 代码内置**：每个码核验过，加快递要发版。**B 运营可配**：不发版就能加，但填错一个码就是发货静默失败，要另做「可配 + 核验」，多一张表与迁移 | **A** |
| X5 | 承运商名要不要随 `Accept-Language` 翻译 | **A 不翻译**，按市场原文：与包裹面单、承运商官网一致，买家对得上。**B 翻译**：英文界面好看，但名称与面单对不上，名称归一（E3）也要按多语匹配 | **A** |
| X6 | 大陆快递是否也给台湾门店（如跨境寄大陆） | **A 首期不给**：台湾一期只做岛内。**B 给**：跨境运费模板、清关都没有，选了也走不通 | **A** |

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 起草（草稿，未拍板） |
| 2026-11-08 | 按商户 App 评审（TDD评审-商户App.md）修订：见上方「评审修订说明」；状态仍为草稿 |
| 2026-11-08 | 用户按「已拍板」各条建议全部接受，状态改为已确认 |
| 2026-11-08 | 实现（分支 `codex/p0-express-companies`）：§5 测试全绿，基线已知失败未增；§6 偏差 6 条；状态改为已实现 |
