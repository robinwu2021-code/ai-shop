// B 端 mock 实现。
//
// 与 C 端共用同一份 `@shared/mock/db` 的**代码与种子数据**：订单/商品/评价的结构与初始
// 数据只有一处定义，两端不会漂移。
//
// ⚠️ 但**运行时状态不共享**：mock 落盘走 uni storage，H5 下即 localStorage，按 origin 隔离。
// 两端跑在不同端口就是两份状态，所以「B 端核销 → C 端看到已完成」在 H5 双服务器下
// 验证不了 —— 那要等接真后端（见 TDD-b-app §4.4）。
//
// 约定同 C 端：真改 db、状态机强制、非法迁移抛错、写后 persist。
import {
  allGoods,
  assertTransition,
  db,
  delay,
  findGoodsSeed,
  buildGroupBuy,
  nextNo,
  paginate,
  pick,
  toGroupRequest,
  persist,
  pushMessage,
  toGoods,
  toCommunity,
  allCommunitySeeds,
} from "@shared/mock/db";
import { currentCurrency, money } from "@shared/utils/money";
// 能力位被拒要抛**带业务码**的错（70023），页面据此渲染示例态而不是错误页
import { ApiError } from "@shared/net/http-client";
import {
  CATEGORY_TYPE,
  MARKETS,
  POINTS,
  SETTLE,
  REVIEW_RULES,
  TEMPLATE_TO_TYPE,
  MERCHANT_LOGO_FALLBACK,
} from "@shared/utils/constants";
import { ensureDemoOrders } from "./demo-orders";
import { DELIVERY_SHAPE, fulfillmentsOf } from "@shared/strategies/order-view";

/**
 * 「要核销」的履约方式：自提点自提、邻居家自提、到店核销。
 *
 * **不再用状态区分**：`ARRIVED`（已到自提点）与 `SHIPPED`（已发货）曾是两个状态，
 * 其实是同一个 `FULFILLING` 乘上履约方式的两种展示。合并回一个之后，
 * 「待核销」这类筛选靠履约方式表达 —— 加一种要核销的履约（如到店核销）
 * 只需归进对应形态，状态一个不加。
 */
const PICKUP_LIKE = new Set<string>(
  fulfillmentsOf(DELIVERY_SHAPE.SELF_PICKUP, DELIVERY_SHAPE.SELF_SERVE),
);
import type { GoodsDraft, MerchantApi } from "./contract";

/** 本店积分开关。mock 内存态，真实实现在 usr_merchant.points_enabled */
let pointsEnabled = true;

/**
 * 发分服务费明细：一单一条，真实数据来自 `stl_bill.points_fee_minor`。
 * mock 里按已有订单折算，让 B 端能看到「一单一条」的形状。
 */
function pointsFeeRecords() {
  return db.orders.slice(0, 8).map((o) => ({
    settleNo: `ST${o.orderNo.slice(-8)}`,
    subOrderNo: o.orderNo,
    points: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio),
    feeMinor: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio / POINTS.perMinor),
    period: "202608",
    at: o.createdAt,
  }));
}

function pointsAccount() {
  const expense = pointsFeeRecords().reduce((s, r) => s + r.feeMinor, 0);
  return {
    periodExpenseMinor: expense,
    period: "2026-08",
    enabled: pointsEnabled,
    disabledReason: pointsEnabled ? undefined : "本店未开启积分",
    forced: false,
  };
}
import type { StaffLogRow } from "@shared/mock/db";
import type {
  MerchantPlan,
  CurrencyCode,
  MarketId,
  Goods,
  I18nText,
  MarketingCampaign,
  MerchantStaff,
  Order,
  PickingRow,
  SpecTemplate,
  StaffRole,
  Store,
  VerifyBatchResult,
  Category,
  CategoryType,
} from "@shared/types";

/** 当前登录商家；未入驻时抛错，页面据此引导去入驻 */
function requireMerchant(): string {
  if (!db.merchant.merchantNo) throw new Error("尚未入驻");
  return db.merchant.merchantNo;
}

function findOrder(orderNo: string): Order {
  const o = db.orders.find((x) => x.orderNo === orderNo);
  if (!o) throw new Error(`订单不存在：${orderNo}`);
  return o;
}

/**
 * 退款落账。与 C 端 `settleRefund` 同一套规则：订单置 REFUNDED + 收回已发积分 + 返还抵扣积分。
 * 两端各写一份是因为 mock 分端，真实后端只会有一处。
 */
function settleRefund(o: Order, label: string) {
  assertTransition(o.status, "REFUNDED");
  o.status = "REFUNDED";
  if (o.afterSale) {
    o.afterSale.status = "REFUNDED";
    o.afterSale.updatedAt = Date.now();
  }
  pushTimeline(o, label);
  pushMessage(
    "TRADE",
    "退款已到账",
    "款项已原路退回，到账时间以支付渠道为准",
    `/pages/order/index?orderNo=${o.orderNo}`,
  );
}

function pushTimeline(order: Order, label: string) {
  order.timeline.push({ status: order.status, label, at: Date.now() });
}

/**
 * 订单是否属于本商家。
 *
 * 拆单落地后（E3）**一单只属于一个商家**，直接比 `order.merchantNo` 即可 ——
 * 之前的「含即算」是没拆单时的将就：跨商家的单会同时出现在两家的列表里，
 * 各自都看到不属于自己的商品与金额。
 *
 * 兼容：拆单之前建的历史单没有 merchantNo，回退到按商品判断，避免旧数据凭空消失。
 */
function belongsToMerchant(o: Order, merchantNo: string): boolean {
  if (o.merchantNo) return o.merchantNo === merchantNo;
  return o.items.some((it) => {
    try {
      return findGoodsSeed(it.goodsNo).merchantNo === merchantNo;
    } catch {
      return false;
    }
  });
}

function myGoods(): Goods[] {
  const merchantNo = db.merchant.merchantNo;
  return allGoods().filter((g) => g.merchant.merchantNo === merchantNo);
}

// ------------------------------------------------ 跨店总览与对比（增值包 P2）

/**
 * mock 下的套餐档位。**开发期两条路都要能走到** ——
 * 跨店那两页有两种完全不同的样子（真实数据 / FREE 示例态），
 * 而恒返回数据的 mock 会让示例态那条路一次都没被看过，
 * 直到一个 FREE 商家点进去为止。
 *
 * 取值顺序：本地存储的显式开关 → 按门店额度推。
 * 默认（`storeQuota === 1`）就是 **FREE**，与生产一致（存量主体全部回填 FREE）。
 *
 * 切档位：控制台里 `uni.setStorageSync("mock:plan", "PRO")` 然后刷新。
 */
const MOCK_PLAN_KEY = "mock:plan";
type MockPlan = "FREE" | "PRO" | "CHAIN";

function mockPlan(): MockPlan {
  const saved = uni.getStorageSync(MOCK_PLAN_KEY) as string;
  if (saved === "FREE" || saved === "PRO" || saved === "CHAIN") return saved;
  return db.storeQuota > 1 ? "PRO" : "FREE";
}

/**
 * mock 下的三档定义。**与 V150 的种子逐字一致**（FREE 1/0、PRO 3/3、CHAIN 10/15）——
 * 自造一套额度的话，套餐页上的数字与建店时那道闸给出的数字对不上，
 * 而两处都是「真的」，谁也说不清哪个错。
 */
const MOCK_TIERS = [
  { planCode: "FREE", name: "孵化版", storeQuota: 1, staffQuota: 0, crossStoreStats: false, trialDays: 0 },
  { planCode: "PRO", name: "成长版", storeQuota: 3, staffQuota: 3, crossStoreStats: true, trialDays: 14 },
  { planCode: "CHAIN", name: "连锁版", storeQuota: 10, staffQuota: 15, crossStoreStats: true, trialDays: 14 },
] as const;

/** 试用开通时刻。有它才能算出「还剩几天」——试用期内店主会反复进来看这个数 */
const MOCK_TRIAL_KEY = "mock:plan:trial-at";

/**
 * 我的套餐视图。用量**现算**（与建店闸门同口径：只数营业中的店），
 * 不存一份计数器 —— 存了就会与门店列表对不上，而那是最容易被店主发现的不一致。
 */
function minePlan(): MerchantPlan {
  const code = mockPlan();
  const tier = MOCK_TIERS.find((t) => t.planCode === code) ?? MOCK_TIERS[0];
  const trialAt = Number(uni.getStorageSync(MOCK_TRIAL_KEY)) || 0;
  const trialUsed = trialAt > 0;
  // 可试用的目标档位：可试用且 sort 最小的那一档（MOCK_TIERS 已按 sort 排）。
  // 不写死 PRO —— 与后端同一条规则，两边各写一套的话开通的档位会不一样
  const trialTier = code === "FREE" && !trialUsed
    ? MOCK_TIERS.find((t) => t.trialDays > 0)
    : undefined;
  return {
    planCode: tier.planCode,
    planName: tier.name,
    status: "ACTIVE",
    startAt: trialAt || null,
    // 试用期算得出到期日；非试用的 mock 档位不设到期（免费档本来就不到期）
    expireAt: trialUsed && code !== "FREE" ? trialAt + tier.trialDays * 86_400_000 : null,
    storeQuota: tier.storeQuota,
    storeUsed: db.stores.filter((s) => s.status === "ACTIVE").length,
    staffQuota: tier.staffQuota,
    staffUsed: db.staff.filter((s) => !s.isOwner && s.status === "ACTIVE").length,
    crossStoreStats: tier.crossStoreStats,
    trialUsed,
    trialTier: trialTier?.planCode ?? null,
    trialDays: trialTier?.trialDays ?? null,
    // mock 里没有降级链路（那是运营端扫描干的），恒空 ——
    // 空数组而不是 undefined：页面按 length 判断要不要显示横幅
    suspendedStores: [],
    tiers: MOCK_TIERS.map((t) => ({ ...t, current: t.planCode === code })),
  };
}

/**
 * 能力位门禁。**抛的是带业务码的 ApiError，不是裸 Error** ——
 * 页面靠 `code === 70023` 区分「这是付费功能」与「这个接口坏了」，
 * 而裸 Error 在端上与网络故障长得一模一样，示例态就永远不会出现。
 */
function requireCrossStoreStats(): void {
  const plan = mockPlan();
  if (plan !== "FREE") return;
  throw new ApiError(
    70023,
    `跨店总览与对比是成长版 / 连锁版的能力，当前是 ${plan} 版。`
      + "升级套餐后，多家店的今日订单、销售额与待办就能并排看",
  );
}

/**
 * PRO/CHAIN 档下的门店。**不够两家就补两家** ——
 * 种子里只有一家店（= FREE 的额度），而这两页的自变量正是门店数：
 * 一行的「跨店对比」验证不了任何东西（列宽、排序、默认店标记、停用店）。
 *
 * 只在显式切到 PRO/CHAIN 之后才补，默认档位下 db 一个字节都不动。
 * 额度跟着一起放开，否则门店管理页会显示「已达上限」而列表里有三家。
 */
function ensureCrossStoreDemoStores(): void {
  if (db.stores.length >= 3) return;
  const extra = [
    { storeNo: "ST-MOCK-2", name: "张记粮油 · 翠苑店", address: "翠苑一区 12 幢底商" },
    // 第三家刻意是**停用**的：停用店仍要出现在总览里（否则店主以为店被删了）,
    // 而它长什么样只有在有这么一行时才看得见
    { storeNo: "ST-MOCK-3", name: "张记粮油 · 文三店", address: "文三路 100 号" },
  ];
  for (const [i, s] of extra.entries()) {
    if (db.stores.some((x) => x.storeNo === s.storeNo)) continue;
    db.stores.push({
      ...s,
      isDefault: false,
      status: i === 1 ? "READONLY" : "ACTIVE",
      payReady: i !== 1,
      staffCount: 0,
    });
  }
  db.storeQuota = Math.max(db.storeQuota, db.stores.length);
  persist();
}

/** 这家店的评分：按「这家店的单」反推它对应的评价（mock 没有 store_no） */
function storeRating(rows: { orderNo: string }[], reviews: { orderNo?: string; rating: number }[]) {
  const nos = new Set(rows.map((o) => o.orderNo));
  const mine = reviews.filter((r) => r.orderNo && nos.has(r.orderNo));
  return {
    rating: mine.length
      ? Number((mine.reduce((s, r) => s + r.rating, 0) / mine.length).toFixed(1))
      : 0,
    // 0 = 暂无评价。端上按条数判空，不按分值 —— 0 分与「没人评过」是两件事
    ratingCount: mine.length,
  };
}

/** 这两页看到的门店列表（顺序同 mStoreList：默认店在前由种子保证） */
function crossStoreStores(): Store[] {
  ensureCrossStoreDemoStores();
  return db.stores;
}

/** 计入统计的订单：我的、且未取消（与 mStats 同一口径） */
function crossStoreOrders(): Order[] {
  const merchantNo = db.merchant.merchantNo;
  return db.orders.filter((o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED");
}

function sumPayable(list: Order[]): number {
  return list.reduce((s, o) => s + o.amount.payableMinor, 0);
}

/** 把一个单号稳定地散到某一家店上 —— 同一个号每次都落到同一家，刷新不跳 */
function hashPick(key: string, stores: Store[]): string {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) % 100_000;
  return stores[h % stores.length]?.storeNo ?? "";
}

/**
 * 这一单算哪家店的。
 *
 * mock 的订单种子上**没有 storeNo**（它比多门店早），而按店分组是这两页的全部内容。
 * 所以这里按单号散列指派，且**只是指派，不是编造**：各店之和恒等于主体总数，
 * 「总览说 3 单、点进去只有 2 单」那类矛盾在 mock 上也不会出现。
 * 真实后端读的是 `ord_order.store_no`，历史空值单不计入任何一行。
 */
function storeOfOrder(o: Order, stores: Store[]): string {
  return hashPick(o.orderNo, stores);
}

/** 规格名与选项仍是单语录入（模板本身跨语言，见 M8 未覆盖项），照旧抄三语 */
function toI18n(text: string) {
  return { "zh-CN": text, en: text, ar: text };
}

/**
 * 商品文案三语落库。**留空的语言回落中文，但不假装它被翻译过** ——
 * 机翻的商品名会直接出现在下单页与小票上，错了没人兜底；
 * 回落至少是诚实的，而且平台端能按「未翻译」筛出来补。
 */
function fillI18n(text: I18nText): I18nText {
  const zh = text["zh-CN"].trim();
  return {
    "zh-CN": zh,
    en: text.en.trim() || zh,
    ar: text.ar.trim() || zh,
  };
}

/** 按售后单号取订单。售后是独立资源，mock 里仍存在 Order 上 —— 寻址方式与契约一致即可 */
function findOrderByAfterSale(afterSaleNo: string): Order {
  const o = db.orders.find((x) => x.afterSale?.afterSaleNo === afterSaleNo);
  if (!o) throw new Error("售后单不存在");
  return o;
}

/** 取「待处理」的售后单 —— 同意与驳回的前置校验完全相同，抽出来免得两处各写一遍 */
function takePendingAfterSale(afterSaleNo: string): Order {
  const o = findOrderByAfterSale(afterSaleNo);
  // 判据是**售后单**的状态，不是订单的 —— 订单在售后期间保持原状态
  if (o.afterSale!.status !== "APPLIED") throw new Error("该售后已处理过");
  return o;
}

function requireStore(storeNo: string) {
  const s = db.stores.find((x) => x.storeNo === storeNo);
  if (!s) throw new Error("门店不存在");
  return s;
}

function requireStaff(mchAccountNo: string) {
  const s = db.staff.find((x) => x.mchAccountNo === mchAccountNo);
  if (!s) throw new Error("员工不存在");
  return s;
}

/**
 * 记一条员工与授权的变更（B-11.10.3）。
 *
 * mock 里的操作人恒为老板 —— 演示会话就是老板，而这条日志的价值在于
 * **它是真的在写**：页面上看到的每一行都来自刚才那次操作，不是种子数据。
 */
function logStaff(
  target: MerchantStaff,
  action: string,
  storeName?: string,
  role?: StaffRole,
  detail?: string,
) {
  const owner = db.staff.find((x) => x.isOwner);
  db.staffLogs.unshift({
    targetAccountNo: target.mchAccountNo,
    // 认人用姓名 —— 审计里一列号码，三个月后谁也想不起那是谁
    actor: owner?.displayName || owner?.loginPhone,
    targetName: target.displayName || target.loginPhone,
    action,
    storeName,
    role,
    detail,
    at: Date.now(),
  });
}

/** 有几个人持有这个角色 —— 删除按钮的依据 */
function usersOfRole(roleCode: string) {
  return db.staff.filter((s) => s.roles.some((r) => r.role === roleCode)).length;
}

/** 权限码 → 中文。**取自 db.permLabels（后端下发的那份）**，不在页面里再抄一遍 */
/**
 * 主单 → 履约台视图（`PickupOrder`）。
 *
 * 自提点上的四个端点（列表、到货登记、按码搜、上报短少）**返回的都是这一份**，
 * 而 mock 此前各自返回主单 —— 页面于是照着主单写（拿 `orderNo`、比 `PAID`），
 * 真机上全部落空。一个 helper 保证四处不会再各写一份。
 */
function pickupView(o: (typeof db.orders)[number]) {
  return {
    subOrderNo: o.orderNo,
    verifyCode: o.verifyCode ?? "",
    buyerNickname: o.buyerNickname,
    merchantName: o.merchantName,
    // mock 用主单状态，后端用子单那一套：没取走的一律 WAIT_FULFILL
    status:
      o.status === "COMPLETED" || o.status === "CANCELLED" || o.status === "REFUNDED"
        ? o.status
        : ("WAIT_FULFILL" as const),
    pickupNo: o.pickupNo,
    items: o.items.map((i) => ({
      goodsNo: i.goodsNo,
      title: i.title,
      spec: i.spec,
      qty: i.qty,
    })),
  };
}

/** 角色码 → 显示名。审计那行字是给老板看的，他没见过 `MANAGER`，更没见过 `R-MOCK-1` */
function roleName(code: string) {
  return db.roles.find((r) => r.roleCode === code)?.name ?? code;
}

function permLabel(code: string) {
  return db.permLabels[code] ?? code;
}

/**
 * 自定义角色不能带 `biz:store:admin` —— 与后端同一条边界。
 *
 * mock 也要拦：只有后端拦的话，开发期能建出一个「副老板」角色，
 * 连上真后端才发现建不了，而那时界面已经按「能建」画好了。
 */
function assertAssignable(perms: string[]) {
  const bad = perms.filter((p) => p === "biz:store:admin" || p === "*");
  if (bad.length) throw new Error("管员工的权限不能授给自定义角色");
  if (!perms.length) throw new Error("至少勾一项权限");
  return [...perms];
}

function logStaffRole(action: string, roleCode: string, detail: string) {
  // role 在类型上是 StaffRole（预置码的联合），而自定义角色码是运行期生成的业务键。
  // 审计里存的是「哪个角色」而不是「哪个预置角色」—— 这里显式放宽，
  // 与后端一致（那边 mch_staff_log.role 也只是一列字符串）
  const owner = db.staff.find((x) => x.isOwner);
  db.staffLogs.unshift({
    targetAccountNo: "",
    actor: owner?.displayName || owner?.loginPhone,
    action,
    role: roleCode as StaffLogRow["role"],
    detail,
    at: Date.now(),
  });
}

/**
 * 脱敏 —— **只用在审计文案里**（与后端同一处口径）。
 *
 * 员工档案上的号码不脱敏：它就是登录用户名，老板要能核对、能改。
 * 但日志是长期留存、可能被导出的文本，那里不需要一个完整号码。
 */
function maskPhone(phone: string) {
  return phone.length < 7 ? phone : `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

/**
 * 类目节点上的 `template`（形态的另一套码）。**深度优先找，找不到返回 undefined。**
 *
 * <p>建品时用它把形态算出来，与真后端的 `CategoryServiceImpl.categoryTypeOf` 同一条规则。
 * mock 自己算而不是抄请求体：请求体里已经没有 `type` 了，而「mock 上建出来是生鲜、
 * 连真后端变成日用品」正是这套 mock 最该防的那类错配。
 */
function findCategoryTemplate(categoryNo: string | undefined): string | undefined {
  if (!categoryNo) return undefined;
  const walk = (nodes: Category[]): string | undefined => {
    for (const n of nodes) {
      if (n.categoryNo === categoryNo) return n.template;
      const hit = walk(n.children ?? []);
      if (hit) return hit;
    }
    return undefined;
  };
  return walk(db.categories as unknown as Category[]);
}

let mockPassword = "";

/** 类目树压平成 categoryNo → 节点。两处要按编号取名字，各写一遍迟早会分叉 */
function flatCategories(nodes: Category[], into = new Map<string, Category>()) {
  for (const n of nodes) {
    into.set(n.categoryNo, n);
    if (n.children?.length) flatCategories(n.children, into);
  }
  return into;
}


/** 门店送货方式的 mock 存储（内存即够：mock 不需要跨会话） */
const mockFulfillment: Record<string, import("@shared/types").StoreFulfillment> = {};
/** 本店自建的取货点（P1）：mock 里只活在内存，刷新即清 */
const mockSelfBuilt: import("@shared/types").PickupCandidate[] = [];

/**
 * 运行时新建一条聚落种子。
 *
 * <p><b>名字与地址必须是 I18nText</b>（`{ "zh-CN", en, ar }`）—— 种子里所有文案都是这个形状，
 * `toCommunity` 用 `pick()` 取值。这里塞裸字符串不会报错，但取出来是 `undefined`：
 * 顶部清单显示成一串社区号、查重按名字比对永远不相等（于是同一个小区能加进去两次）、
 * 跨级搜索里 `name.includes()` 直接抛异常 —— 三个症状没有一个指向真正的原因。
 */
function newCommunitySeed(name: string, address?: string, streetCode?: string, kind = "ESTATE") {
  const i18n = (v: string) => ({ "zh-CN": v, en: v, ar: v });
  return {
    communityNo: `C${Date.now()}`,
    cityCode: "3301",
    regionCode: streetCode ?? "330106002",
    kind,
    name: i18n(name),
    address: i18n(address ?? ""),
    distance: 0,
    pickups: [],
  } as unknown as (typeof db.communitySeeds)[number];
}

export const mockApi: MerchantApi = {
  // ---------------------------------------------------------------- 账号与入驻
  async mLogin(req) {
    // 注册的合规前置：没勾协议不建号。真实后端要把同意时间与协议版本号一起留痕
    if (!req.agreed) throw new Error("请先阅读并同意协议");

    // 手机号是商家账号的主标识；第三方登录拿到的是 code，手机号由服务端换取后回填。
    // mock 无服务端换号能力，这里用占位号让流程能继续，并在 profile 上标出待补绑。
    const isPhone = req.grantType === "PHONE_OTP";
    if (isPhone && !/^\d{11}$/.test(req.principal)) throw new Error("手机号格式不对");
    db.merchant.phone = isPhone ? req.principal : db.merchant.phone || "";
    db.merchant.loginBy = req.grantType;
    persist();
    return delay({ token: `mock-b-token-${Date.now()}`, merchant: { ...db.merchant } });
  },

  async mSendOtp(phone: string) {
    if (!/^\d{11}$/.test(phone)) throw new Error("手机号格式不对");
    await delay(undefined);
  },

  /** mock 里密码只存在内存：它只为让「设了密码 → 能用密码登录」这条链在 mock 下走得通 */
  async mSetPassword(password: string) {
    if (password.length < 6) throw new Error("密码至少 6 位");
    mockPassword = password;
    await delay(undefined);
  },

  async mHasPassword() {
    await delay(undefined);
    return { hasPassword: mockPassword.length > 0 };
  },

  async mStaffLogin(payload) {
    /*
     * mock 也照「非在职员工返回 403」来：恒成功的话，
     * 「输错号码时该显示什么」这段永远走不到，而它是员工登录最常见的一次失败。
     */
    const staff = db.staff.find(
      (x) => x.status === "ACTIVE" && x.loginPhone === payload.phone,
    );
    if (!staff) throw new Error("该手机号不是本店员工");
    return delay({ token: "demo-staff-token", merchant: { ...db.merchant } });
  },

  async mProfile() {
    return delay({ ...db.merchant });
  },

  async mApply(payload) {
    // 一份记录同时承载内容与进度 —— 后端 usr_merchant_apply 就是一行
    db.merchantApply = {
      ...payload,
      applyNo: db.merchantApply?.applyNo || nextNo("MA"),
      status: "PENDING",
      createdAt: Date.now(),
    };
    db.merchant = {
      ...db.merchant,
      // 提交后是 APPLYING（已交，等着）而不是 REVIEWING（有人在看）——
      // 此刻还没有任何人受理，报 REVIEWING 是替运营做了一个没发生的承诺
      merchantNo: db.merchant.merchantNo || nextNo("M"),
      name: payload.name,
      subject: payload.subject,
      status: "APPLYING",
    };
    persist();
    return delay({ ...db.merchant });
  },

  async mApplyDraft() {
    return delay(db.merchantApply ? { ...db.merchantApply } : null);
  },

  // ---------------------------------------------------------------- 店铺与获客
  async mStore() {
    const out = { ...db.store } as typeof db.store & { announcementUntil?: number | null };
    // 与保存那一处同一条判断：过期的公告读出来就是空的
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out);
  },

  async mMasterData() {
    /*
     * mock 的行业白名单要**带一个不允许小微的行业**（线上服务），
     * 否则「行业决定能不能选小微」这条联动在 mock 下永远看不出效果，
     * 而它正是选错主体导致进件被拒的地方。
     */
    return delay({
      industries: [
        { industry: "FRESH", name: "生鲜果蔬", microAllowed: true },
        { industry: "GROCERY", name: "粮油日用", microAllowed: true },
        { industry: "BAKERY", name: "烘焙熟食", microAllowed: true },
        { industry: "ONLINE_SERVICE", name: "线上服务", microAllowed: false },
      ],
      subjects: [
        { subjectType: "NATURAL_PERSON" as const, name: "自然人", needLicense: false,
          industryGated: true, settleAccountType: "PERSONAL_BANK_CARD" as const },
        { subjectType: "INDIVIDUAL" as const, name: "个体工商户", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
        { subjectType: "ENTERPRISE" as const, name: "企业", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
      ],
      channels: [{ payChannel: "WECHAT", name: "微信支付", enabled: true, payMethods: ["JSAPI"] }],
      /*
       * **只给两档，与一期真实配置一致**（自营模式下 PLATFORM 没开）。
       * mock 里把三档全给上的话，「端上照下发的档位渲染」这件事就演示不出来 ——
       * 界面看着和写死三档完全一样，而真环境里第三档点下去会被拒。
       */
      serviceScopes: ["COMMUNITY", "CITY"] as const,
    });
  },

  async mPayments() {
    return delay([{ ...db.payment }]);
  },

  async mSubmitPayment(payload) {
    /*
     * mock 也走「资料齐了才通过」这条规则：恒成功的 mock 会让端上
     * 「缺什么就说缺什么」那段界面永远走不到，而它正是商家最需要的一段。
     */
    if (!payload.settleAccount) {
      throw new Error("还差结算账户");
    }
    const tail = payload.settleAccount.slice(-4);
    db.payment = {
      ...db.payment,
      applyStatus: "ACTIVE",
      canReceiveMoney: true,
      payMerchantNo: "PM-MOCK-0001",
      settleAccountType: payload.settleAccountType ?? "MERCHANT_ID",
      // 明文不进本地库 —— mock 也照这条来，免得端上养成读明文的习惯
      settleAccountMasked: `****${tail}`,
      missing: [],
      activatedAt: Date.now(),
    };
    persist();
    return delay({ ...db.payment });
  },

  async mRefreshPayment() {
    return delay({ ...db.payment });
  },

  async mStoreCategories(storeNo) {
    return delay((db.storeCategories[storeNo] ?? []).map((c) => ({ ...c })));
  },

  async mSaveStoreCategories(storeNo, items) {
    const before = db.storeCategories[storeNo] ?? [];
    /*
     * mock 也照真库拒：**撤掉一个底下还有商品的货架**要报错。
     * 恒成功的 mock 会让这条最常被触发的拒绝在开发期永远走不到 ——
     * 而它正是「店铺页里消失、商品列表里还在」那种状态的唯一防线。
     */
    const kept = new Set(items.map((i) => i.categoryNo));
    const blocked = before.find((c) => !kept.has(c.categoryNo) && c.goodsCount > 0);
    if (blocked) throw new Error(`「${blocked.name}」下还有 ${blocked.goodsCount} 件商品，请先移走`);

    const flat = flatCategories(db.categories);
    const next = items.map((i, idx) => {
      const platformName = flat.get(i.categoryNo)?.name ?? i.categoryNo;
      const old = before.find((c) => c.categoryNo === i.categoryNo);
      return {
        categoryNo: i.categoryNo,
        name: i.displayName?.trim() || platformName,
        platformName,
        displayName: i.displayName?.trim() || undefined,
        sort: i.sort ?? idx,
        goodsCount: old?.goodsCount ?? 0,
      };
    });
    db.storeCategories[storeNo] = next;
    return delay(next.map((c) => ({ ...c })));
  },

        // 三个数分开：在售/待审是「卖得怎么样」，goodsCount 是「能不能撤架」
        onSaleCount: old?.onSaleCount ?? 0,
        pendingCount: old?.pendingCount ?? 0,
  // 门店送货方式（方案 v4）：mock 里每店一份，默认「自提两路开」——与生产播种同一映射
  async mStoreFulfillment(storeNo) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const saved = mockFulfillment[no];
    return delay(
      saved ?? {
        storeNo: no,
        channels: [
          { channel: "STORE_PICKUP", enabled: true, denied: false },
          { channel: "NEIGHBOR_PICKUP", enabled: true, denied: false },
          { channel: "MERCHANT_DELIVERY", enabled: false, denied: false },
          { channel: "EXPRESS", enabled: false, denied: false },
        ],
      },
    );
  },

  async mSaveStoreFulfillment(storeNo, payload) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const saved = mockFulfillment[no];
    // mock 也照写入口的硬规则拒：一路都不开的店等于开不了张
    if (!payload.channels.some((c) => c.enabled)) {
      throw new Error("至少开启一种送货方式");
    }
    const next = {
      storeNo: no,
      channels: payload.channels.map((c) => ({
        channel: c.channel as import("@shared/types").FulfillmentType,
        enabled: c.enabled,
        denied: false,
        templateNo: c.templateNo ?? null,
        pickups: c.channel === "NEIGHBOR_PICKUP"
          ? (c.pickupNos
              ? c.pickupNos.map((no) => {
                  const own = mockSelfBuilt.find((p) => p.pickupNo === no);
                  return { pickupNo: no, name: own?.name ?? no, address: own?.address ?? null, type: "STORE" as const, status: own?.status ?? "ACTIVE" };
                })
              : saved?.channels.find((x) => x.channel === "NEIGHBOR_PICKUP")?.pickups ?? [])
          : undefined,
      })),
    };
    mockFulfillment[no] = next;
    return delay({ ...next });
  },

  async mStoreList() {
    return delay(db.stores.map((s) => ({ ...s })));
  },

  async mCreateStore(payload) {
    /*
     * mock 也照额度拒。恒成功的 mock 会让「超额」那段界面永远走不到，
     * 而它是多门店里最常被触发的一条路径 —— FREE 档只能有一家店。
     */
    if (db.stores.length >= db.storeQuota) {
      throw new Error(`当前套餐最多 ${db.storeQuota} 家门店`);
    }
    const store = {
      storeNo: `ST-MOCK-${db.stores.length + 1}`,
      name: payload.name,
      address: payload.address ?? "",
      isDefault: db.stores.length === 0,
      status: "ACTIVE" as const,
      payReady: true,
      staffCount: 0,
    };
    db.stores.push(store);
    persist();
    return delay({ ...store });
  },

  async mRenameStore(storeNo, payload) {
    const s = requireStore(storeNo);
    s.name = payload.name || s.name;
    if (payload.address !== undefined) s.address = payload.address;
    persist();
    return delay({ ...s });
  },

  async mSetStoreStatus(storeNo, active) {
    const s = requireStore(storeNo);
    // 默认店不能停用 —— 停掉之后「这个主体的店在哪」就没有答案了
    if (!active && s.isDefault) throw new Error("默认店不能停用，请先把默认标转给别家");
    s.status = active ? "ACTIVE" : "READONLY";
    persist();
    return delay({ ...s });
  },

  async mSetDefaultStore(storeNo) {
    const s = requireStore(storeNo);
    if (s.status !== "ACTIVE") throw new Error("已停用的店不能设为默认");
    db.stores.forEach((x) => { x.isDefault = x.storeNo === storeNo; });
    persist();
    return delay({ ...s });
  },

  async mSetStorePayment(storeNo, payMerchantNo) {
    const s = requireStore(storeNo);
    // 传空 = 回到主体默认号，是合法操作不是清空错误
    s.payMerchantNo = payMerchantNo || undefined;
    persist();
    return delay({ ...s });
  },

  async mStaffList() {
    return delay(db.staff.map((x) => ({ ...x })));
  },

  async mAddStaff(loginPhone, displayName) {
    if (!/^\d{11}$/.test(loginPhone)) throw new Error("请填 11 位手机号");
    const existing = db.staff.find((x) => x.loginPhone === loginPhone);
    if (existing) {
      // 离职再回来是常事：重新启用而不是报「已存在」
      existing.status = "ACTIVE";
      // 对老板来说这就是「把人加回来」，所以记 STAFF_ADD 而不是 ENABLE ——
      // 审计要还原他做了什么，不是还原代码走了哪个分支
      logStaff(existing, "STAFF_ADD", undefined, undefined,
        `重新启用已存在的员工 ${maskPhone(loginPhone)}`);
      persist();
      return delay({ ...existing });
    }
    const staff = {
      mchAccountNo: `SF-MOCK-${db.staff.length + 1}`,
      displayName: displayName?.trim() || undefined,
      // 号码就是登录用户名，完整存 —— 与后端同口径
      loginPhone,
      isOwner: false,
      status: "ACTIVE" as const,
      roles: [],
    };
    db.staff.push(staff);
    logStaff(staff, "STAFF_ADD", undefined, undefined, `新增员工 ${maskPhone(loginPhone)}`);
    persist();
    return delay({ ...staff });
  },

  async mSetStaffStatus(mchAccountNo, active) {
    const st = requireStaff(mchAccountNo);
    // 老板不能被停用 —— 那是个能把自己锁在门外的按钮
    if (st.isOwner && !active) throw new Error("老板不能被停用");
    st.status = active ? "ACTIVE" : "DISABLED";
    logStaff(st, active ? "STAFF_ENABLE" : "STAFF_DISABLE", undefined, undefined,
      active ? "启用员工" : "停用员工（门店授权保留）");
    persist();
    return delay({ ...st });
  },

  async mBizScope() {
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    // mock 里的演示会话恒为老板 —— 要体验受限角色请连真后端用员工账号登录。
    // 这里不编一个「假的店员」：那会让开发期看到的裁剪结果与真实的不一样
    return delay({
      merchantNo: db.merchant.merchantNo,
      currentStoreNo: home?.storeNo ?? "",
      owner: true,
      storeNos: db.stores.map((s) => s.storeNo),
      pickupNos: db.merchant.isPickupPoint ? ["PP-MOCK-1"] : [],
      groupNos: [],
      staffRoles: ["OWNER"],
      perms: ["*"],
      /*
       * **只给两张证**，不给全集：给全了「缺资质」这条路在开发期永远走不到，
       * 而它正是类目选择器上那个角标要表达的东西。
       * mock 商家能卖蔬菜与预包装食品，卖不了酒、肉、奶粉。
       */
      categoryCodes: ["FRESH_VEG", "PACKAGED_FOOD"],
    });
  },

  async mGrantStore(mchAccountNo, storeNo, role, granted) {
    const st = requireStaff(mchAccountNo);
    const store = requireStore(storeNo);
    /*
     * **增量式：只动这一个角色**（一人一店可多角色）。
     *
     * 原先是先把这家店的角色全 filter 掉再 push 一个 —— 那是覆盖式，
     * 老板想「再加一个配送员」会把「店员」冲掉，而且不报错。
     * mock 与后端必须同一套语义，否则开发期看到的是另一个产品。
     */
    const had = st.roles.some((r) => r.storeNo === storeNo && r.role === role);
    st.roles = st.roles.filter((r) => !(r.storeNo === storeNo && r.role === role));
    if (granted !== false) st.roles.push({ storeNo, storeName: store.name, role });
    // 撤销一个他本来就没有的角色是空操作，不留痕 —— 与后端同口径，
    // 否则日志里会出现一串「撤销了店长」而他从来不是店长
    if (granted !== false) {
      logStaff(st, "ROLE_GRANT", store.name, role, `授予 ${store.name} 的 ${roleName(role)}`);
    } else if (had) {
      logStaff(st, "ROLE_REVOKE", store.name, role, `撤销 ${store.name} 的 ${roleName(role)}`);
    }
    persist();
    return delay({ ...st });
  },

  /**
   * 员工与授权的变更记录（B-11.10.3）。倒序 —— 最近做的那一件最可能是要查的。
   */
  /**
   * 角色列表：6 个预置（只读）+ 自定义。
   *
   * 预置那份**与后端 V71 的 seed 同一套语义** —— mock 里编一份不一样的，
   * 开发期看到的角色能力就与真实的不同，而这正是最不该分岔的地方。
   */
  async mRoles() {
    return delay(db.roles.map((r) => ({ ...r, usedBy: usersOfRole(r.roleCode) })));
  },

  /**
   * 可勾的权限点：**db.permLabels 全表减掉 `biz:store:admin`** ——
   * 与后端 `BizPerms.assignableCodes()` 同一条口径（那边也是全表减一条）。
   */
  async mRolePerms() {
    return delay(
      Object.entries(db.permLabels)
        .filter(([code]) => code !== "biz:store:admin")
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([code, label]) => ({ code, label })),
    );
  },

  async mCreateRole(payload) {
    const perms = assertAssignable(payload.perms);
    const role = {
      roleCode: `R-MOCK-${db.roles.length + 1}`,
      name: payload.name.trim(),
      builtin: false,
      perms,
      permLabels: perms.map(permLabel),
      usedBy: 0,
    };
    db.roles.push(role);
    logStaffRole("ROLE_CREATE", role.roleCode, `新建角色「${role.name}」`);
    persist();
    return delay({ ...role });
  },

  async mUpdateRole(roleCode, payload) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    // 预置只读：与后端同口径，要改先复制一份
    if (role.builtin) throw new Error("平台预置角色不可修改，请复制为自定义角色");
    const perms = assertAssignable(payload.perms);
    role.name = payload.name.trim();
    role.perms = perms;
    role.permLabels = perms.map(permLabel);
    logStaffRole("ROLE_UPDATE", roleCode, `角色「${role.name}」权限已更新`);
    persist();
    return delay({ ...role, usedBy: usersOfRole(roleCode) });
  },

  async mDeleteRole(roleCode) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    if (role.builtin) throw new Error("平台预置角色不可删除");
    const used = usersOfRole(roleCode);
    // 还有人在用就不许删 —— 删了那些人的权限凭空消失，而他们看不到任何解释
    if (used > 0) throw new Error(`还有 ${used} 人在用这个角色，先把他们撤下来`);
    db.roles = db.roles.filter((r) => r.roleCode !== roleCode);
    logStaffRole("ROLE_DELETE", roleCode, `删除角色「${role.name}」`);
    persist();
    return delay(undefined as unknown as void);
  },

  async mStaffLogs(mchAccountNo) {
    const all = db.staffLogs ?? [];
    return delay(
      [...all]
        .filter((l) => !mchAccountNo || l.targetAccountNo === mchAccountNo)
        .sort((a, b) => b.at - a.at),
    );
  },

  async mCommunities() {
    return delay(allCommunitySeeds().map(toCommunity));
  },

  async mRegions(parent) {
    // 恒定只给启用的 —— 与后端 /biz/regions 同口径（停用的是运营的维护对象）
    return delay(
      db.regionSeeds.filter((r) => r.enabled && (parent ? r.parentCode === parent : !r.parentCode)),
    );
  },

  /**
   * 村名词典。mock 里给北山街道配了两条官方村级（regionSeeds），
   * 词典就查它们 —— 与后端同口径：按街道过滤 + 名称包含。
   */
  // ---- P1：跨级搜索 / 路径 / 关路清单 / 取货点 ----
  async mRegionSearch(kw) {
    const q = (kw ?? "").trim();
    const pathOf = (code?: string): string => {
      const chain: string[] = [];
      let cur = code ? db.regionSeeds.find((r) => r.regionCode === code) : undefined;
      while (cur) {
        chain.unshift(cur.name);
        cur = cur.parentCode ? db.regionSeeds.find((r) => r.regionCode === cur!.parentCode) : undefined;
      }
      return chain.join(" / ");
    };
    /*
     * **四级都搜（省也搜），并且按级配额** —— 与后端 RegionService#search 同一口径。
     * 曾经这里和后端都把省排除在外、又共用一份 LIMIT，于是搜「山西」一条也没有、
     * 搜「运城」被街道占满；mock 不跟着改的话，开发期永远复现不出这两件事。
     */
    const QUOTA: Record<string, number> = { PROVINCE: 3, CITY: 5, DISTRICT: 8, STREET: 8 };
    const strength = (name: string) => (name === q ? 0 : name.startsWith(q) ? 1 : 2);
    const regions = !q ? [] : Object.keys(QUOTA).flatMap((level) => db.regionSeeds
      .filter((r) => r.enabled && r.level === level && r.name.includes(q))
      .sort((a, b) => strength(a.name) - strength(b.name) || a.regionCode.localeCompare(b.regionCode))
      .slice(0, QUOTA[level])
      .map((r) => ({ regionCode: r.regionCode, level: r.level, name: r.name, path: pathOf(r.parentCode) })));
    const communities = q.length < 2 ? [] : allCommunitySeeds().map(toCommunity)
      .filter((c) => c.name.includes(q))
      .slice(0, 30)
      .map((c) => ({ communityNo: c.communityNo, name: c.name, regionCode: c.regionCode, path: pathOf(c.regionCode) }));
    // 还没开通的官方村：与后端同口径 —— 已开通的走 communities，这里不重复出
    const openedNames = new Set(communities.map((c) => c.name));
    const villages = q.length < 2 ? [] : db.regionSeeds
      .filter((r) => r.level === "VILLAGE" && r.enabled && r.name.includes(q) && !openedNames.has(r.name))
      .slice(0, 20)
      .map((r) => ({
        regionCode: r.regionCode, name: r.name,
        streetCode: r.parentCode ?? "", path: pathOf(r.parentCode),
        latE6: null, lngE6: null,
      }));
    return delay({ regions, communities, villages });
  },

  async mGeoReverse(lat, lng) {
    return delay({ recommend: `阳光里小区南门（${lat.toFixed(4)}, ${lng.toFixed(4)}）`, address: "浙江省杭州市西湖区阳光里" });
  },

  async mGeoTips(kw) {
    const q = kw.trim();
    if (!q) return delay([]);
    // 两条带坐标、一条不带：端上要把没坐标的提示过滤掉
    return delay([
      { name: `${q}花园`, address: "西湖区文三路 88 号", adcode: "330106", latE6: 30279000, lngE6: 120131000, typecode: "120302" },
      { name: `${q}公寓`, address: "西湖区文二路 12 号", adcode: "330106", latE6: 30281000, lngE6: 120128000, typecode: "120302" },
      { name: `${q}路`, address: "西湖区", adcode: "330106", latE6: null, lngE6: null, typecode: "190301" },
    ]);
  },

  async mRegionPath(code) {
    const chain: import("@shared/types").Region[] = [];
    let cur = db.regionSeeds.find((r) => r.regionCode === code);
    while (cur) {
      chain.unshift(cur);
      cur = cur.parentCode ? db.regionSeeds.find((r) => r.regionCode === cur!.parentCode) : undefined;
    }
    return delay(chain.filter((r) => r.level !== "VILLAGE"));
  },

  async mFulfillmentImpact(_storeNo, channel) {
    const four = new Set(["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS"]);
    return delay(
      myGoods()
        .filter((g) => g.onSale)
        .filter((g) => {
          const ways = ((g as { fulfillments?: string[] }).fulfillments ?? []).filter((w) => four.has(w));
          return ways.length === 1 && ways[0] === channel;
        })
        .map((g) => ({ goodsNo: g.goodsNo, title: g.title })),
    );
  },

  async mPickupCandidates(storeNo) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const mine = mockSelfBuilt.filter((p) => p.ownerStoreNo === no);
    const nearby: import("@shared/types").PickupCandidate[] = allCommunitySeeds().flatMap((c) => {
      const vo = toCommunity(c);
      return (vo.pickups ?? []).map((p) => ({
        pickupNo: p.pickupNo,
        name: p.name,
        address: p.address,
        type: "STORE" as const,
        status: "ACTIVE",
        communityNo: vo.communityNo,
        communityName: vo.name,
        ownerStoreNo: null,
      }));
    });
    return delay([...mine, ...nearby]);
  },

  async mSelfBuildPickup(payload) {
    const no = payload.storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : payload.storeNo;
    if (mockSelfBuilt.some((p) => p.ownerStoreNo === no && p.name === payload.name.trim())) {
      throw new Error("这个取货点已经提交过了");
    }
    const created: import("@shared/types").PickupCandidate = {
      pickupNo: `PK${Date.now()}`,
      name: payload.name.trim(),
      address: payload.address.trim(),
      type: "STORE",
      status: "PENDING",
      communityNo: payload.communityNo ?? allCommunitySeeds()[0]!.communityNo,
      communityName: toCommunity(allCommunitySeeds()[0]!).name,
      ownerStoreNo: no,
    };
    mockSelfBuilt.unshift(created);
    return delay(created);
  },

  async mVillageDict(street, keyword) {
    const kw = (keyword ?? "").trim();
    return delay(
      db.regionSeeds
        .filter((r) => r.parentCode === street && r.level === "VILLAGE")
        .filter((r) => !kw || r.name.includes(kw))
        .slice(0, 50),
    );
  },

  async mOpenCommunityFromMap(payload) {
    /*
     * mock 也照真库查重：**同名就复用，不新建**。
     * 恒新建的 mock 会让「同一个小区被建成两条」这个最要命的后果在开发期永远走不到。
     * （坐标那道闸在真库里跑，mock 的种子没有坐标，比不了。）
     */
    const exist = allCommunitySeeds().map(toCommunity).find((c) => c.name === payload.name);
    if (exist) return delay(exist);
    const seed = newCommunitySeed(payload.name, payload.address, payload.streetCode, "ESTATE");
    db.communityOpened.push(seed);
    persist();
    return delay(toCommunity(seed));
  },

  async mApplyCommunity(payload) {
    const merchantNo = requireMerchant();
    if (db.communityApplies.some((a) => a.name === payload.name && a.status === "PENDING")) {
      // 与后端同口径：重复提报不会让它更快通过，只会让运营的队列里多一条一样的
      throw new Error("这个小区你已经提报过，正在等运营处理");
    }
    const apply = {
      applyNo: `CA${Date.now()}`,
      // 聚落模型：kind 与定位随提报走，通过时带进聚落
      kind: payload.kind === "VILLAGE" ? "VILLAGE" : "ESTATE",
      originCode: payload.originCode,
      latE6: payload.latE6,
      lngE6: payload.lngE6,
      merchantNo,
      merchantName: (() => {
        const n = db.merchantSeeds.find((m) => m.merchantNo === merchantNo)?.name;
        return n ? pick(n) : merchantNo;
      })(),
      ...payload,
      status: "PENDING" as const,
      submittedAt: Date.now(),
    };
    /*
     * **官方名录里的村免审直开**（与后端 submitApply 同口径）：名录本身就是权威，
     * 再让运营点一次「通过」只是把商家晾在那儿等一天。台账仍然留一条 APPROVED 的记录。
     * mock 不照做的话，端上「点一下村＝加入范围」这条路在开发期永远停在「等运营处理」。
     */
    if (payload.originCode) {
      const seed = newCommunitySeed(payload.name, payload.address, payload.regionCode, "VILLAGE");
      db.communityOpened.push(seed);
      const opened = { ...apply, status: "APPROVED" as const, communityNo: seed.communityNo };
      db.communityApplies.unshift(opened);
      persist();
      return delay({ ...opened });
    }
    db.communityApplies.unshift(apply);
    persist();
    return delay({ ...apply });
  },

  async mMyCommunityApplies() {
    const merchantNo = requireMerchant();
    return delay(db.communityApplies.filter((a) => a.merchantNo === merchantNo));
  },

  async mSaveStore(payload) {
    /*
     * 先脱响应式外壳（同 mSaveGoods）：`serviceAreas` 是页面 `form.value` 里的
     * reactive 代理数组，而 `delay()` 用 structuredClone 返回副本 —— Chrome **拒绝克隆 Proxy**，
     * 于是保存经营范围会弹一句「Failed to execute 'structuredClone'…」，
     * 商家看到的是保存失败，而他什么也没做错。深拷贝一次＝HTTP 上的 JSON 往返。
     */
    db.store = JSON.parse(JSON.stringify(payload)) as typeof db.store;
    persist();
    /*
     * **过期即空**：与后端 `MchStore.effectiveAnnouncement()` 同一条判断。
     * 只在真库里做的话，「昨天到货挂到今天」这个最要紧的后果在 mock 上看不见。
     */
    const out = { ...db.store } as typeof db.store & { announcementUntil?: number | null };
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out);
  },

  async mStoreQrcode() {
    const merchantNo = requireMerchant();
    // 落地页必须带 merchant_no —— 扫码进店的归因就靠它，进而决定费率档（ADR-004 §6）
    const url = `/pages/store/index?merchantNo=${merchantNo}&from=QR`;
    return delay({ url });
  },

  async mShareKit(goodsNo) {
    const merchantNo = requireMerchant();
    const name = db.merchant.name || "我的小店";
    if (goodsNo) {
      const g = toGoods(findGoodsSeed(goodsNo));
      return delay({
        text: `【${name}】${g.title} ${money(g.price)}，到店自提或送货上门，点开直接下单`,
        posterUrl: "",
      });
    }
    return delay({
      text: `【${name}】开在你家楼下，常买的东西点两下就能再来一单：/pages/store/index?merchantNo=${merchantNo}`,
      posterUrl: "",
    });
  },

  // ---------------------------------------------------------------- 工作台
  async mTodo() {
    const merchantNo = db.merchant.merchantNo;
    const mine = merchantNo ? db.orders.filter((o) => belongsToMerchant(o, merchantNo)) : [];
    const pickupNo = db.merchant.pickupNo;
    const atMyPoint = db.merchant.isPickupPoint
      ? db.orders.filter((o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo))
      : [];
    return delay({
      toShip: mine.filter((o) => o.fulfillment === "EXPRESS" && o.status === "PAID").length,
    /*
     * 「常用公告」由**服务端**维护（去重 + 最近的排最前 + 最多 5 条），端上只读 ——
     * mock 不照做的话，这一段在开发期永远是空的，而它正是这次改版的主角。
     */
    const now = (payload.announcement ?? "").trim();
    const prev = (before as { announcementRecent?: string[] }).announcementRecent ?? [];
    (db.store as { announcementRecent?: string[] }).announcementRecent =
      [now, ...prev.filter((x) => x && x !== now)].filter(Boolean).slice(0, 5);
      toDeliver: mine.filter((o) => o.fulfillment === "MERCHANT_DELIVERY" && o.status === "PAID").length,
      // 待备货按**我的单**算（mine），不是按我的自提点（atMyPoint）——
      // 买家常常选别家的点，两个数因此不相等。后端也是这个口径
      toStock: mine.filter((o) => o.fulfillment === "STORE_PICKUP" && o.status === "PAID").length,
      toVerify: atMyPoint.filter((o) => o.status === "FULFILLING").length,
      toPick: atMyPoint.filter((o) => o.status === "PAID").length,
      afterSale: mine.filter((o) => o.afterSale?.status === "APPLIED").length,
      toReply: db.reviews.filter((r) => r.merchantNo === merchantNo && !r.reply).length,
      quotable: 0, // 求团报价在 M3 批次交付
    });
  },

  async mStats() {
    const merchantNo = db.merchant.merchantNo;
    const mine = db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED",
    );
    const dayStart = new Date().setHours(0, 0, 0, 0);
  async mSaveAnnouncement(payload) {
    /*
     * 与真库同口径：只动公告与有效期，**不碰门面其它字段**；
     * 「常用」由服务端维护（去重 + 最近的排最前 + 最多 5 条）。
     */
    const st = db.store as typeof db.store & {
      announcementUntil?: number | null; announcementRecent?: string[];
    };
    const now = (payload.announcement ?? "").trim();
    st.announcementRecent = [now, ...(st.announcementRecent ?? []).filter((x) => x && x !== now)]
      .filter(Boolean).slice(0, 5);
    st.announcement = now;
    st.announcementUntil = payload.announcementUntil ?? null;
    persist();
    const out = { ...st };
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out as typeof db.store);
  },

    const today = mine.filter((o) => o.createdAt >= dayStart);
    const sum = (list: Order[]) => list.reduce((s, o) => s + o.amount.payableMinor, 0);
    const rs = db.reviews.filter((r) => r.merchantNo === merchantNo);
    const owned = mine.filter((o) => o.trafficSource === "MERCHANT_OWNED").length;
    return delay({
      todayOrders: today.length,
      todayGmvMinor: sum(today),
      monthOrders: mine.length,
      monthGmvMinor: sum(mine),
      currency: currentCurrency(),
      rating: rs.length ? Number((rs.reduce((s, r) => s + r.rating, 0) / rs.length).toFixed(1)) : 0,
      ratingCount: rs.length,
      ownedTrafficRate: mine.length ? owned / mine.length : 0,
    });
  },

  // ------------------------------------------------ 我的增值包（增值包 P4）
  async mMyPlan() {
    return delay(minePlan());
  },

  async mStartTrial() {
    const plan = minePlan();
    if (!plan.trialTier) {
      // 与后端同一个口径：三种拒因（已用过 / 已经是付费档 / 没配试用）合成一个
      throw new ApiError(10400, "当前不能开通试用");
    }
    /*
     * **真落库**：写进本地存储的档位开关 + 放开额度，重开小程序读回来还是试用中。
     * 只在内存里改的话，页面上「试用已开通」而下一次进来又回到 FREE ——
     * 而那正是这个功能最需要被看到的一段（试用期内他会反复进来看还剩几天）。
     */
    uni.setStorageSync(MOCK_PLAN_KEY, plan.trialTier);
    const tier = plan.tiers.find((t: { planCode: string }) => t.planCode === plan.trialTier);
    db.storeQuota = Math.max(db.storeQuota, tier?.storeQuota ?? 1);
    uni.setStorageSync(MOCK_TRIAL_KEY, Date.now());
    persist();
    return delay(minePlan());
  },

  // ------------------------------------------------ 跨店总览与对比（增值包 P2）
  async mCrossStoreOverview() {
    requireCrossStoreStats();
    const stores = crossStoreStores();
    const mine = crossStoreOrders();
    const dayStart = new Date().setHours(0, 0, 0, 0);

    return delay({
      currency: currentCurrency(),
      stores: stores.map((s) => {
        const rows = mine.filter((o) => storeOfOrder(o, stores) === s.storeNo);
        const today = rows.filter((o) => o.createdAt >= dayStart);
        const paid = (f: string) => rows.filter((o) => o.fulfillment === f && o.status === "PAID").length;
        return {
          storeNo: s.storeNo,
          storeName: s.name,
          isDefault: s.isDefault,
          status: s.status,
          todayOrders: today.length,
          todayGmvMinor: sumPayable(today),
          monthOrders: rows.length,
          monthGmvMinor: sumPayable(rows),
          toShip: paid("EXPRESS"),
          toDeliver: paid("MERCHANT_DELIVERY"),
          toStock: paid("STORE_PICKUP"),
        };
      }),
    });
  },

  async mCrossStoreCompare(days) {
    requireCrossStoreStats();
    // 与后端同一条夹取：端上传 0 或 99999 不该让整页报错
    const window = Math.min(Math.max(days ?? 30, 1), 365);
    const stores = crossStoreStores();
    const since = Date.now() - window * 86_400_000;
    const mine = crossStoreOrders().filter((o) => o.createdAt >= since);
    const rs = db.reviews.filter((r) => r.merchantNo === db.merchant.merchantNo);
    // 缺货：可用量 ≤ 0 的 SKU。mock 没有店级库存表，按同一套散列分给各店
    const oosSkus = myGoods().flatMap((g) =>
      g.skus.filter((k) => (k.stock ?? 0) <= 0).map((k) => k.skuNo),
    );

    return delay({
      days: window,
      currency: currentCurrency(),
      /*
       * 主体整体评分：与 mStats 用**同一个算法**（同一批评价、同一个口径）。
       * 每家店自己的分在下面每行的 rating 上（V155 起，评价归门店）。
       */
      rating: rs.length ? Number((rs.reduce((s, r) => s + r.rating, 0) / rs.length).toFixed(1)) : 0,
      ratingCount: rs.length,
      stores: stores.map((s) => {
        const rows = mine.filter((o) => storeOfOrder(o, stores) === s.storeNo);
        const perBuyer = new Map<string, number>();
        for (const o of rows) {
          const who = o.buyerNickname || o.receiver?.name || o.orderNo;
          perBuyer.set(who, (perBuyer.get(who) ?? 0) + 1);
        }
        const buyers = perBuyer.size;
        const repeatBuyers = [...perBuyer.values()].filter((n) => n >= 2).length;
        return {
          storeNo: s.storeNo,
          storeName: s.name,
          isDefault: s.isDefault,
          status: s.status,
          orders: rows.length,
          gmvMinor: sumPayable(rows),
          buyers,
          repeatBuyers,
          /*
           * 门店评分（V155）。mock 里的评价没有 store_no，所以**按订单反推**：
           * 这家店的单对应的那些评价。真后端读的是 rvw_review.store_no ——
           * 两边算法不同但**语义相同**，而这里刻意不去伪造一个 store_no：
           * 伪造的话，mock 与真库对「老评价没有门店归属」这件事的表现会不一样。
           */
          ...storeRating(rows, rs),
          // 分母为 0 时是 0，不是除零、不是 null —— 还没开张的店显示 0%
          repeatRate: buyers ? repeatBuyers / buyers : 0,
          outOfStockSkus: oosSkus.filter((no) => hashPick(no, stores) === s.storeNo).length,
        };
      }),
    });
  },

  // ---------------------------------------------------------------- 商品
  async mGoodsList(q) {
    let list = myGoods();
    /*
     * **按四态筛，不是按 onSale 布尔值**。
     *
     * 此前只认 ON_SALE / OFF_SALE 两个值，而 `status` 有四态 ——
     * 「审核中」与「已驳回」两个页签落进 else：不过滤，显示全部。
     * 商家点「已驳回」看到的是所有商品，包括在售的。
     */
    if (q.status === "ON_SALE") list = list.filter((g) => g.onSale);
    else if (q.status === "OFF_SALE") list = list.filter((g) => !g.onSale && !g.status);
    else if (q.status) list = list.filter((g) => g.status === q.status);

    /*
     * **关键词与类目过滤此前完全没实现。**
     *
     * 页面两个都在发（真后端 `GET /biz/goods` 也都支持），而 mock 只看 status ——
     * 于是搜索框输什么都返回全部、类目筛点哪个都返回全部。
     * 这比「筛出 0 条」更难发现：界面一直有内容，看起来在工作。
     *
     * 实测：按 6 个一级类目分别筛，每个都返回同样的 2 条。
     */
    const kw = q.keyword?.trim().toLowerCase();
    if (kw) {
      list = list.filter((g) => String(g.title ?? "").toLowerCase().includes(kw));
    }
    if (q.categoryNo) {
      /*
       * **要连子孙一起匹配**：页面筛的是**一级**类目，而商品挂在二级上 ——
       * 只比对 categoryNo 相等的话，选「食品生鲜」一条也筛不出来。
       */
      /*
       * 先把树摊平成 `{编号, 上级}` 再算，**不在树上做类型断言** ——
       * db 里各层节点的字面量类型并不一致（`parentNo` 一级是 null、二级是 string），
       * 强转会被 TS 拒绝，而绕过它的 `as unknown as` 只是把问题藏起来。
       */
      interface Node { categoryNo: string; parentNo?: string | null; children?: Node[] }
      const flat: Node[] = [];
      const collect = (nodes: Node[]) => {
        for (const c of nodes) {
          flat.push(c);
          if (c.children?.length) collect(c.children);
        }
      };
      collect(db.categories as unknown as Node[]);

      const wanted = new Set<string>([q.categoryNo]);
      // 逐层展开：树最深三层，跑到不再新增为止（比写死轮数稳）
      for (let grew = true; grew; ) {
        grew = false;
        for (const c of flat) {
          if (c.parentNo && wanted.has(c.parentNo) && !wanted.has(c.categoryNo)) {
            wanted.add(c.categoryNo);
            grew = true;
          }
        }
      }
      list = list.filter((g) => wanted.has(g.categoryNo));
    }
    return delay(paginate(list, q.page, q.size));
  },

  async mGoodsDetail(goodsNo) {
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  async mSaveGoods(payload) {
    const merchantNo = requireMerchant();
    /*
     * **先脱掉响应式外壳再往库里存。**
     *
     * 页面传进来的 `images` / `optionValues` 是 Vue 的 reactive 代理数组
     * （`images: images.value`、`optionValues: r.optionValues` 都是直接给的引用）。
     * 直接存下去有两个后果，第二个更严重：
     *
     *   1. `delay()` 用 `structuredClone` 返回副本，而 Chrome **拒绝克隆 Proxy** ——
     *      于是在 mock 上建完商品，再打开它就抛 DataCloneError，
     *      整个商品详情打不开（实测：goods.images → DataCloneError）；
     *   2. 库里存的是**页面状态的活引用**：商家在编辑页再改一下，
     *      没点保存也已经改到了「数据库」里。真后端不可能有这种事
     *      （HTTP 那条路上一切都经过 JSON 序列化）。
     *
     * 在这个边界上做一次深拷贝，等价于 HTTP 的 JSON 往返 —— 这正是 mock 该模仿的。
     */
    payload = JSON.parse(JSON.stringify(payload)) as typeof payload;
    if (!payload.skus.length) throw new Error("至少要有一个规格");
    // 中文是基准语言：没有它就没有回落目标
    if (!payload.title["zh-CN"].trim()) throw new Error("中文商品名必填");

    // 展示价取最低 SKU 价 —— 列表页「¥12 起」的口径，端上不各算各的
    const price = Math.min(...payload.skus.map((k) => k.price));
    /*
     * 契约里 `priceByMarket` 的键是**市场码**（CN/AE/US），而 mock 库内部一律按
     * **币种**索引（`priceIn()` 拿 currentCurrency 去查）。所以在这个边界上换一次码 ——
     * 与真后端做的是同一件事：它把市场码原样落进 `prd_sku.market`。
     * 不换的话 mock 里查得到、线上查不到，两套实现对同一份契约给出不同结果。
     */
    const toCurrency = (m: MarketId): CurrencyCode =>
      MARKETS.find((x) => x.id === m)!.currency;
    const skuPricesByCurrency = (k: { priceByMarket?: Partial<Record<MarketId, number>> }) =>
      Object.entries(k.priceByMarket ?? {}).reduce<Partial<Record<CurrencyCode, number>>>(
        (acc, [mid, v]) => {
          if (v !== undefined) acc[toCurrency(mid as MarketId)] = v;
          return acc;
        },
        {},
      );
    // 商品级也存一份按市场的展示价：各市场分别取该市场下的最低 SKU 价
    const priceByMarket = MARKETS.reduce<Partial<Record<CurrencyCode, number>>>((acc, m) => {
      const vals = payload.skus
        .map((k) => k.priceByMarket?.[m.id])
        .filter((v): v is number => v !== undefined);
      if (vals.length) acc[m.currency] = Math.min(...vals);
      return acc;
    }, {});
    /*
     * **标准品收敛：mock 也要做一遍**（TDD-标准品库 §3.2）。
     *
     * 真后端在 `save()` 里用标准品的 categoryNo 与 optionCode 覆盖请求值。
     * mock 不做的话就是「mock 上改得掉、连真后端改不掉」—— 而 mock 是开发期
     * 唯一看得见的那份数据，这种错配最难查。
     */
    const std = payload.stdNo ? db.spuStds.find((t) => t.stdNo === payload.stdNo) : undefined;
    if (payload.stdNo && !std) throw new Error("所选标准品不存在");
    const effectiveCategoryNo = std ? std.categoryNo : payload.categoryNo;
    const specSource = std
      ? payload.specGroups.map((g, i) => {
          const sg = std.specGroups[i];
          if (!sg) return g; // 商家追加的规格组：没有对应的标准组，原样保留
          const codes = sg.optionCodes ?? [];
          return { ...g, optionCodes: g.options.map((_, j) => codes[j]) };
        })
      : payload.specGroups;

    const specGroups = specSource.map((g) => ({
      name: toI18n(g.name),
      options: g.options.map(toI18n),
      // 模板编码要跟着落库：不存就等于没做模板 ——
      // 二期想按规格聚合时，历史商品全是自由文本，只能回头刷数据
      optionCodes: g.optionCodes,
      templateNo: g.templateNo,
    }));
    const buildSkus = (existing: { skuNo: string; optionValues: unknown[] }[] = []) =>
      payload.skus.map((k) => ({
        // 复用原 skuNo：历史订单行、购物车、库存流水都引用它，重新生成等于把它们指向不存在的规格
        skuNo:
          k.skuNo ??
          existing.find(
            (e) => e.optionValues.length === k.optionValues.length && e.skuNo === k.skuNo,
          )?.skuNo ??
          nextNo("SK"),
        optionValues: k.optionValues.map(toI18n),
        price: k.price,
        // 分别定价是真源；只填了当前市场时其余市场留空 = 不在那边卖。
        // 契约按市场码来，mock 库按币种存 —— 在这里换码（见上方 toCurrency 的说明）
        priceByMarket: skuPricesByCurrency(k),
        stock: k.stock,
        // 划线价与标称重量：**不传 = 不改**，与真后端同一条规矩。
        // 不落盘的话「mock 上填了、保存后消失」—— 正是这轮在修的那类故障，
        // 只不过发生在 mock 里，而 mock 恰恰是开发期唯一看得见的那一份
        originPrice: k.originPrice,
        nominalGram: k.nominalGram,
        // 成本价同一条规矩：mock 不落盘的话「填了、保存后消失」，
        // 而毛利那行会跟着一起不见 —— 看着像算错了，其实是没存
        costPrice: k.costPrice,
      }));

    /**
     * 商品级的可选字段。**「不传 = 不改」逐个字段判空**，与后端 `applyOptional` 同形状。
     *
     * <p>生鲜段 / 服务段按**形态**写：一件大米带上「服务时长 90 分钟」不会报错，
     * 但它会出现在服务类的详情模板里。形态由类目派生，所以两边判的是同一个东西。
     */
    const applyOptional = (seed: Record<string, unknown>, formType: string) => {
      if (payload.limitPerUser !== undefined) seed.limitPerUser = Math.max(payload.limitPerUser, 0);
      if (payload.fresh && formType === CATEGORY_TYPE.FRESH) {
        const f = payload.fresh;
        if (f.cutoffAt !== undefined) seed.cutoffAt = f.cutoffAt;
        if (f.arrivalDesc !== undefined) seed.arrivalDesc = toI18n(f.arrivalDesc);
        if (f.weighed !== undefined) seed.weighed = f.weighed;
        if (f.origin !== undefined) seed.origin = toI18n(f.origin);
      }
      if (payload.service && formType === CATEGORY_TYPE.SERVICE) {
        const sv = payload.service;
        if (sv.durationMin !== undefined) seed.durationMin = sv.durationMin;
        if (sv.storeName !== undefined) seed.storeName = toI18n(sv.storeName);
      }
      if (payload.groupBuy) {
        const gb = payload.groupBuy;
        // 两个都空 = 显式关掉拼团；只填一个后端会拒，这里跟着拒，
        // 否则「mock 上存得下、连真后端报错」
        if (gb.minCount === undefined && gb.price === undefined) {
          seed.groupBuy = undefined;
        } else if (gb.minCount === undefined || gb.price === undefined) {
          throw new Error("起团人数与团购价要一起填");
        } else {
          if (gb.minCount < 2) throw new Error("一个人不叫团，起团人数至少 2");
          seed.groupBuy = { minCount: gb.minCount, price: gb.price };
        }
      }
    };

    if (payload.goodsNo) {
      const seed = findGoodsSeed(payload.goodsNo);
      seed.title = fillI18n(payload.title);
      seed.subtitle = fillI18n(payload.subtitle);
      // 不传 = 不改，与 images 同一口径：无条件覆盖会让「只改标题」把详情清空
      if (payload.detail !== undefined) seed.detail = payload.detail;
      // 详情图与 images 同一口径：不判空的话，只改标题就把详情图清空
      if (payload.detailImages !== undefined) seed.detailImages = payload.detailImages;
      seed.price = price;
      seed.priceByMarket = priceByMarket;
      seed.specGroups = specGroups as (typeof seed.specGroups);
      seed.skus = buildSkus(seed.skus) as (typeof seed.skus);
      /*
       * 形态跟着类目重算 —— 改类目而形态不跟，就又出现了这轮消掉的那种矛盾，
       * 只是换到了 mock 这一侧（而 mock 是开发期唯一看得见的那份数据）。
       */
      const editedType = TEMPLATE_TO_TYPE[findCategoryTemplate(effectiveCategoryNo) ?? ""];
      if (editedType) seed.type = editedType as CategoryType;
      seed.categoryNo = effectiveCategoryNo;
      // 溯源：不传 = 脱离标准品（与真后端一致，不是「不改」）
      seed.stdNo = payload.stdNo;
      // 「不传 = 不改」，与后端一致：不判空的话，改一次标题就把轮播图/履约方式清空
      if (payload.images !== undefined) seed.images = payload.images;
      if (payload.fulfillments !== undefined) {
        if (!payload.fulfillments.length) throw new Error("至少选一种履约方式");
        seed.fulfillments = payload.fulfillments as (typeof seed.fulfillments);
      }
      applyOptional(seed as unknown as Record<string, unknown>, seed.type);
      persist();
      return delay(toGoods(seed));
    }

    const goodsNo = nextNo("G");
    const newType = (TEMPLATE_TO_TYPE[findCategoryTemplate(effectiveCategoryNo) ?? ""] ??
      CATEGORY_TYPE.NORMAL) as CategoryType;
    const seed = {
      goodsNo,
      merchantNo,
      // 形态由类目派生，与真后端同一条规则（P1-1）—— mock 自己算一遍，
      // 而不是抄 payload：payload 里已经没有 type 了，而「mock 上能建、
      // 连真后端就变成另一种货」是最难查的一类错配
      type: newType,
      categoryNo: effectiveCategoryNo,
      stdNo: payload.stdNo,
      title: fillI18n(payload.title),
      subtitle: fillI18n(payload.subtitle),
      cover: payload.cover || "📦",
      detail: payload.detail,
      detailImages: payload.detailImages ?? [],
      // 端上没传就给一个占位，传了就用他上传的那几张
      images: payload.images?.length ? payload.images : ["📦"],
      fulfillments: payload.fulfillments?.length ? payload.fulfillments : ["STORE_PICKUP"],
      price,
      priceByMarket,
      /*
       * **新建落草稿、不在售**（批 D）：mock 此前直接给 onSale: true，
       * 于是「录完就能卖」在开发期看着完全正常，而真后端一直是「录完要过审」。
       * 两边不同的后果是端上按 mock 的样子做交互，接真后端才发现少了两步。
       */
      status: "DRAFT" as const,
      onSale: false,
      salesCount: 0,
      specGroups,
      skus: buildSkus(),
      promotions: [],
    } as unknown as (typeof db.goodsSeeds)[number];
    applyOptional(seed as unknown as Record<string, unknown>, newType);
    db.goodsSeeds.unshift(seed);
    persist();
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  async mToggleGoods(goodsNo, onSale) {
    const seed = findGoodsSeed(goodsNo);
    seed.onSale = onSale;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStock(goodsNo, skuNo, stock) {
    const seed = findGoodsSeed(goodsNo);
    const sku = seed.skus.find((s) => s.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    sku.stock = stock;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStoreStock(goodsNo, skuNo, stock) {
    /*
     * mock 里没有门店维度的库存表 —— 单店是 mock 的默认形态，
     * 而门店级库存要在真后端上才谈得上。这里与 mSaveStock 同行为：
     * 让端上的交互能跑通，真实语义（没设库存的店视为 0）由后端用例守。
     */
    return this.mSaveStock(goodsNo, skuNo, stock);
  },

  async mSubmitGoods(goodsNo) {
    const seed = findGoodsSeed(goodsNo);
    // 只有草稿会动 —— 重复点击是常态，报错只会让商家以为提交失败
    if (seed.status === "DRAFT") seed.status = "PENDING";
    persist();
    return delay(toGoods(seed));
  },

  async mSavePresale(goodsNo, cutoffAt, arrivalDesc) {
    const seed = findGoodsSeed(goodsNo);
    if (seed.type !== "FRESH") throw new Error("只有生鲜有截单时间");
    if (cutoffAt != null) seed.cutoffAt = cutoffAt;
    // 种子里这一列是多语言（与 origin 同）—— 商家填的是一句中文，回落到三语
    if (arrivalDesc != null) seed.arrivalDesc = toI18n(arrivalDesc);
    /*
     * ★ **不动 status** —— 这正是它与 mSaveGoods 的分界。
     * mock 也照此实现：改成回待审的话，「改截单会不会下架」这个最要紧的问题
     * 在开发期得到的是错误答案。
     */
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStorePrice(goodsNo, skuNo, price) {
    const seed = findGoodsSeed(goodsNo);
    const sku = seed.skus.find((k) => k.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    if (price != null && price < 0) throw new Error("价格不能为负");
    /*
     * **空 = 取消本店单独定价**，回到主体价 —— 不是改成 0。
     * mock 也要照此实现：写成 0 的话「取消定价」这条路在开发期看着像「白送」。
     */
    sku.storePrice = price ?? undefined;
    persist();
    return delay(toGoods(seed));
  },

  // ---------------------------------------------------------------- 图片与识别
  async mUploadImage(tempPath) {
    // mock 直接把端上的临时路径当 URL 用 —— H5 下 blob: 路径能直接显示。
    // 真实环境：小程序走 uni.uploadFile（域名要在白名单），App 无此限制；
    // 服务端返回 CDN URL（E9）
    if (!tempPath) throw new Error("没有选到图片");
    return delay({ url: tempPath }, 400);
  },

  async mRecognizeGoods() {
    // ⚠️ **这是假识别**：mock 里没有模型，按当前时间在几个常见品类里轮换，
    // 只为把「识别 → 预填 → 店主改 → 保存」这条交互链路跑通。
    // 真实实现在服务端（小程序不能跑本地模型），置信度由模型给。
    const guesses: { title: string; subtitle: string; type: Goods["type"]; categoryNo: string }[] = [
      { title: "东北五常大米 10斤装", subtitle: "当季新米 颗粒饱满", type: CATEGORY_TYPE.NORMAL, categoryNo: "CAT131" },
      { title: "本地土鸡蛋 30枚", subtitle: "当日现捡 冷链直达", type: CATEGORY_TYPE.FRESH, categoryNo: "CAT130" },
      { title: "洗衣液 大容量装 3kg", subtitle: "深层洁净 低泡易漂", type: CATEGORY_TYPE.NORMAL, categoryNo: "CAT210" },
    ];
    const g = guesses[db.seq % guesses.length]!;
    return delay({ ...g, confidence: 0.72 }, 700);
  },

  /**
   * 自动生成图文详情。**这是假生成**：mock 里没有模型，按标题套一个模板。
   *
   * 保留它的意义是把「点按钮 → 转圈 → 文字进框 → 商家改 → 保存」整条链路跑通，
   * 包括**没填标题时应当拒绝**这一档 —— 真实实现里模型没有名字只能瞎编，
   * 所以那一档在服务端也是拒绝，不该只在真机上才发现。
   */
  async mDescribeGoods(req) {
    if (!req.title?.trim()) return delay({ detail: "" }, 300);
    const lines = [
      `· ${req.subtitle?.trim() || req.title.trim()}，适合日常家庭采买。`,
      "· 规格与分量以商品页所列为准，下单后按规格备货。",
      "· 建议收到后尽快食用或使用，开封后请按包装说明保存。",
      "· 如遇缺货或规格调整，我们会在发货前与你确认。",
    ];
    return delay({ detail: lines.join("\n") }, 900);
  },

  // ---------------------------------------------------------------- 标准品库
  /**
   * 标准品搜索。**mock 自己也做一遍收敛**（见 mSaveGoods）——
   * 「mock 上建出来是这样、连真后端变成那样」是这套 mock 最该防的错配。
   */
  async mSpuStdSearch(q) {
    const kw = (q.keyword ?? "").trim();
    const rows = db.spuStds
      .filter((t) => t.status !== "ARCHIVED")
      .filter((t) => !q.categoryNo || t.categoryNo === q.categoryNo)
      // 标题与别名一起搜：商家嘴里的「洋芋」与标准品标题「土豆」对不上时，
      // 结果不是报错，是他以为标准库里没有 —— 然后自建一个，可比性在这一次就丢了
      .filter((t) => !kw || t.title.includes(kw) || (t.keywords ?? "").includes(kw))
      .slice(0, q.limit && q.limit > 0 ? q.limit : 20);
    return delay(rows.map((t) => ({ ...t })));
  },

  // ---------------------------------------------------------------- 类目
  async mCategoryTree() {
    // 直接给整棵树：类目就几十条且极少变，分层拉取只会让选择器多两次等待
    return delay(db.categories.map((c) => ({ ...c })));
  },

  // ---------------------------------------------------------------- 规格模板
  /**
   * 规格模板。**mock 必须把两层的取舍也做一遍** ——
   * 「mock 上推荐成这样、连真后端变成那样」是这套 mock 最该防的错配。
   *
   * 两层：`categoryNo` 为空的是品类兜底，填了的是类目专属。
   * 类目专属排前面，并用**同名**规格组顶掉兜底那条
   * （休闲零食的「重量」应当替代普通实物的「规格」，不是两个都推）。
   */
  async mSpecTemplates(categoryType, categoryNo) {
    const merchantNo = db.merchant.merchantNo;
    const picked = categoryNo?.trim() || undefined;
    const usable = db.specTemplates.filter((tpl) => {
      // 商家自己存的不限品类也不限类目 —— 他存的时候就是按自己的货存的
      if (tpl.scope === "MERCHANT") return tpl.merchantNo === merchantNo;
      if (categoryType && tpl.categoryType !== categoryType) return false;
      /*
       * 别家类目的专属模板要挡掉。类目级模板的 categoryType 也填着
       * （不填会变成谁都查不到的孤儿），所以只按品类过滤的话，
       * 选「休闲零食」会连「手机数码 → 颜色/存储」一起推过来 —— 同属 NORMAL。
       */
      if (tpl.categoryNo) return tpl.categoryNo === picked;
      return true;
    });
    if (!picked) return delay(usable);

    const catLevel = usable.filter((t) => t.categoryNo === picked);
    const shadowed = new Set(catLevel.map((t) => t.name));
    const rest = usable.filter(
      (t) => t.categoryNo !== picked && !(t.scope === "PLATFORM" && shadowed.has(t.name)),
    );
    return delay([...catLevel, ...rest]);
  },

  /**
   * 在平台维度下加一个自有值。mock 里只做两件真会影响界面的事：
   * 撞车直接回平台那一档、量纲维度抽不出数字就拒。
   */
  async mAddSpecValue(dimNo, label) {
    requireMerchant();
    const text = label.trim();
    if (!text) throw new Error("先填规格值");
    const tpl = db.specTemplates.find((t) => t.templateNo === dimNo);
    const hit = tpl?.options.find((o) => o.label === text);
    if (hit) return delay({ valueNo: dimNo + "_" + (hit.code ?? text), code: hit.code ?? "", label: hit.label });
    // 量纲维度：文案里得写着数量，否则这一档排不了序也比不了价
    if (/重量|容量|长度|口径/.test(tpl?.name ?? "") && !/\d/.test(text)) {
      throw new Error("这一档要写清数量，例如 750g");
    }
    tpl?.options.push({ label: text });
    return delay({ valueNo: dimNo + "_M" + db.specTemplates.length, code: "", label: text });
  },

  /** 自建维度：只在本店可用，不参与跨店比价 */
  async mAddSpecDim(name, labels) {
    const merchantNo = requireMerchant();
    const nm = name.trim();
    if (!nm) throw new Error("先填规格名");
    if (["规格", "型号", "类型", "属性", "参数"].includes(nm)) {
      throw new Error("「" + nm + "」太泛，换一个说清楚是什么的名字");
    }
    const created: SpecTemplate = {
  /**
   * 能挑的维度。mock 里的规格库只有模板表这一份，所以分组的判据与真后端一致：
   * 本类目的（categoryNo 命中）→ 平台通用（无 categoryNo）→ 自建（scope=MERCHANT）。
   */
  async mPickableDims(categoryNo) {
    const merchantNo = db.merchant.merchantNo;
    const picked = categoryNo?.trim() || undefined;
    const cat = picked ? db.specTemplates.filter((t) => t.categoryNo === picked) : [];
    const seen = new Set(cat.map((t) => t.templateNo));
    const universal = db.specTemplates.filter(
      (t) => t.scope === "PLATFORM" && !t.categoryNo && !seen.has(t.templateNo),
    );
    universal.forEach((t) => seen.add(t.templateNo));
    const mine = db.specTemplates.filter(
  /**
   * 「我的规格」。mock 里没有真的规格库，就拿商家自存的模板当自建维度 ——
   * **用量按规格组名从 db.goods 里数**，与真后端同一条判据（存量商品的
   * 规格快照里只有名字，没有维度编号）。
   */
  async mMySpecDims() {
  async mStoreSpecDims(storeNo) {
    // mock 里按门店货架分组：与真后端同一个形状，值取该类目的模板
    // 不传就用第一家店 —— mock 的演示会话只有一家在用
    const key = storeNo || db.stores[0]?.storeNo || "";
    const cats = db.storeCategories[key] ?? [];
    return delay(cats.map((c) => ({
      categoryNo: c.categoryNo,
      categoryName: c.name,
      dims: db.specTemplates.filter((t) => t.categoryNo === c.categoryNo),
    })));
  },

  /**
   * mock 的覆盖只做到「看得出生效」：按提交的顺序与启用重排模板的 options。
   * 不落库 —— mock 没有覆盖表，而这一步真正要验的是端上提交的形状对不对。
   */
  async mDimValues(dimNo) {
    // mock 里模板表就是值池：这个维度的全部档位
    return delay(db.specTemplates.find((t) => t.templateNo === dimNo)?.options ?? []);
  },

  async mSaveSpecOverride(categoryNo, dims) {
    const on = dims.filter((d) => d.enabled);
    return delay(on.map((d) => {
      const tpl = db.specTemplates.find((t) => t.templateNo === d.dimNo);
      const off = new Set((d.values ?? []).filter((v) => !v.enabled).map((v) => v.code));
      // 只做取舍与顺序 —— 名字不给改，所以这里也不改
      return { ...tpl!, options: (tpl?.options ?? []).filter((o) => !off.has(o.code ?? "")) };
    }));
  },

  async mMySpecDims() {
    const merchantNo = db.merchant.merchantNo;
    const mine = db.specTemplates.filter((t) => t.scope === "MERCHANT" && t.merchantNo === merchantNo);
    const used = (name: string) =>
      myGoods().filter((g) => (g.specGroups ?? []).some((x) => x.name === name)).length;
    return delay(mine.map((t) => ({
      dimNo: t.templateNo,
      name: t.name,
      valueCount: t.options.length,
      usedCount: used(t.name),
      status: "ACTIVE" as const,
      dimUsed: mine.length,
      dimQuota: 10,
      valueQuota: 20,
      values: t.options,
    })));
  },

  async mRenameSpecDim(dimNo, name) {
    const t = db.specTemplates.find((x) => x.templateNo === dimNo);
    if (!t) throw new Error("规格不存在");
    // 与真后端同一条：撞平台维度名不给改 —— 换个名字也不会让它变成平台维度
    if (db.specTemplates.some((x) => x.scope === "PLATFORM" && x.name === name.trim())) {
      throw new Error("平台已有同名规格，直接在建品页里挑它");
    }
    t.name = name.trim();
    return delay(undefined);
  },

  async mArchiveSpecDim(dimNo, archived) {
    const t = db.specTemplates.find((x) => x.templateNo === dimNo);
    if (!t) throw new Error("规格不存在");
    // mock 的模板表没有 status 字段：停用就从可挑清单里摘掉，效果与真后端一致
    if (archived) db.specTemplates = db.specTemplates.filter((x) => x.templateNo !== dimNo);
    return delay(undefined);
  },

  /**
   * 能挑的维度。mock 里的规格库只有模板表这一份，所以分组的判据与真后端一致：
   * 本类目的（categoryNo 命中）→ 平台通用（无 categoryNo）→ 自建（scope=MERCHANT）。
   */
  async mPickableDims(categoryNo) {
    const merchantNo = db.merchant.merchantNo;
    const picked = categoryNo?.trim() || undefined;
    const cat = picked ? db.specTemplates.filter((t) => t.categoryNo === picked) : [];
    const seen = new Set(cat.map((t) => t.templateNo));
    const universal = db.specTemplates.filter(
      (t) => t.scope === "PLATFORM" && !t.categoryNo && !seen.has(t.templateNo),
    );
    universal.forEach((t) => seen.add(t.templateNo));
    const mine = db.specTemplates.filter(
      (t) => t.scope === "MERCHANT" && t.merchantNo === merchantNo && !seen.has(t.templateNo),
    );
    return delay([...cat, ...universal, ...mine]);
  },

      (t) => t.scope === "MERCHANT" && t.merchantNo === merchantNo && !seen.has(t.templateNo),
    );
    return delay([...cat, ...universal, ...mine]);
  },

      templateNo: nextNo("SD"),
      scope: "MERCHANT",
      merchantNo,
      name: nm,
      options: labels.map((l) => l.trim()).filter(Boolean).map((label) => ({ label })),
    };
    db.specTemplates.push(created);
    return delay(created);
  },

  /**
   * 我的资质。mock 里给一条「已传但还没授码」的样本 ——
   * 那正是这一页要说清楚的状态：传了 ≠ 解锁了。
   */
  async mQualifications() {
    requireMerchant();
    return delay({
      items: db.myQualifications,
      grantedCodes: ["FRESH_VEG", "FRESH_FRUIT"],
      catalog: [
        { code: "FRESH_VEG", name: "蔬菜", requiredQualification: "营业执照（食用农产品）",
          qualType: "BUSINESS_LICENSE" as const, categoryNames: ["蔬菜"] },
        { code: "FRESH_FRUIT", name: "水果", requiredQualification: "营业执照（食用农产品）",
          qualType: "BUSINESS_LICENSE" as const, categoryNames: ["水果"] },
        { code: "FOOD", name: "熟食加工", requiredQualification: "食品经营许可证",
          qualType: "FOOD_PERMIT" as const, categoryNames: ["熟食卤味"] },
        { code: "FRESH_MEAT", name: "肉禽蛋", requiredQualification: "食品经营许可证",
          qualType: "FOOD_PERMIT" as const, categoryNames: ["肉禽蛋", "水产海鲜"] },
        { code: "DAILY", name: "日用百货", requiredQualification: null,
          qualType: null, categoryNames: ["纸品清洁", "家居用品"] },
      ],
    });
  },

  async mSaveQualification(payload) {
    requireMerchant();
    if (!payload.qualName?.trim()) throw new Error("先填证件名称");
    const created = {
      qualNo: nextNo("QL"),
      qualType: payload.qualType,
      qualName: payload.qualName.trim(),
      qualNumber: payload.qualNumber ?? null,
      imageUrl: payload.imageUrl ?? null,
      expireAt: payload.expireAt ?? null,
      status: "VALID",
    };
    db.myQualifications.push(created);
    return delay(created);
  },

  async mSaveSpecTemplate(payload) {
    const merchantNo = requireMerchant();
    const options = payload.options.map((o) => o.trim()).filter(Boolean);
    if (!payload.name.trim() || !options.length) throw new Error("规格名和选项都要填");

    // 商家自存的模板**不给 code**：code 的意义是跨商家统一口径，
    // 各家自己起的编码互不相通，给了反而制造「看起来能聚合其实不能」的假象
    const created: SpecTemplate = {
      templateNo: nextNo("ST"),
      scope: "MERCHANT",
      merchantNo,
      name: payload.name.trim(),
      options: options.map((label) => ({ label })),
    };
    db.specTemplates.push(created);
    persist();
    return delay({ ...created });
  },

  // ---------------------------------------------------------------- 订单与配送
  async mOrderList(q) {
    const merchantNo = db.merchant.merchantNo;
    let list = merchantNo ? db.orders.filter((o) => belongsToMerchant(o, merchantNo)) : [];
    if (q.status) list = list.filter((o) => o.status === q.status);
    // 与 status 正交：商家的「待核销」= FULFILLING + 自提/到店核销类
    if (q.fulfillments?.length) {
      const want = new Set(q.fulfillments);
      list = list.filter((o) => want.has(o.fulfillment));
    }
    return delay(paginate(list, q.page, q.size));
  },

  async mOrderDetail(orderNo) {
    return delay({ ...findOrder(orderNo) });
  },

  async mShip(orderNo, expressNo) {
    const o = findOrder(orderNo);
    assertTransition(o.status, "FULFILLING");
    o.status = "FULFILLING";
    o.expressNo = expressNo;
    pushTimeline(o, "已发货");
    pushMessage(
      "TRADE",
      "你的订单已发货",
      `运单号 ${expressNo}，可在订单详情查看物流`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mDelivered(orderNo) {
    const o = findOrder(orderNo);
    // 商家自送没有骑手轨迹，老板点一下就是送到了 —— 直接进完成态（ADR-005 §5）
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已送达");
    pushMessage(
      "TRADE",
      "订单已送达",
      "商家已标记送达，有问题可在订单里申请售后",
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mDeliveryRule() {
    return delay({ ...db.deliveryRule });
  },

  async mSaveDeliveryRule(rule) {
    db.deliveryRule = { ...rule };
    persist();
    return delay({ ...db.deliveryRule });
  },

  // ---------------------------------------------------------------- 自提点履约
  /**
   * 履约总览。三个数都从**同一份订单数据**算出来，不另存计数器 ——
   * 计数器与明细分开维护，迟早会出现「总览说 3 单、点进去只有 2 单」。
   */
  async mPickupOverview() {
    const pickupNo = db.merchant.pickupNo;
    const mine = db.orders.filter(
      (o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo),
    );
    const startOfDay = new Date().setHours(0, 0, 0, 0);
    const itemCount = mine
      .filter((o) => o.status === "COMPLETED")
      .reduce((n, o) => n + o.items.reduce((k, it) => k + it.qty, 0), 0);
    return delay({
      pickupNo: pickupNo || "",
      pickupName: db.merchant.name || "",
      pendingVerify: mine.filter((o) => o.status === "FULFILLING"
        && PICKUP_LIKE.has(o.fulfillment)).length,
      // 「批次」= 今天标记过到货的单，按到货动作聚合
      arrivedBatches: mine.filter((o) => o.status !== "PAID" && o.createdAt >= startOfDay)
        .length,
      // 服务费按**已完成**的件数算：货还没交到人手上，这笔钱不该先算进来
      serviceFeeMinor: itemCount * SETTLE.fulfillFeePerItemMinor,
    });
  },

  /** 履约台的单：**按 PickupOrder 的形状发**（子单号 + 裁剪过的字段），与后端一致 */
  async mPickupOrders() {
    const pickupNo = db.merchant.pickupNo;
    return delay(
      db.orders
        .filter((o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo))
        .map(pickupView),
    );
  },

  async mPickingList() {
    const pickupNo = db.merchant.pickupNo;
    const map = new Map<string, PickingRow>();
    for (const o of db.orders) {
      if (o.fulfillment !== "STORE_PICKUP") continue;
      if (pickupNo && o.pickupNo !== pickupNo) continue;
      if (!["PAID", "FULFILLING"].includes(o.status)) continue;
      const buyer = o.buyerNickname ?? db.user.nickname;
      for (const it of o.items) {
        const cur = map.get(it.skuNo) ?? {
          goodsNo: it.goodsNo,
          skuNo: it.skuNo,
          title: it.title,
          cover: it.cover,
          spec: it.spec,
          totalQty: 0,
          buyers: [],
        };
        cur.totalQty += it.qty;
        cur.buyers.push({ nickname: buyer, qty: it.qty, orderNo: o.orderNo });
        map.set(it.skuNo, cur);
      }
    }
    return delay([...map.values()].sort((a, b) => b.totalQty - a.totalQty));
  },

  async mMarkArrived(subOrderNos, _pickupNo) {
    const changed: ReturnType<typeof pickupView>[] = [];
    for (const no of subOrderNos) {
      // mock 的主单号当子单号用（一单一商家），与 pickupView 同一口径
      const o = db.orders.find((x) => x.orderNo === no);
      if (!o || o.status !== "PAID") continue;
      assertTransition(o.status, "FULFILLING");
      o.status = "FULFILLING";
      pushTimeline(o, "已到自提点，请及时取货");
      pushMessage(
        "TRADE",
        "到货了，记得来取",
        `取货码 ${o.verifyCode ?? ""}，到 ${o.pickupName ?? "自提点"} 报码即可`,
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      changed.push(pickupView(o));
    }
    persist();
    return delay(changed);
  },

  // ---------------------------------------------------------------- 售后
  async mAfterSaleList() {
    const merchantNo = db.merchant.merchantNo;
    /*
     * 返回**售后单**，不是订单。后端 /biz/after-sale 给的就是 List<AfterSaleVO>，
     * 而这里此前返回的是订单、且按 `o.status === "REFUNDING"` 筛 ——
     * 两个错误叠在一起：订单永远不会是这个状态（那是售后单的状态），
     * 于是商家端「售后」页签恒为空；就算筛出来了，形状也和后端对不上。
     */
    return delay(
      db.orders
        .filter((o) => o.afterSale && belongsToMerchant(o, merchantNo))
        .map((o) => o.afterSale!),
    );
  },

  async mApproveAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    as.merchantReply = reply;
    as.updatedAt = Date.now();

    /**
     * 同意后**按类型分流**，这是售后闭环此前缺的那半段：
     *   · 仅退款   → 直接退款
     *   · 退货退款 → 等用户寄回、商家确认收货**之后**才退款
     * 两者合成一条路的后果是「退款了货没回来」。
     */
    if (as.type === "RETURN_REFUND") {
      as.status = "REFUNDING";
      pushTimeline(o, "商家已同意退货，等待寄回");
      pushMessage(
        "TRADE",
        "退货申请已通过",
        "请寄回商品并在订单里填写退货运单号",
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      persist();
      return delay(o.afterSale!);
    }

    settleRefund(o, "商家已同意退款");
    persist();
    return delay(o.afterSale!);
  },

  async mRejectAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (!reply.trim()) throw new Error("驳回必须填写理由");
    as.merchantReply = reply;
    as.updatedAt = Date.now();
    // 驳回**不改订单状态** —— 用户还得能上升平台，直接置回已完成就把路堵死了
    as.status = "REJECTED";
    pushTimeline(o, `商家驳回：${reply}`);
    pushMessage(
      "TRADE",
      "售后被驳回",
      reply,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o.afterSale!);
  },

  async mConfirmReturn(afterSaleNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 用户还没寄（没填运单号）就点确认收货，多半是误操作
    // 后端没有独立的「等寄回 / 已收货」两态：同意即 REFUNDING，
    // 是否已寄回看 returnExpressNo 有没有值
    if (as.status !== "REFUNDING") throw new Error("该售后已处理或状态不对");
    if (!as.returnExpressNo) throw new Error("用户还未填写退货运单号");
    as.updatedAt = Date.now();
    pushTimeline(o, "商家已确认收到退货");
    // 确认收货与退款是同一个动作的两半，中间不留悬空态
    settleRefund(o, "退款已发起");
    persist();
    return delay(o.afterSale!);
  },

  // ---------------------------------------------------------------- 团购与报价
  async mGroupList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(
      db.groupSeeds
        .map(buildGroupBuy)
        .filter((g) => g.merchant.merchantNo === merchantNo),
    );
  },

  async mCreateGroup(goodsNo) {
    requireMerchant();
    const goods = toGoods(findGoodsSeed(goodsNo));
    // 商品没配 {起团人数, 团购价} 就不能开团 —— 团价从哪来？（需求 §五之四）
    if (!goods.groupBuy) throw new Error("该商品未配置团购价，先在商品里配置");
    // 截止时间取「团有效期」与「当日截单」的更早者：截单已过就只能开出一个死团
    // （倒计时直接 00:00:00），不如当场说清楚
    if (goods.cutoffAt && goods.cutoffAt <= Date.now()) {
      throw new Error("今日已截单，明天再开团");
    }
    const seed = {
      groupNo: nextNo("GB"),
      goodsNo,
      // 成团单位是自提点：拼的是一车送到一个点的成本，跨点凑人对成本无帮助
      pickupNo: db.merchant.pickupNo ?? allCommunitySeeds()[0]!.pickups[0]!.pickupNo,
      initiatorNickname: db.merchant.name || "商家",
      initiatorAvatar: db.merchant.logo || MERCHANT_LOGO_FALLBACK,
      createdAt: Date.now(),
      members: [],
      joined: false,
    };
    db.groupSeeds.unshift(seed as (typeof db.groupSeeds)[number]);
    persist();
    return delay(buildGroupBuy(seed as (typeof db.groupSeeds)[number]));
  },

  async mRequestList() {
    // 商家看得到所有开放中的需求单。初期靠运营人肉指派（P-8.2.2），
    // 这里先全量放出，商家自己挑 —— 需求少的时候人肉和自助没差别
    return delay(db.requests.filter((r) => r.status === "COLLECTING").map(toGroupRequest));
  },

  async mQuote(requestNo, payload) {
    const merchantNo = requireMerchant();
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求单不存在");
    if (seed.status !== "COLLECTING") throw new Error("该需求单已不接受报价");

    const exist = seed.quotes.find((q) => q.merchantNo === merchantNo);
    if (exist) {
      // 选定后锁价：加价在技术上做不到，不靠事前审核（ADR-003）
      if (exist.locked) throw new Error("已被选定并锁价，不能再改");
      // 改价留痕。**只公示涨价** —— 降价对邻居是好事，公示反而劝退商家降价
      if (payload.priceMinor > exist.priceMinor) {
        exist.revisions.push({ priceMinor: exist.priceMinor, at: Date.now() });
      }
      exist.priceMinor = payload.priceMinor;
      exist.minCount = payload.minCount;
      exist.desc = payload.desc;
    } else {
      seed.quotes.push({
        quoteNo: nextNo("QT"),
        merchantNo,
        priceMinor: payload.priceMinor,
        minCount: payload.minCount,
        desc: payload.desc,
        validUntil: Date.now() + 3 * 86400_000,
        createdAt: Date.now(),
        chosen: false,
        revisions: [],
        locked: false,
      });
    }
    persist();
    // **返回这条报价**，不是整张需求单：后端 /biz/group-request/{no}/quote 发的是 QuoteVO。
    // 此前返回需求单，端上拿到的字段与真机完全不同（只是没人用到，所以一直没暴露）
    // 复用 toGroupRequest 里那份换算（价格要按当前市场换算，自己再写一遍必漂）
    const mine = toGroupRequest(seed).quotes.find((q) => q.merchant.merchantNo === merchantNo)!;
    return delay(mine);
  },

  // ---------------------------------------------------------------- 评价
  async mReviewList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(db.reviews.filter((r) => r.merchantNo === merchantNo));
  },

  async mReplyReview(reviewNo, reply) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    r.reply = reply;
    persist();
    return delay({ ...r });
  },

  async mAppealReview(reviewNo, reason, images = []) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    // 只有低分可申诉：四星五星开放申诉，等于「凡是不满意的都申诉一遍」，
    // 平台裁决台会被淹掉，真正的恶意差评反而排不上
    if (r.rating > REVIEW_RULES.appealMaxRating) {
      throw new Error(`只有 ${REVIEW_RULES.appealMaxRating} 星及以下的评价可以申诉`);
    }
    if (r.appeal) throw new Error("该评价已申诉过，等待平台裁决");
    if (!reason.trim()) throw new Error("请填写申诉理由");

    r.appeal = {
      appealNo: nextNo("RA"),
      reason: reason.trim(),
      images,
      status: "PENDING",
      submittedAt: Date.now(),
    };
    pushMessage("SYSTEM", "申诉已提交", "平台会在 3 个工作日内给出裁决", "/pages/reviews/index");
    persist();
    return delay({ ...r });
  },

  // ---------------------------------------------------------------- 营销
  async mCampaignList() {
    const merchantNo = db.merchant.merchantNo;
    // 过期的活动自动置 ENDED：靠人手动结束的话，列表里永远挂着一堆「进行中」的死活动
    const now = Date.now();
    db.campaigns.forEach((c) => {
      if (c.status === "RUNNING" && c.endAt <= now) c.status = "ENDED";
    });
    return delay(db.campaigns.filter((c) => c.merchantNo === merchantNo));
  },

  async mSaveCampaign(payload) {
    const merchantNo = requireMerchant();
    if (payload.endAt <= payload.startAt) throw new Error("结束时间要晚于开始时间");
    // 限时特价必须限定商品：全店改价不是「特价」，是调价，走商品编辑
    if (payload.type === "FLASH" && !payload.goodsNos.length) {
      throw new Error("限时特价必须选择参与商品");
    }
    /*
     * 只有满减能限定门店（后端 70005）。判据是活动在哪一刻生效：
     * 满减在算价时生效，那时顾客已选好自提点；限时特价与买赠改的是商品页的展示，
     * 而浏览商品时自提点还没选 —— 会出现「页面 ¥9.90、下单 ¥12.80」。
     * mock 也要拒，否则开发期建得成、连真后端才被打回。
     */
    if (payload.storeNo && payload.type !== "FULL_CUT") {
      throw new Error("只有满减能限定门店");
    }
    if (payload.type === "COUPON" && !payload.totalCount) {
      // 不设上限的券等于开着口子发钱，预算穿了才发现就晚了
      throw new Error("店铺券必须设置发放总量");
    }

    if (payload.campaignNo) {
      const c = db.campaigns.find((x) => x.campaignNo === payload.campaignNo);
      if (!c) throw new Error("活动不存在");
      if (c.status === "ENDED") throw new Error("已结束的活动不能再改");
      Object.assign(c, payload);
      persist();
      return delay({ ...c });
    }

    const created: MarketingCampaign = {
      ...payload,
      campaignNo: nextNo("CP"),
      merchantNo,
      status: payload.startAt <= Date.now() ? "RUNNING" : "DRAFT",
      takenCount: 0,
      usedCount: 0,
      goodsNos: payload.goodsNos,
    };
    db.campaigns.unshift(created);
    persist();
    return delay({ ...created });
  },

  async mToggleCampaign(campaignNo, running) {
    const c = db.campaigns.find((x) => x.campaignNo === campaignNo);
    if (!c) throw new Error("活动不存在");
    // 已结束不可复活：时段已过，再打开只会得到一个立刻又结束的活动
    if (c.status === "ENDED") throw new Error("活动已结束，不能重新开启");
    c.status = running ? "RUNNING" : "PAUSED";
    persist();
    return delay({ ...c });
  },

  // ---------------------------------------------------------------- 客户与复购
  async mCustomers() {
    const merchantNo = db.merchant.merchantNo;
    const DAY = 86400_000;
    const map = new Map<
      string,
      { avatar: string; count: number; spent: number; last: number; owned: number }
    >();

    for (const o of db.orders) {
      if (o.status === "CANCELLED" || !belongsToMerchant(o, merchantNo)) continue;
      const key = o.buyerNickname ?? db.user.nickname;
      const cur = map.get(key) ?? { avatar: "🙂", count: 0, spent: 0, last: 0, owned: 0 };
      cur.count += 1;
      cur.spent += o.amount.payableMinor;
      cur.last = Math.max(cur.last, o.createdAt);
      if (o.trafficSource === "MERCHANT_OWNED") cur.owned += 1;
      map.set(key, cur);
    }

    const rows = [...map.entries()].map(([nickname, v]) => {
      const days = Math.floor((Date.now() - v.last) / DAY);
      return {
        nickname,
        avatar: v.avatar,
        orderCount: v.count,
        totalSpentMinor: v.spent,
        lastOrderAt: v.last,
        daysSinceLast: days,
        // 沉默 = **曾经常来**（买过 ≥2 次）**且**最近没来（超 14 天）。
        // 只看「久没来」会把只买过一次的路人也算进去 —— 那不是流失，是本来就没建立关系
        silent: v.count >= 2 && days >= 14,
        source: v.owned > v.count / 2 ? ("MERCHANT_OWNED" as const) : ("PLATFORM" as const),
      };
    });

    // 沉默的排前面：这是店主唯一能立刻行动的信号，埋在列表底部等于没有
    rows.sort((a, b) => Number(b.silent) - Number(a.silent) || b.orderCount - a.orderCount);
    return delay(rows);
  },

  // ---------------------------------------------------------------- 结算
  /**
   * 费率卡。**费率是万分比整数**（与后端 RateCardVO 一致）：2% 存成 200。
   * 直接当百分数显示会把 2% 显示成 200%，这种错在界面上看着还挺"合理"。
   */
  async mRateCard() {
    const pct = (r: number) => Math.round(r * 10000);
    return delay({
      merchantOwnedRate: pct(SETTLE.commissionRate.MERCHANT_OWNED),
      platformRate: pct(SETTLE.commissionRate.PLATFORM),
      note: "自带客流（扫店铺码进店）零佣金；平台客流按公示费率收取。费率以下单时快照为准，调整不影响历史订单。",
    });
  },

  async mSettleList(allStores) {
    const merchantNo = db.merchant.merchantNo;
    /*
     * **一个子订单一行**，与后端 stl_bill 同形 —— 这里此前造的是一套「按周聚合的账单」
     * （billNo / periodStart / orderCount），后端从来没有过那个模型。
     * 页面照着 mock 写，于是连真后端时字段整片 undefined，而 mock 下一直是绿的。
     */
    const settled = db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && ["COMPLETED", "REFUNDED"].includes(o.status),
    );
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    const scope = allStores ? null : home?.storeNo;

    return delay(
      settled
        .filter(() => !scope || Boolean(home))
        .map((o) => {
          const gross = o.amount.payableMinor;
          // 佣金按客流来源分档：自带客流零佣金（ADR-004 §6）
          const rate = SETTLE.commissionRate[o.trafficSource ?? "PLATFORM"];
          const commission = Math.round(gross * rate);
          // 自提点履约服务费按件。供货方付、承接方收，两个角色都是自己时账面抵消
          const serviceFee =
            o.fulfillment === "STORE_PICKUP"
              ? o.items.reduce((n, it) => n + it.qty, 0) * SETTLE.fulfillFeePerItemMinor
              : 0;
          return {
            settleNo: `SB${o.orderNo}`,
            subOrderNo: o.orderNo,
            orderNo: o.orderNo,
            merchantNo,
            grossMinor: gross,
            commissionMinor: commission,
            serviceFeeMinor: serviceFee,
            netMinor: gross - commission - serviceFee,
            trafficSource: o.trafficSource ?? "PLATFORM",
            commissionRate: Math.round(rate * 10000),
            // 退过款的走回退态：账面上不能出现「退过款还照结」的钱（ADR-002 §3）
            status: o.status === "REFUNDED" ? ("REVERSED" as const) : ("SPLIT" as const),
            createdAt: o.createdAt,
            splitAt: o.status === "REFUNDED" ? undefined : o.createdAt,
            storeNo: home?.storeNo,
            // 门店没单独配号就走主体默认号 —— 那就是合并结算
            payMerchantNo: home?.payMerchantNo ?? "PM-MOCK-ENTITY",
          };
        }),
    );
  },

  // ---------------------------------------------------------------- 到货异常
  async mReportShortage(subOrderNo, payload) {
    const o = findOrder(subOrderNo);
    const label = payload.kind === "SHORTAGE" ? "短少" : "破损";
    pushTimeline(o, `自提点上报${label}：${payload.note}`);
    // 只留痕、通知用户，**不自动退款** —— 责任在供货方还是承接方尚未定（矩阵 M4），
    // 自动退等于默认平台兜底
    pushMessage(
      "TRADE",
      `商品${label}已上报`,
      `${payload.note}。我们会尽快处理，你也可以直接申请售后`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(pickupView(o));
  },

  /**
   * 核销。**失败不抛异常，返回 `success: false` + reason** —— 与真实后端同口径。
   *
   * 此前 mock 用抛异常表达失败，而后端把失败当业务结果回（code 0）。
   * 端上照着 mock 写「try/catch，能走到下一行就是成功」，
   * 于是**真机上任何一次失败都提示「核销成功」**。
   * mock 与后端在「失败长什么样」上分岔，比在字段名上分岔危险得多。
   */
  async mVerify(code) {
    const o = db.orders.find((x) => x.verifyCode === code);
    if (!o) return delay({ success: false, subOrderNo: null, reason: "CODE_NOT_FOUND" });
    if (o.status === "COMPLETED") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "ALREADY_VERIFIED" });
    }
    if (o.status === "CANCELLED" || o.status === "REFUNDED") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "REFUNDED" });
    }
    if (o.status === "WAIT_PAY") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "NOT_PAID" });
    }
    const pickupNo = db.merchant.pickupNo;
    if (pickupNo && o.pickupNo && o.pickupNo !== pickupNo) {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "NOT_THIS_PICKUP" });
    }
    if (o.status === "PAID") {
      o.status = "FULFILLING";
      pushTimeline(o, "已到自提点");
    }
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已核销完成");
    persist();
    return delay({ success: true, subOrderNo: o.orderNo, reason: null });
  },

  /**
   * 批量核销。**逐条尝试、失败逐条回报**，不整批回滚 ——
   * 一张废码不该让另外四单白扫；而「3 成功 2 失败」这种汇总，店主还得自己一个个找出是哪两单。
   * 单条的三校验完全复用，避免两条路的规则各写一遍（那必然漂）。
   */
  async mVerifyBatch(codes) {
    const failed: VerifyBatchResult["failed"] = [];
    let successCount = 0;
    for (const code of codes) {
      try {
        await this.mVerify(code);
        successCount += 1;
      } catch (e) {
        failed.push({ code, reason: (e as Error).message });
      }
    }
    return delay({ successCount, failed });
  },

  /**
   * 按取货码**片段**搜单。输码核销走不通时的最后一条路：
   * 码磨花了、屏幕反光、邻居只记得后四位。
   *
   * 与真后端同口径：**子串匹配**（`contains`），且只在本自提点的单里找 ——
   * 跨点搜出来的单他也核销不了，列出来只会让人以为「明明有这单为什么核不了」。
   */
  async mVerifySearch(keyword) {
    const k = keyword.trim();
    const pickupNo = db.merchant.pickupNo;
    if (!k) return delay([]);
    return delay(
      db.orders
        .filter(
          (o) =>
            !!o.verifyCode
            && o.verifyCode.includes(k)
            && (!pickupNo || o.pickupNo === pickupNo),
        )
        .map(pickupView),
    );
  },

  // ---- 积分：商家只感知发分服务费与开关（V34）。
  // 抵扣、补差、资金池对他全部不可见 —— 他收到的是订单全额减各项费用。
  async mPointsAccount() {
    return delay(pointsAccount());
  },

  async mPointsRecords() {
    return delay(pointsFeeRecords());
  },

  async mPointsToggle(req) {
    pointsEnabled = req.enabled;
    return delay(pointsAccount());
  },

  // ---- 消息。mock 世界与 C 端共用一个消息池（没有 receiver 维度）——
  // 这里演示的是消息中心的交互，不是收件箱隔离；隔离由后端场景测试保证
  async mMessageList() {
    return delay([...db.messages].sort((a, b) => b.at - a.at));
  },

  async mMessageUnread() {
    return delay(db.messages.filter((m) => !m.read).length);
  },

  async mMessageRead(messageNo) {
    const m = db.messages.find((x) => x.messageNo === messageNo);
    if (m) m.read = true;
    persist();
    return delay([...db.messages]);
  },

  async mMessageReadAll() {
    db.messages.forEach((m) => (m.read = true));
    persist();
    return delay([...db.messages]);
  },

  // mock 世界没有真设备（H5 下 getPushDevice 恒为 null，这两个不会被调到）
  async mRegisterPushToken() {
    return delay(undefined);
  },

  async mUnregisterPushToken() {
    return delay(undefined);
  },
};
