# TDD-双地图提供方与门店选点

状态：**已确认**（2026-11-05 用户确认 D1/D2/D3 均按建议；后端已实现，见 §5/§6；App 选点页另行实现）
关联：用户 2026-11-05「地图同时支持高德和 Google，高德项目中已有实现，Google 在 sharehub 中也有实现，同时集成，系统根据情况可以切换」；
原型 `ai-shop-bapp` · `docs/prototypes/core-pages/v4-store-onboarding.html` S06 / S06b；
前置 `TDD-注册地区与三步建店.md`（store-setup 已带 `mapProvider`，App 尚未接地图组件）。
档位：**2**（新增地图提供方实现与路由、配置项、端点入参；App 新页面与新依赖）

---

## L1 定位

建店第 2 步的「地图选点」要真能用，而且**按地区自动用对的地图**：大陆用高德，台湾 / 阿联酋用 Google。
两套都已有实现可复用 —— 高德在 ai-shop 后端（`GeoPort` / `GeoServiceImpl`，后端代理，密钥不落端），
Google 在 sharehub（`ops-web/lib/gmaps.ts`：Maps JS 画图 + Places REST 搜索 / 详情 / 逆地理；`c-app/pb-map.vue` 在 App WebView 里跑）。
本期把两者接到同一个抽象下，由服务端按门店地区决定用哪家；地图不可用时退回手动输入，不挡建店。

## L2 结构

| 层 | 现状 | 本期 |
|---|---|---|
| 后端地理能力 | `GeoPort` 只有高德实现；`/biz/geo/reverse` `/geocode` `/tips` 不分地区 | `GeoPort` 增 Google 实现（移植 sharehub 的 Places / Geocoding 调用，改为服务端代理）；`GeoRouter` 按地区选提供方 |
| 地区 → 提供方 | 无（store-setup 的 `mapProvider` 只在 CN 且高德可用时给 `AMAP`） | 配置 `shop.geo.provider-by-market`（缺省 `CN:AMAP,TW:GOOGLE,AE:GOOGLE`）；提供方没配密钥则该地区为 null |
| 端上取地图 | Flutter 无地图 | store-setup 每个市场带 `mapProvider` + `mapJsKey` + `mapJsSecurity`（浏览器端可见、限定包名 / 域名的 JS key；安全密钥仅高德）；`/biz/geo/tips` `/biz/geo/reverse` 带 `market` 走对应提供方 |
| App 选点页 | 无 | 新页面：WebView 加载内置 HTML（高德 JS API 或 Google Maps JS，二选一），搜索走后端 `/biz/geo/tips`，选中回填地址 + 坐标 |

**为什么不跨提供方兜底**：高德在阿联酋**没有底图数据**（sharehub 2026-09-26 实测：迪拜瓦片 179 字节纯色，且不报错）；
Google 在大陆网络下不可达。互相兜底只会得到「看着能用、实际空白」的地图，所以某地区的提供方不可用时直接退回手动输入。

## §0 对账一 · 需求 → 设计

| AC | 需求（可断言） | 落点 |
|---|---|---|
| AC1 | 缺省配置下 CN → AMAP，TW / AE → GOOGLE；配置可改（如测试时台湾临时用高德） | `GeoRouter#providerFor(market)` |
| AC2 | 某地区的提供方未配服务端密钥**或**未配端上 JS key → store-setup 该市场 `mapProvider=null`，App 不显示「地图选点」 | `BizFoundationController#storeSetup` |
| AC3 | `GET /biz/geo/tips?kw&market` / `GET /biz/geo/reverse?lat&lng&market` 按地区走对应提供方，该地区不可用给空结果（不报错、不换另一家）；不带 `market` 时保持今天的高德行为（见 §6 出入 1） | `BizGeoController` → `GeoRouter` |
| AC4 | Google 实现：Places Autocomplete（文本搜索）、Geocoding reverse；服务端密钥不出现在任何响应与日志里 | `GoogleGeoPortImpl` |
| AC5 | 坐标统一存 `mch_store.lat_e6/lng_e6`：AMAP 为 gcj02，GOOGLE 为 wgs84；门店记录坐标系（新列 `coord_sys`） | quick-start 新增 `latE6/lngE6/coordSys` |
| AC6 | App：有 `mapProvider` 时第 2 步显示「地图选点」；选点页可搜索、拖动定位、点选附近地点，「确认此位置」回填地址与坐标，仍可手改；「改为手动输入」直接返回 | Flutter `StoreLocationPickerPage` |
| AC7 | 地图脚本加载失败 / 无网络 / 无定位权限：选点页提示并只保留搜索列表与手动输入，不白屏 | 同上 |

## §2 方案（摘要）

- **后端**：新增 `GoogleGeoPortImpl`（`shop.geo.google-key`，环境变量 `GOOGLE_MAPS_SERVER_KEY`）；`GeoRouter` 持有两个实现，按 `provider-by-market` 选择；
  现有调用方（社区 / 地址校验）不带地区时仍走高德，行为不变。
- **配置**：`shop.geo.provider-by-market`、`shop.geo.google-key`、`shop.geo.amap-js-key` + `amap-js-security`（端上 JS 用，与服务端 `amap-key` 分开）、`shop.geo.google-js-key`（端上 JS 用，限定 Android 包名 / iOS Bundle / 域名）。
- **库表**：`mch_store.coord_sys VARCHAR(8) NULL`（下一个迁移号）。
- **App**：依赖 `webview_flutter`（选点页）、`geolocator`（当前位置，可选）；HTML 模板内置于 assets，按 `mapProvider` 注入高德或 Google 脚本；
  JS ↔ Flutter 用 JavaScriptChannel 传「中心点移动 / 点选」；搜索列表用原生 Flutter 控件调后端 `/biz/geo/tips`。

## §3 选型

| 方案 | 结论 |
|---|---|
| A. WebView + 两家 JS 地图（同 sharehub `pb-map.vue` 思路）+ 后端代理搜索 | ✅ 建议：一套页面两种脚本，切换只是换 provider；与 sharehub 已验证的实现同路 |
| B. 原生 SDK：`amap_flutter_map` + `google_maps_flutter` | 两个原生 SDK 同时打包，高德 Flutter 插件多年未维护，与当前 Flutter 版本兼容风险高；iOS / Android 各要一套原生 key 配置 |
| C. 只做搜索列表 + 静态图，不做可拖动地图 | 不满足「地图选点」 |

## §4 风险

| 风险 | 缓解 |
|---|---|
| 端上 JS key 可被抓包看到 | 只用限定包名 / 域名的浏览器 key；服务端 key 不下发 |
| Google 计费 | 搜索走 Places 会话 token（sharehub 已有做法），一次选点算一次 |
| 坐标系混用 | 门店带 `coord_sys`；导航与距离计算按坐标系处理 |

## L4 待决

- **D1 App 渲染方式**：✅ 已确认 A（WebView + JS）。
- **D2 切换规则**：✅ 已确认按门店地区（缺省 CN→高德，TW/AE→Google，可配置覆盖），不跨提供方兜底。
- **D3 密钥来源**：✅ 已确认。需要四把 key —— 高德服务端（`AMAP_WEB_KEY`，ai-shop 生产已有）、高德 JS + 安全密钥、Google 服务端、Google JS。
  建议从 `~/work/env/server-mirror/ai-shop/shop-app.env` 与 `~/work/env/server-mirror/powerbank/sharehub-app.env` **只提取地图相关变量**到本机 `~/work/env/local-notify.env`（同上次邮件 / 短信的做法），需你授权。

## §5 对账三 · 实现 → 需求（测试）

测试与实现同轮写（TDD 先写用例，首跑即编译不过：`GoogleGeoPortImpl` / `GeoRouter` / 新字段都不存在），
「红」以消融为准：撤掉关键实现行 → 对应用例变红 → 还原并 `touch`。全部 HTTP 用罐头 JSON，**测试不连网**。

| AC | 测试方法 | 结果 | 消融（撤掉 → 变红的用例） |
|---|---|---|---|
| AC1 | `GeoRouterTest#defaultMappingIsCaseInsensitive`、`#mappingIsConfigurable` | 绿 | — |
| AC1（不兜底） | `GeoRouterTest#portForNeverFallsBack` | 绿 | Google 不可用时退回高德 → 红（「Google 没配密钥时台湾没有地图，不退回高德」） |
| AC2 | `GeoRouterTest#clientMapNeedsBothServerPortAndJsKey`；`BizStoreSetupTest#mapsAc2StoreSetupCarriesTheMarketsProviderAndItsBrowserKeys`、`#ac5StoreSetupCarriesMapProvider…`（改写为按 ClientMap） | 绿 | 去掉「端上 JS key 必须配置」→ `clientMapNeedsBoth…` 红 |
| AC3 | `BizGeoMarketRoutingTest` ×8（tips / reverse 带与不带 market、不可用给空、越界 10400、AMAP 按 sys 归一 gcj02、Google 原样 wgs84） | 绿 | tips 在该地区不可用时退回高德 → `tipsForAnUnavailableMarketAreEmpty` 红 |
| AC4 | `GoogleGeoPortImplTest` ×9（请求形状：POST searchText、`X-Goog-Api-Key`、FieldMask 四字段、zh-TW、regionCode；映射：e6 坐标、无坐标条目丢弃、POI→STREET 回落、adcode/township 为 null；失败一律空：抛错 / 非 OK / 坏 JSON；无 key 不发请求） | 绿 | FieldMask 改 `*` → `tipsUseTextSearch…` 红 |
| AC5 | `BizStoreSetupTest#mapsAc5QuickStartCoordinatesAreBothOrNeither…`、`#mapsAc5QuickStartCommandCarriesCoordinatesOnlyWhenGiven`；`RegistrationMarketFlowTest#quickStartPersistsTheMapPickedCoordinates`（HTTP + H2 `mch_store.lat_e6/lng_e6/coord_sys`；只给一半 → 10400 且不建壳） | 绿 | 删 `requireCoordinates` → 单测与场景都红；删 `store.setCoordSys` → 场景红 |
| AC6 / AC7 | App 选点页 | — | 不在本次（后端）范围 |

回归（2026-11-05，`mvn-neargo -o -q -pl shop-app -am test -Dtest=…`）：
GoogleGeoPortImplTest 9/9、GeoRouterTest 5/5、PlaceResolverHotCacheTest 2/2、BizGeoMarketRoutingTest 8/8、BizStoreSetupTest 12/12、
RegistrationMarketFlowTest 7/7、QuickStartFlowTest 10/10、BizFoundationJourneyFlowTest 7/7、ApiContractTest 5/5、SchemaDriftTest 6/6、
PlaceResolveChainTest 7/7、EstateCacheBackfillFlowTest 3/3、EstateCacheFlowTest 11/11、CommerceAccessSecurityFlowTest 5（2 skip）、
CommerceStoreCreationFlowTest 4（1 skip）、ConditionalBeanWiringTest 1/1、BackendI18nParityTest 6/6、HostStoreContextTest 2/2、
MultiEntityIdentityFlowTest 11/11、StoreBannerFlowTest 5/5、MerchantEntityStoreMarketTest 3/3 —— 全绿。
`ArchitectureTest` 5 红、`BizEndpointPermTest#everyBizEndpointHasADecision` 1 红、`MapperSmokeTest` 1 红（10 个缺表 mapper：
execution_retention / resource_occupation / service_execution）均为既有，无本期类、未新增端点。

## §6 对账二 · 设计 → 实现

### 契约（最终）

**`GET /biz/merchant/store-setup`** —— `markets[]` 每项：

| 字段 | 取值 |
|---|---|
| `mapProvider` | `"AMAP"` / `"GOOGLE"` / null。= `GeoRouter#providerFor(market)`，且该家服务端 key 可用**且**该家端上 JS key 已配置；否则 null |
| `mapJsKey`（新） | 该家浏览器 JS key（`shop.geo.amap-js-key` / `shop.geo.google-js-key`）；mapProvider 为 null 时 null |
| `mapJsSecurity`（新） | 高德 JS 安全密钥（`shop.geo.amap-js-security`，空为 null）；GOOGLE 恒 null |

**`GET /biz/geo/tips?kw&city&market`**：`market` 可选。不带 → 原高德路径。带 → `GeoRouter#portFor(market)`，不可用 → `[]`。
每条 `GeoPort.Tip` 末尾新增 `coordSys`（`gcj02` 高德 / `wgs84` Google；高德不带 market 的旧路径也填 `gcj02`）。

**`GET /biz/geo/reverse?lat&lng&sys&market`**：`market` 可选。不带 → 原行为（不可用 10503）。带 → 越界 10400；
不可用或查不到 → `code=0`，`recommend=""`、`address=""`、`adcode/township=null`、`latE6/lngE6`=请求点、`coordSys=null`；
有结果 → AMAP 按 `sys`（缺省 gcj02）归一到 gcj02、`coordSys=gcj02`；GOOGLE 原样、`coordSys=wgs84`，`adcode/township=null`。
响应 `GeoService.ReverseVO` 末尾新增 `coordSys`（保留 6 参构造，缺省 gcj02）。

**`POST /biz/merchant/quick-start`** 新增可选 `latE6`、`lngE6`（int，×1e6）、`coordSys`（`gcj02`|`wgs84`）：
经纬度同有同无；|lat| ≤ 90 000 000、|lng| ≤ 180 000 000；给了坐标则 coordSys 必须是二者之一 —— 任一不满足 10400，且校验先于建壳。
没给坐标时 coordSys 忽略。坐标原样写 `mch_store.lat_e6/lng_e6`，坐标系写新列 `mch_store.coord_sys`（V630），不做转换。

**配置**（`application.yml` `shop.geo`）：`provider-by-market`（`${SHOP_GEO_PROVIDER_BY_MARKET:CN:AMAP,TW:GOOGLE,AE:GOOGLE}`）、
`google-key: ${GOOGLE_MAPS_SERVER_KEY:}`、`amap-js-key: ${AMAP_JS_KEY:}`、`amap-js-security: ${AMAP_JS_SECURITY:}`、`google-js-key: ${GOOGLE_MAPS_JS_KEY:}`。

### 模块落点

| 设计 | 实现 |
|---|---|
| Google 实现 | `shop-core` `ai.neargo.shop.platform.port.GoogleGeoPortImpl implements GeoPort`：Places API (New) `places:searchText`（FieldMask `places.id,places.displayName,places.formattedAddress,places.location`、`languageCode=zh-TW`、`maxResultCount=10`、按地区带 `regionCode`）；Geocoding API 做 reverse / geocode；around = searchText + `locationBias.circle`（高德类目码忽略）；`toGcj02` 原样返回。HTTP 走可替换的 `Transport`，测试注入罐头 JSON；任何失败回空，不打日志（异常里带着含 key 的 URL） |
| 路由 | `shop-core` `ai.neargo.shop.platform.port.GeoRouter`（`@Component`）：`providerFor` / `portFor`（只给 available 的那家，不兜底）/ `clientMapFor` / `coordSysOf` |
| 端上地图下发 | `BizFoundationController#storeSetup` 改注入 `ObjectProvider<GeoRouter>` |
| 端点 | `BizGeoController#tips` / `#reverse` 加 `market` |
| 建店坐标 | `QuickStartReq` / `MerchantAdminPort.QuickStartCommand` 末尾加三字段（保留旧构造）；`BizMerchantController#requireCoordinates`；`MerchantPortImpl#quickStart` 写入；`MchStore.coordSys`；迁移 `V630__store_coord_sys.sql` + `schema-test.sql` |
| 契约文档 | `docs/api/openapi-b-onboarding.json`（StoreSetupMarket、QuickStartRequest、新收录 `/biz/geo/tips`、`/biz/geo/reverse` 与 `GeoTip` / `GeoReverse`）；`b-foundation.json` 示例未变（只比对 quick-start 响应，形状没变） |

### 与设计的出入

1. **不带 `market` 时**：AC3 原写「按账号地区」，实现为**保持今天的高德行为**（委托方明确要求；也避免 TW 实例上老页面突然改走 Google）。App 选点页一律带 `market`。
2. **Google 实现不是 Spring bean**：只由 `GeoRouter` 构造持有。按类型注入 `GeoPort` 的十几处（社区、地址校验）与测试里的 `@MockitoBean GeoPort` 仍只看到高德一个候选，比「高德标 `@Primary`」更稳 —— 后者对 `@MockitoBean` 的候选判定依赖框架版本细节。
3. **reverse 的「空结果」**：`latE6/lngE6` 是 int 不可空，空结果填请求点、`coordSys=null`，端上以 `recommend` 为空判定「无结果」。
4. **Google 语言**：TW 与 AE 都用 `zh-TW`（按委托方给定）；阿联酋若要英文地名再按市场分语言。
5. **未做**：`/biz/stores/mine` 等读取路径未回传 `coordSys`（当前也不回传坐标）；`/biz/geo/geocode`、`/biz/geo/estates` 未加 `market`；quick-start 不校验 coordSys 与市场提供方一致（如 CN 提交 wgs84 也收）；App 选点页（AC6/AC7）。

实现提交：见 §7（本地提交，未推送）。
