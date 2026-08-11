import type { Role } from "./auth";
import { UI_PERM_MAP, UNIMPLEMENTED } from "./perm-map";

// 权限码 `<模块>:<资源>:<动作>`。模块前缀与 lib/nav.ts 的 NavSection.module 一一对应
// （nav.test.ts 断言这条对应关系，改一处必须改两处）。
// 通配：'*' 全部；'merchant:*' 该模块全部。
//
// ⚠️ 这里只是**前端展示裁剪**。后端是权威（矩阵 §2.3：数据可见性由归属键裁剪，不靠前端隐藏）。
// ⚠️ 高危动作（授权/封禁/打款/分账/环境切换）单独发码，不要并进模块通配 —— 矩阵 §2.3 的
//    「高危权限」列就是这批；混进通配后「谁能动钱」在代码里就看不出来了。

const ROLE_PERMS: Record<Role, string[]> = {
  // 超管：账号、角色、权限、系统配置。全量。
  SUPER_ADMIN: ["*"],

  // 商品/类目运营：类目树、商品池、上下架、多语言文案
  PRODUCT_OPS: [
    "dashboard:overview:read",
    "product:*",
    "order:order:read",
    "content:material:read",
    "system:param:read",
  ],

  // 活动运营：券、满减、限时、拼团、新人礼、皮肤下发
  CAMPAIGN_OPS: [
    "dashboard:overview:read",
    "marketing:*",
    "growth:fission:read", "growth:fission:update",
    "group:campaign:read", "group:campaign:audit",
    "product:sku:read",
    "content:*",
    "system:theme:update",
  ],

  // 社区运营：社区网格、自提点建档与启停、履约调度
  COMMUNITY_OPS: [
    "dashboard:overview:read",
    "community:*",
    "fulfillment:*",
    "order:order:read",
    "merchant:merchant:read",
  ],

  // 商家运营（BD）：商家拉新与入驻审核、资质、类目授权、求团人肉撮合
  MERCHANT_BD: [
    "dashboard:overview:read",
    "merchant:merchant:read", "merchant:apply:audit", "merchant:category:grant",
    "merchant:verify:grant", "merchant:merchant:ban",
    "store:page:read", "store:qrcode:export",
    "group:demand:read", "group:demand:assign",
    "community:community:read",
    "product:sku:read",
  ],

  // 审核员：商品、评价、内容、凭证、图片审核（只看待审队列，无封禁权）
  AUDITOR: [
    "dashboard:overview:read",
    "product:sku:read", "product:sku:audit",
    "review:review:read", "review:review:audit", "review:score:update",
    "content:material:read", "content:material:audit",
    "store:page:read", "store:page:audit",
    "merchant:merchant:read",
  ],

  // 客服：工单、争议介入、代客操作、退款审批（阈值内）
  CS: [
    "dashboard:overview:read",
    "order:order:read", "order:order:export", "order:order:modify", "order:order:proxy",
    "aftersale:ticket:read", "aftersale:ticket:handle", "aftersale:refund:approve",
    "message:ticket:read", "message:ticket:handle", "message:faq:update",
    "review:review:read",
    "merchant:merchant:read",
    "fulfillment:redeem:read",
  ],

  // 财务/结算：分账、结算单、提现、发票、个税代扣（唯一持有打款/分账码的角色）
  FINANCE: [
    "dashboard:overview:read",
    "finance:settle:read", "finance:settle:execute", "finance:withdraw:approve",
    "finance:invoice:read", "finance:rate:update",
    "order:order:read", "order:order:export",
    "aftersale:ticket:read",
    "merchant:merchant:read",
  ],

  // 风控：刷单、异常裂变、恶意退款、黑名单（可拦截、封禁）
  RISK: [
    "dashboard:overview:read",
    "risk:*",
    "growth:attribution:read",
    "order:order:read",
    "merchant:merchant:read", "merchant:merchant:ban",
    "community:pickup:read",
  ],

  // 数据分析：只读脱敏，无任何写权
  ANALYST: [
    "dashboard:*",
    "order:order:read",
    "merchant:merchant:read",
    "finance:settle:read",
    "growth:attribution:read",
    "product:sku:read",
    "community:community:read",
  ],

  // 技术运维：配置、灰度、日志、Mock 开关、发版
  TECH_OPS: [
    "dashboard:overview:read",
    "system:*",
    "message:template:read", "message:template:update",
    "order:pay:read", "order:pay:repair",
    "growth:attribution:read",
  ],
};

function match(pattern: string, code: string): boolean {
  if (pattern === code) return true;
  if (pattern.endsWith("*")) return code.startsWith(pattern.slice(0, -1));
  return false;
}

/**
 * 按钮/操作级鉴权。**以后端下发的 perms 为准**。
 *
 * <p>前端的 UI 码（45 个，`模块:对象:动作`）与后端的权限码（14 个，`对象:动作`）
 * 粒度不同，先经 {@link UI_PERM_MAP} 翻译再查 —— 直接拿 UI 码去比后端 perms
 * 会全判 false，表现是**整个运营端所有按钮都消失**。
 *
 * <p>三种判 false 的情况，含义各不相同，但对用户是同一件事（看不见入口）：
 * <ul>
 *   <li>没登录 / perms 为空 —— 本来就该什么都看不见</li>
 *   <li>映射到 UNIMPLEMENTED —— 后端还没这块能力，入口不该存在</li>
 *   <li>后端 perms 里没有 —— 这个角色确实没被授权</li>
 * </ul>
 *
 * <p><b>映射表里查不到的码判 false 并告警</b>：那是漏登记，
 * 而漏登记的默认值必须是拒绝 —— 默认放行的话，一个手滑的新码会静默开一个口子。
 */
export function can(perms: string[] | undefined, code: string): boolean {
  if (!perms?.length) return false;

  /*
   * **先查映射，后判通配**。反过来的话超管会看到 UNIMPLEMENTED 的入口 ——
   * 而那些端点根本不存在，他点下去同样 404。
   * 「超管什么都能做」说的是权限，不是「后端还没写的功能他也能用」。
   */
  const mapped = UI_PERM_MAP[code];
  if (mapped === undefined) {
    // 漏登记。守卫（perm-map.test.ts）会在 CI 拦住，这里是运行时兜底
    if (process.env.NODE_ENV !== "production") {
      console.warn(`[perm] UI 码未登记进 UI_PERM_MAP，按无权限处理：${code}`);
    }
    return false;
  }
  if (mapped === UNIMPLEMENTED) return false;
  if (perms.includes("*")) return true; // 超管通配
  return perms.some((p) => match(p, mapped));
}

/**
 * 模块级（导航/页面）：这个模块下有没有任何一个 UI 码是放行的。
 *
 * <p>按 UI 码前缀找，逐个走 {@link can} —— 不直接拿模块名去比后端 perms：
 * 两边的模块划分不一样（前端 17 个模块，后端没有模块概念），比不上。
 */
export function canModule(perms: string[] | undefined, module: string): boolean {
  if (!perms?.length) return false;
  const prefix = module.toLowerCase() + ":";
  const codes = Object.keys(UI_PERM_MAP).filter((ui) => ui.toLowerCase().startsWith(prefix));
  /*
   * **这个模块下一个受控码都没有 = 它不受权限约束**（看板就是这样：
   * 登录后的首页，人人可见）。返回 false 的话，所有人登录后落到空导航。
   *
   * 与「UNIMPLEMENTED 不可见」不冲突：那是「这块能力后端没有」，
   * 这是「这块本来就不需要权限」。两者都不该靠 canModule 猜。
   */
  if (!codes.length) return true;
  // 超管也不例外：整域未开工的模块，菜单不该出现
  return codes.some((ui) => can(perms, ui));
}

/**
 * 角色 → **后端权限码**。逐字镜像后端 `Perms.ROLE_PERMS`。
 *
 * <p>为什么前端也要有一份：登录时后端会下发 `staff.perms`，判权用的是那个 ——
 * 这张表只在**没有真实登录态的地方**用：mock 登录、以及导航守卫里
 * 「这个菜单至少有一个角色能看见吗」这类断言。
 *
 * <p><b>它会漂移</b>，所以 `perm-map.test.ts` 直接读 Java 源码比对。
 * 不比对的话，后端给某个角色加了权限而这里没跟上，
 * 表现是「守卫说这个菜单没人能看见，而实际上有人能」——
 * 一条会让人删掉好功能的假警报。
 */
export const BACKEND_ROLE_PERMS: Record<string, string[]> = {
  SUPER_ADMIN: ["*"],
  // BD 要读社区才能审核（选覆盖小区），但不该改社区主数据
  MERCHANT_BD: ["merchant:audit", "order:view", "community:view", "quote:govern"],
  PRODUCT_OPS: ["goods:audit", "category:manage", "order:view", "community:view", "marketing:govern"],
  // ops-web 叫 CS，后端叫 SUPPORT（toOpsRole 翻译过一次）
  CS: ["order:view", "review:govern", "order:intervene", "community:view", "ticket:handle"],

  // ── 后端 2026-08-11 补齐的七个（此前 ops-web 有角色、后端没配置）──
  // 短得反常的那几个不是漏配：风控/技术运维/数据分析的核心能力后端还没有端点，
  // 详见 Perms.java 里逐条的说明。**凭空映射一个语义相近的码会让权限表看着是满的，
  // 而实际上什么都点不动** —— 或者顺手给出远超职责的权限。
  CAMPAIGN_OPS: ["marketing:govern", "order:view", "community:view"],
  COMMUNITY_OPS: ["industry:manage", "community:view", "order:view"],
  AUDITOR: ["goods:audit", "review:govern", "community:view"],
  FINANCE: ["settle:manage", "order:view"],
  // 风控域后端零端点，只能给「看单」——排查刷单的最低限度
  RISK: ["order:view"],
  // 矩阵写明「只读脱敏」，所以**故意不给 order:view**（那是完整订单）
  ANALYST: ["community:view"],
  // 矩阵那一行（配置、灰度、日志）现在对上两条；环境切换仍无端点
  TECH_OPS: ["audit:view", "platform:config"],
};

/** 这个角色实际拿到的后端权限码。没有登录态时用它推算 */
export function backendPermsOf(role: Role | undefined): string[] {
  return role ? (BACKEND_ROLE_PERMS[role] ?? []) : [];
}

/** 测试与「角色-权限对照」页用；页面不要直接读它做鉴权，走 can()。 */
export function permsOf(role: Role): string[] {
  return ROLE_PERMS[role] ?? [];
}

/**
 * 这个角色在**前端本地角色表**里有没有某个 UI 码。
 *
 * <p><b>只用于展示</b>（dev 的「角色 × 权限对照」页）。判权走 {@link can} ——
 * 它读的是后端下发的 perms，而这张本地表只是「设计上这个岗位该有什么」的记录。
 *
 * <p>两者会不一致，而<b>不一致本身就是要看的东西</b>：
 * 本地表说 BD 能改类目、后端没给他 category:manage，那要么是后端漏配，
 * 要么是本地表过期了。分成两个函数，就是为了让这种不一致看得见而不是被抹平。
 */
export function roleHas(role: Role | undefined, code: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => match(p, code)) ?? false;
}

/** 同上，模块级。仅供对照页展示 */
export function roleHasModule(role: Role | undefined, module: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => p === "*" || p === module || p.startsWith(module + ":")) ?? false;
}

export const ROLE_LABEL: Record<Role, string> = {
  SUPER_ADMIN: "超级管理员",
  PRODUCT_OPS: "商品运营",
  CAMPAIGN_OPS: "活动运营",
  COMMUNITY_OPS: "社区运营",
  MERCHANT_BD: "商家运营",
  AUDITOR: "审核员",
  CS: "客服",
  FINANCE: "财务",
  RISK: "风控",
  ANALYST: "数据分析",
  TECH_OPS: "技术运维",
};

/** 高危权限码：矩阵 §2.3「高危权限」列。发布前要能一眼数清谁持有它们。 */
export const CRITICAL_PERMS = [
  "iam:role:grant",
  "merchant:merchant:ban",
  "finance:settle:execute",
  "finance:withdraw:approve",
  "risk:blacklist:update",
  "system:env:switch",
] as const;
