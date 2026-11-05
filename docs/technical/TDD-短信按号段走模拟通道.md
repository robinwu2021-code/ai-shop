# TDD-短信按号段走模拟通道

状态：**已实现**（2026-11-05；本地提交，未推送）
关联需求：`ai-shop-bapp` · `PRD-台湾一期注册开店Plan套餐商城与支付开通-V3.md` §3.1（「台湾短信尚未具备正式能力时……不能把模拟短信显示为真实可用」）；
用户 2026-11-05 指示：大陆短信用已实现的真实通道，台湾 / 阿联酋用 mock。
档位：**1**（新增配置项 `shop.sms.mock-prefixes`）
创建：2026-11-05

## §0 对账一 · 需求 → 设计

| AC | 需求（可断言） | 落点 |
|---|---|---|
| AC1 | Given 配置 `+886,+971` When 给 +886 / +971 号码发码 Then 验证码照常存入 `OtpStore`、不调 `SmsPort`、debug 日志可见 `[sms-mock] otp to … = 123456` | `AuthServiceImpl#sendOtp` · `#mockedSmsRegion` |
| AC2 | Given 同一配置 When 给大陆号码（不带 `+`，如 `13800138000`）发码 Then 照常调 `SmsPort.sendOtp`（真实环境即阿里云） | 同上 |
| AC3 | Given 未配置 When 给任意号码发码 Then 行为与改动前一致，全部走 `SmsPort` | `setMockSmsPrefixes("")` → 空列表 |

孤立项：无。

不做：给台湾 / 阿联酋接真实国际短信；在接口响应里回传验证码（App 的「开发模拟验证码」展示需要它，但那等于把码放进 HTTP 响应，不在本期做）。

## §1 现状与影响面

- `AuthServiceImpl#sendOtp`：限流 → 预设码 → 测试号白名单 → 随机码 + `SmsPort`。本期只在最后一步前加一个分支。
- 发码限流、失败锁定、测试号白名单、预设码：不受影响（分支在它们之后）。
- 现有短信实现：`AliSmsGateway`（`shop.sms.stub=false`）/ `StubSmsGateway`（缺省），不改。
- **生产不配 `shop.sms.mock-prefixes`，行为不变。** 生产与本地 profile 同名（见 `FixedOtpGuard`），所以这里用缺省为空的显式配置，不按 profile 判断。

## §2 方案

- 配置项：`shop.sms.mock-prefixes`（逗号分隔的号码前缀，缺省空）。本机启动脚本设为 `+886,+971`。
- 修改：`backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/AuthServiceImpl.java`
  —— `setMockSmsPrefixes(String)`（`@Value` 加在 setter 上，避免再给十几个参数的构造器加一个）与 `mockedSmsRegion(phone)`。
- 新增：`backend/shop-core/src/test/java/ai/neargo/shop/user/service/SmsMockPrefixTest.java`。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `SmsMockPrefixTest#taiwanAndUaeNumbersGetAStoredCodeButNoSms` | ✅ 3/0 | 去掉分流分支 → 变红 ✅ |
| AC2 | `SmsMockPrefixTest#mainlandNumbersStillGoThroughTheRealSmsPort` | ✅ | — |
| AC3 | `SmsMockPrefixTest#withoutConfigurationEveryNumberUsesTheSmsPortAsBefore` | ✅ | — |

同时跑过：`EmailOtpAuthTest` 14/0、`ProfileAndPasswordTest` 9/0、`OtpTestPhoneFlowTest` 8/0、`BizAuthEmailLoginTest` 2/0（后两条加载完整 Spring 上下文，确认 setter 上的 `@Value` 正常注入）。

真实阿里云短信的投递验证依赖本机接入短信凭据，见对话记录；未接入前本节不覆盖真实投递。
