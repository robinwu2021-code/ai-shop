# TDD · 测试号固定验证码

> 档位：1（新库表 + `/ops` 端点 + 权限码 + 运营端菜单）
> 依据：无现成 PRD —— 口头需求「给苹果审核一个演示账号，但不想把真实手机号交出去」，
> 验收标准写在 §1。
> 状态：**已实现（2026-09-28）**

## 1. 要解决什么（验收标准）

| # | AC | 谁满足 |
| --- | --- | --- |
| AC1 | 指定手机号请求验证码时，**不发真实短信**，验证码恒为配置里那个值 | `AuthServiceImpl.sendOtp` |
| AC2 | 未指定的手机号**行为完全不变**：随机码 + 真实短信 | 同上 |
| AC3 | 配置为空时，整条链路与改动前**逐字一致** | 同上（消融验证） |
| AC4 | **录入已存在账号的手机号被拒绝** —— 掐掉「登进已有账号」这个用法 | ops Service |
| AC5 | 增/删/启停都写审计日志（谁、何时、哪个号） | ops Service |
| AC6 | 停用后**立即**失效（不等缓存过期、不等重启） | 缓存失效 |
| AC7 | 启用中的条目超上限、或码短于 6 位，**拒绝保存** | ops Service |

### 为什么不用现有的 `shop.auth.otp.fixed`

那个是**全局**的：一开，任何手机号都能用同一个码登进去。`FixedOtpGuard` 因此在
「短信通道是真的」时直接拒绝启动 —— 而生产正是这个形状，所以它在生产上**永远不可用**，
这是有意的设计，不是遗漏。

本方案与它的差别只有一处，但是关键的一处：**作用域从「任意手机号」收窄到「列表里的号」**。
`FixedOtpGuard` 类注释里设想的事故主线是「测试环境的 env 被拷到生产」——
在本方案下，那种事故暴露的只有列表里那个测试号，不是所有人。

### 为什么不是「把验证码写进数据库再捞出来」

两个原因，第一个是决定性的：

1. **它达不到目的。** `sendOtp` 的顺序是「存码 → 调短信网关」。入库只改了前半句，
   后半句照样给那个号发真实短信。而那个号如果是真实号段（`13800000000` 是），
   就是在骚扰陌生人。
2. 影响面是全部用户：验证码明文入库 = 凡是能读库的人（备份、只读从库、一次注入、
   一个权限过宽的服务）都能登进**任何**账号，包括有真实资金流水的商家。
   现在它只活在 JVM 内存里 5 分钟（`OtpStore` 用 `ConcurrentHashMap`），那是有意的。

**「发送记录要能查」这件事已经有了**：`sys_notify_log` 一直在记每条验证码短信的
接收号、模板号、状态、运营商消息号、时间（`biz_type=OTP`），只是不记码本身。

## 2. 怎么做（模块设计）

白名单**落库、由运营端增删改与启停**，不是配置项 —— 配置项要改一次就重启一次生产，
而重启会把所有在线商家踢掉（`scp` 覆盖正在跑的 jar）。

### 风险模型变了，护栏要跟着变

配置版的口子只有能上服务器的人能开，值在 env 与启动日志里都看得见。
运营端版本意味着：**任何拿到那个权限码的人，都能给任意手机号发一个自己知道的验证码**——
那等于一键登进别人的店。所以：

| 护栏 | 为什么 |
| --- | --- |
| **拒绝录入已存在账号的手机号** | **最关键一条**。演示账号的用法是「先录白名单 → 再注册」，录的时候那个号不存在；而要拿别人的店，那个号一定已经存在。这条直接掐掉「登进已有账号」这个用法 |
| 独立权限码，只给超管 | 不与普通运营权限混在一起 |
| 每次增/删/启停写审计（谁、何时、哪个号） | 事后查得到 |
| 启用中的条目数有上限 | 防止它长成通用后门 |
| 码长下限 6 | 与 `PWD_MIN_LEN` 同档，挡住 `1234` |
| 列表页显式展示启用/停用，停用即时生效 | 出事能当场关掉，不用等部署 |

### 涉及的文件

**为什么整套落在 user 域而不是 platform** —— 表叫 `usr_otp_test_phone` 不叫 `sys_*`：
读它的是 `AuthServiceImpl.sendOtp`（user 域），而最关键那条护栏要查 `usr_identity`（也是 user 域）。
放 platform 的话两头都得开 SPI Port，换来的只是表名前缀好看一点。菜单挂在「系统设置」下是另一回事 ——
**代码跟着数据走**（同 `OpsBannedWordController` 的口径）。

| 角色 | 文件 |
| --- | --- |
| 建表 + 系统自带演示号 | `V354__otp_test_phone.sql` |
| 菜单功能点 + 角色授权 | `V355__otp_test_phone_function_point.sql` |
| H2 测试 schema（生成物） | `backend/shop-app/src/test/resources/schema-test.sql` |
| 实体 | `user/entity/UsrOtpTestPhone.java` |
| Mapper（**带物理删**，见下） | `user/mapper/UserMappers.java#OtpTestPhoneMapper` |
| Service 接口 / 实现 | `user/service/OtpTestPhoneService.java` · `user/service/impl/OtpTestPhoneServiceImpl.java` |
| `/ops` Controller | `user/api/ops/OpsTestPhoneController.java` |
| 读侧接线 | `user/service/impl/AuthServiceImpl.java#sendOtp` |
| 跨域：B 端登录号在不在 | `spi/user/StaffLoginPhonePort.java` · `merchant/port/StaffLoginPhonePortImpl.java` |
| 错误码 + 三语文案 | `common/ErrorCode.java`（10461–10464）· `i18n/messages{,_en,_ar}.properties` |
| 权限码 | `auth/Perms.java`（`system:testphone:read` / `:update`，**不配给任何角色**） |
| 端点 → 权限码 | `scripts/perm-endpoint-map.mjs` |
| 运营端类型 / 契约 / 真后端 / mock | `ops-web/lib/types/system.ts` · `lib/api/{contracts,https,mocks}/system.ts` · `lib/mock/db/system.ts` |
| 运营端页面 | `ops-web/app/system/test-phone-tab.tsx` · `page.tsx` · `copy.ts` |
| 菜单与权限登记 | `ops-web/lib/{nav.ts,perm-map.ts,permissions.ts,point-codes.ts,i18n/nav-labels.ts,nav.test.ts}` |
| 测试 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/OtpTestPhoneFlowTest.java` |

**两处不显然的实现决定**：

1. **删是物理删**。`BaseEntity` 带 `@TableLogic`，而 `uk_otp_test_phone` 唯一键里没有 `deleted` ——
   软删掉的行仍占着那个手机号，于是删过一次就再也录不回来（插入撞唯一键、接口 500，
   而报错与「删过一次」看不出任何关系）。同 `IdentityMapper.deleteAllByUserPhysically`。
2. **「已存在账号」要查两个登录面**。C 端走 `usr_identity`，B 端店主与子账号走
   `mch_account.login_phone`（`MerchantStaffServiceImpl.loginByPhone`），而**两边共用同一个
   `OtpStore`**。只查 C 端那一面的后果不是「少查一张表」，而是拿到权限码的人可以录入任意店主的
   手机号、用自己配的码直接登进他的店 —— 而护栏看起来是生效的（C 端那边确实拦住了）。

后端读取：`AuthServiceImpl.sendOtp` 在**限流闸之后、生成随机码之前**查白名单；
命中则存该条目的固定码并直接返回，**不调 `smsPort`**。
查库带整表缓存（TTL 60 秒 + 写后 `invalidate()`），照 `BannedWordPortImpl` / `RolePermResolver` 那一套。

后端读取：`AuthServiceImpl.sendOtp` 在**限流闸之后、生成随机码之前**查白名单；
命中则存该条目的固定码并直接返回，**不调 `smsPort`**。
查库要带缓存（每次发码打一次库不可接受），失效策略照抄仓库里现成的配置类缓存。

### 不做的

- **不为白名单补 `sys_notify_log`**。那张表记的是「短信发没发出去」，白名单这条压根没发；
  硬塞一行 `SENT` 是假记录。审计走运营端自己的审计日志。
## 3. 怎么验（实现 → 需求）

全部落在 `OtpTestPhoneFlowTest`（8 条，`mvn -o -pl shop-app test -Dtest=OtpTestPhoneFlowTest`）。

| AC | 测试方法 |
| --- | --- |
| AC1 | `#whitelistedPhoneGetsFixedCodeAndNoSms` |
| AC2 / AC3 | `#nonWhitelistedPhoneUnchanged` |
| AC4 | `#rejectsPhoneWithExistingConsumerAccount` · `#rejectsPhoneWithExistingStaffAccount` |
| AC5 | `#everyWriteLeavesAnAuditRow` |
| AC6 | `#disableTakesEffectAtOnce` |
| AC7 | `#rejectsShortCodeAndBadPhone` · `#rejectsBeyondEnabledLimit` |

**判据取真实链路的痕迹，不用替身**：「发没发短信」查 `sys_notify_log`
（`NotifyLoggingSmsPort` 装饰器每次发送都写一行，成功失败都写），
「码是哪一个」查 `OtpStore.peek`（与生产验码读的是同一份）。
⚠️ `sys_notify_log.target` 存的是**掩码后**的号（`199****1234`）——
按明文查一条都查不到，而那条断言会静默变绿：「发了」和「没发」在明文口径下都是 0。

### 消融（2026-09-28 实跑，四处）

| 消融 | 预期 | 实际 |
| --- | --- | --- |
| `sendOtp` 里的白名单分支换成 `Optional.empty()` | AC1 红 | ✅ 只有 `whitelistedPhoneGetsFixedCodeAndNoSms` 红 |
| 「已存在账号」那条校验整条短路 | AC4 红 | ✅ 两条 AC4 都红 |
| `accountExists` 只查 `usr_identity`、不查 `mch_account.login_phone` | 只有 B 端那条红 | ✅ **恰好一条**：`rejectsPhoneWithExistingStaffAccount` |
| 写口不再 `invalidate()` | AC6 红 | ✅ AC6 + AC1 都红 |

第三条是这四条里最该跑的：它证明「两个登录面」不是装饰。少了它，
护栏在 C 端看起来完全正常，而 B 端店主的号可以随便录。

**反向断言也在**：AC2 断言非白名单号 `sys_notify_log` **恰好多一行** ——
只断言「白名单不发」的话，把 `smsPort` 整条注掉也能让那一条变绿，
而那时所有人都收不到验证码。

## 4. 偏差说明

与 §2 的设计相比，实现里多了两件当时没写的事：

1. **「已存在账号」要查两个登录面。** 原方案只想到 C 端的 `usr_identity`。
   B 端店主与子账号登录走 `mch_account.login_phone`，而**两边共用同一个 `OtpStore`** ——
   漏掉那一面的话，拿到权限码的人可以录入任意店主的手机号、用自己配的码登进他的店，
   而护栏看起来是生效的。为此新开了一条最小 SPI：`StaffLoginPhonePort`。
   查的时候**不筛 status**：停用的账号也算「已存在」，否则「先停用 → 录白名单 → 再启用」
   三步都是合法操作，合起来是一次接管。
2. **重新启用要重过护栏。** 停用不是删除，而「停用 → 那个号注册了店 → 再启用」
   同样绕得过。所以 `setEnabled(true)` 那条路上把「已存在账号」与上限都再判一遍。

另外一处取舍写在这里免得被当成漏洞：**改一条已有的记录时不再判「已存在账号」**。
判的话会把唯一的正常用法自己堵死 —— 先录白名单、再用它注册，注册完之后
连改个备注都改不了。所以那条护栏只在**新录**时生效。
