# TDD-短信按号段走模拟通道

状态：**2026-11-11 地区策略修订中；旧模拟通道仅在地区准入开启后适用**
历史状态：**已实现**（2026-11-05；本地提交，未推送）
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


## 2026-11-11 用户确认的地区策略修订

档位：1。台湾手机号只作资料记录，不做格式或短信验证；系统按地区配置，短信通道接入后再开放。此修订覆盖上文台湾默认 mock 的历史约定。

### §0 需求对账

| AC | 需求 | 落点 |
|---|---|---|
| R1 | 台湾默认仅记录号码，保留空格/前导号等原始联系方式，不以手机号建登录身份 | 地区手机号策略与运营代填；店主关联已注册邮箱账号 |
| R2 | 大陆旧校验及旧手机号代填继续可用，市场必须为系统已有且启用 | 同一策略服务，CN 默认格式校验；旧请求未传市场仍按 CN |
| R3 | 每个地区可保存“仅记录/格式校验”和短信开关，未接通地区不能开启短信 | SettingService 按市场存配置；通道已接入市场由部署配置声明 |
| R4 | 台湾关闭时，发送及验证验证码均在生成码/测试白名单之前拒绝；不影响邮箱登录 | AuthService 统一短信准入，不能靠 mock 或旧码绕过 |
| R5 | 申请保存所选地区，保留协议本人确认、资质及代填/审核分离 | nullable market_code 增量，不改历史申请或冻结 SQL |

### §1 影响面

运营系统市场页、代填页及相关真实/模拟合同；平台设置、用户邮箱只读归属查询、统一短信发码/验证。仅记录号码不表示已验证或自动拥有该号码对应账号。

### §2 执行计划

1. 新地区手机号策略服务复用 MarketPort、SettingService；TW 默认 recordOnly=true、smsVerificationEnabled=false；SMS 通道准入声明为 shop.sms.channel-ready-markets（默认 CN），区号来源为 shop.sms.calling-prefixes。
2. 增加受 system:param:read/update 保护的策略维护接口与系统页卡片；运营代填选地区，台湾联系方式不格式校验，店主使用已有注册邮箱。
3. 申请 market_code 通过 V656 新增可空列，旧提交命令构造及 CN 请求保持兼容；仅记录号码不写 PHONE 身份或 verified_at。
4. 在 AuthService.sendOtp / verifyPhoneOtp 最前面检查地区短信开关，已有测试白名单或模拟分流不能覆盖关闭状态。
5. 集中验证策略/控制器/邮箱归属/短信拒绝及真实页面合同，按同一发布闭包重新部署 UME166，再续线上流程。输入与异常规则引用项目 Java 规范§八。

### §5 验证

本地增量验证：MarketPhonePolicyServiceTest 5、OpsMerchantRegionalContactTest 3、RegionalSmsAdmissionTest 1、SmsMockPrefixTest 3、EmailOtpAuthTest 18、ProfileAndPasswordTest 9、BackendI18nParityTest 6，共45项通过，0失败/错误/跳过。运营端地区表单6项（含大陆号码空格与仅大陆启用回归）、原系统维护/HTTP7项通过，typecheck及变更文件ESLint通过。新策略先于预设码、模拟前缀和测试身份检查，旧mock测试仅说明已准入地区的分流机制仍可用。

UME166发布及线上验证进行中；现有S/P发布基线为1e9975ee，不因本地用例通过声明地区增量已上线。
