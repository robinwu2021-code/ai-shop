# 交接：App 按门店开关付款方式（2026-11-17）

设计：[TDD-门店付款方式-App设置](../../technical/TDD-门店付款方式-App设置.md)（状态：已确认；§3.1 记录了路线修正）。
目标：运营端控制商户的渠道（已有），商户 App 控制门店的渠道（本次新增）。

## 一、结论先读

- 运营端已能按商户、按门店控制渠道（账户状态、账户产品、商户收款策略；门店绑定、门店收款策略、收款入口产品）。缺的是 App 侧：没有任何接口和页面能开关门店的付款方式。
- 方案：App 只改「门店收款策略」（INHERIT / OVERRIDE，只能在商户能力内收窄），与运营端「门店收款配置」同一份数据。
- **路线修正**（实现中发现，已写入 TDD §3.1）：
  1. 商户 App 是 Flutter 工程 `ai-shop-bapp/apps/merchant_app`，不是 uni-app `b-app`。
  2. App 不直连 near-pay 商户端接口。链路是 App → ai-shop `/biz/payment/...`（`@PreAuthorize canBiz`）→ `BizPaymentAppService` → UME166 为 `shop.pay.deployment=standalone`，走 `RemoteBizPaymentAppService` → `PayBizInternalApi`（HTTP，`127.0.0.1:18093`，调用方 `AI_SHOP_UME`）→ near-pay `/internal/pay/v1/biz/stores/{storeNo}/**` → near-pay `PayBizFacade`。

## 二、各仓库状态

| 仓库 / 分支 | 提交 | 推送 | 内容 |
|---|---|---|---|
| near-pay `feat/biz-store-collection-methods-20261117`（基于 release/166 42811c35） | b99ed40e | 已推 | `StoreCollectionMethodService` + 测试（4 例通过）；另含一个已被撤掉的 `/biz/pay/v2` 控制器 |
| 同上 | d08209be | **未推** | 改走内部接口：`PayBizInternalApi`（`StoreMethodItem/StoreMethods/StoreMethodsReq`，`GET/PUT …/collection-methods`）、`PayBizFacade#storeMethods/#saveStoreMethods`、`InternalPayBizEndpoint` 两条路由；删掉 `/biz/pay/v2` 控制器。测试：`StoreCollectionMethodServiceTest` 4/4、`PayBizInternalApiRoundTripTest` 6/6、`PortalRouteRulesTest` 6/6 |
| near-pay `feat/sandbox-method-options-20261117` | 76df9102 | **未推、未发布** | 收银台弹框简化（拖拽条 + 标题 + 打勾，测试渠道「測試」胶囊标签）；vitest 292 通过，本机预览已看 |
| ai-shop `docs/store-payment-methods-tdd-20261117`（基于 main） | 3a176ec34、9f2c3658b、d1f08598f、c6d0b9553 | 已推 | TDD 草稿→确认→偏差记录；d1f08598f 是按旧路线写的转发白名单 |
| 同上 | 05e748dc6 | **未推** | 撤回 d1f08598f |
| 同上 | 本交接提交 | **未推** | ai-shop 后端修正路线（见下）+ 本文档 |

### ai-shop 后端（本交接提交里，**测试未跑完**）

- `BizPerms.PAY_STORE_METHOD = "mch:pay:store-method"`：只授予主体属主（OWNER 的 `*`），不进店长/店员模板，无需迁移。
- `BizPaymentController`：`GET|PUT /biz/payment/store-method`（门店取 `BizContext.requireStoreNo()`；PUT 体 `StoreMethodReq{requestNo, expectedVersion, enabledProductCodes}`）。
- `BizPaymentAppService#storeMethods/#saveStoreMethods`；`RemoteBizPaymentAppService` 经 `PayBizInternalApi`，保存成功记审计 `BIZ_PAY_STORE_METHOD`；内嵌形态 `BizPaymentAppServiceImpl` 抛 `PAY_CHANNEL_UNAVAILABLE`（内嵌没有门店收款策略服务）。
- ai-shop 那份 `PayBizInternalApi` 已同步 near-pay 的三个 record 与两个方法（两份必须同形）。
- `BizEndpointPermTest` 登记 `/biz/payment/store-method → PAY_STORE_METHOD`；`RemoteBizPaymentAppServiceTest` 新增用例。
- **下一步先跑**：`mvn -q -o -pl shop-app -am test -Dtest='RemoteBizPaymentAppServiceTest,BizEndpointPermTest,ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false`（被中断，结果未知）。

## 三、接口契约（实现后的真实形状）

ai-shop（App 调用）：

```
GET  /biz/payment/store-method           ← 当前门店（App 请求头里的门店）
PUT  /biz/payment/store-method  { requestNo, expectedVersion, enabledProductCodes[] }
→ { storeNo, mode: INHERIT|OVERRIDE, version,
    items: [ { productCode, channelCode, method, test, enabled, editable, excludedBy } ] }
```

- 列表 = 门店 ACTIVE 绑定 → 账户 ACTIVE → 产品 OPEN（不列目录里门店用不上的产品）。
- `excludedBy`：null 开启；STORE 门店关（可重开）；MERCHANT / CATALOG / STORE_STATE 商户层或目录关；ENTRY 门店有 ACTIVE 的 H5 收款入口但未包含该产品（Q3）。后四种 `editable=false`。
- 保存：开启集合 = 可开全集 → INHERIT；否则 OVERRIDE；至少保留一种；越权开启拒绝（BAD_REQUEST）；版本冲突（CONFLICT）提示刷新。
- 生效：收银台展示有 5 分钟缓存。

## 四、剩余工作（按顺序）

1. ai-shop：跑上面的测试；补 b-app `src/api/endpoints.ts` 登记 + `scripts/gen-openapi.mjs` 类型映射，重新生成 `docs/api/openapi-b.yaml`、API 清单、`glossary.json`（推送前「契约」「生成文档」闸门会比对）；`bash .githooks/pre-push </dev/null` 整套。
2. Flutter App（`ai-shop-bapp`）：「店铺设置」新增「付款方式」页（只对属主显示；正式/测试分组，测试带「測試」标签；不可开置灰写原因；只剩一项禁用其开关；只剩测试方式时警示；保存提示「约 5 分钟生效」）；台湾版隐藏旧「收款设置」入口（Q1）。该仓库有 `feat/core-flow-ui`、`feat/entity-ocr` 两个会话在改，先协调再动。
3. TDD：§2 按修正路线改写（端点、模块、b-app→Flutter），§5/§6 对账；状态改「已实现」。
4. 发布：near-pay 覆盖包（`PayBizFacade`、`PayBizInternalApi`、`InternalPayBizEndpoint`、`StoreCollectionMethodService`；基线为线上 jar 61a6fb2e ≡ release/166 42811c35）；ai-shop 覆盖包（shop-app、shop-base-auth、pay-domain 客户端；先合进 ai-shop `release/166-live-20261117`）；Flutter 发版。合并、推送、发布都需用户确认。

## 五、同一会话中其他未结事项

- **ume早餐店当前对顾客显示「LINE Pay（測試）」「信用卡（測試）」**：测试结束后从收款入口 `CE3A941F09E7E94974` 撤下 `LINEPAY_TEST_ONLINE`、`NEWEBPAY_TEST_CARD_ONETIME`（需停用入口几秒）。
- 沙箱试运行资格 24 小时：LINE `LYD_LINE_TRIAL_20261117:1` 至 2026-11-18 05:00 UTC；蓝新 `LYD_MPG_TRIAL_20261117:1` 至 06:07 UTC。PM082A 已无沙箱账户。
- 测试门店 `ST261117JC0WT5KSDV`「ume早餐店（LINE Pay 沙箱測試）」（占李昱達门店额度一家，Oscar App 可见），收款码 `Maswh_NWjnpkfp1MPfLfGw`。
- 正式环境收款推送要求 ai-shop `payment_collection_source` 有该门店 PRODUCTION 映射；目前只有 2070、2074 两家。批量迁移其余商户前要补，或让 near-pay 能解析 storeNo。
- 迁入的旧沙箱资格材料坐标仍写 SANDBOX：该账户资格列表报「资格材料归属冲突」（near-pay `QualificationService#read`）。
- 结果页对「明确授权失败」显示「无法确认，请联系商家」，应改为「付款失败，可重新付款」（near-pay 结果视图）。
- 运营端收款码接口返回的 `url` 不带 `/h5`（现已与去 /h5 的方向一致，无需改）。
- 其余约 380 家商户批量迁移仍待用户检查首户后启动。
