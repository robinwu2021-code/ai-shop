# TDD-来单多渠道通知

状态：**已上线**（2026-10-10 `75976197b`，闸门 3221/0）· 待真实下单验五条都到
档位：**3**（新表 ×2 · 新端点 ×5 · spi 契约 ×3 · 新配置键 · 改全域事件投递）
关联：[TDD-微信订阅消息优先](TDD-微信订阅消息优先.md) · [TDD-通知与消息推送](design/TDD-通知与消息推送.md) §8 · [ADR-018 App生产形态与推送通道](ADR/ADR-018-App生产形态与推送通道.md)
创建：2026-10-10 · 最后更新：2026-10-10

> **这份替代 `TDD-商家企微群来单通知.md` 与 `TDD-来单四渠道与商家通知设置.md`。**
> 那两份是同一件事在两天里分五次演进出来的，一路打补丁，已经不好读了
> （「四渠道」这个名字现在也是错的——是五条）。这份按**最终形态**重写，
> 演进过程只保留「为什么是现在这样」所需的那部分。

## L1 一句话

买家付款成功后，这张单的商家会**同时**从五条路收到提醒：微信订阅消息、企业微信群、
短信、邮件、App 推送；哪几条开着、发到哪个地址，由店主**按门店**自己设。

---

## §0 需求与它的五次演进

| # | 用户原话（2026-10-09 / 10） | 带来的改变 |
|---|---|---|
| 1 | 「同时要增加客户下单后推送到企业微信」 | 企微群这条通道 |
| 2 | 「应该是商家的企业微信群，目前因为是自营，所以商家的企业微信群就是之前配置的群」 | 群**按商家路由**，不是平台一个群播全量 |
| 3 | 「最重要的通知就是消费者下单后，商家通过微信通知，企业微信以及短信都同时能收到…以上三个渠道可以在商家端进行设置。同时商家端 app 也能收到通知」 | 加短信；加商家端设置页；四条并列 |
| 4 | 「订正，开关和设置是基于门店」「售后和评价都是基于门店」 | 粒度从**主体**改成**门店**；售后与评价两个事件补 `storeNo` |
| 5 | 「邮箱，企业微信，短信都需要可以输入对应的接受地址，短信默认是登录手机号，可以增加最多两个」「都放到一张表，短信、邮箱地址、webhook 都是单独的一列」「webhook 存明文即可」 | 加邮件这条通道；收件地址独立成表 |
| 6 | 「要增加各渠道的推送记录，在订单支付成功后异步调用推送…推送按渠道顺序逐个推送。推送失败不能影响下一个通道。成功失败都要记录」「发送不要用 job，用队列即可，单机并发限定在 5 个。要防止系统重启，重启后要能继续发送」「同一个订单的所有事件是一个任务，任务中按顺序执行多个通知」 | 顺序显式化；留痕覆盖；投递从定时轮改成**按聚合分线的并发泵** |

**AC 对账**

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 五条通道同时发 | `NotificationConsumer#handle` 的 `SUB_ORDER_PAID` 分支 |
| AC2 | 按门店设开关 | `mch_notify_pref` + `MerchantNotifyPrefs` |
| AC3 | 按门店填地址 | `mch_notify_recipient` + `MerchantNotifyRecipients` |
| AC4 | 短信默认登录手机号 + 最多两个 | `MerchantStaffPort#ownerPhone` + `sms_phones` 列 |
| AC5 | 商家端能设置 | `BizNotifySettingController` 五条端点 + b-app `notify-settings` |
| AC6 | 异步、不影响订单流程 | 事务性发件箱 + `OutboxPump`（提交后投） |
| AC7 | 并发 5、同单有序 | `OutboxPump` 的 5 条单线程处理线，按聚合取模选线 |
| AC8 | 顺序逐个、失败不影响下一个 | `STORE_CHANNEL_ORDER` + 逐条 try |
| AC9 | 成功失败都记录 | `sys_notify_log`（五条通道各有留痕点） |
| AC10 | 重启后能继续发 | `sys_outbox` 持久 + `OutboxPump#onReady` 启动补扫 |

---

## §1 数据库

### 1.1 这件事涉及的五张表

```
                    ┌──────────────────┐
   下单事务里写 ──→ │   sys_outbox     │  事件队列（持久）
                    │  status/retry    │  ← 「重启后能继续发送」靠它
                    └────────┬─────────┘
                             │ OutboxPump 提交后立刻取
                             ▼
                    ┌──────────────────┐
                    │ NotificationConsumer │
                    └────────┬─────────┘
            读开关 ┌─────────┼─────────┐ 读地址
                   ▼         │         ▼
      ┌────────────────┐     │   ┌──────────────────────┐
      │ mch_notify_pref│     │   │ mch_notify_recipient │
      │ 门店×场景×通道 │     │   │ 一店一行，三列地址    │
      │   的开关       │     │   └──────────────────────┘
      └────────┬───────┘     │
               │ 串联        │ 发完写
      ┌────────▼───────┐     ▼
      │notify_scene_   │  ┌──────────────┐
      │channel（平台） │  │sys_notify_log│  投递记录
      └────────────────┘  └──────────────┘
```

### 1.2 `mch_notify_pref` —— 开关（V394）

| 列 | 说明 |
|---|---|
| `store_no` | **门店**，不是主体。自营一个主体下已有 4 家店（鲜果 / 福田 / 粮油 / 测试店），各店的人不同、各店可以有自己的群 |
| `scene` | `SUB_ORDER_PAID` / `AFTER_SALE_APPLIED` / `REVIEW_CREATED` |
| `channel` | `WXSUB` / `WEBHOOK` / `SMS` / `MAIL` / `PUSH`（**没有 INAPP**） |
| `enabled` | 商家自己的开关 |

唯一键 `(store_no, scene, channel, tenant_no)`。

**三条不变量：**

1. **缺行 = 开，不是关。** 反过来的话这张表一建，所有存量门店当天就一条来单提醒都收不到，
   而症状是「没有消息」——没有报错、没有日志，商家只会以为最近没单。**所以也不写种子**：
   种子等于把默认值写死两遍，两处迟早分叉。
2. **INAPP 不进这张表。** 站内信是事实记录（`msg_message` 的一行），恒发不可关；
   想关它的请求在 `MerchantNotifyPrefs#set` 里被拒 —— 守在后端，前端被绕过也兜住。
3. **与平台总闸串联，商家级只能更严。** `notify_scene_channel`（运营配）关掉的，
   商家开着也不发。反过来会让运营的全局停发（某条通道出故障时）被商家配置绕过。

### 1.3 `mch_notify_recipient` —— 收件地址（V395）

**一个门店一行，三条通道各一列**（用户 5：「都放到一张表…都是单独的一列」）。

| 列 | 内容 |
|---|---|
| `store_no` | 门店（唯一键） |
| `sms_phones` | 额外短信号，**逗号分隔**，代码限制 ≤2 |
| `email` | 邮件地址，一个 |
| `wecom_webhook` | 企微群地址，**明文** |

**为什么不塞进 `notify_channel.config_json`**（第一版的想法，被用户一问就否了）：
那是 `VARCHAR(1024)` 的自由 JSON，建出来到今天生产上 12 行全是 `{}` ——
没有列级校验、查不了（「哪些店配了邮箱」要全表扫加解析）、
改一个要读-改-写整串（并发下会把另一个会话刚加的覆盖掉），
而手机号与邮箱是个人信息，混在自由 JSON 里将来要清理或留痕没有下手的地方。

**三列都是明文**（用户 5：「webhook 存明文即可，加密将来再考虑」）。
手机号与邮箱本来就该明文——它们是**收件人**，店主填完要能回显核对。
webhook 不同，它是**凭据**：拿到的人就能往那个群发消息，库被读走等于一批商家的群发权限。
要改回加密时 `NotifyCredCipher`（AES-256-GCM，密钥 `SHOP_NOTIFY_CRED_KEY` 2026-10-09 已配在生产）
是现成的，改动面是这一列 + `MerchantNotifyRecipients` 的读写两处。
**存明文不等于回显**：接口只给「配过没有」。

**店主的登录手机号不在这张表里。** 那一个恒发、删不掉，真源是 `mch_account.login_phone`。
冗余进来的话，店主改了登录号这里就是个过期的号，而症状是「短信发到旧号上」，没有任何报错。
店主自己又填了一遍时**去重**——短信按条计费，发两遍既花钱又像系统出错。

### 1.4 复用的三张

- **`notify_scene_channel`**（平台总闸，运营配）：V394/V395 给三个场景补齐了
  `SMS` / `WEBHOOK` / `MAIL` 行。没有这些行，`SceneChannelRouting`「查不到 = 关」会把它们关死——
  那条兜底是为「新场景别擅自外发」设的，不是为「这条通道不存在」设的。
- **`sys_notify_log`**（投递记录）：五条外发通道各有留痕点，见 §3.3。
- **`sys_outbox`**（事件队列）：见 §2。

---

## §2 投递：从付款成功到五条通道发出

### 2.1 链路

```
支付回调
  └─ 订单事务
       ├─ 改订单状态
       └─ eventBus.publish(SubOrderPaid)   ← 只写 sys_outbox 一行（同事务）
     COMMIT
       └─ afterCommit → OutboxPump.submit(id, 订单号)     【用户 6：订单完成后直接执行】
            └─ 按 hash(订单号)%5 选一条处理线
                 └─ OutboxDispatcher.dispatchOne(id)
                      └─ NotificationConsumer.handle(event)
                           ├─ 站内信（恒发，msg_message）
                           ├─ 微信订阅     ┐ 按人扇出
                           ├─ App 推送     ┘ （fanOutToStaff）
                           └─ STORE_CHANNEL_ORDER 逐条：
                                企微群 → 短信 → 邮件    按店
```

**订单流程不等任何一条通知** —— 事务里只有一次 insert。

### 2.2 并发 5、同单有序（用户 6 / 7）

`OutboxPump` 开 **5 条各自单线程**的处理线，按 `Math.floorMod(hash(aggregateId), 5)` 固定选线：

```
单 A 的 PAID、SHIPPED、COMPLETED → 恒定落在同一条线 → 按入队先后一个接一个
单 B                            → 可能是另一条线   → 与 A 并发
```

于是**同一张单的事件永远有序**（不会 SHIPPED 先于 PAID 发出去），最多 5 张单同时在发，
第 6 张在它那条线的队列里等。每条事件内部的几条通道又按 `STORE_CHANNEL_ORDER` 顺序走完——
**两层顺序都有保证**。

> 不是真的把同一张单的多个事件聚成一个任务：它们是**陆续产生**的
> （支付与发货隔着小时级），没法预先聚。分线是达成同一语义的办法。

**代价**：负载不均（3 号线堆了 10 个时空着的 1 号线帮不上忙）、
全局先来先服务被破坏（后到的单可能先发）。这是换顺序保证付的价，
而顺序对账比吞吐重要——结算与库存也走这条投递器。

### 2.3 重启后继续发（用户 6）

**队列是 `sys_outbox` 那张表，不是内存。** 这一点容易搞反：
换成纯内存队列的话，「重启后继续发送」恰恰不成立——进程一停，队列里的全丢。

三条进入路径：

| 路径 | 作用 |
|---|---|
| 提交后即时（主路） | 订单完成直接投，不等任何轮次 |
| **启动补扫**（`OutboxPump#onReady`） | 把库里所有 PENDING 重新入队 —— 这就是「重启后能继续发送」 |
| 失败延迟重排 | 按退避把自己塞回**本条线**。不是定时任务，是一个只负责「到点唤醒」的计时器 |

### 2.4 定时轮去掉了（用户 6：「不要用 job」）

旧的 `OutboxDispatchJob`（5 秒一轮 + ShedLock）**默认不再跑**
（`shop.outbox.legacy-scan.enabled` 默认 false）。

⚠️ **代价要记住**：进程崩溃时内存队列里排队的那些会丢，它们在库里仍是 PENDING，
但要等到下次启动的补扫才会被捞。留那个开关是为了不用紧急发版——
新路径万一有问题，一个配置就能把旧路一开回来。

⚠️ **这是全域基础设施**：结算、库存、商品镜像都走同一个投递器，不只通知。

---

## §3 代码结构

### 3.1 后端

| 层 | 类 | 职责 |
|---|---|---|
| 事件 | `OutboxEventBus` | 写 `sys_outbox`；**afterCommit** 交给泵 |
| | `OutboxPump`（新） | 5 条处理线、按聚合选线、启动补扫、失败重排 |
| | `OutboxDispatcher` | `dispatchOne`（单条，泵用）/ `dispatchPending`（批量，兜底与测试用） |
| 编排 | `NotificationConsumer` | 场景 → 哪些人、哪些通道、什么内容。`STORE_CHANNEL_ORDER` 在这里 |
| 开关 | `MerchantNotifyPrefs` | 两级串联（平台 × 门店）。**调用方只问 `on()` 这一个方法** |
| 地址 | `MerchantNotifyRecipients` | 三列地址的唯一读写入口：逗号怎么切、上限几个、前缀校验都收在这里 |
| 通道 | `WxSubscribeSender` · `PushSender` · `SmsPort` · `MailPort` · `WeComBotSender` | 各自发送 + 留痕 |
| 内容 | `WeComOrderAlert` | 来单那条的排版。`content()` 给企微（markdown）、`plainText()` 给邮件 |
| 端点 | `BizNotifySettingController` | 五条 `/biz/notify/**` |

### 3.2 端上（b-app）

- 页面 `pages/notify-settings/index.vue`：三个场景各五个开关 + 三段收件地址
- 契约四处：`endpoints.ts` · `http.ts` · `contract.ts` · `mocks/message.ts`
- 名单两份：`store-scope.ts`（门店级端点与页面）、`enum-registry.ts`（`NotifyChannelCode`）

### 3.3 留痕口径

| 情形 | 记不记 |
|---|---|
| 真的尝试发了，成功 | 记 `SENT` |
| 真的尝试发了，失败 | 记 `FAILED` + 原因 |
| 开关关着 | **不记** |
| 地址没填 | **不记** |

后两种不是失败的发送，是「这条通道对这家店不存在」。记的话，绝大多数没配企微群的门店
每单都会刷一行「没配」，真正的失败就埋在里面了。

**站内信不在这张表里**——它本身就是 `msg_message` 的一行，那是事实记录不是投递记录。

---

## §4 外部前置（我做不了的）

| 事 | 状态 |
|---|---|
| 阿里云**来单短信模板**报备 | ⬜ 人工审批，几小时到一天。代码已就位，模板号填进 `ALI_SMS_TPL_ORDER_PAID` 那一刻就通，不用发版 |
| 邮件通道 | ✅ 生产四项配置早就齐了、`SHOP_MAIL_STUB=false` —— **五条里唯一即开即用的一条**，短信下来之前它是实际主力 |
| `SHOP_NOTIFY_CRED_KEY` | ✅ 2026-10-09 在服务器上生成并写入 env（值未经过本地）。webhook 改存明文后这一批不再用它，但 `notify_channel.secret_cipher` 那套还在用 |
| 微信订阅模板 `MCH_REVIEW` | ⬜ 后台选定后回填 |

**`ALI_SMS_TPL_ORDER_PAID` 刻意不进 `AliSmsGateway.requireConfigured()`**：
列成必需的后果是「审批没下来、生产一重启就起不来」——一条锦上添花的通知把整个服务拖下线。
缺它只有来单短信这一条发不出去，并写一行 `SMS/FAILED/tpl_unconfigured` 留痕，不静默。

---

## §5 测试与消融

| 测试 | 断言 |
|---|---|
| `OrderPaidFourChannelsFlowTest#allFourFire` | 一单付款，各条通道各发一次 |
| `#switchesAreIsolatedPerStore` | **一家店关掉，同主体另一家照发** —— 「基于门店」的量具 |
| `#afterSaleAndReviewAlsoPerStore` | 售后与评价也按门店 |
| `#smsGoesToOwnerPlusExtras` | 店主 + 自填两个号都收到 |
| `#ownerPhoneIsNotSentTwice` | 店主把自己的号又填一遍 → 只发一条 |
| `#mailGoesToConfiguredAddress` | 邮件发到填的地址；没填不发 |
| `#merchantCanMuteSms` / `#merchantCanMuteMail` | 关一条，其余照发 |
| `#everyAttemptedChannelIsLogged` | 真发过的通道在 `sys_notify_log` 里各有一行 |
| `#unconfiguredChannelLeavesNoRow` | **没配地址的不留痕** |
| `#storeChannelOrderIsDeclared` | 企微 → 短信 → 邮件，按名单 |
| `#weComFailureDoesNotBlockOtherChannels` | 群发炸了：其余照发**且事件不重投** |
| `#missingRowMeansOn` | 没设置过的门店全开 |
| `#inappHasNoSwitch` | 想关站内信被拒 |
| `MerchantNotifyRecipientsTest`（8 条） | 上限 2、号与邮箱形状、逗号切分只在一处 |
| `WeComOrderAlertTest`（8 条） | 发到哪家店的群、排版、企微与邮件同一份内容 |

**消融（都验过，红在该红的那条）**：
① 开关退回按主体判 → 门店隔离那两条红；
② 调换 `STORE_CHANNEL_ORDER` → 顺序那条红；
③ 没配地址也留痕 → 覆盖那条红；
④ 拆掉 `WeComOrderAlert` 的 try/catch → 「不拖累其余出口」那条红
（量具是 `dispatcher.pendingCount()`，断言「其余通道还在」在异常冒出去时也绿）。

**投递泵改造踩的三个坑**（都记在代码注释里）：

1. **`ObjectProvider` 放在 `@PostConstruct` 里解析等于没打断环** —— 那还在装配阶段。
   症状是全量 2323 个 context 起不来，报的是 `invManagedAppService` 的循环依赖，
   与 outbox 看着毫无关系。改成用时才取。
2. **测试世界必须关掉泵**。场景测试自己 `drainOutbox()` 手动投，泵开着两边同时投同一条，
   泵的 `REQUIRES_NEW` 事务与测试事务在 H2 上撞锁 —— 满屏
   `UnexpectedRollbackException: marked as rollback-only`，45 条红，
   而报错都在被撞的那些测试上。
3. **消融之后用 `mv` 还原不生效**：mv 保留旧 mtime，maven 认为源码没变，
   跑的还是消融版的字节码。当时误判成「满负载下的时序问题」还据此改了注释 ——
   那条假归因后来清掉了。还原要 `touch`。

**两个被测试抓出来的真缺陷**：

1. `StubWeComBotSender` 只记内存、**不写 `sys_notify_log`** —— 于是「企微留痕」
   在测试里一直没有量具，而生产上是有的。桩替换的只是发 HTTP 那一步，留痕是另一半职责。
2. V394 建表注释的取值域没跟着加 `MAIL`，两侧真的分叉（端上有、后端没有）。

---

## §6 现状

| 项 | 状态 |
|---|---|
| 两张表 + 平台总闸补行 | ✅ V394 / V395 |
| 开关、地址、五条通道编排 | ✅ |
| 五条 `/biz` 端点 | ✅ |
| b-app 设置页 | ✅（mock 包里验过交互） |
| 顺序名单 + 留痕覆盖 | ✅ |
| `OutboxPump`（并发泵） | ✅ 8 条单测 + 2 处消融；旧定时轮已退成应急开关（默认关） |
| 测试世界关掉泵 | ✅ `application-test.yml` —— 场景测试靠 `drainOutbox()` 手动投，泵开着两边会撞锁 |
| 部署 | ✅ `75976197b`，health=200，V394/V395 已应用 |
| 自营的群搬到新表 | ✅ 主体下 4 家店各落一行（共用同一个群，与搬之前行为一致）；`notify_channel` 那行已软删——不留第二处真源 |
| 停用孤儿 job | ✅ 见下 |
| 真实下单验五条都到 | ⬜ **等一单真实支付** |

## §6.1 上线时发现并处理的一件事

旧的 `outbox-dispatch` 在独立调度器的 `job_definition` 里还有一行，
而 handler 随 `OutboxDispatchJob` 一起不装配了 —— 于是调度器每次触发都报
`调度器要跑一个不存在的 handler：outbox-dispatch`，job 记录也会一直失败。
不影响业务（投递已走泵），但是我这次改动留下的噪音，而且「任务一直失败」会误导排查。

**停用那一行而不是删**（`enabled` 1→0）：应急把 `shop.outbox.legacy-scan.enabled`
打开时，这条还要用。`job_definition` 的 cron/enabled「入库即归运营，代码永不覆盖」，
所以这是运维动作不是迁移。它的 `missing=1` 本来就标着 handler 不在。

⚠️ job 库是**另一个库、另一套凭据**（`ai_shop_job`，`SHOP_JOB_DATASOURCE_*`）——
主库的账号连它 `SHOW DATABASES` 都看不见；列名也不是 `name` 而是 `job_name` / `handler_name`。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 企微群那条落地并上线（`a5a30d401`） |
| 2026-10-10 | 门店粒度 + 四渠道 + 设置页（`cede77cea` / `f8aa1ca78`） |
| 2026-10-10 | 邮件通道 + 收件地址表（`9054e8f63`） |
| 2026-10-10 | 用户：「有必要重新整理技术方案」→ 本文档，替代前两份 |
| 2026-10-10 | 投递泵落地（`75976197b`）并上线；自营的群搬到新表、按门店 4 行；停用孤儿 job |
