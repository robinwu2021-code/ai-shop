// 端点 → 细粒度权限码的**登记表**。权限码细化改造（阶段 A）的输入。
//
// 原则：**权限码对应界面功能**，而不是对应「一整个域」。
// 现状是 16 个码盖 147 个端点，粗到「只读商家档案」与「封禁商家」同码。
//
// 三条写在前面的取舍：
//
// 1. **读写分开**。GET 给 `:read` 码，写给动作码。
//    这不是洁癖 —— `Perms.COMMUNITY_VIEW` 的注释记着实测过的代价：
//    读写合一时，只要有任何一个角色需要看而不需要改，它就会被迫多拿一份写权限，
//    或者干脆做不了本职工作（BD 打开审核抽屉，覆盖小区一个选项都没有）。
//
// 2. **码名取自 ops-web 的 `uiPermCode`**（64 个，与菜单项一一对应）。
//    那份码表一直在维护，后端从来没采纳它。另起一套等于再造翻译层。
//
// 3. **没有菜单项的端点也要有码**，码名按它实际干的事起。
//    这类有 28 条（保证金、支付额度、准入策略、进项发票、经营模式…）——
//    **后端有能力、ops-web 没有入口**，是「有能力没有消费方」的又一批，
//    见文件末尾的 NO_UI 清单。给它们编码不是为了配菜单，是为了别再共用一把大钥匙。
//
// 规则按**从具体到一般**匹配，第一条命中的生效。
// 顺序敏感 —— 所以下面每一组内部都是「特例在前、兜底在后」。

/** [方法或 *, 路径正则, 权限码, 理由（只在不显然时写）] */
export const RULES = [
  // ── 商家治理 ───────────────────────────────────────────────────────────
  ["*", /^\/ops\/merchant\/apply/, "merchant:apply:audit"],
  // 封禁 / 归档 / 记违规 = 处置。**与只读档案分开是这次改造最主要的目的之一**
  ["POST", /^\/ops\/merchants\/[^/]+\/(status|archive|unarchive|violations)$/, "merchant:merchant:ban"],
  ["POST", /^\/ops\/merchants\/[^/]+\/verified$/, "merchant:verify:grant"],
  // 资质授予：类目上挂着 required_code，授资质等于放行一整类商品的准入。
  // 读单独一个码 —— 商家档案页要显示「这家店有哪些资质」，看不代表能授。
  //
  // **注意与 `/ops/auth-codes`（资质码字典）分开**：那是「世界上有哪些资质码」，
  // 归类目维护面；这里是「给这家店授哪几个」，归商家治理。
  // 第一版把两者归成同一个码，自检发现它**横跨 category:manage 与 merchant:audit
  // 两个粗码** —— 机械展开时会让只有其中一个粗码的角色白拿另一半，
  // 那正是这次改造要防的静默放宽。
  ["GET", /^\/ops\/(merchants\/[^/]+\/)?qualifications/, "merchant:category:read"],
  ["GET", /^\/ops\/merchants\/[^/]+\/auth-codes/, "merchant:category:read"],
  ["*", /^\/ops\/(merchants\/[^/]+\/)?qualifications/, "merchant:category:grant"],
  ["*", /^\/ops\/merchants\/[^/]+\/auth-codes/, "merchant:category:grant"],
  // 资质码字典本身，与类目树同一个维护面（类目上挂 required_code，改哪个都影响准入）
  ["GET", /^\/ops\/auth-codes/, "product:category:read"],
  ["*", /^\/ops\/auth-codes/, "product:category:update"],
  ["POST", /^\/ops\/qualifications\/[^/]+\/revoke$/, "merchant:category:grant"],
  ["GET", /^\/ops\/merchants\/[^/]+\/store-modes$/, "merchant:mode:read"],
  ["PUT", /^\/ops\/stores\/[^/]+\/business-mode$/, "merchant:mode:update",
    "现在挂在 settle:manage 下 —— 改门店经营模式根本不是结算"],
  ["GET", /^\/ops\/merchants/, "merchant:merchant:read"],

  // 店招公告审核。**后端其实有**，而 P-10.1 在三方对齐里被记成「后端零实现」——
  // 那份 review 这一条是错的，门店主页域里审核这两条一直在跑。
  //
  // 这里**故意不拆读写**（是全表唯一的例外）：待审队列的「读」就是审核这个动作的一半，
  // 拆出来会得到一个只有审核员用、且审核员必然同时持有的码 —— 那种码只增加配置负担。
  // 对比 `GET /ops/goods/audit-queue` 归 `product:sku:read`：商品池本身是个浏览场景，
  // 有人只看不审；店招待审队列没有这种角色。
  ["*", /^\/ops\/stores\/audits/, "store:page:audit"],

  // ── 准入（保证金 / 支付额度 / 准入策略）· 无菜单入口 ─────────────────────
  ["GET", /^\/ops\/admission\//, "merchant:admission:read"],
  ["*", /^\/ops\/admission\//, "merchant:admission:update"],

  // ── 商品与类目 ─────────────────────────────────────────────────────────
  ["*", /^\/ops\/goods\/[^/]+\/audit$/, "product:sku:audit"],
  ["GET", /^\/ops\/goods\//, "product:sku:read"],
  ["GET", /^\/ops\/categories/, "product:category:read"],
  ["*", /^\/ops\/categories/, "product:category:update"],

  // ── 交易订单 ───────────────────────────────────────────────────────────
  ["POST", /^\/ops\/orders\/[^/]+\/intervene$/, "order:order:modify"],
  ["POST", /^\/ops\/orders\/[^/]+\/proxy-cancel$/, "order:order:proxy"],
  ["GET", /^\/ops\/(order|orders)/, "order:order:read"],

  // ── 售后 ───────────────────────────────────────────────────────────────
  ["GET", /^\/ops\/after-sales\/fast-refund-rule$/, "aftersale:refund:read"],
  ["POST", /^\/ops\/after-sales\/fast-refund-rule$/, "aftersale:refund:approve"],
  ["POST", /^\/ops\/after-sales\/[^/]+\/decide$/, "aftersale:ticket:handle"],
  ["GET", /^\/ops\/after-sales/, "aftersale:ticket:read"],

  // ── 经营看板 ───────────────────────────────────────────────────────────
  ["GET", /^\/ops\/dashboard\//, "dashboard:overview:read"],

  // ── 营销 ───────────────────────────────────────────────────────────────
  ["POST", /^\/ops\/coupons\/[^/]+\/issue$/, "marketing:coupon:issue"],
  ["GET", /^\/ops\/(coupons|coupon-issues)/, "marketing:coupon:read"],
  ["*", /^\/ops\/coupons/, "marketing:coupon:update"],
  ["GET", /^\/ops\/campaigns/, "marketing:campaign:read"],
  ["*", /^\/ops\/campaigns/, "marketing:campaign:update"],

  // ── 团购与求团 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/groups/, "group:campaign:read"],
  ["*", /^\/ops\/groups/, "group:campaign:audit"],
  ["POST", /^\/ops\/quotes\/[^/]+\/(price|breach)$/, "group:demand:assign",
    "改价与判毁约都会写进商家信用档案，与只读需求单池分开"],
  ["GET", /^\/ops\/quotes/, "group:demand:read"],

  // ── 结算与资金 ─────────────────────────────────────────────────────────
  ["POST", /^\/ops\/payables\/[^/]+\/paid$/, "finance:payout:execute",
    "登记付款是财务在网银付款的依据 —— 与「看结算单」同码是内控问题，不是粒度问题"],
  ["POST", /^\/ops\/payables\/[^/]+\/no-invoice$/, "finance:invoice:verify",
    "标记无票 = 接受这笔支出不能税前列支，是税务判断"],
  ["POST", /^\/ops\/payables\/[^/]+\/confirm$/, "finance:settle:execute"],
  ["GET", /^\/ops\/(settlements|split-records|payables)/, "finance:settle:read"],
  ["GET", /^\/ops\/(purchase-invoices|finance\/invoice-title)/, "finance:invoice:read"],
  ["*", /^\/ops\/(purchase-invoices|finance\/invoice-title)/, "finance:invoice:verify"],
  // 支付对账差异（并发会话 2026-08-12 新增）。**这条是守卫抓出来的** ——
  // 它们刚落进 settle:manage，矩阵当场报 FINANCE「多出来 4 条」。
  // 处理差异会改账，与只读覆盖率分开
  ["GET", /^\/ops\/payments\/recon/, "finance:recon:read"],
  ["*", /^\/ops\/payments\/recon/, "finance:recon:resolve"],
  ["GET", /^\/ops\/settle\/fee-rules/, "finance:rate:read"],
  ["*", /^\/ops\/settle\/fee-rules/, "finance:rate:update"],

  // ── 评价 ───────────────────────────────────────────────────────────────
  ["GET", /^\/ops\/review-score-config/, "review:score:read"],
  ["POST", /^\/ops\/review-score-config/, "review:score:update"],
  ["GET", /^\/ops\/(reviews|review-appeals)/, "review:review:read"],
  ["*", /^\/ops\/(reviews|review-appeals)/, "review:review:audit"],

  // ── 消息与客服 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/(msg-templates|notify-quota)/, "message:template:read"],
  ["*", /^\/ops\/(msg-templates|notify-quota)/, "message:template:update"],
  ["GET", /^\/ops\/tickets/, "message:ticket:read"],
  ["*", /^\/ops\/tickets/, "message:ticket:handle"],

  // ── 社区与网点 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/pickups/, "community:pickup:read"],
  ["*", /^\/ops\/pickups/, "community:pickup:update"],
  ["GET", /^\/ops\/communities/, "community:community:read"],
  ["*", /^\/ops\/communities/, "community:community:update"],
  ["GET", /^\/ops\/regions/, "community:region:read",
    "行政区划是主数据，读它是选覆盖社区的前置 —— 与「改社区」分开"],

  // ── 内容与素材 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/materials/, "content:material:read"],
  ["*", /^\/ops\/materials/, "content:material:update"],
  // 兜底那条是 `*`，所以每个子资源的 GET 都要在它之前显式列出来 ——
  // 第一版漏了 questions / rankings，两条读落进了 update 码
  ["GET", /^\/ops\/contents\//, "content:material:read"],
  ["POST", /^\/ops\/contents\/posts/, "content:material:audit"],
  ["*", /^\/ops\/contents\//, "content:material:update"],

  // ── 员工与权限 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/perm\//, "iam:role:read"],
  ["*", /^\/ops\/perm\//, "iam:role:grant"],
  ["GET", /^\/ops\/staffs/, "iam:staff:read"],
  ["*", /^\/ops\/staffs/, "iam:staff:update"],
  ["GET", /^\/ops\/audit-log/, "iam:audit:read"],

  // ── 平台配置与主数据 ───────────────────────────────────────────────────
  ["GET", /^\/ops\/(industries|service-scopes)/, "system:industry:read"],
  ["*", /^\/ops\/(industries|service-scopes)/, "system:industry:update",
    "改的是「哪些行业能开小微」，改错一批商家进件被拒 —— 与外观配置不是一回事"],
  ["GET", /^\/ops\/(appearance|rule-texts)/, "system:theme:read"],
  ["*", /^\/ops\/(appearance|rule-texts)/, "system:theme:update"],
  ["GET", /^\/ops\/(feature-flags|markets)/, "system:param:read"],
  ["*", /^\/ops\/(feature-flags|markets)/, "system:param:update"],
];

/**
 * 后端有能力、ops-web 没有任何菜单入口的端点。
 *
 * **不是缺陷登记，是事实登记** —— 这些能力今天只能由超管通过接口用。
 * 放在这里是为了让「平台端没有入口」这件事有个地方能读到，
 * 而不是等某天有人问「保证金到底在哪儿加」。
 */
export const NO_UI_PREFIXES = [
  "/ops/admission/",              // 保证金、支付额度、准入策略
  "/ops/purchase-invoices",       // 进项发票核验
  "/ops/finance/invoice-title",   // 发票抬头
  "/ops/industries",              // 行业开关（能否开小微、是否强制积分）
  "/ops/service-scopes",          // 服务范围开关
  "/ops/notify-quota",            // 推送额度
  "/ops/stores/{storeNo}/business-mode", // 经营模式切换
  "/ops/merchants/{merchantNo}/store-modes",
];

/** 端点 → 码；命中不了返回 null（守卫会把它报出来） */
export function codeOf(method, path) {
  for (const [m, re, code] of RULES) {
    if ((m === "*" || m === method) && re.test(path)) return code;
  }
  return null;
}
