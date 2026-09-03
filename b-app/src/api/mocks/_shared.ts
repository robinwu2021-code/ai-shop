// B 端 mock 的**内部工具与状态**：种子的派生、状态机断言、当前门店、演示用的模块级变量。
//
// <p>它就是原来 `mock.ts` 前 1000 行 —— 拆分时整体搬过来，一个字没改，
// 只把顶层声明加上 `export`，好让各域的替身文件能用。
//
// <p><b>模块级状态必须只有这一份</b>（`mockOutbounds` 这类）：
// 复制成两份的话，出库单在一处加、在另一处读不到，而两边都不会报错。
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
import { isPhone } from "@shared/utils/validate";
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
  STORAGE,
} from "@shared/utils/constants";
import { ensureDemoOrders } from "../demo-orders";
import { DELIVERY_SHAPE, fulfillmentsOf } from "@shared/strategies/order-view";

/**
 * 「要核销」的履约方式：自提点自提、邻居家自提、到店核销。
 *
 * **不再用状态区分**：`ARRIVED`（已到自提点）与 `SHIPPED`（已发货）曾是两个状态，
 * 其实是同一个 `FULFILLING` 乘上履约方式的两种展示。合并回一个之后，
 * 「待核销」这类筛选靠履约方式表达 —— 加一种要核销的履约（如到店核销）
 * 只需归进对应形态，状态一个不加。
 */
export const PICKUP_LIKE = new Set<string>(
  fulfillmentsOf(DELIVERY_SHAPE.SELF_PICKUP, DELIVERY_SHAPE.SELF_SERVE),
);
import type { GoodsDraft, MerchantApi, PublishPreview } from "../contract";

/** 本店积分开关。mock 内存态，真实实现在 usr_merchant.points_enabled */


/**
 * 发布重入标志（双版本）。mPublishGoods 复用 mSaveGoods 的整条落库路径 ——
 * 置位期间保存**直写种子**而不是再落一份草稿。与真后端 `PUBLISHING`
 * ThreadLocal 同一个手法：换版不另写第二套写入逻辑。
 */


/** 商家侧出口统一带上 hasDraft —— C 端的 toGoods 不带它（买家不消费编辑态） */
export function withDraftFlag(g: Goods): Goods {
  return { ...g, hasDraft: !!db.goodsDrafts[g.goodsNo] };
}

/**
 * 发分服务费明细：一单一条，真实数据来自 `stl_bill.points_fee_minor`。
 * mock 里按已有订单折算，让 B 端能看到「一单一条」的形状。
 */
export function pointsFeeRecords() {
  return db.orders.slice(0, 8).map((o) => ({
    settleNo: `ST${o.orderNo.slice(-8)}`,
    subOrderNo: o.orderNo,
    points: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio),
    feeMinor: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio / POINTS.perMinor),
    period: "202608",
    at: o.createdAt,
  }));
}

export function pointsAccount() {
  const expense = pointsFeeRecords().reduce((s, r) => s + r.feeMinor, 0);
  return {
    periodExpenseMinor: expense,
    period: "2026-08",
    enabled: mockState.pointsEnabled,
    disabledReason: mockState.pointsEnabled ? undefined : "本店未开启积分",
    forced: false,
  };
}
import type { StaffLogRow } from "@shared/mock/db";
import type {
  MyDebt,
  MySettleBatch,
  SettleBill,
  AppointmentSlot,
  ActivityConflict,
  CouponIssueBatch,
  MemberSegmentRule,
  MerchantCoupon,
  StoreActivity,
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
  SkuIdentityReport,
  StockBalance,
  Supplier,
  Carrier,
  StockLedgerRow,
  StockDocument,
  StockCount,
  StockTransfer,
} from "@shared/types";

/** 当前登录商家；未入驻时抛错，页面据此引导去入驻 */
export function requireMerchant(): string {
  if (!db.merchant.merchantNo) throw new Error("尚未入驻");
  return db.merchant.merchantNo;
}

/*
 * ── 商品编码（条码/货号/单位）批量导入导出的实现 ──────────────────────
 *
 * 规则与后端 `SkuIdentityServiceImpl` 逐条对齐。**这里最容易出的问题不是写错，
 * 是写宽**：mock 宽一分，商家就会在 mock 上验出一个后端不认的用法，
 * 而那种分歧的症状是「在我这儿好好的」。
 */

interface IdentityRow {
  skuNo: string;
  goods: string;
  spec: string;
  barcode?: string;
  code?: string;
  unit?: string;
}

/** 本店全部规格行。mock 的真源在 goodsSeeds[].skus 上，直接改那儿 */
export function identityRows(): IdentityRow[] {
  const merchantNo = requireMerchant();
  const out: IdentityRow[] = [];
  for (const g of db.goodsSeeds.filter((x) => x.merchantNo === merchantNo)) {
    for (const s of g.skus) {
      out.push({
        skuNo: s.skuNo,
        goods: pick(g.title),
        spec: (s.optionValues ?? []).map((v) => pick(v)).join(" · "),
        barcode: s.barcode,
        code: s.merchantSkuCode,
        unit: s.saleUnit,
      });
    }
  }
  return out;
}

export function csvCell(v?: string): string {
  if (!v) return "";
  return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}

/** 够用的 CSV 解析：双引号包裹、引号内的逗号与换行、"" 转义 */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  if (!text) return rows;
  const t = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  let cur: string[] = [];
  let cell = "";
  let inQuote = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (inQuote) {
      if (c === '"') {
        if (t[i + 1] === '"') { cell += '"'; i++; } else { inQuote = false; }
      } else cell += c;
      continue;
    }
    if (c === '"') inQuote = true;
    else if (c === ",") { cur.push(cell); cell = ""; }
    else if (c === "\r") { /* \r\n 与 \n 都收 */ }
    else if (c === "\n") { cur.push(cell); cell = ""; rows.push(cur); cur = []; }
    else cell += c;
  }
  if (cell || cur.length) { cur.push(cell); rows.push(cur); }
  return rows;
}

/**
 * 合并一个格子与现值 —— **整个功能的安全边界**：
 * 整列不在表头 → 原值；格子空 → 原值；格子写 `-` → 清空。
 */
export function mergeCell(cell: string | undefined, current: string | undefined, present: boolean) {
  if (!present || cell === undefined) return current;
  const v = cell.trim();
  if (!v) return current;
  return v === "-" ? undefined : v;
}

export function runIdentityImport(csv: string, write: boolean): SkuIdentityReport {
  const merchantNo = requireMerchant();
  const rows = parseCsv(csv);
  const problems: SkuIdentityReport["problems"] = [];
  const samples: SkuIdentityReport["samples"] = [];
  if (!rows.length) {
    return { total: 0, willSet: 0, noChange: 0, problems: [{ line: 1, reason: "文件是空的" }], samples };
  }

  const known = ["skuNo", "商品", "规格", "条码", "货号", "单位"];
  const col: Record<string, number> = {};
  (rows[0] ?? []).forEach((h, i) => {
    const t = (h ?? "").trim();
    const hit = known.find((k) => k.toLowerCase() === t.toLowerCase());
    if (hit && col[hit] === undefined) col[hit] = i;
  });
  if (col["skuNo"] === undefined && col["货号"] === undefined) {
    problems.push({ line: 1, reason: "表头里既没有 skuNo 也没有货号，认不出每一行对应哪个规格。请用导出的文件改，别自己新建" });
    return { total: 0, willSet: 0, noChange: 0, problems, samples };
  }

  // 真源是 goodsSeeds[].skus 上的那几个字段
  const all: { seedSku: Record<string, unknown>; row: IdentityRow }[] = [];
  for (const g of db.goodsSeeds.filter((x) => x.merchantNo === merchantNo)) {
    for (const s of g.skus) {
      all.push({
        seedSku: s as unknown as Record<string, unknown>,
        row: {
          skuNo: s.skuNo, goods: pick(g.title),
          spec: (s.optionValues ?? []).map((v) => pick(v)).join(" · "),
          barcode: s.barcode, code: s.merchantSkuCode, unit: s.saleUnit,
        },
      });
    }
  }
  const bySkuNo = new Map(all.map((x) => [x.row.skuNo, x]));
  const byCode = new Map(all.filter((x) => x.row.code).map((x) => [x.row.code as string, x]));

  const cell = (r: string[], name: string) => {
    const i = col[name];
    return i === undefined || i >= r.length ? undefined : r[i];
  };
  const codeTakenAt = new Map<string, number>();
  const touched = new Set<string>();
  const pending: { target: Record<string, unknown>; barcode?: string; code?: string; unit?: string }[] = [];
  let total = 0, willSet = 0, noChange = 0;

  for (let i = 1; i < rows.length; i++) {
    const r = rows[i] ?? [];
    const line = i + 1;
    if (r.every((c) => !c || !c.trim())) continue;   // 尾部空行是常态
    total++;

    const skuNo = cell(r, "skuNo");
    const rawCode = cell(r, "货号");
    let hit = undefined as (typeof all)[number] | undefined;
    if (skuNo && skuNo.trim()) {
      hit = bySkuNo.get(skuNo.trim());
      if (!hit) { problems.push({ line, reason: skuNo + " 不是本店的规格行" }); continue; }
    } else if (rawCode && rawCode.trim() && rawCode.trim() !== "-") {
      // 货号回退：他的 ERP 只认货号 —— 先解析成行，再谈写什么
      hit = byCode.get(rawCode.trim());
      if (!hit) { problems.push({ line, reason: "货号 " + rawCode.trim() + " 在本店找不到对应的规格行" }); continue; }
    } else {
      problems.push({ line, reason: "这一行既没有 skuNo 也没有货号，认不出改哪一行" });
      continue;
    }

    if (touched.has(hit.row.skuNo)) {
      problems.push({ line, reason: "同一个规格行在文件里出现了不止一次" });
      continue;
    }
    touched.add(hit.row.skuNo);

    const barcode = mergeCell(cell(r, "条码"), hit.row.barcode, col["条码"] !== undefined);
    const code = mergeCell(rawCode, hit.row.code, col["货号"] !== undefined);
    const unit = mergeCell(cell(r, "单位"), hit.row.unit, col["单位"] !== undefined);

    if (code && code !== hit.row.code) {
      const owner = byCode.get(code);
      const dup = codeTakenAt.get(code);
      if (owner && owner.row.skuNo !== hit.row.skuNo) {
        problems.push({ line, reason: "货号 " + code + " 已经被本店另一个规格行占着" });
        continue;
      }
      if (dup !== undefined) {
        problems.push({ line, reason: "货号 " + code + " 与第 " + dup + " 行重复" });
        continue;
      }
      codeTakenAt.set(code, line);
    }

    if (barcode === hit.row.barcode && code === hit.row.code && unit === hit.row.unit) {
      noChange++;
      continue;
    }
    if (samples.length < 20) {
      samples.push({
        skuNo: hit.row.skuNo, goods: hit.row.goods, spec: hit.row.spec,
        barcodeFrom: hit.row.barcode, barcodeTo: barcode,
        codeFrom: hit.row.code, codeTo: code,
        unitFrom: hit.row.unit, unitTo: unit,
      });
    }
    willSet++;
    pending.push({ target: hit.seedSku, barcode, code, unit });
  }

  if (write) {
    for (const p of pending) {
      p.target.barcode = p.barcode;
      p.target.merchantSkuCode = p.code;
      p.target.saleUnit = p.unit;
    }
    persist();
  }
  return { total, willSet, noChange, problems, samples };
}

export function findOrder(orderNo: string): Order {
  const o = db.orders.find((x) => x.orderNo === orderNo);
  if (!o) throw new Error(`订单不存在：${orderNo}`);
  return o;
}

/**
 * 退款落账。与 C 端 `settleRefund` 同一套规则：订单置 REFUNDED + 收回已发积分 + 返还抵扣积分。
 * 两端各写一份是因为 mock 分端，真实后端只会有一处。
 */
export function settleRefund(o: Order, label: string) {
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

export function pushTimeline(order: Order, label: string) {
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
export function belongsToMerchant(o: Order, merchantNo: string): boolean {
  if (o.merchantNo) return o.merchantNo === merchantNo;
  return o.items.some((it) => {
    try {
      return findGoodsSeed(it.goodsNo).merchantNo === merchantNo;
    } catch {
      return false;
    }
  });
}

// ------------------------------------------------ 账期（T2 / T3）
//
// 账期的两个时刻在 mock 里也要**分开算**，因为它们回答的不是同一个问题：
//   T2 可结算 = 履约完成 + 售后期，说的是「这笔钱不会再被退回去了」；
//   T3 应结日 = T2 之后按账期规则落到的那一天的**零点**，说的是「哪天放」。
// 合成一个数的话，「售后期还没过」和「账期还没到」在界面上就成了同一句话，
// 而商家能自己解决的只有前者（催买家确认收货）。

/** mock 售后期：7 天。真值由后端按类目给，端上不自己算 */
export const AFTER_SALE_MS = 7 * 86_400_000;

/** T+1 应结日 = 可结算次日**零点**（与后端 SettleCycles.dueAt 同口径） */
export function dueOf(settleableAt: number): number {
  const d = new Date(settleableAt + 86_400_000);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/**
 * 批次号只由应结日决定。**结算单上的批次号与账期页的必须是同一个** ——
 * 各算各的话，商家从单子上抄下批次号在账期页里搜不到。
 */
export function batchNoOf(dueAt: number): string {
  /*
   * **按本地日期拼，不能用 toISOString** —— 应结日是本地零点，
   * 转 UTC 后退到前一天，于是界面上「08-04 应结」配着一个 …0803 的批次号。
   * 商家会以为自己看错了行，客服照着号也查不到。第一次看页面就撞见了。
   */
  const d = new Date(dueAt);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `STB${d.getFullYear()}${mm}${dd}`;
}

/**
 * 批次状态**只由应结日推**，不看数组下标。
 *
 * 下标法（「第一批算挂起」）在两个接口里会给出不同答案：
 * 结算单列表和账期列表筛的单子不一样，同一批在两页上就成了两个状态 ——
 * 而商家看到的是「结算单说已放款，账期页说挂起」。
 *
 * 规则：还没到期 = 收单中；刚到期那一批 = 挂起（**这条渲染路径要有人看过**）；
 * 更早的 = 已放款。
 */
export function batchStateOf(dueAt: number, now = Date.now()): {
  status: MySettleBatch["status"];
  blockedReason?: string;
  blockExpireAt?: number;
  releasedAt?: number;
} {
  if (dueAt > now) return { status: "DRAFT" };
  /*
   * 挂起窗口取 7 天而不是 1 天：T+1 的应结日落在**零点**，
   * 「刚过期一天以内」这个窗口在一天里绝大多数时刻都是空的 ——
   * 于是挂起这条渲染路径在 mock 下几乎永远看不到，
   * 而它恰恰是这一页唯一需要商家看懂的状态。
   */
  if (dueAt > now - 7 * 86_400_000) {
    return {
      status: "BLOCKED",
      // 挂起原因**带具体数字与阈值**：只说「风控拦截」的话，商家除了打客服没有别的动作
      blockedReason: "本批退款率 12.5%（近 7 天），高于 10% 的复核线，已转人工复核。",
      // 时限不能落在过去：显示一个已经过去的「自动放行时刻」而批次还挂着，
      // 是页面上自相矛盾的一句话
      blockExpireAt: Math.max(dueAt + 2 * 86_400_000, now + 86_400_000),
    };
  }
  return { status: "RELEASED", releasedAt: dueAt + 3_600_000 };
}

export function myGoods(): Goods[] {
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
export const MOCK_PLAN_KEY = "mock:plan";

/**
 * 由订单聚合出会员名单（mock 专用）。
 *
 * <p>分层口径与后端一致：**先判沉睡（60 天没来）再判活跃**，
 * 否则一个三个月没来的熟客会显示成「熟客」，商家照着给他发熟客专享。
 */
export function mockMembers() {
  const DAY = 86400_000;
  const map = new Map<string, { count: number; spent: number; last: number; owned: number }>();
  for (const o of db.orders) {
    if (o.status === "CANCELLED" || !belongsToMerchant(o, db.merchant.merchantNo)) continue;
    const key = o.buyerNickname ?? db.user.nickname;
    const cur = map.get(key) ?? { count: 0, spent: 0, last: 0, owned: 0 };
    cur.count += 1;
    cur.spent += o.amount.payableMinor;
    cur.last = Math.max(cur.last, o.createdAt);
    if (o.trafficSource === "MERCHANT_OWNED") cur.owned += 1;
    map.set(key, cur);
  }
  const rows = [...map.entries()].map(([name, v], i) => {
    const days = Math.floor((Date.now() - v.last) / DAY);
    const d90 = days <= 90 ? v.count : 0;
    const level = days > 60 ? "SLEEPING" : d90 >= 6 ? "LOYAL" : d90 >= 2 ? "REGULAR" : "NEW";
    return {
      memberNo: `MB-MOCK-${i + 1}`,
      personNo: `PS-MOCK-${i + 1}`,
      phoneTail: String(1000 + ((name.length * 37 + i * 13) % 9000)),
      status: "ACTIVE",
      source: v.owned > v.count / 2 ? "SHARE" : "ORDER",
      level,
      firstStoreNo: db.stores[0]?.storeNo ?? null,
      orderCount: v.count,
      totalSpentMinor: v.spent,
      d90OrderCount: d90,
      lastOrderAt: v.last,
      daysSinceLast: days,
      reachOptOut: false,
      remark: null,
      joinedAt: v.last - v.count * DAY,
      nickname: name,
    };
  });
  // 沉睡置顶：那是店主唯一能立刻行动的信号，埋在列表底部等于没有
  rows.sort((a, b) =>
    Number(b.level === "SLEEPING") - Number(a.level === "SLEEPING") || b.orderCount - a.orderCount);
  return rows;
}

/** 手工录入的线索 + 订单聚合出来的会员，合在一起就是这家店的名单 */
export function allMockMembers() {
  return [...db.memberLeads, ...mockMembers()];
}

export function mockMemberTags(): Record<string, string[]> {
  return db.memberTagRel;
}

export function countTag(tagNo: string) {
  return Object.values(db.memberTagRel).filter((tags) => tags.includes(tagNo)).length;
}

/**
 * 按人群条件筛人。**与列表筛选同一处**（真库那边也是一个 baseQuery）——
 * 两处各写一遍，同一群人会算出两个数，而演示时没人分得清哪个对。
 *
 * <p>标签**取交集**：选两个标签是「都要满足」。
 */
export function matchSegment(rule: MemberSegmentRule) {
  let out = allMockMembers();
  if (rule.level) out = out.filter((m) => m.level === rule.level);
  if (rule.source) out = out.filter((m) => m.source === rule.source);
  if (rule.status) out = out.filter((m) => m.status === rule.status);
  if (rule.tagNos?.length) {
    out = out.filter((m) =>
      rule.tagNos!.every((t) => (db.memberTagRel[m.memberNo] ?? []).includes(t)));
  }
  if (rule.lastOrderBefore) {
    out = out.filter((m) => (m.lastOrderAt ?? 0) <= rule.lastOrderBefore!);
  }
  if (rule.lastOrderAfter) {
    out = out.filter((m) => (m.lastOrderAt ?? 0) >= rule.lastOrderAfter!);
  }
  if (rule.spentMin) out = out.filter((m) => m.totalSpentMinor >= rule.spentMin!);
  if (rule.spentMax) out = out.filter((m) => m.totalSpentMinor <= rule.spentMax!);
  return out;
}

/** 字典 + 人数。**人数是数出来的**，与真库一样不存冗余列 */
export function mockTags() {
  return db.memberTags
    .filter((t) => t.status !== "MERGED")
    .map((t) => ({ ...t, count: countTag(t.tagNo) }));
}

/** 机审词表，取自 V10 种进 sys_setting 的那份前几条。命中即转人审，不是直接拒 */
export const SENSITIVE_WORDS = ["最低价", "全网第一", "国家级", "微信", "加V"];
type MockPlan = "FREE" | "PRO" | "CHAIN";

export function mockPlan(): MockPlan {
  const saved = uni.getStorageSync(MOCK_PLAN_KEY) as string;
  if (saved === "FREE" || saved === "PRO" || saved === "CHAIN") return saved;
  return db.storeQuota > 1 ? "PRO" : "FREE";
}

/**
 * mock 下的三档定义。**与 V150 的种子逐字一致**（FREE 1/0、PRO 3/3、CHAIN 10/15）——
 * 自造一套额度的话，套餐页上的数字与建店时那道闸给出的数字对不上，
 * 而两处都是「真的」，谁也说不清哪个错。
 */
export const MOCK_TIERS = [
  { planCode: "FREE", name: "孵化版", storeQuota: 1, staffQuota: 0, crossStoreStats: false, trialDays: 0 },
  { planCode: "PRO", name: "成长版", storeQuota: 3, staffQuota: 3, crossStoreStats: true, trialDays: 14 },
  { planCode: "CHAIN", name: "连锁版", storeQuota: 10, staffQuota: 15, crossStoreStats: true, trialDays: 14 },
] as const;

/** 试用开通时刻。有它才能算出「还剩几天」——试用期内店主会反复进来看这个数 */
export const MOCK_TRIAL_KEY = "mock:plan:trial-at";

/**
 * 我的套餐视图。用量**现算**（与建店闸门同口径：只数营业中的店），
 * 不存一份计数器 —— 存了就会与门店列表对不上，而那是最容易被店主发现的不一致。
 */
export function minePlan(): MerchantPlan {
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
export function requireCrossStoreStats(): void {
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
export function ensureCrossStoreDemoStores(): void {
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
export function storeRating(rows: { orderNo: string }[], reviews: { orderNo?: string; rating: number }[]) {
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
export function crossStoreStores(): Store[] {
  ensureCrossStoreDemoStores();
  return db.stores;
}

/** 计入统计的订单：我的、且未取消（与 mStats 同一口径） */
export function crossStoreOrders(): Order[] {
  const merchantNo = db.merchant.merchantNo;
  return db.orders.filter((o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED");
}

export function sumPayable(list: Order[]): number {
  return list.reduce((s, o) => s + o.amount.payableMinor, 0);
}

/** 把一个单号稳定地散到某一家店上 —— 同一个号每次都落到同一家，刷新不跳 */
export function hashPick(key: string, stores: Store[]): string {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) % 100_000;
  return stores[h % stores.length]?.storeNo ?? "";
}

/**
 * <b>当前门店（mock 版）</b>。
 *
 * <p>mock 不走 HTTP，读不到 `X-Store-No` —— 但 pinia 切店时会把门店号落到
 * `STORAGE.storeNo`，读同一个键即可（`mStoreQrcode` 早先就是这么做的）。
 *
 * <p><b>为什么必须有它</b>：后端有 5 个 B 端控制器按当前门店取数
 * （工作台统计与待办、订单、商品、库存、配送规则），而 mock 一处都不认。
 * 表现是**切了门店，界面纹丝不动** —— 而这正是「门店切换好像没做好」的样子：
 * 切店本身是好的（存储写了、请求头也带了），只是替身看不见它。
 *
 * <p>单店返回空 = 不筛，与后端 `currentStoreScope()` 的口径一致。
 */
export function currentStoreNo(): string {
  if (db.stores.length <= 1) return "";
  try {
    return (uni.getStorageSync(STORAGE.storeNo) as string) || "";
  } catch {
    return "";
  }
}

/** 按当前门店筛订单。单店 / 没切过店时原样返回 —— 与后端「不限定即全主体」一致。 */
export function scopedToStore(list: Order[]): Order[] {
  const cur = currentStoreNo();
  if (!cur) return list;
  return list.filter((o) => storeOfOrder(o, db.stores) === cur);
}

/**
 * 这一单算哪家店的。
 *
 * mock 的订单种子上**没有 storeNo**（它比多门店早），而按店分组是这两页的全部内容。
 * 所以这里按单号散列指派，且**只是指派，不是编造**：各店之和恒等于主体总数，
 * 「总览说 3 单、点进去只有 2 单」那类矛盾在 mock 上也不会出现。
 * 真实后端读的是 `ord_order.store_no`，历史空值单不计入任何一行。
 */
export function storeOfOrder(o: Order, stores: Store[]): string {
  return hashPick(o.orderNo, stores);
}

/** 规格名与选项仍是单语录入（模板本身跨语言，见 M8 未覆盖项），照旧抄三语 */
export function toI18n(text: string) {
  return { "zh-CN": text, en: text, ar: text };
}

/**
 * 商品文案三语落库。**留空的语言回落中文，但不假装它被翻译过** ——
 * 机翻的商品名会直接出现在下单页与小票上，错了没人兜底；
 * 回落至少是诚实的，而且平台端能按「未翻译」筛出来补。
 */
export function fillI18n(text: I18nText): I18nText {
  const zh = text["zh-CN"].trim();
  return {
    "zh-CN": zh,
    en: text.en.trim() || zh,
    ar: text.ar.trim() || zh,
  };
}

/** 按售后单号取订单。售后是独立资源，mock 里仍存在 Order 上 —— 寻址方式与契约一致即可 */
export function findOrderByAfterSale(afterSaleNo: string): Order {
  const o = db.orders.find((x) => x.afterSale?.afterSaleNo === afterSaleNo);
  if (!o) throw new Error("售后单不存在");
  return o;
}

/** 取「待处理」的售后单 —— 同意与驳回的前置校验完全相同，抽出来免得两处各写一遍 */
export function takePendingAfterSale(afterSaleNo: string): Order {
  const o = findOrderByAfterSale(afterSaleNo);
  // 判据是**售后单**的状态，不是订单的 —— 订单在售后期间保持原状态
  if (o.afterSale!.status !== "APPLIED") throw new Error("该售后已处理过");
  return o;
}

/**
 * 这张证照的资质桶。`entityNo` 为空 = 当前证照。
 *
 * <p>**分桶存**是这段 mock 的重点：资质挂在证照上，不是挂在账号上。
 * 共用一份的话，「在证照详情页看的是第二张、传上去却落到第一张」——
 * 这个最要命的错在 mock 下永远看不出来，而它正是 entityNo 这个参数要防的事。
 *
 * <p>不认识的证照号**直接拒**，与后端同一口径（403 而不是静默落到当前那张）。
 */
export function qualsOf(entityNo?: string) {
  if (!entityNo || entityNo === db.merchant.merchantNo) return db.myQualifications;
  if (entityNo === db.secondEntity.entityNo) return db.secondEntityQualifications;
  throw new ApiError(10403, "这张证照不属于你");
}

export function requireStore(storeNo: string) {
  const s = db.stores.find((x) => x.storeNo === storeNo);
  if (!s) throw new Error("门店不存在");
  return s;
}

export function requireStaff(mchAccountNo: string) {
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
export function logStaff(
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
export function usersOfRole(roleCode: string) {
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
export function pickupView(o: (typeof db.orders)[number]) {
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
export function roleName(code: string) {
  return db.roles.find((r) => r.roleCode === code)?.name ?? code;
}

export function permLabel(code: string) {
  return db.permLabels[code] ?? code;
}

/**
 * 自定义角色不能带 `biz:store:admin` —— 与后端同一条边界。
 *
 * mock 也要拦：只有后端拦的话，开发期能建出一个「副老板」角色，
 * 连上真后端才发现建不了，而那时界面已经按「能建」画好了。
 */
export function assertAssignable(perms: string[]) {
  const bad = perms.filter((p) => p === "biz:store:admin" || p === "*");
  if (bad.length) throw new Error("管员工的权限不能授给自定义角色");
  if (!perms.length) throw new Error("至少勾一项权限");
  return [...perms];
}

export function logStaffRole(action: string, roleCode: string, detail: string) {
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
export function maskPhone(phone: string) {
  return phone.length < 7 ? phone : `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

/**
 * 类目节点上的 `template`（形态的另一套码）。**深度优先找，找不到返回 undefined。**
 *
 * <p>建品时用它把形态算出来，与真后端的 `CategoryServiceImpl.categoryTypeOf` 同一条规则。
 * mock 自己算而不是抄请求体：请求体里已经没有 `type` 了，而「mock 上建出来是生鲜、
 * 连真后端变成日用品」正是这套 mock 最该防的那类错配。
 */
export function findCategoryTemplate(categoryNo: string | undefined): string | undefined {
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



/** 类目树压平成 categoryNo → 节点。两处要按编号取名字，各写一遍迟早会分叉 */
export function flatCategories(nodes: Category[], into = new Map<string, Category>()) {
  for (const n of nodes) {
    into.set(n.categoryNo, n);
    if (n.children?.length) flatCategories(n.children, into);
  }
  return into;
}


/** 门店送货方式的 mock 存储（内存即够：mock 不需要跨会话） */
export const mockFulfillment: Record<string, import("@shared/types").StoreFulfillment> = {};
/** 本店自建的取货点（P1）：mock 里只活在内存，刷新即清 */
export const mockSelfBuilt: import("@shared/types").PickupCandidate[] = [];

/**
 * 运行时新建一条聚落种子。
 *
 * <p><b>名字与地址必须是 I18nText</b>（`{ "zh-CN", en, ar }`）—— 种子里所有文案都是这个形状，
 * `toCommunity` 用 `pick()` 取值。这里塞裸字符串不会报错，但取出来是 `undefined`：
 * 顶部清单显示成一串社区号、查重按名字比对永远不相等（于是同一个小区能加进去两次）、
 * 跨级搜索里 `name.includes()` 直接抛异常 —— 三个症状没有一个指向真正的原因。
 */
export function newCommunitySeed(name: string, address?: string, streetCode?: string, kind = "ESTATE") {
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

/**
 * 小区缓存的 mock：一片一条，进程内存着就够验端上那条「读→补→写回」的流程。
 * 按 parentCode 归属（与后端同口径）—— 已开通社区的 scope 是 `C<号>`，
 * 不是区划码前缀，靠前缀匹配的写法会把它漏掉。
 */
export const mockEstateCache = new Map<string, { parentCode: string; items: import("../contract").EstateItem[] }>();

/**
 * 本店规格覆盖的 mock（对应 `prd_merchant_spec_override`）。
 *
 * <p>**此前这里什么都不存** —— `mSaveSpecOverride` 只把提交的内容原样回一份，
 * 刷新就没了，而 `mSpecTemplates` 从来不看它。于是「本店叫法」「停掉的档位」
 * 这两件事**在 mock 上永远看不出做没做**，只有连真后端才验得到。
 * 这一层补上之前，我自己在 mock 上判断过三次「联动没做」，三次都是错的。
 */
export const mockSpecOverride = new Map<string, {
  dimNo: string; enabled: boolean; label?: string;
  values: { code: string; enabled: boolean }[];
}[]>();

/** 本次会话里建出来的出库单。**替身要留痕** —— 吞掉请求的替身验不出「带没带上」 */
export const mockOutbounds: StockDocument[] = [];

/** 三条刻意各是一种状态（在用 / 平台档案 / 已停用）—— 见 mSuppliers 的注释 */
export const mockSuppliers: Supplier[] = [
  { supplierNo: "SUP-M1", name: "老周粮油", shortName: "老周", contactName: "周老板",
    contactPhone: "13800000001", remark: null, status: "ACTIVE", fromPlatform: false },
  { supplierNo: "SUP-M2", name: "平台直供·中粮", shortName: "中粮", contactName: null,
    contactPhone: null, remark: null, status: "ACTIVE", fromPlatform: true },
  { supplierNo: "SUP-M3", name: "已经不合作的那家", shortName: null, contactName: null,
    contactPhone: null, remark: null, status: "ARCHIVED", fromPlatform: false },
];

/**
 * <b>跨域可变的三个开关</b>。
 *
 * <p>原来是三个模块级 `let`（同一个文件里随手改）。拆成多文件之后，
 * 别的域**改不了 import 进来的绑定** —— 所以收进一个对象：
 * 谁都能读、谁都能改，而且只有这一份。复制成两份的话，
 * 「关了积分，另一处仍按开着算」这类事不会有任何报错。
 */
export const mockState = {
  /** 积分开关（商家侧只感知发分服务费与开关，V34） */
  pointsEnabled: true,
  /** 双版本发布的重入标志：换版时复用 mSaveGoods 的整条落库路径 */
  publishingDraft: false,
  /** 演示用的登录密码。空 = 还没设过 */
  password: "",
};

// ── 原文件里排在 mockApi **之后**的那部分工具（进销存的余额派生等）

// ── 进销存的假数据 ──────────────────────────────────────────────────────

/**
 * 安全库存阈值的替身状态。**必须是可变的**：设完读不回来的替身会把
 * 「只写不读」那一类缺陷整个盖住 —— 界面上看不出区别，而闸门全绿。
 * 同一条教训在调拨发货上吃过一次（三列写进了库，VO 一个都没下发）。
 *
 * key：`itemId` 是物料默认值，`itemId@locationId` 是库位覆盖。
 * 初值给 I1 一个 5 —— 它的可用是 3，于是「缺货」是**算出来的**而不是写死的。
 */
export const mockSafety = new Map<string, number>([["I1", 5]]);

/**
 * 条码 → itemId。**只种一条**：线上 `prd_sku.barcode` 是 0/396，
 * 替身给满的话「第一天扫什么都不中」这个事实就被盖住了 ——
 * 而那正是这个功能上线后最需要有人预期到的一件事。
 */
export const mockBarcodes = new Map<string, string>([["6901234567892", "I1"]]);

/** 缺货判据，与后端 `StockQueryServiceImpl.build` 同一套：阈值优先，否则看可用是否见底 */
export function shortage(available: number, safety: number): boolean {
  return safety > 0 ? available < safety : available <= 0;
}

/**
 * 当前门店的库存。<b>散列指派，不是编造</b>：同一件货每次都落到同一家店，
 * 各店之和恒等于主体总数 —— 「总览说 216 件、点进去只有 80」那类矛盾不会出现。
 * 真实后端读的是这家店自己的库存行（inv_balance.store_no）。
 */
export function scopedBalances(): StockBalance[] {
  const all = invBalances();
  const cur = currentStoreNo();
  if (!cur) return all;
  const idx = db.stores.findIndex((x) => x.storeNo === cur);
  return all.filter((b, i) => {
    let h = i;
    for (const ch of b.itemId) h = (h * 31 + ch.charCodeAt(0)) % 100_000;
    return h % db.stores.length === (idx < 0 ? 0 : idx);
  });
}

/**
 * <b>按门店的门面资料与配送规则</b>。
 *
 * <p>共享 mock 库里那两份是**主体级单例**（`db.store` / `db.deliveryRule`，
 * 一期单店时够用）。多门店之后它们是每家店各一份 —— 后端就是这样
 * （`BizMerchantController` / `BizDashboardController` 都读 `currentStoreNo()`）。
 * 不分开的话，店主切到第二家店看到的是第一家的地址与配送范围，
 * 而界面上没有任何地方告诉他这一点。
 *
 * <p>只放在 b-app 的替身里、不动共享库：这是 B 端的形状，C 端读的仍是主体那份。
 */
export const storeOverrides = new Map<string, Partial<typeof db.store>>();
export const deliveryOverrides = new Map<string, typeof db.deliveryRule>();

export function invBalances(): StockBalance[] {
  /*
   * **flags 由数算出来，不写死。** 写死的话，改完阈值列表纹丝不动 ——
   * 而「改了阈值，那件货进不进缺货」正是这一屏要验的唯一一件事。
   */
  const withFlags = (b: StockBalance): StockBalance => {
    const safety = mockSafety.get(b.itemId) ?? 0;
    const rest = b.flags.filter((f) => f !== "SHORTAGE");
    return {
      ...b,
      safetyStock: safety,
      flags: shortage(b.available, safety) ? ["SHORTAGE", ...rest] : rest,
    };
  };
  return [
    { itemId: "I1", name: "东北大米", specText: "5斤装", baseUom: "袋",
      onHand: 5, reserved: 2, available: 3, flags: [], lastMovedAt: "2026-08-26T14:22:00" },
    { itemId: "I2", skuNo: "SK0002", name: "小米", specText: "2斤装", baseUom: "袋",
      onHand: 0, reserved: 0, available: 0, flags: [], lastMovedAt: "2026-08-24T10:00:00" },
    { itemId: "I3", name: "陈醋", specText: "500ml", baseUom: "瓶",
      onHand: 24, reserved: 0, available: 24, flags: ["STALE"], lastMovedAt: "2026-05-26T09:00:00" },
    { itemId: "I4", name: "土鸡蛋", specText: "30枚装", baseUom: "箱",
      onHand: 48, reserved: 0, available: 48, flags: [], lastMovedAt: "2026-08-25T18:40:00" },
    /*
     * **这两行是线上那个场景的复制品**：同名、同规格、同单位、**库存也一样**，
     * 唯一的差别是其中一件的来源商品已经下架（线上量到 13 组这样的，
     * 其中一组三行「金龙鱼调和油 5L · 5L　80」完全一样）。
     *
     * 不造这两行，「已下架」那个标记在 mock 上就永远看不见 —— 而它正是
     * 这次要验的东西。同一条教训刚在调拨发货上吃过：替身太干净会盖住真缺陷。
     */
    { itemId: "I5", skuNo: "SK0005", name: "金龙鱼调和油 5L", specText: "5L", baseUom: "桶",
      onHand: 80, reserved: 0, available: 80, flags: [], lastMovedAt: "2026-08-27T18:11:00" },
    { itemId: "I6", skuNo: "SK0006", name: "金龙鱼调和油 5L", specText: "5L", baseUom: "桶",
      onHand: 80, reserved: 0, available: 80, flags: ["OFF_SALE"],
      lastMovedAt: "2026-08-27T18:11:00" },
  ].map(withFlags);
}

export function invLedger(): StockLedgerRow[] {
  return [
    { id: 8812345, itemId: "I1", itemName: "东北五常大米", docKind: "OUT", docNo: "OUT-2408260031",
      reasonCode: "SALE", qtyDelta: -2, balanceAfter: 3, occurredAt: "2026-08-26T14:22:00", operator: "系统" },
    // 同一张单动两件货 —— 按单查那一屏要看得出这一点，一行的假数据看不出
    { id: 8812346, itemId: "I4", itemName: "土鸡蛋", docKind: "OUT", docNo: "OUT-2408260031",
      reasonCode: "SALE", qtyDelta: -1, balanceAfter: 48, occurredAt: "2026-08-26T14:22:00", operator: "系统" },
    { id: 8812344, itemId: "I1", itemName: "东北五常大米", docKind: "OUT", docNo: "CNT-24082601",
      reasonCode: "COUNT_LOSS", qtyDelta: -1, balanceAfter: 5, occurredAt: "2026-08-26T09:10:00", operator: "张伟" },
    { id: 8812343, itemId: "I1", itemName: "东北五常大米", docKind: "IN", docNo: "IN-24082502",
      // 6 而不是 20：这一行是最早的一笔，balanceAfter 是 6，
      // 写 20 的话界面上算出「前 = −14」—— 库存不可能是负的，假数据要自洽
      reasonCode: "PURCHASE", qtyDelta: 6, balanceAfter: 6, occurredAt: "2026-08-25T18:40:00", operator: "老板" },
  ];
}

/** mock 里作废过的单号。**要能看见结果** —— 空实现的话点完列表纹丝不动，
 *  分不清是「没生效」还是「生效了但列表没刷」 */
export const voidedDocs = new Set<string>();
/** 调拨发货信息。**进程内，刷新即失** —— 替身不是数据库，够验一条闭环即可 */
export const shipped = new Map<string, { carrierName?: string; trackingNo?: string }>();

/*
 * **种子按后端真实下发的形状写，不写成好看的样子。**
 *
 * 这几行此前把 `subtitle` 手写成中文（「报损」），而后端下发的是裸枚举 `SCRAP` ——
 * 于是「商家看到的是英文枚举」这个缺陷在替身上一处都看不出来，
 * 直到 2026-09-02 加 RETURN_SUPPLIER 时才撞见。现在码走 `label`、文案回端上，
 * 种子也跟着改成码，替身与后端说同一种话。
 */
export function invDocuments(): StockDocument[] {
  return [
    { kind: "OUT", docNo: "OUT-2408260031", status: "POSTED", label: "SALE",
      subtitle: "SO-88213",
      totalQty: -2, occurredAt: "2026-08-26T14:22:00", operator: "系统" },
    { kind: "OUT", docNo: "OUT-2408260029", status: "POSTED", label: "COUNT_LOSS",
      subtitle: "CNT-24082601",
      totalQty: -3, occurredAt: "2026-08-26T09:10:00" },
    { kind: "OUT", docNo: "OUT-2408260028", status: "DRAFT", label: "BROKEN",
      totalQty: -2, occurredAt: "2026-08-26T08:50:00", operator: "张伟" },
    { kind: "IN", docNo: "IN-24082502", status: "POSTED", label: "PURCHASE",
      subtitle: "老周粮油",
      totalQty: 54, occurredAt: "2026-08-25T18:40:00", operator: "老板" },
    { kind: "TRANSFER", docNo: "TRF-24082507", status: "SHIPPED", label: "TRANSFER",
      subtitle: "城西仓 → 文三路店",
      totalQty: 20, occurredAt: "2026-08-26T07:30:00" },
  ];
}
