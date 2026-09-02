/**
 * 前端 UI 能力码 → 后端权限码。
 *
 * ## 2026-08-12 之后这张表大部分是**恒等映射**
 *
 * 后端权限码从 16 个细化到 68 个，**采纳的就是这份 UI 码表** ——
 * 界面功能与权限码一一对应之后，「翻译」这件事本身基本消失了。
 * 表没有删掉，因为它还担着两件事：
 *
 * 1. **UNIMPLEMENTED 标记** —— 后端整域没开工的那些码（风控、履约、增长…）。
 *    标了的一律判 `false`：**入口不该存在**。让它显示然后点出 404，
 *    比藏起来坏得多（见 `docs/technical/运营端死按钮实测清单.md`）。
 * 2. **少数还需要真翻译的** —— 见下面 §「仍需翻译的四条」。
 *
 * ## 它此前是什么样、为什么必须改
 *
 * 细化前 45 个 UI 码挤在 14 个后端码上：`merchant:apply:audit`、
 * `merchant:merchant:ban` 都映到 `merchant:audit` ——
 * **「能查商家」等于「能封店」**，想分开做不到。
 *
 * 换码那天如果不同步改这张表，后果是立刻可见的：`can()` 会把每个 UI 码
 * 映到一个**已经不存在的旧粗码**，于是所有 perms 检查全 false，整个界面空掉。
 * 写 A2 时差点漏掉这一处。
 *
 * ## 仍需翻译的四条
 *
 * 它们的界面功能后端没有独立端点，映到覆盖它的那个码：
 * 支付流水核对 → 订单只读；掉单补偿/关单策略 → 订单干预；
 * 预售额度 → 商品审核；`category:manage`（历史遗留的 ACTION 码）→ 类目维护。
 *
 * ## 加新码时
 *
 * 守卫（`perm-map.test.ts`）会强制两件事：页面用到的码必须在这张表里、
 * 表里映射到的后端码必须真的存在于 `Perms.java`。
 * 漏一个的表现都是「按钮神秘消失」—— 那是最难查的一类。
 */

/** 后端还没有这块能力。判 false，入口不渲染 */
export const UNIMPLEMENTED = Symbol("backend-unimplemented");

export const UI_PERM_MAP: Record<string, string | typeof UNIMPLEMENTED> = {
  "merchant:apply:audit": "merchant:apply:audit",
  "merchant:merchant:ban": "merchant:merchant:ban",
  "merchant:verify:grant": "merchant:verify:grant",
  "merchant:category:grant": "merchant:category:grant",
  "order:order:modify": "order:order:modify",
  "order:order:proxy": "order:order:proxy",
  "order:pay:repair": "order:order:modify",
  "product:sku:audit": "product:sku:audit",
  "product:stock:update": "product:sku:audit",
  "product:category:update": "product:category:update",
  "product:std:update": "product:std:update",
  "product:topic:update": "product:topic:update",
  "category:manage": "product:category:update",
  "community:community:update": "community:community:update",
  "community:region:update": "community:region:update",
  "community:pickup:update": "community:pickup:update",
  "review:review:audit": "review:review:audit",
  "review:score:update": "review:score:update",
  "aftersale:refund:approve": "aftersale:refund:approve",
  "aftersale:ticket:handle": "aftersale:ticket:handle",
  "message:ticket:handle": "message:ticket:handle",
  "marketing:campaign:read": "marketing:campaign:read",
  "marketing:campaign:update": "marketing:campaign:update",
  "marketing:coupon:issue": "marketing:coupon:issue",
  // 会员与人档（P8）。后端 O1–O7 已落地，所以是真码不是 UNIMPLEMENTED
  "member:member:read": "member:member:read",
  "member:person:read": "member:person:read",
  "member:person:merge": "member:person:merge",
  "member:phone:reveal": "member:phone:reveal",
  "marketing:slot:update": UNIMPLEMENTED,
  "marketing:member:update": UNIMPLEMENTED,
  "group:campaign:audit": "group:campaign:audit",
  "group:demand:assign": "group:demand:assign",
  "message:template:update": "message:template:update",
  "finance:settle:execute": "finance:settle:execute",
  "iam:audit:read": "iam:audit:read",
  "finance:invoice:read": "finance:invoice:read",
  "finance:rate:update": "finance:rate:update",
  // 2026-08-13 接通：OpsWithdrawController（列表 + 审批）。**恒等映射** ——
  // 后端新增了同名码 Perms.FINANCE_WITHDRAW_APPROVE，且 V112 把 OPS_FINANCE_05
  // 的 perm_code 从 NULL 补上、授给 FINANCE。
  // ⚠️ 持有它不等于能打款：通过后落 APPROVED，出款是线下动作（B-12.5）
  // 这两条漏登记：`can()` 对未登记的码返回 false，于是财务页的「登记付款」
  // 与发票核验按钮**对谁都不显示**（含财务角色本人），且没有任何报错 ——
  // 就是这张表注释里说的「按钮神秘消失」。两个码在 Perms.java 里一直都有。
  "finance:payout:execute": "finance:payout:execute",
  "finance:invoice:verify": "finance:invoice:verify",
  "finance:withdraw:approve": "finance:withdraw:approve",
  "risk:blacklist:update": "risk:blacklist:update",
  "risk:rule:update": "risk:rule:update",
  "content:material:audit": "content:material:audit",
  "content:material:update": "content:material:update",
  // FAQ 维护后端一直是有的（/ops/faqs 三条，判 message:ticket:handle），
  // 只是没有独立的码 —— 映到覆盖它的那个，与上面「仍需翻译的四条」同一用法。
  // 之前写 UNIMPLEMENTED：`can()` 先查映射后判通配，于是**超管也看不见这一页**。
  "message:faq:update": "message:ticket:handle",
  "fulfillment:batch:read": "fulfillment:batch:read",
  "fulfillment:rule:update": "fulfillment:rule:update",
  "growth:attribution:read": "growth:attribution:read",
  "growth:fission:update": "growth:fission:update",
  "store:page:audit": "store:page:audit",
  // 后端没有 store:qrcode:export 这个码；店铺码与店招审核是同一拨人（BD）在做，
  // 端点判的就是 store:page:audit。映过去而不是新增后端码 —— 见 V293。
  "store:qrcode:export": "store:page:audit",
  "system:theme:update": "system:theme:update",
  "system:param:read": "system:param:read",
  // env:switch 映到它（见下），而映射目标自己也要登记 —— 守卫会检查这一点
  "system:param:update": "system:param:update",
  /*
   * 开关与灰度的写码。**后端叫 system:param:update** ——
   * 这里此前标着 UNIMPLEMENTED，于是那一页对所有人都是只读的，
   * 开关永远拨不动。而它一直没被发现，是因为后端的默认开关清单是空的：
   * 一个没有任何行的表格，看不出它的按钮是不是灰的。
   *
   * 端上这个名字（env:switch）保留：它在 CRITICAL_PERMS 与角色矩阵里都用着，
   * 改名要动的地方比接一条映射多得多，而这张表存在的理由正是「翻译」。
   */
  "system:env:switch": "system:param:update",
  // 存储空间治理：看清单与发起回收是两个码，页面按后者显隐勾选框与批量条
  "system:media:read": "system:media:read",
  "system:media:purge": "system:media:purge",
  // 定时任务：看与管两个码。读的人多得多 —— 一个任务出事时，
  // 先来看的往往是被它影响到的那条业务线的人，而他们不该有权把它停掉
  "system:job:read": "system:job:read",
  "system:job:manage": "system:job:manage",
  "iam:role:grant": "iam:role:grant",
  "merchant:merchant:read": "merchant:merchant:read",
  /*
   * 准入与保证金。**后端早就有**（OpsAdmissionController 5 个端点，
   * Perms.ROLE_PERMS 里 FINANCE 也持有这两个码），漏的只是这张映射表 ——
   * 而 can() 是先查映射后判通配的，未登记一律判无权限，
   * 于是财务在界面上根本看不到自己有权做的事，且没有任何报错。
   */
  "merchant:admission:read": "merchant:admission:read",
  // 门店经营模式（含无照自营风险表）。不登记的话 can() 一律判无权限 ——
  // 后端明明给了 BD 这个码，界面上却什么也看不到，且不报错
  "merchant:mode:read": "merchant:mode:read",
  // 发放/吊销开放对接凭据（进销存「开放对接」的两个写动作）判的就是它
  "merchant:mode:update": "merchant:mode:update",
  // 资质档案。同一个坑第二次：后端有码、UI_PERM_MAP 没登记 → can() 一律判无权限，
  // 页面上什么也看不到且不报错
  "merchant:category:read": "merchant:category:read",
  "merchant:admission:update": "merchant:admission:update",
  "merchant:fulfillment:update": "merchant:fulfillment:update",
  /*
   * 进销存（V272）。**不登记的后果这份文件已经记过两次**：后端有码、
   * UI_PERM_MAP 没登记 → can() 一律判无权限，页面上什么也看不到且不报错。
   * 这三个码是 2026-08-29 从 product:sku:read / merchant:mode:update 分出来的 ——
   * 进销存是平台的独立模块，此前整段寄在别人名下（见 Perms.java 的进销存那一段）。
   */
  "inventory:stock:read": "inventory:stock:read",
  "inventory:credential:read": "inventory:credential:read",
  "inventory:credential:grant": "inventory:credential:grant",
  "order:order:read": "order:order:read",
  "order:pay:read": "order:order:read",
  "product:sku:read": "product:sku:read",
  "product:category:read": "product:category:read",
  // 规格库（V195）：与类目分开的一对码 —— 类目权限还兼着资质门槛，
  // 而规格库改一条会影响所有商家的建品页
  "product:spec:read": "product:spec:read",
  "product:spec:update": "product:spec:update",
  "product:std:read": "product:std:read",
  "product:topic:read": "product:topic:read",
  "community:community:read": "community:community:read",
  "community:region:read": "community:region:read",
  "community:pickup:read": "community:pickup:read",
  "aftersale:ticket:read": "aftersale:ticket:read",
  "message:ticket:read": "message:ticket:read",
  "message:template:read": "message:template:read",
  "marketing:coupon:read": "marketing:coupon:read",
  "group:demand:read": "group:demand:read",
  "finance:settle:read": "finance:settle:read",
  "iam:staff:read": "iam:staff:read",
  "content:material:read": "content:material:read",
  "fulfillment:logistics:read": "fulfillment:logistics:read",
  "fulfillment:redeem:read": "fulfillment:redeem:read",
  "risk:rule:read": "risk:rule:read",
  "store:page:read": UNIMPLEMENTED,
};
