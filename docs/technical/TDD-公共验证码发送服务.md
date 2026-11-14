# TDD-公共验证码发送服务

状态：已实现（已合入统一开发分支，166部署与模拟器验收完成）。日期：2026-11-13。档位：1；扩展既有公共验证码能力、SPI与场景策略，不新增业务域、Maven模块或表族。

> **For agentic workers:** 实施采用 superpowers:executing-plans；按本项目 MEMORY 及 Java规范§八集中编码、一次受影响编译与测试，修复后只重验受影响部分。验证与发布进度见复选项及§5。

**Goal:** 将短信/邮箱发码封装为与认证业务平级的公共能力，通过静态场景常量和独立策略按需要求滑块，优先复用现有代码。
**Architecture:** 公共契约落现有 shop-base，编排与安全基础实现落 shop-base-auth 的 common.otp 包；旧发码中的渠道差异通过一份 SPI 渠道验证码能力抽取复用，Tianai仍留shop-app。AuthService、会员、订单等业务都是调用方，不作为公共服务的父层。
**Tech Stack:** Java21、现有Spring/Jakarta Validation、JDK内存计数、现有SmsPort/MailPort/OtpStore、Tianai1.5.6与既有MySQL9滑块状态。
**Spec:** 本轮用户确认的架构要求与本文§0；基础约束复用 [安全整改方案](design/安全整改方案-认证与账号体系.md)、[通知与消息推送](design/TDD-通知与消息推送.md)、[B端邮箱验证码](TDD-B端邮箱验证码注册登录.md)、[滑块验证码](TDD-B端滑块验证码.md)。

## §0 对账一 · 需求 → 设计

| AC | 用户要求 | 设计落点 |
|---|---|---|
| AC1 | 公共发码能力，支持各种业务，不属于AuthService | OtpSendService公共接口；业务→公共能力→投递/校验，公共层不引用user/order等业务Service |
| AC2 | 账户、平台两级按小时/一天频率，达到阈值才要求滑块 | InMemoryOtpFrequency；固定窗口、允许近似与进程间不同步 |
| AC3 | 性能优先，频率判断不新增MySQL访问 | 内存计数与内存策略快照，不建频率表、不查发送日志计算次数 |
| AC4 | 场景代码静态常量，每个场景策略不同 | OtpScenes、OtpScenePolicy、OtpScenePolicies |
| AC5 | 高度封装并尽量复用既有代码 | OtpStore、OtpSendGuard、RateRule、SmsPort/MailPort、通知日志、Tianai及SQL原子消费复用 |
| AC6 | 滑块前置请求不误扣额度，凭据一次消费 | 先判是否需验证，验证满足后才执行硬限流/计数/投递；滑块凭据仍在现有MySQL独立事务消费 |
| AC7 | 保持已发布App、旧发码入口及测试身份规则 | 旧方法委托LOGIN；B端HTTP协议兼容；C端尚未支持滑块时保留现有保护方式，不意外强制新弹窗 |

孤立项：无。业务授权由业务调用方完成；公共服务不判断订单是否可提货，也不创建账号、发会话或执行订单状态变更。

## §1 现状、复用边界与影响面

| 已有代码 | 实际现状 | 本次处理 |
|---|---|---|
| shop-base/common/OtpStore | 现有OTP保存、有效期、校验失败锁定、原子消费；目前OTP本身是进程内存储 | 继续复用，不改成数据库。扩展场景键；首期复用6位码、失败5次/15分钟规则，旧方法保持默认行为 |
| shop-base-auth/common/ratelimit/OtpSendGuard | 60秒冷却、目标每日上限、IP每小时上限、已登录发起人上限 | 继续作为硬限制，统一流程只执行一次；不在投递实现再次扣数 |
| shop-base/common/ratelimit/InMemoryRateLimiter | ConcurrentHashMap+逐次时间队列，供现有硬限流/密码等功能使用 | 保持现有用途，不全局改写。软阈值用紧凑固定窗口计数，避免平台逐请求保存时间戳 |
| RateRule | 窗口和上限类型；上限必须正数 | 复用；“每次滑块”单独用模式表达，不用0次上限绕过构造约束 |
| AuthServiceImpl.sendOtp | 地区能力、限流、随机码、测试身份/测试号/固定码、存储和短信投递 | 抽取原有投递逻辑，不重写规则；旧公开方法改为委托统一服务 |
| EmailOtpServiceImpl | 邮箱归一化、限流、语言内容、真实/桩通道、投递成功后存码 | 抽取原有投递逻辑；send委托公共服务，verify保留对外签名与LOGIN兼容 |
| SmsPort/MailPort及各Gateway | 真实渠道、通道异常；短信目前只有固定OTP用途的方法 | 复用，必要时增加携带场景的OTP重载；供应商模板号仍由通道配置决定 |
| NotifyLoggingSmsPort/NotifyLoggingMailPort | 统一记录成功、失败、模板和供应商消息号 | 保留，不再新建另一套OTP发送流水；不会额外按日志查频率 |
| BizCaptchaService/TianaiSliderEngine | 发题、验轨迹、发凭据，并混有“每次必须校验”策略 | 保留引擎与HTTP；抽出公共凭据消费能力，是否弹窗由场景策略决定 |
| JdbcCaptchaStore | V663表、共享状态/限流与REQUIRES_NEW原子消费 | 复用SQL和表；移到共享基础模块供公共凭据消费者使用，保持包名；不重写存储 |

必须保留的渠道差异：短信现有存码/投递次序、邮箱成功后存码、邮箱繁简文案、短信地区规则、固定码与测试身份边界。抽取时不顺便统一这些行为；新场景不能默认继承LOGIN测试身份跳过规则。

性能口径：不增加频率统计SQL与配置逐请求查询；已有通知日志和既有业务查询仍会访问数据库，不能声称“发码完全不访问数据库”。普通低频发码不访问滑块状态表。

## §2 开发设计与执行计划

### 2.1 方案选择与模块依赖

采用“公共编排服务＋复用既有投递实现”。将策略分别塞进短信、邮箱会重复；塞进滑块模块会令图形验证依赖发送业务。公共编排的责任是统一流程，不替换底层能力。

| 模块 | 新增/调整位置 | 职责 |
|---|---|---|
| shop-base | common.otp.service.OtpSendService | 唯一公共服务接口；简单请求/结果record及Channel可内嵌；同时封装发送与消费校验 |
| shop-base | common.otp.OtpScenes、OtpEntries、OtpScenePolicy、OtpKeys | 场景/入口常量、不可变策略值、归一化与场景存储键规则 |
| shop-base | spi.otp.OtpChannelPort | 唯一跨模块渠道验证码接口；投递与校验共用既有测试身份/存储规则，不接收滑块决策 |
| shop-base-auth | common.otp.service.impl.OtpSendServiceImpl | 调用场景策略、内存频率、公共滑块凭据消费、硬限流、投递接口 |
| shop-base-auth | common.otp.policy.OtpScenePolicies、InMemoryOtpFrequency | 内存策略快照与紧凑窗口计数 |
| shop-base-auth | auth.captcha.JdbcCaptchaStore、CaptchaProofService | 将既有store搬到共享模块；提取纯凭据消费与统一scope构造，不依赖Tianai轨迹DTO |
| shop-core | user.port.OtpChannelPortImpl | 将原短信/邮件投递及OTP验证分支抽入同一渠道验证码实现；复用原短信/邮件端口、OtpStore与测试策略 |
| shop-app | config中的装配、auth.captcha及portal/biz | 装配各能力；滑块引擎仍只属于API；Controller只构造可信发送场景与上下文 |

投递适配器位于原实现所属模块，是为了复用其已有测试身份/地区/渠道依赖；公共契约、频率、场景策略均不属于user业务。common.otp物理复用现有基础jar，不能因jar名含auth而将服务放到AuthService或user业务层之下。不得由公共服务回调AuthService.sendOtp，否则旧方法委托公共服务后会形成循环调用。

API与OPS启动都要兼容：公共凭据消费者只依赖现有数据库/事务，不依赖仅API启用的Tianai Bean。基础模块不得反向import shop-core或shop-app。场景策略只在支持交互的服务端入口开启，不能以客户端自报字段切换免验证。

### 2.2 公共接口与场景

规划公共接口：

```java
OtpSendResult send(SendCommand command, OtpContext context);
boolean verifyAndConsume(VerifyCommand command, OtpContext context);

record SendCommand(String scene, Channel channel, String recipient,
                   String businessKey, String locale,
                   Map<String, String> parameters, String captchaToken) {}
record VerifyCommand(String scene, Channel channel, String recipient,
                     String businessKey, String code) {}
record OtpContext(String entryCode, String clientIp, String senderKey) {}
record OtpSendResult(long expiresInSeconds, int resendAfterSeconds) {}
```

- SendCommand：以上字段中的parameters仅传场景允许的业务逻辑参数；验证码、有效期及供应商模板号由服务端决定，不能由参数覆盖。
- VerifyCommand：scene、channel、recipient、businessKey、code。
- OtpContext：entryCode由服务端入口固定选择，clientIp由可信请求元数据提供，senderKey仅取已认证发起人。后台调用可无IP，保持原OtpSendGuard此时跳过IP补充闸的行为；账号/发起人硬限及平台统计照常，不伪造IP。
- OtpSendResult：expiresInSeconds、resendAfterSeconds；不返回OTP正文。
- OtpChannelPort：`DeliveryResult deliver(DeliveryCommand command)`及`boolean verifyAndConsume(VerificationCommand command)`。DeliveryCommand内嵌record包含scene、channel、recipient、storageKey、ttl、locale、logicalTemplateKey、parameters；VerificationCommand包含scene、channel、recipient、storageKey、code。DeliveryResult只含实际expiresInSeconds；requireAvailable负责地区/通道资格预检，在凭据和限流前执行，保留原地区拒绝顺序。接收的均为公共服务解析后的可信值，不再执行发码计数。
- 渠道实现同时承接原LOGIN测试身份/固定码的发送与校验分支。公共verify先检查场景/入口与键，再调用渠道Port；真实码仍由原OtpStore原子消费，LOGIN不存码测试身份继续按原受控规则验证。新场景不自动继承此旁路。
- 验证码生成/存码时序和校验规则保留在渠道实现中。旧AuthService/EmailOtpService发送与OTP校验方法委托公共入口，避免发送成功但公共校验不支持测试身份、或保留两份验证分支。用户名解析、账号创建、密码认证仍属于原业务服务。

静态场景：`OtpScenes.LOGIN`、`PASSWORD_RESET`、`PHONE_CHANGE`、`EMAIL_CHANGE`、`ORDER_PICKUP_CONFIRM`。值与常量同名，使用`public static final String`；已发布值固定。登录即注册继续用LOGIN。

| 场景 | 渠道/有效期建议 | 滑块建议 | 首批启用 |
|---|---|---|---|
| LOGIN | SMS5分钟、EMAIL10分钟，保留现状 | 账户小时3次/24小时5次免验证，之后触发 | nearboss已支持滑块的入口 |
| PASSWORD_RESET | SMS/EMAIL5分钟 | ALWAYS | 完成业务授权、模板配置与调用接入后 |
| PHONE_CHANGE | SMS5分钟 | ALWAYS | 同上 |
| EMAIL_CHANGE | EMAIL10分钟 | ALWAYS | 同上 |
| ORDER_PICKUP_CONFIRM | SMS5分钟，绑定业务对象 | 账户小时1次/24小时3次免验证 | 同上；不替代订单本身的取货凭证 |

以下默认策略已经实现；线上生效时间见§5发布记录。场景常量存在不代表场景已开放；未开放、未配齐对应渠道/模板的场景明确拒绝，不能借用LOGIN模板发送。

OtpScenePolicy首期集中登记：允许渠道、各渠道TTL、滑块模式及软阈值、是否要求业务键；逻辑模板按OTP_<SCENE>映射，尚无已注册业务参数，非空parameters拒绝。6位数字码、60秒重发间隔和原硬限制、失败5次/15分钟规则作为现有公共基线复用；首期不宣称可按场景改写尚未支持的硬限或失败规则。配置在启动时绑定/校验后形成内存快照；业务发送不逐次读配置库。首期不实现规则脚本引擎。

场景与入口分开：新增静态入口常量`OtpEntries.BIZ_LOGIN`、`LEGACY_AUTH`。策略解析先确认scene已启用，再确认(scene, entryCode)组合已登记，读取场景基线后应用明确的入口滑块覆盖；未登记组合拒绝，不能自动退为免验证。BIZ_LOGIN/LOGIN为ADAPTIVE；LEGACY_AUTH/LOGIN为RATE_ONLY（计数、硬限制不变，不加新弹窗）。其他场景/入口完成授权、模板、客户端上下文接入后逐项登记启用。

旧AuthService.sendOtp/sendEmailOtp、EmailOtpService.send及原OTP验证兼容方法固定构造LEGACY_AUTH；B端已迁移Controller直接调用公共入口并构造BIZ_LOGIN。这样同一LOGIN不会因旧签名缺少凭据或入口信息而误选策略。新业务不能通过复用旧兼容方法获得免滑块入口。

账户与平台软阈值按全部已接入场景累计，再使用当前场景阈值判断；平台指本进程所有已接入业务发码总量，不跨实例合并。平台初始建议500次/小时、3000次/24小时。各场景不会分别获得一份平台免费额度。

### 2.3 频率与发送时序

1. 使用框架校验输入；解析已登记场景、允许渠道、业务键与统一收件账号规则。
2. 内存读取账户/平台小时、24小时窗口，决定是否需要滑块；此时不扣发送次数，也不生成或覆盖OTP。
3. 需要验证时，缺少/无效凭据返回既有10452与captchaRequired。有效凭据按原独立事务消费一次；只读内存的低风险路径不消费/查询滑块状态。
4. 满足滑块条件后，执行一次现有OtpSendGuard硬限制；继承现有60秒间隔、每日及IP/发起人限制，不把已发布所有硬限流改成新算法。硬限流的目标键继续使用跨场景收件账号键（短信原phone、邮箱原email哈希键），不能传场景隔离的OTP存储键而意外给每场景重开硬额度。
5. 记录本次即将进入投递的账户/平台频率，调用OtpChannelPort。通道失败保留计数，沿用原成功/失败日志和错误语义；不自动重投短信或邮件。
6. 投递按原方式生成/激活验证码；返回有效期与重发间隔。

先做软风险判断、凭据消费，再执行现有会扣额度的硬限流，是为了复用当前顺序并保证滑块重试不会被第一次请求自己扣掉的额度拦住。实际硬限流可能在通过滑块后拒绝；首期不为提前提示重写通用RateLimiter或增加一套预占/回滚机制。

InMemoryOtpFrequency：每个接收账号只保存小时/24小时计数与到期时间；平台同样两组，平台计数不放入可淘汰账户Map。固定窗口从首个受理投递开始计时，窗口到期懒重置，不保存逐次时间明细。线程内短临界区/原子更新，不持锁调用数据库或投递通道。OtpSendGuard增加`check(String targetKey, String senderKey, String clientIp)`重载供公共服务使用；旧check方法读取RequestMetaContext后委托，保持原行为，避免新上下文IP被忽略。增加`resendAfterSeconds()`只读访问器，公共返回值取自同一硬限配置，不另复制一份重发间隔配置。软阈值允许并发少量超出、重启清零和多实例各自计数。

账户Map沿用现有限流器100000键的容量口径，先清过期；容量仍满时，新账号按需要滑块处理，保留平台统计，不通过清空全表重新给全部账号免验证。不要把这些计数塞入AuthCache。

### 2.4 场景隔离、模板与兼容

- LOGIN短信与邮箱归一化、OtpStore键、有效期、错误码保持原样；EmailOtpService.normalize/key委托唯一共享规则，不能复制两套实现。
- 新场景存储键包含场景、渠道、收件账号以及策略要求的业务键；发送、校验共用OtpKeys。业务数据只用稳定标识，不把OTP明文、原始手机号/邮箱写进诊断日志。
- 滑块挑战/通过凭据也绑定同一用途。提取scope规则供发题、验题、消费共同使用。旧LOGIN发码与密码登录的action/键继续兼容；新用途不能消费旧用途凭据。新场景10452数据须带captchaRequired、action、scene、businessKey（无业务键时省略），客户端在发题、验题、发送重试中原样传递；旧LOGIN可继续只用action/principal。新场景只有完成整条上下文往返后才能启用，首批不要求改写现有LOGIN Flutter resolver。
- 当前challenge/verify请求仅有action/principal；为新场景扩展可选scene/businessKey。旧OTP_SEND/EMAIL_OTP_SEND缺省映射LOGIN；原LOGIN密码动作不随本轮改成按发码次数判断。
- 现有B端SMS/EMAIL发送URL、请求体和成功JSON保持不变；Controller把固定LOGIN与X-Captcha-Token传入公共服务。旧AuthService.sendOtp、sendEmailOtp及EmailOtpService.send保留兼容签名，只委托，不重复限流。
- 其他端逐入口迁移。C端未接滑块的旧入口保留原硬限流，不凭公共服务上线就要求旧客户端弹新窗口；是否开启由服务端入口配置决定，不能由客户端声明“无交互”绕过。
- SmsPort扩展携带场景用途的OTP重载，旧签名只表示LOGIN；通道按场景选择内部配置的供应商模板号。保留旧LOGIN配置键，新场景用独立配置命名空间，不把旧标量配置改成同名Map，也不允许任意新场景回落到LOGIN供应商模板。
- MailPort和现有日志装饰器复用；将原EmailOtpServiceImpl.Content整体搬为OtpChannelPortImpl的private EmailContent helper，并从旧类删除，再扩展场景/语言/实际TTL，避免正文写10分钟而策略只有5分钟。NotifyBizType.OTP继续表示消息分类，不能当作OTP业务场景替代品。
- 内部外部码转发、运营手工测试等既有通知调用逐项分类，不自动改成平台生成OTP；来单、物流等普通通知不走OtpSendService。

### 2.5 实施任务

#### 任务1：公共契约、场景策略与键规则

Files：新建shop-base/src/main/java/ai/neargo/shop/common/otp/{OtpScenes,OtpEntries,OtpScenePolicy,OtpKeys}.java、common/otp/service/OtpSendService.java、spi/otp/OtpChannelPort.java；新建shop-base-auth/.../common/otp/policy/OtpScenePolicies.java；复用OtpStore原有方法和锁定规则，保留旧方法。

- [x] 定义§2.2公共签名、内嵌record、场景/入口静态常量和场景允许参数；校验字段使用现有框架。
- [x] 静态登记默认策略、配置一次绑定到内存；只启用已准备好的LOGIN入口。
- [x] 实现LOGIN旧键兼容及新场景/业务对象隔离；场景无效、渠道不允许、业务键缺失明确拒绝。
- [x] 新建OtpScenePolicyTest：LOGIN短信/邮件默认5/10分钟、未开放场景拒绝、场景/业务键不可混用；原OtpStore默认方法行为不变；BIZ_LOGIN与LEGACY_AUTH策略明确且未登记入口拒绝。

#### 任务2：轻量内存频率和公共发送编排

Files：新建shop-base-auth/.../common/otp/policy/InMemoryOtpFrequency.java、common/otp/service/impl/OtpSendServiceImpl.java；扩展现有OtpSendGuard的显式IP重载，复用RateRule和现有错误码。

- [x] 实现§2.3固定窗口计数，Clock可注入；账户与平台总量跨场景共享。
- [x] 按§2.3时序实现公共send与verifyAndConsume（后者经渠道Port统一处理真实码和原受控LOGIN测试身份）；低风险不调用滑块凭据消费者。
- [x] OtpFrequencyCounterTest：前3次/第4次、24小时阈值、平台触发、跨场景累计、窗口到期、容量上限；用可控Clock，不sleep。
- [x] OtpSendServiceTest：需滑块但无凭据时不扣额度/不投递；有效凭据后只扣一次；硬上限不能靠滑块绕过；通道失败保留频率；低风险verifyNoInteractions证明不访问滑块存储；显式IP参与原来源限流、后台无IP保持原跳过IP补充闸行为，场景OTP键与硬限流账号键不会混用。

#### 任务3：抽取原发送代码，完成通道复用

Files：shop-core/user/service/impl/{AuthServiceImpl,EmailOtpServiceImpl}.java；新增user/port/OtpChannelPortImpl.java（含private EmailContent）；调整SmsPort及现有Gateway/NotifyLoggingSmsPort的OTP场景重载；复用MailPort、邮件Content与通知日志。

- [x] 把短信/邮箱的现有投递与OTP验证分支移动到一个实现中；移除分支内重复硬限流，不复制旧流程。
- [x] 旧发送/OTP验证方法委托公共接口(LOGIN, LEGACY_AUTH)，公共服务调用渠道Port，不回调AuthService，避免循环。
- [x] 保留地区规则、固定验证码、测试身份/测试号、不投递白名单、原存码时序和SMTP异常映射。
- [x] 扩展现有短信/邮件模板处理，使场景路由和正文TTL匹配策略；未配齐的新场景不发送。
- [x] OtpSendCompatibilityTest：LOGIN旧OTP键和接口结果、验证码只消费一次、邮件发送失败不激活新码、原白名单不真实投递且仍限流、非LOGIN不自动继承登录测试绕过；直接使用公共send+verify也可验证同一受控LOGIN测试身份，和旧包装结果一致。

#### 任务4：提取滑块凭据能力，接入现有业务入口

Files：JdbcCaptchaStore搬到shop-base-auth保持现包名；新增auth/captcha/CaptchaProofService.java；调整shop-app/config/BizCaptchaConfiguration.java、auth/captcha/impl/BizCaptchaServiceImpl.java、portal/biz/{BizCaptchaController,BizAuthController}.java及必要共享装配。

- [x] 复用现有表与消费SQL；纯凭据消费者可由公共服务调用，不引用Tianai DTO或上层业务Service。
- [x] 发题/验题/凭据消费统一scope，扩展scene/businessKey与10452往返上下文，并兼容现有App缺省LOGIN；不修改历史Flyway。
- [x] 将B端发码从Controller每次强制require改为公共send；密码登录策略保持原样。
- [x] 检查API/OPS、纯模块测试的构造与Bean装配，无API专属Bean硬依赖；所有硬限制只执行一次。
- [x] 扩展BizCaptchaHttpTest及相关MySQL9消费用例，覆盖旧协议、新场景10452→发题→验题→发送重试的上下文一致、跨场景/业务对象拒绝、失败后凭据仍已消费；增加装配回归。不得访问真实短信/邮件通道。

#### 任务5：集中增量验证与发布

- [x] 本批编码完成后，统一编译shop-app及其实际受影响依赖；运行任务1—4相关测试、既有邮箱/短信回归和受影响架构检查。普通输入走框架，不新增通用验证引擎。
- [x] 共享SQL/事务与scope逻辑变更涉及的原生用例只在MySQL9验证；不使用3306 MariaDB，不改已应用V663，不因文档或提交变化重复测试。
- [x] 更新TDD§5实测结果与实现文件对账；只有涉及客户端scene上下文扩展时才改前端，LOGIN旧App协议优先保持兼容。
- [x] 合入统一开发分支后部署166：先确认运行版本/权限/策略配置；不改已有通知与其他业务配置。账户/平台阈值以启动配置为准。
- [x] Android模拟器验证低频直接进入发码、越阈值才弹、取消无投递、通过后只发送一次、冷却/硬限仍有效。正向发送仅使用已授权的测试身份或受控桩，不给真实陌生账号发码。

不要求每完成一个小步骤就跑整套Maven或提交；按MEMORY在一批有效改动后集中验证。构建/依赖条件未变时沿用已有证据；只扩验实际受影响范围。

### 2.6 只读可实施性复核结论

已复核并明确：入口策略分辨B/C兼容；统一发送/验证对受控测试身份的语义一致；首批可变策略与继承的硬规则分开；显式IP传到既有guard；邮件私有内容构造器随代码迁移；新场景滑块上下文在错误、发题、验题、重试中一致。没有新增另一套OTPStore、通知日志、短信/邮件客户端或Tianai引擎。上述决策已实现；实测范围见§5。

## §5 对账三 · 实现 → 需求

| AC | 已运行的方法/范围 | 实测结果 |
|---|---|---|
| AC1/AC4 | OtpScenePolicyTest 全3项；ArchitectureTest 全23项 | 策略3通过；架构22通过/1已有条件跳过；无新增域依赖 |
| AC2/AC3 | OtpFrequencyCounterTest 全3项；OtpSendServiceTest#lowFrequencySendsSkipProofAndPassTrustedContextAndTtl、legacyEntryNeverRequiresSliderButSharesAccountCounter | 账户/平台窗口、到期、并发、容量均通过；低风险verifyNoInteractions(proofs)，不新增频率SQL |
| AC5/AC7 | OtpSendCompatibilityTest；EmailOtpAuthTest 18项、RegionalSmsAdmissionTest、SmsMockPrefixTest 3项、BizAuthEmailLoginTest 3项 | 新旧键、SMTP失败、地区、号段模拟、旧HTTP结果、测试身份边界均通过 |
| AC6 | OtpSendServiceTest#missingProofHasNoHardGuardOrSendSideEffects、proofIsConsumedBeforeHardGuardAndAttemptCountsBeforeDeliveryFailure；BizCaptchaServiceTest；BizCaptchaNativeMysqlTest | HTTP/引擎共享测试8通过；原生MySQL9.7.4全10项通过，含新场景+业务键、一用凭据、并发、业务回滚 |
| 新场景往返 | BizCaptchaHttpTest#enabledBusinessSceneRoundTripsThroughHttpAndRetiresProofOnRetry | 测试配置启用ORDER_PICKUP_CONFIRM，公共服务10452→HTTP发题→HTTP验题→公共服务发送→重复凭据拒绝；仅stub短信 |

验证日志：`/tmp/otp-service-affected.log`（新增策略/计数/服务12项通过，core24/25通过，旧guard签名断言失败）；`/tmp/otp-service-app.log`（该失败方法修正通过，随后发现verify接口漏声明）；`/tmp/otp-service-http.log`（接口修正，架构23、HTTP7、引擎8、邮箱HTTP3，无失败）；`/tmp/otp-service-final.log`（TTL新测试1、新场景HTTP1、原生MySQL10，无失败）；`/tmp/otp-service-framework.log`（框架校验归位后，仅重验受影响公共服务/兼容/邮箱/HTTP，6+22+8项通过，BUILD SUCCESS）。

MySQL原生用例使用单独临时MySQL9.7.4，127.0.0.1:3319，每方法随机临时库后删除；未用3306，未改历史V663。没有因文档、提交或原样搬迁SQL重复跑无关全仓测试。

### 发布及模拟器验收

2026-11-13已正常推送统一开发分支bcd8acd5c，并部署166。本轮制品SHA256为cf21b018bba92333983b2e333fa68fad15a3eacb4de469af0f30391e0f978f57；保留此前支付SSE制品c477fa54591c和最新运营代码。API18082及OPS18081均UP，本轮原容器/环境不改，nearpay/inventory不重启。V665已由Flyway成功应用（checksum -188811131），V664支付（256749001）及V663滑块（-981765053）不变。

验收期间支付任务再次部署（API启动于11:26:09 UTC），运行包更新为2c627892534f5abf646ce64106deed2de4663eaf4cb92d8b4e08a303bf5ca024；逐类比对确认本轮全部既有类原样保留，仅新增两个支付异常处理类，限定支付控制器，不影响OTP。全部迁移资源及依赖库一致。该重启发生在首轮第三次发送后，第四次遇网关暂不可用，App明确显示结果未知，未将此当作验证码失败；按内存计数重启清零约定重新完整验收，没有回退其他发布。

最终使用现有nearboss Flutter APK（0d6ba2f81、Android14/API34只读独立模拟器5558）连接真实166：重启后第1/2/3次发送均无滑块且有60秒倒计时；低频立即重复HTTP10450；第4次弹原生滑块，Android返回取消后无倒计时，立即重试仍弹。取消前后服务端测试身份发送日志均3条；真实触摸拖动通过后弹窗关闭、倒计时恢复，日志只增至4条。全部为已有受控白名单身份，通道明确跳过真实投递。最新API/OPS健康均UP。详细脱敏证据见[evidence目录](evidence/otp-service-20261113/README.md)，不声明真实邮件送达或真实业务登录成功。

正常push记录：`/tmp/otp-service-unified-push-final.log`。适用UI/契约/权限/i18n/SQL等脚本及干净副本完整test-compile通过；该独立worktree没有node_modules，Hook明确跳过依赖它的前端类型/单测及部分生成物守卫，未声明它们通过。业务用例按Java规范§八及本轮实际影响定向运行（SKIP_TEST_GATE=1避免另跑无关全量），没有使用SKIP_COMPILE_GATE或force push。

## §6 对账二 · 设计 → 实现

公共契约/策略/编排、既有渠道提取、旧包装、B端Controller和装配均按§2落地。JdbcCaptchaStore仅跨模块搬迁，包名及源码完全一致，OTP无新表/历史迁移修改。具体变更文件统计在提交时登记。

实现裁定：

- 增加渠道requireAvailable前置资格检查，保留地区拒绝先于凭据/限流的原语义；否则关闭地区可能误报滑块。其代价是投递前仍有既有地区能力查询，不属于新增频率SQL。
- 当前没有注册业务模板参数，parameters仅允许空；各场景逻辑模板固定OTP_<SCENE>，阿里短信通过shop.sms.ali.otp-scenes.<SCENE>启动配置绑定供应商模板，不借用LOGIN。新场景上线仍需业务授权及客户端上下文接入。
- 首次实现曾在服务内部调用Validator；收尾按Java规范§八移回公共DTO的Bean Validation条件属性，删除内部重复校验；普通发送不增加一层验证引擎。
- 最终审查没有Critical/Important。TTL小于一分钟的文案问题涉及§2明确的正文/有效期一致要求，按实际误导用户的影响纳入本轮修复，秒级配置显示秒数，默认整分钟原文案不变；新增30/90秒回归通过。审查暂不判断的启动与新场景HTTP分别由后续装配/发布与真实往返证据闭合。

本轮实现文件统计（提交前暂存区；不含后续发布证据）：

```text
 .../shop/auth/captcha/BizCaptchaService.java       |  11 +-
 .../auth/captcha/impl/BizCaptchaServiceImpl.java   |  19 +--
 .../shop/config/BizCaptchaConfiguration.java       |   9 +-
 .../ai/neargo/shop/config/OtpConfiguration.java    |  38 +++++
 .../neargo/shop/portal/biz/BizAuthController.java  |  21 ++-
 .../shop/portal/biz/BizCaptchaController.java      |  27 +++-
 .../shop-app/src/main/resources/application.yml    |   8 +
 .../shop/authcaptcha/BizCaptchaHttpTest.java       |  56 ++++++-
 .../shop/authcaptcha/BizCaptchaServiceTest.java    |  17 ++
 .../commercebridge/BizIdentityNativeMysqlTest.java |  22 ++-
 .../BizLoginStoreNativeMysqlTest.java              |  21 ++-
 .../shop/auth/captcha/CaptchaProofService.java     |  14 ++
 .../neargo/shop/auth/captcha/JdbcCaptchaStore.java |   0
 .../neargo/shop/common/GlobalExceptionHandler.java |   2 +-
 .../common/otp/policy/InMemoryOtpFrequency.java    |  54 +++++++
 .../shop/common/otp/policy/OtpScenePolicies.java   |  69 ++++++++
 .../neargo/shop/common/otp/policy/OtpSettings.java |  36 +++++
 .../otp/service/impl/OtpSendServiceImpl.java       |  53 ++++++
 .../neargo/shop/common/ratelimit/OtpSendGuard.java |   9 +-
 .../shop/common/otp/OtpFrequencyCounterTest.java   |  49 ++++++
 .../neargo/shop/common/otp/OtpScenePolicyTest.java |  51 ++++++
 .../neargo/shop/common/otp/OtpSendServiceTest.java |  92 +++++++++++
 .../common/captcha/CaptchaRequiredException.java   |  17 +-
 .../java/ai/neargo/shop/common/otp/OtpEntries.java |   8 +
 .../java/ai/neargo/shop/common/otp/OtpKeys.java    |  33 ++++
 .../ai/neargo/shop/common/otp/OtpScenePolicy.java  |  16 ++
 .../java/ai/neargo/shop/common/otp/OtpScenes.java  |  13 ++
 .../shop/common/otp/service/OtpSendService.java    |  34 ++++
 .../java/ai/neargo/shop/spi/notify/SmsPort.java    |  12 ++
 .../ai/neargo/shop/spi/otp/OtpChannelPort.java     |  16 ++
 .../message/notify/port/NotifyLoggingSmsPort.java  |  16 +-
 .../neargo/shop/user/port/OtpChannelPortImpl.java  | 130 +++++++++++++++
 .../neargo/shop/user/service/EmailOtpService.java  |  19 +--
 .../shop/user/service/impl/AuthServiceImpl.java    | 177 ++++-----------------
 .../user/service/impl/EmailOtpServiceImpl.java     | 147 +++--------------
 .../neargo/shop/user/ProfileAndPasswordTest.java   |  17 +-
 .../neargo/shop/user/service/EmailOtpAuthTest.java |  27 ++--
 .../ai/neargo/shop/user/service/OtpFixtures.java   |  32 ++++
 .../user/service/OtpSendCompatibilityTest.java     |  83 ++++++++++
 .../user/service/RegionalSmsAdmissionTest.java     |  17 +-
 .../shop/user/service/SmsMockPrefixTest.java       |  17 +-
 .../ai/neargo/shop/notify/port/AliSmsGateway.java  |  30 +++-
 .../ai/neargo/shop/notify/port/StubSmsGateway.java |   9 ++
 docs/api/biz-auth-captcha.openapi.json             |  40 ++++-
 ...235\227\351\252\214\350\257\201\347\240\201.md" |   3 +
 ...217\221\351\200\201\346\234\215\345\212\241.md" |  82 ++++++----
 46 files changed, 1272 insertions(+), 401 deletions(-)
```

统一分支发布裁定：166在本轮期间先部署支付SSE5fba0e2cc，故保留该提交再合并OTP；首次正常push被既有SSE的Vue交付清单缺项拦截（GET /biz/payment/events）。只补该已部署接口的精确OpenAPI及Flutter/legacy-Vue交付清单，不扩大known-*欠账基线，不改支付行为；未包含原工作区未提交代码。

统一分支并发更新：首次通过全部适用push检查及干净test-compile后，远端推进4a9baa006，正常拒绝更新，未force。合并该运营提交时发现其新迁移也占V664；DB61只读核对166已成功应用V664__sandbox_payment_collection_stream.sql（checksum256749001），故保留其原字节，将尚未应用的运营菜单DML迁移仅改名为V665。其内容不改；此为集成编号冲突处理，非修改历史已应用迁移。后续候选同时保留运营新代码，并定向执行其新增两组测试。

最终Java受影响用例汇总：96项，95通过、0失败/错误、1项美业插件条件跳过（beauty.arch.required未设置）；含运营合并定向6项。报告按最终各测试类结果去重，见evidence/otp-service-20261113/test-summary.json。候选以166当前制品现场清单比对，78个类变更属于OTP及已合并运营代码；MarketPhonePolicyServiceImpl源码未改，新增SPI协变返回类型由javac生成两个ACC_BRIDGE方法，已核对。依赖库集合与所有历史SQL原样保留，唯一新增迁移为运营V665。

后续发布文件对账：精确补齐`docs/api/biz-payment-events.openapi.json`及`backend-ui-delivery-payment-events.json`，生成器更新既有运营功能清单，未应用运营SQL仅顺延V665；本次脱敏验收目录7个文件。以上均为保留统一分支运行基线及记录交付所需，不复制其他会话未提交源文件。发布后再快进合并12308bf7a的运营测试夹具/迁移引用修正，无OTP生产代码变化，不重跑既有业务证据。
