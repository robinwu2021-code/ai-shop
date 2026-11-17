# TDD-支付渠道三端统一数据源

状态：已确认（2026-11-17，用户「按默认口径开工」）
档位：2（near-pay 新增商户渠道视图端点、门店付款方式视图加收银台字段；ai-shop `/biz/pay/v2` 转发白名单加一条；Flutter 主体详情改数据源；运营端商户详情加渠道视图；不改库表）
关联：`TDD-门店付款方式-App设置.md`、`TDD-App支付权限由ai-shop决定.md`
创建：2026-11-17

## 验收标准（需求）

用户原话：「H5 上的渠道列表和 app 上的渠道列表，以及在运营端展示的渠道列表，来自于一个数据源，运营端开通，app 端展示并可以编辑，H5 展示。同时要考虑申请的时候的展示以及开通后的展示。」口径（用户按默认确认）：测试付款方式照常展示并带「測試」标签，由门店开关控制；App 只能开关门店级付款方式，账户与商户级由运营端控制。

- **AC1** Given 商户在 near-pay 有渠道账户（含测试账户、非进件来源的账户），When 店主打开 App 主体详情「渠道审核」，Then 「已開通」逐个列出账户：渠道、商户号后 4 位、已开通付款方式、状态；测试账户带「測試」标签排在正式之后。
- **AC2** Given 某渠道有未结束的进件且该渠道还没有账户，When 打开主体详情，Then 「申請中」列出它的阶段与待办，可点进进度/补件。
- **AC3** Given 某渠道既无正式账户也无进件，When 打开主体详情，Then 能在线申请的放「可新增」（有申请入口），不能在线申请的放「暫未開放」（提示由平台开通）。
- **AC4** Given 同一商户，When 运营端打开商户详情「渠道」，Then 看到与 AC1–AC3 同一份数据（同一端点数据）。
- **AC5** Given 同一门店，When App「付款方式」页加载，Then 每项标出「收银台显示」与否，且与 H5 收银台实际显示的付款方式一致（同一计算、同一缓存）。
- **AC6** 旧数据（ai-shop 本地进件表）不再出现在主体详情。

不做：App 关闭账户/商户级付款方式；改门店绑定；删除 ai-shop `/biz/merchant/payment` 接口。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1–AC3 | near-pay `MerchantChannelViewService#view`（账户 + 产品 + 进件合并，渠道目录 + 在线进件网关）；`GET /biz/pay/v2/merchant-channels`（商户端，店主委托）；ai-shop `BizPayOnboardingController` + 转发白名单；Flutter `entity_channel_rows.dart` 改按视图分组 |
| AC4 | near-pay `GET /pay-ops/v2/merchants/{merchantNo}/channels`（同一服务）；ops-web 商户详情「渠道」页签 |
| AC5 | `StoreCollectionMethodService.Item#checkout`：问收银台本身 `CheckoutService#displayMethods`（与扫码首页同一计算、同一份 5 分钟缓存、启动预热），按付款方式 + 正式/测试匹配；门店 ID 用来源门店 ID（投影 `externalStoreId`）；App 付款方式页对开着但收银台不显示的项标「收銀台目前不顯示」 |
| AC6 | Flutter 主体详情不再调用 `/biz/merchant/payment` |

## §1 合并规则

- 行 = 渠道账户（`stage=OPEN`）；账户带 `onboardingNo` 时同进件合并为一行。
- 无账户的渠道取最新一条进件：进行中/补件/驳回/已通过待交接 → `stage=APPLYING`；取消/关闭/被替代不出现。
- `test` = 渠道目录 `testChannel`；排序：正式在前，按目录顺序。
- 渠道选项 = 目录中与商户同市场、启用、非测试的渠道；`onlineApplication` = 已装配进件网关。

## §2 实施计划

| # | 改动 | 测试 |
|---|---|---|
| 1 | near-pay `MerchantChannelViewService` + 运营端/商户端两个 GET | 服务单测（正式测试并存、进件转账户、非进件账户、驳回、取消）+ 控制器 |
| 2 | near-pay `StoreCollectionMethodService.Item#checkout` | 单测：收银台集合决定 `checkout` |
| 3 | ai-shop 转发白名单 + 映射 `GET /biz/pay/v2/merchant-channels` | 转发测试 |
| 4 | Flutter 主体详情四组 + 門店付款方式「收银台显示」 | 分组单测 + 页面测试 |
| 5 | ops-web 商户详情「渠道」页签 | 页面单测 |
| 6 | 166 发布；李昱達三端核对 | 线上证据 |

## §3 发布与核对（2026-11-17，UME166）

- near-pay `b198eda6c` + `d1ecb836b` + `a21e4a150`（overlay，`pay-svc.jar` 31ab99a90188）；运营端静态页 `np-channel-view-20261117-b198eda6c`；ai-shop `341a03065`（`shop-app.jar` 692766fc1575）；Flutter `f8ec65b`（nearboss 包）。
- 李昱達 PM2026111707510001：运营端「渠道总览」、App 主体详情「已開通」均为 4 行（藍新 Apple Pay、LINE Pay、LINE Pay 測試、藍新 Sandbox 信用卡）；ume早餐店 H5 收银台 `APPLE_PAY / LINE_PAY / CARD(測試)` 与 App 门店「付款方式」4 项一致。
- 首版 AC5 直接重算路由选择、且误用投影主键当门店 ID：门店页全标「不显示」、冷启动超 5 秒；已改为问收银台本身（`d1ecb836b`、`a21e4a150`）。
