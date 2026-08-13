# TDD-短信与邮件通道接入

状态：**待确认（有两处阻塞信息缺失，见 §7）**
关联缺陷：[安全整改方案-认证与账号体系](安全整改方案-认证与账号体系.md) §二 **缺陷 B：OTP 零限流**（**未修**）
关联架构：[TDD-backend](TDD-backend.md) §4（`shop-channel` 只装适配器，不装业务判断）
创建日期：2026-08-13

---

## 1. 需求摘要

接入真实短信（阿里云 dysmsapi）与邮件（Office365 SMTP），替换当前的日志占位。

**现状**：

| | 现状 | 消费方 |
|---|---|---|
| 短信 | ❌ `AuthServiceImpl.sendOtp()` **只打日志**：`log.info("[DEV-ONLY] otp for {} = {}")` | `/mp/user/otp/send` · `/biz/auth/otp/send` · 店员登录 |
| 邮件 | ❌ **完全不存在**，无接口无实现无调用方 | **运营端密码交付**（§3.4，2026-08-13 确认） |
| 推送 | ❌ 不存在 | 无（本次不做，见 §6） |

依赖也都还没引：全仓 `pom.xml` 无 mail、无 aliyun SDK。

---

## 2. ⚠️ 先说阻塞：接真短信之前必须先修限流

**这不是洁癖，是三件具体的事同时成立**：

1. `sendOtp` **零限流**——`OtpStore` 只有 `save/verifyAndConsume/peek`，没有任何计数
2. 接上真通道后，**每一次调用都是钱**。今天打日志不花钱，所以这个洞没有代价
3. 端点是**公网未鉴权**的（发码必须在登录前）

合起来：**任何人循环调 `/mp/user/otp/send` 就能烧平台的短信费**，
且 6 位码无失败次数限制 → 可枚举（安全文档原话：「没有失败计数就等于没有验证码」）。

安全方案 §2.2 已经设计好四道闸，本方案**只做其中三道**（发码侧），验码侧那道
（④ 连续失败锁定）属于缺陷 B 本身，建议同批但可独立发布：

| 闸 | 限什么 | 阈值 | 本批 |
|---|---|---|---|
| ① 发码间隔 | 同手机号 | 60 秒 | ✅ 必做 |
| ② 发码总量 | 同手机号 / 天 | 10 次 | ✅ 必做 |
| ③ 发码来源 | 同 IP / 小时 | 20 次 | ✅ 必做 |
| ④ 验码失败锁定 | 同手机号连续 5 次 → 锁 15 分钟 | | ⚠️ 缺陷 B 本体，建议同批 |

**若限流不同批做，短信通道的 `enabled` 必须保持 `false`。** 配置可以先落地、
密钥可以先注入，但不能打开——打开就是给一个公网端点接上计费器。

---

## 3. 方案设计

### 3.1 分层：Port 在 base，实现在 channel（照抄支付通道的既有口径）

```
shop-base/spi/notify/
 ├─ SmsPort          send(phone, template, params) → 结果
 └─ MailPort         send(to, subject, body)

shop-channel/notify/
 ├─ AliSmsGateway     实现 SmsPort（dysmsapi）
 ├─ SmtpMailGateway   实现 MailPort（JavaMailSender）
 ├─ StubSmsGateway    只打日志，开发/测试用
 └─ StubMailGateway   同上
```

**为什么 Port 落 `shop-base` 而不是各域各写一个**：发码的消费方有三处
（C 端登录、B 端登录、店员登录），分属 user 与 merchant 两个域。
落 base 与 `OtpStore` 同层——那个类的注释已经写明了同样的理由。

**为什么不复用 `ChannelClient`**：它是为支付的双向签名（微信 APIv3 证书 / 支付宝 RSA2）
抽的，短信只需单向 AK/SK 签名，套进去要给它加一个用不到的分支。

### 3.2 选实现：`@ConditionalOnProperty` + Stub 兜底

与支付通道同一套口径（`shop.pay.stub`）：

```yaml
shop:
  sms:
    stub: ${SHOP_SMS_STUB:true}        # 默认 true —— 默认发真短信 = 默认花钱
    ali:
      enabled: ${ALI_SMS_ENABLED:false}
      sign: ${ALI_SMS_SIGN:数智邻购}
      endpoint: ${ALI_SMS_ENDPOINT:dysmsapi.aliyuncs.com}
      access-key-id: ${ALI_SMS_AK:}
      access-key-secret: ${ALI_SMS_SK:}
      templates:                        # ⚠️ 见 §7-①
        otp: ${ALI_SMS_TPL_OTP:}
  mail:
    stub: ${SHOP_MAIL_STUB:true}
    enabled: ${MAIL_ENABLED:false}
    host: ${MAIL_HOST:smtp.office365.com}
    port: ${MAIL_PORT:587}
    starttls: true
    protocols: TLSv1.2
    from: ${MAIL_FROM:system@neargo.ai}
    username: ${MAIL_USERNAME:platform@neargo.ai}
    password: ${MAIL_PASSWORD:}         # ⚠️ 见 §7-②
```

**密钥一律走环境变量**，不写进 yml、不进代码库——与 `shop.pay` 的既有规矩一致。
`sign`/`endpoint`/`host`/`from`/`username` 是**非密**配置，可以留默认值；
`accessKeySecret` 与邮箱密码**没有默认值**，不注入就起不来（比静默降级安全）。

**`stub` 默认 `true`**：与支付相反（支付 stub 默认 false，因为「假装支付成功」是资金事故）。
短信反过来——默认发真短信意味着**本地跑一次测试就在花钱**，且会真的骚扰到测试手机号。

### 3.3 顺带修安全方案 §2.4 点名的两处

| 问题 | 现状 | 改法 |
|---|---|---|
| OTP 明文入日志 | `log.info("[DEV-ONLY] otp for {} = {}")` | 降 `debug` + 仅 `dev` profile |
| OTP 明文存储 | `Entry(String code, …)` | 存 `hash(code+phone)`，验时比哈希 |

`peek()` **保留不动**——它只在测试用，且比给生产开「万能验证码」后门正确得多
（安全文档原话）。

### 3.4 邮件的消费方：运营端密码交付（2026-08-13 确认）

**骨架已经在了，不用新建任何字段**：

| 现成的 | 位置 |
|---|---|
| 运营账号的登录名**强制是邮箱** | `OpsServiceImpl.createStaff` 的 `EMAIL` 正则校验 |
| 一次性初始密码已生成 | `randomPassword()` |
| 首登强制改密的闸 | `SysOpsStaff.mustChangePassword` ＋ `changeOwnPassword` |
| 审计里不写密码 | `audit("STAFF_CREATE", …)` 只记用户名与角色 |

**今天的问题是交付方式**：`createStaff` 把明文放在 `CreatedStaffVO.initialPassword`
**返回给调用方**，ops-web 弹一个抽屉把它显示出来（`app/iam/page.tsx:848`），
管理员抄下来自己转告本人。

于是这串明文经过：后端响应体 → 网络 → 浏览器内存 → 屏幕。
它会进浏览器的网络面板、会被截图、会被复制进聊天工具，而**管理员本人不该知道
另一个人的密码**——即使有 `mustChangePassword`，那也只保证「本人首登后会变」，
不保证「管理员在这之前没登过」。

#### 场景 A：新建账号 → 邮件发本人，接口不再返回明文

```
createStaff → 生成一次性密码 → MailPort.send(username, …) → 落审计「已发至 xxx」
                                    ↓
            CreatedStaffVO.initialPassword 改为 **不返回**
```

**收益是明文的传播面从「后端→浏览器→人」缩到「后端→邮件」**，
管理员界面上只显示「初始密码已发送至 zhang@neargo.ai」。

⚠️ 这是**破坏性变更**：`CreatedStaffVO` 少一个字段，ops-web 的抽屉要改。
两端同批改，且要留降级——见 §3.5。

#### 场景 B：忘记密码 —— 今天**根本没有这条路径**

`OpsService` 只有 `login` 与 `changeOwnPassword`，**没有任何重置入口**
（管理员侧没有、员工侧没有，ops-web 登录页也搜不到「忘记密码」）。
今天员工忘了密码只能找人改库。

邮件通道一旦有了，这条才可能做。建议本批一起：

```
POST /ops/auth/forgot   { username }   → 无论账号存在与否都返回成功（不泄露账号是否存在）
                                        → 存在则发一次性重置链接（15 分钟、一次性）
POST /ops/auth/reset    { token, newPassword }
```

### 3.5 破坏性变更的降级开关

`shop.ops.password-delivery: mail | response`（默认 `mail`）

- `mail`：接口不返回明文，发邮件
- `response`：维持今天的行为——**邮件不通时的逃生口**

理由：邮件发不出去时（密码错、MFA、SMTP 被封），如果没有逃生口，
**新建的账号就永远没人能登录**，而这时管理员连一个能用的运营账号都可能没有。
开关默认 `mail`，但保留 `response` 让部署方能自救。

---

## 4. 依赖

| 依赖 | 用途 | 说明 |
|---|---|---|
| `spring-boot-starter-mail` | JavaMailSender | 标准件，Boot 4 自带自动配置 |
| `com.aliyun:dysmsapi20170525` | 阿里云短信 | 官方 SDK。**不手写签名**——AK/SK 签名写错的表现是「一直返回签名错误」，排查成本远高于一个依赖 |

两个都只进 `shop-channel`，不进 `shop-base`（域模块不该传递依赖到通道 SDK）。

---

## 5. 测试策略

| # | 场景 | 层 |
|---|---|---|
| 1 | Stub 模式下 `sendOtp` 不调真通道，`peek()` 仍拿得到码（现有测试链路不破） | 集成 |
| 2 | ★★★ **同手机号 60 秒内第二次发码被拒**（闸①） | 集成 |
| 3 | ★★★ 同手机号当日第 11 次被拒（闸②）· 同 IP 每小时第 21 次被拒（闸③） | 集成 |
| 4 | `enabled=false` 时即使配了密钥也不发真短信 | 单元 |
| 5 | 缺 `access-key-secret` 时启动失败，**不静默降级到 stub** | 单元 |
| 6 | OTP 不再以 info 级别打进日志；`OtpStore` 里存的不是明文 | 单元 |
| 7 | 邮件：SMTP 连接失败时抛可识别异常，不吞 | 单元 |
| 8 | ★★★ `password-delivery=mail` 时 `createStaff` 的响应体里**没有明文密码** | 集成 |
| 9 | ★★ 邮件发送失败时 `createStaff` **整体失败并回滚**——不能留下一个「已建号但没人知道密码」的账号 | 集成 |
| 10 | ★★★ `/ops/auth/forgot` 对**不存在的账号也返回成功**（不泄露账号是否存在） | 集成 |
| 11 | 重置令牌一次性、15 分钟过期；用过的令牌再用返回失败 | 集成 |

场景 2、3 是能不能打开短信开关的凭据；**场景 8 是这次邮件改造的全部意义**
（明文不再经过浏览器）；场景 9 防的是一个比原问题更糟的状态。

---

## 6. 不做的

- **推送（个推 appKey/masterSecret）**：配置给了，但全仓无推送代码、无消费方，
  且它与站内信（`msg_message`）的关系没有定过。单独立项。
- 邮件的业务场景（见 §3.4）
- 短信模板管理界面（`msg_template` 表是站内信模板，与阿里云短信模板不是一回事）

---

## 7. ⚠️ 阻塞：两处配置信息缺失

| # | 缺什么 | 为什么必须有 |
|---|---|---|
| ① | **阿里云短信模板 CODE**（形如 `SMS_1234567`） | 阿里云发短信要 `signName` ＋ `templateCode` ＋ `templateParam` 三件套。给的配置只有 `sign`，**没有模板 CODE 就发不出任何一条**。且验证码模板必须在阿里云后台报备通过 |
| ② | **邮箱密码**（配置里 `password:` 为空） | Office365 SMTP 需要。且若账号开了 MFA，**普通密码不可用**，要「应用密码」或改走 OAuth2 |

两项都不需要现在给我——**密钥不要贴在对话里**，注入到部署环境的环境变量即可
（`ALI_SMS_TPL_OTP` / `MAIL_PASSWORD`）。
但**模板 CODE 是否已在阿里云报备**这件事需要确认，它决定这批能不能真的发出短信。

---

## 8. 实现任务

- [ ] T1 `RateLimiter` ＋ `InMemoryRateLimiter`（落 `shop-base/common/ratelimit`，按安全方案 §2.3）
- [ ] T2 三道发码闸接进 `AuthServiceImpl.sendOtp` ＋ 集成测试（场景 2、3）
- [ ] T3 `SmsPort` / `MailPort` ＋ 两个 Stub 实现，`sendOtp` 改调 Port（场景 1）
- [ ] T4 `AliSmsGateway`（dysmsapi SDK）＋ 配置 ＋ 缺密钥启动失败（场景 4、5）
- [ ] T5 `SmtpMailGateway`（JavaMailSender）＋ 配置 ＋ 自检（场景 7）
- [ ] T6 OTP 日志降级 ＋ 存哈希（场景 6，安全方案 §2.4）
- [ ] **T7 场景 A：`createStaff` 改为邮件交付**，响应体不再含明文
      ＋ `password-delivery` 降级开关 ＋ ops-web 抽屉改文案（场景 8、9）
- [ ] **T8 场景 B：`/ops/auth/forgot` ＋ `/ops/auth/reset`**（今天完全没有这条路径）
      ＋ ops-web 登录页加「忘记密码」（场景 10、11）
- [ ] T9 全量回归；文档记录「打开开关的前置条件」
- [ ] （可选同批）T10 闸④验码失败锁定 —— 缺陷 B 本体

> T7 与 T8 都依赖 T5（真实 SMTP）。**在邮箱密码拿到之前，它们可以先用 Stub 做完并测完**
> ——Stub 会把「发给谁、发了什么」记下来，场景 8–11 全都验得了。

---

确认记录：待确认
