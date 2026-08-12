/**
 * 前端 UI 能力码 → 后端权限码。
 *
 * ## 为什么需要这张表
 *
 * 两边的码是**两套独立长出来的**：ops-web 按页面细分到 45 个
 * （`模块:对象:动作`），后端 `Perms.java` 只有 14 个（`对象:动作`）。
 * 数量差不是错 —— 前端要分得细才能精确控制按钮，后端只需要管住端点。
 *
 * 错的是此前**根本没有连接**：`can()` 查的是前端自己写死的 `ROLE_PERMS[role]`，
 * 而后端下发的 `staff.perms` 一个字节都没被读过。于是两种错都会发生：
 *
 * - **前端放行、后端 403** —— 用户点得到按钮，点完报「没有操作权限」
 * - **前端拦住、后端本来允许** —— 功能存在，而用户根本看不见入口
 *
 * ## UNIMPLEMENTED 是这张表最有价值的部分
 *
 * 45 个里有 26 个后端**没有对应能力**（风控、财务、内容、履约…整域未开工）。
 * 把它们标出来，是让「前端以为有、后端其实没有」的差集变成一份可读、可测的清单 ——
 * 而那份差集此前散在 58 个码里，没有任何人看得见。
 *
 * 标了 UNIMPLEMENTED 的码一律判 `false`：**入口不该存在**。
 * 让它显示然后点出 404，比藏起来坏得多（见
 * `docs/technical/运营端死按钮实测清单.md`）。
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
  // ── 商家（后端 merchant:audit 一个码管整块） ──
  "merchant:apply:audit": "merchant:audit",
  "merchant:merchant:ban": "merchant:audit",
  "merchant:verify:grant": "merchant:audit",
  "merchant:category:grant": "merchant:audit",

  // ── 订单 ──
  "order:order:modify": "order:intervene",
  // 代客下单与支付补单：后端只有「介入」这一个写权限，粒度到不了这么细。
  // 映射到 order:intervene 是**保守**的选择 —— 宁可要求更高的权限，
  // 也不要放行一个后端会拒的动作
  "order:order:proxy": "order:intervene",
  "order:pay:repair": "order:intervene",

  // ── 商品与类目 ──
  "product:sku:audit": "goods:audit",
  "product:stock:update": "goods:audit",
  "product:category:update": "category:manage",
  "category:manage": "category:manage",

  // ── 社区与自提点 ──
  "community:community:update": "industry:manage",
  "community:pickup:update": "industry:manage",

  // ── 评价 ──
  "review:review:audit": "review:govern",
  "review:score:update": "review:govern",

  // ── 售后与工单 ──
  "aftersale:refund:approve": "order:intervene",
  "aftersale:ticket:handle": "ticket:handle",
  "message:ticket:handle": "ticket:handle",

  // ── 营销 ──
  "marketing:campaign:update": "marketing:govern",
  "marketing:coupon:issue": "marketing:govern",
  "marketing:slot:update": "marketing:govern",
  "marketing:member:update": "marketing:govern",
  "group:campaign:audit": "marketing:govern",
  "group:demand:assign": "quote:govern",

  // ── 消息模板 ──
  "message:template:update": "ticket:handle",

  // ── 结算 ──
  "finance:settle:execute": "settle:manage",

  // ── 审计 ──
  "iam:audit:read": "audit:view",

  // ══════════════════════════════════════════════════════════════════
  // 以下 UI 码后端**没有对应能力**。判 false，入口不渲染。
  //
  // 它们不是「忘了映射」，而是「那块后端还没开工」——
  // 对照 packages/shared/tests/ops-endpoint-exists.test.ts 里的
  // UNBUILT_DOMAINS，两份清单说的是同一件事的两面：
  // 那边是「端点不存在」，这边是「所以入口也不该出现」。
  // ══════════════════════════════════════════════════════════════════

  // 财务（invoice / rate / withdraw 三块后端都没有；settle 有，已映射在上面）
  "finance:invoice:read": UNIMPLEMENTED,
  "finance:rate:update": UNIMPLEMENTED,
  "finance:withdraw:approve": UNIMPLEMENTED,

  // 风控
  "risk:blacklist:update": UNIMPLEMENTED,
  "risk:rule:update": UNIMPLEMENTED,

  // 内容运营。**已接通**（2026-08-11）—— content:govern 不复用 review:govern：
  // 那是评价裁决给客服的，而客服不该能改首页榜单
  "content:material:audit": "content:govern",
  "content:material:update": "content:govern",
  // 帮助中心后端仍然没有
  "message:faq:update": UNIMPLEMENTED,

  // 履约调度
  "fulfillment:batch:read": UNIMPLEMENTED,
  "fulfillment:rule:update": UNIMPLEMENTED,

  // 增长
  "growth:attribution:read": UNIMPLEMENTED,
  "growth:fission:update": UNIMPLEMENTED,

  // 门店经营支持（实测三个 tab 打开即 404）
  "store:page:audit": UNIMPLEMENTED,
  "store:qrcode:export": UNIMPLEMENTED,

  // 平台自身配置。**皮肤/开关/文案/汇率四类已接通**（2026-08-11），
  // 后端新加了 platform:config —— 它与 industry:manage 分开，
  // 因为改的是全平台行为（汇率错一位是所有人立刻受影响）
  "system:theme:update": "platform:config",
  "system:param:read": "platform:config",
  // 环境切换后端仍然没有端点
  "system:env:switch": UNIMPLEMENTED,

  // IAM 写操作：后端只有 GET /ops/staffs（读），角色授权页一个能通的写接口都没有
  "iam:role:grant": UNIMPLEMENTED,

  // ══════════════════════════════════════════════════════════════════
  // 导航叶子的 `:read` 码（lib/nav.ts 的 leaf.perm）。
  //
  // 读权限比写宽 —— 能进这个页面看，与能在里面按按钮，是两件事。
  // 但后端没有单独的「只读」码，所以映射到管这块的那个码：
  // 有权改的人一定有权看，反过来不成立 —— 于是这里比理想中严，
  // **宁可严，也不要让人看见一个点进去全是 403 的页面**。
  // ══════════════════════════════════════════════════════════════════
  "merchant:merchant:read": "merchant:audit",
  "order:order:read": "order:view",
  "order:pay:read": "order:view",
  "product:sku:read": "goods:audit",
  "product:category:read": "category:manage",
  "community:community:read": "community:view",
  "community:pickup:read": "community:view",
  "aftersale:ticket:read": "ticket:handle",
  "message:ticket:read": "ticket:handle",
  "message:template:read": "ticket:handle",
  "marketing:coupon:read": "marketing:govern",
  "group:demand:read": "quote:govern",
  "finance:settle:read": "settle:manage",
  "iam:staff:read": "staff:manage",

  "content:material:read": "content:govern",

  // 后端整域未开工的那几个只读页
  "fulfillment:logistics:read": UNIMPLEMENTED,
  "fulfillment:redeem:read": UNIMPLEMENTED,
  "risk:rule:read": UNIMPLEMENTED,
  "store:page:read": UNIMPLEMENTED,
};
