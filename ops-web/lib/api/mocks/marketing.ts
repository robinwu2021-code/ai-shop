// 覆盖范围：券 / 活动 / 内容位（P-7）。预算与时间校验在此强制。
import * as db from "@/lib/mock/db";
import { MIN_MEMBER_DISCOUNT } from "@/lib/constants";
import { MEMBER_CARD_TRANSITIONS } from "@/lib/types";
import { COUPON_TRANSITIONS, type ContentSlot, type Coupon, type PlatformSlot } from "@/lib/types";
import type { MarketingApi } from "../contracts/marketing";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findCoupon(no: string): Coupon {
  const c = db.coupons.find((x) => x.couponNo === no);
  if (!c) notFound("券模板", "Coupon template", no);
  return c;
}
function findSlot(no: string): ContentSlot {
  const s = db.contentSlots.find((x) => x.slotNo === no);
  if (!s) notFound("内容位", "Content slot", no);
  return s;
}

/** 单张券的面值（折扣券按门槛估算占用预算，没有门槛时按 0 记，只统计张数）。 */
function unitAmount(c: Coupon): number {
  if (c.type !== "DISCOUNT") return c.value;
  // 折扣券的实际让利要等核销才知道，这里按门槛 ×（1-折扣）估一个预算占用，
  // 宁可高估：预算是用来"挡住超支"的，低估等于没挡。
  return Math.round((c.threshold * (10000 - c.value)) / 10000);
}

/** 时间区间是否重叠（半开区间：首尾相接不算重叠，秒杀 07:00-08:00 与 08:00-09:00 可连排）。 */
const overlaps = (aStart: string, aEnd: string, bStart: string, bEnd: string) =>
  new Date(aStart) < new Date(bEnd) && new Date(bStart) < new Date(aEnd);

export const marketingMock: MarketingApi = {
  listCoupons: (q = {}) =>
    wait(
      db.paginate(db.coupons, q.page, q.size, (c) =>
        db.liveHit(c, q.showArchived) &&
        db.eqHit(q.type, c.type) &&
        db.eqHit(q.status, c.status) &&
        db.kwHit(q.keyword, c.couponNo, c.name),
      ),
    ),

  /**
   * 建券 / 改券（TDD-营销预算前置）。**镜像后端 `CouponServiceImpl.saveCoupon` 的
   * 三条硬校验**，不是简化版——mock 比后端宽松的话，这三条校验在演示里永远看不出来。
   */
  saveCoupon: async (v) => {
    if (!v.name.trim()) fail("券名不能为空", "The coupon name cannot be empty");
    if (!v.totalCount || v.totalCount <= 0) {
      fail("发行量必须大于 0", "Total count must be greater than 0");
    }
    if (!v.validFrom || !v.validTo || v.validTo <= v.validFrom) {
      fail("结束时间必须晚于开始时间", "The end time must be after the start time");
    }

    const existing = v.couponNo ? db.coupons.find((x) => x.couponNo === v.couponNo) : undefined;
    if (v.couponNo && !existing) notFound("券模板", "Coupon template", v.couponNo);
    const received = existing?.issued ?? 0;
    if (existing && v.totalCount < received) {
      fail(`已发放 ${received} 张，发行量不能改到低于它`, `${received} coupons already issued — total count cannot go below that`);
    }

    let maxExposure: number;
    let value: number;
    let maxDiscountMinor: number;
    if (v.type === "DISCOUNT") {
      if (!v.discountRate || v.discountRate <= 0 || !v.maxDiscountMinor || v.maxDiscountMinor <= 0) {
        fail("折扣券必须设置封顶金额", "Discount coupons must have a maximum discount cap");
      }
      maxExposure = v.totalCount * v.maxDiscountMinor!;
      value = v.discountRate!;
      maxDiscountMinor = v.maxDiscountMinor!;
    } else {
      if (!v.faceMinor || v.faceMinor <= 0) {
        fail("满减面额必须大于 0", "The full-cut face value must be greater than 0");
      }
      maxExposure = v.totalCount * v.faceMinor!;
      value = v.faceMinor!;
      maxDiscountMinor = 0;
    }

    const budget = v.budget ?? 0;
    if (budget > 0 && budget < maxExposure) {
      fail(
        `预算不能低于最大敞口（发行量 × 单张最大优惠 = ${(maxExposure / 100).toFixed(2)} 元）`,
        `Budget cannot be below the maximum exposure (total count × max discount per coupon = ¥${(maxExposure / 100).toFixed(2)})`,
      );
    }
    if (existing && budget > 0 && budget < existing.issuedAmount) {
      fail(`预算不能低于已发放金额（已发 ${(existing.issuedAmount / 100).toFixed(2)} 元）`, `The budget cannot fall below what has already been issued (¥${(existing.issuedAmount / 100).toFixed(2)})`);
    }

    if (existing) {
      Object.assign(existing, {
        name: v.name, type: v.type, value, threshold: v.threshold ?? 0,
        maxDiscountMinor, totalCount: v.totalCount, perUserLimit: v.perUserLimit ?? 1,
        budget, validFrom: v.validFrom, validTo: v.validTo,
      });
      return wait(existing, 400);
    }
    const created: Coupon = {
      couponNo: db.nextNo("CP", db.coupons, 9200, "couponNo"),
      name: v.name, type: v.type, status: "ACTIVE",
      value, threshold: v.threshold ?? 0, maxDiscountMinor,
      totalCount: v.totalCount, perUserLimit: v.perUserLimit ?? 1,
      budget, issuedAmount: 0, issued: 0, redeemed: 0,
      validFrom: v.validFrom, validTo: v.validTo, createdAt: Date.now(),
    };
    db.coupons.unshift(created);
    return wait(created, 400);
  },

  setCouponStatus: async (couponNo, status, reason) => {
    // **mock 必须和后端一样严**：后端 reason 空就 10400。
    // 此前 mock 没有这条，于是「运营点暂停必然失败」在演示里完全看不出来
    if (!reason?.trim()) fail("请说明原因", "A reason is required");
    const c = findCoupon(couponNo);
    db.assertTransition(COUPON_TRANSITIONS, c.status, status, "券模板", "Coupon template");
    c.status = status;
    return wait(c, 400);
  },

  setCouponBudget: async (couponNo, budget) => {
    const c = findCoupon(couponNo);
    // 改到小于已发放金额，账面立刻"已超支"，财务对不上（P-7.1.3）
    if (budget < c.issuedAmount) {
      fail(`预算不能低于已发放金额（已发 ${(c.issuedAmount / 100).toFixed(2)} 元）`, `The budget cannot fall below what has already been issued (¥${(c.issuedAmount / 100).toFixed(2)})`);
    }
    c.budget = budget;
    return wait(c, 400);
  },

  issueCoupon: async ({ couponNo, target, targetDesc, count }) => {
    const c = findCoupon(couponNo);
    if (c.status !== "ACTIVE") fail("只有启用中的券可以发放", "Only live coupons can be issued");
    if (count < 1) fail("发放数量至少为 1", "Issue at least 1");
    const amount = unitAmount(c) * count;
    // 超预算直接拒绝。"先发了再说"意味着这笔钱已经花出去了，事后只能认。
    if (c.issuedAmount + amount > c.budget) {
      const left = Math.max(0, c.budget - c.issuedAmount);
      fail(`超出预算：本次需 ${(amount / 100).toFixed(2)} 元，剩余预算 ${(left / 100).toFixed(2)} 元`, `Over budget: this issue needs ¥${(amount / 100).toFixed(2)} and only ¥${(left / 100).toFixed(2)} is left`);
    }
    c.issuedAmount += amount;
    c.issued += count;
    const rec = {
      issueNo: db.nextNo("CI", db.couponIssues, 9100, "issueNo"),
      couponNo, couponName: c.name, target, targetDesc, count, amount,
      operator: "admin", createdAt: "2026-08-06T00:00:00Z",
    };
    db.couponIssues.unshift(rec);
    return wait(rec, 400);
  },

  listCouponIssues: (q = {}) =>
    wait(db.paginate(db.couponIssues, q.page, q.size, (r) => db.kwHit(q.keyword, r.issueNo, r.couponName, r.targetDesc, r.operator))),

  archiveCoupon: async (no) => wait(db.archiveRow(db.coupons, "couponNo", no), 400),
  unarchiveCoupon: async (no) => wait(db.unarchiveRow(db.coupons, "couponNo", no), 400),

  listCampaigns: (q = {}) =>
    wait(
      db.paginate(db.merchantCampaigns, q.page, q.size, (c) =>
        db.liveHit(c, q.showArchived) &&
        db.eqHit(q.type, c.type) &&
        db.eqHit(q.status, c.status) &&
        // 按商家号也能搜到 —— 平台治理时最常见的问法是「这家店在跑什么活动」
        db.kwHit(q.keyword, c.campaignNo, c.name, c.merchantNo),
      ),
    ),

  toggleCampaign: async (no, running, reason) => {
    if (!reason?.trim()) fail("请说明原因", "A reason is required");
    const row = db.merchantCampaigns.find((c) => c.campaignNo === no);
    if (!row) fail("活动不存在", "Campaign not found");
    // 平台只改「还跑不跑」，不动活动内容 —— 那是商家自己的经营决定
    row!.status = running ? "RUNNING" : "PAUSED";
    return wait(row!, 400);
  },

  /*
   * 这里曾有 saveCampaign（平台投放场次）。**2026-08-12 随契约一起删**：
   * 后端不做这个对象，而页面上从来没有调用方 —— 三层都写着、零个消费方。
   *
   * **它定过的两条规则记在这里**，将来真做时不用重新想：
   *   ① 结束必须晚于开始；
   *   ② 秒杀场次**同一位置不可重叠** —— 场次的意义就在不重叠，
   *      同一位置同时跑两场，用户看到哪一场取决于查询顺序；
   *      跨位置重叠是合法的（首页与频道页可以同时跑）。
   * 完整设计见 docs/technical/design/TDD-ops-平台场次.md。
   */
  archiveCampaign: async (no) => wait(db.archiveRow(db.merchantCampaigns, "campaignNo", no), 400),
  unarchiveCampaign: async (no) => wait(db.unarchiveRow(db.merchantCampaigns, "campaignNo", no), 400),

  listContentSlots: (q = {}) =>
    wait(
      db.paginate(db.contentSlots, q.page, q.size, (s) =>
        db.liveHit(s, q.showArchived) &&
        db.eqHit(q.kind, s.kind) &&
        (!q.enabled || (q.enabled === "1") === s.enabled) &&
        db.kwHit(q.keyword, s.slotNo, s.title),
      ),
    ),

  saveContentSlot: async (v) => {
    if (!v.title.trim()) fail("内容位要有标题", "A content slot needs a title");
    if (new Date(v.offlineAt) <= new Date(v.onlineAt)) fail("下线时间必须晚于上线时间", "It has to come down after it goes up");
    // 有序去重：顺序就是首页里的展示顺序，用 Set 直接转会把它洗掉
    const goodsNos = v.kind === "HOME_FLOOR" ? [...new Set(v.goodsNos.map((g) => g.trim()).filter(Boolean))] : [];
    // HOME_FLOOR 没有货 = 首页上一块空白，而运营看着自己刚保存的配置以为它在生效
    if (v.kind === "HOME_FLOOR" && goodsNos.length === 0) fail("首页楼层至少要放一件商品", "A home floor needs at least one item");
    if (goodsNos.length > 30) fail("一个楼层最多 30 件", "A floor holds at most 30 items");
    // 「货号必须真的存在」只有后端能判（mock 里没有完整商品库）——
    // 所以 mock 上打错货号是存得进去的，真后端会 404。这是 mock 的边界，不是双方规则不同。
    const found = v.slotNo ? db.contentSlots.find((x) => x.slotNo === v.slotNo) : undefined;
    if (v.slotNo && !found) notFound("内容位", "Content slot", v.slotNo);
    const row: ContentSlot = {
      ...(found ?? { slotNo: db.nextNo("SL", db.contentSlots, 9100, "slotNo") }),
      title: v.title.trim(), kind: v.kind, sort: v.sort,
      communityNos: v.communityNos, goodsNos,
      onlineAt: v.onlineAt, offlineAt: v.offlineAt, enabled: v.enabled,
    };
    if (found) Object.assign(found, row);
    else db.contentSlots.unshift(row);
    return wait(row, 400);
  },

  setSlotEnabled: async (slotNo, enabled) => {
    const s = findSlot(slotNo);
    s.enabled = enabled;
    return wait(s, 400);
  },

  setSlotSchedule: async (slotNo, onlineAt, offlineAt) => {
    const s = findSlot(slotNo);
    if (new Date(offlineAt) <= new Date(onlineAt)) fail("下线时间必须晚于上线时间", "It has to come down after it goes up");
    s.onlineAt = onlineAt;
    s.offlineAt = offlineAt;
    return wait(s, 400);
  },

  archiveSlot: async (no) => wait(db.archiveRow(db.contentSlots, "slotNo", no), 400),
  unarchiveSlot: async (no) => wait(db.unarchiveRow(db.contentSlots, "slotNo", no), 400),

  listMemberCards: (q = {}) =>
    wait(
      db.paginate(db.memberCards, q.page, q.size, (m) =>
        db.liveHit(m, q.showArchived) && db.eqHit(q.status, m.status) && db.kwHit(q.keyword, m.cardNo, m.name),
      ),
    ),

  saveMemberCard: async (v) => {
    if (!v.name.trim()) fail("会员卡名称不能为空", "The card name cannot be empty");
    if (v.priceMonthly <= 0) fail("月费必须大于 0", "The monthly fee must be greater than 0");
    if (!v.benefits.length) fail("至少要配一项权益 —— 没有权益的会员卡就是纯收费", "At least one benefit is required — a card with none is a pure charge");

    const existing = v.cardNo ? db.memberCards.find((x) => x.cardNo === v.cardNo) : undefined;
    // 卖出去的是承诺，不是配置：要调整就新建一张卡、把旧卡停售
    if (existing && existing.holderCount > 0) {
      const changed =
        existing.priceMonthly !== v.priceMonthly ||
        JSON.stringify(existing.benefits) !== JSON.stringify(v.benefits);
      if (changed) {
        fail(
          `${existing.name} 已有 ${existing.holderCount} 人持卡，权益与月费不能改 —— 请新建一张卡并把这张停售`,
          `${existing.holderCount} people hold ${existing.name}, so its benefits and fee cannot change — create a new card and retire this one`,
        );
      }
    }

    const seen = new Set<string>();
    for (const b of v.benefits) {
      // 同类两条时命中哪条取决于顺序，那是隐性行为
      if (seen.has(b.kind)) fail(`权益类型重复：${b.kind}`, `Duplicate benefit type: ${b.kind}`);
      seen.add(b.kind);

      if (b.kind === "DISCOUNT") {
        if (b.value <= 0 || b.value >= 10000) fail("会员折扣必须在 0 与原价之间", "A member discount has to sit between 0 and the full price");
        if (b.value < MIN_MEMBER_DISCOUNT) {
          fail(`会员折扣不得低于 ${MIN_MEMBER_DISCOUNT / 1000} 折 —— 月费远补不回被打穿的毛利`, `Member discounts cannot exceed ${(10000 - MIN_MEMBER_DISCOUNT) / 100}% off — the monthly fee never covers that much lost margin`);
        }
      }
      if (b.kind === "POINTS_BOOST" && b.value < 10000) fail("积分倍率不能低于 1 倍", "The points multiplier cannot go below 1x");
      if ((b.kind === "FREE_SHIPPING" || b.kind === "COUPON_PACK") && (!Number.isInteger(b.value) || b.value <= 0)) {
        fail("免运费次数与赠券张数必须是正整数", "Free-shipping uses and coupon counts must be positive whole numbers");
      }
      if (b.kind === "COUPON_PACK") {
        const cp = db.coupons.find((x) => x.couponNo === b.couponNo);
        if (!cp) fail("赠券权益必须绑定一张券模板", "A coupon benefit has to link a coupon template");
        // 绑草稿券的话，用户开卡当天就领不到
        if (cp.status !== "ACTIVE") fail(`${cp.name} 当前未启用，不能作为会员赠券`, `${cp.name} is not live and cannot be given to members`);
      }
    }

    const saved = db.upsert(
      db.memberCards,
      {
        ...v,
        status: existing?.status ?? "DRAFT",
        holderCount: existing?.holderCount ?? 0,
        createdAt: existing?.createdAt ?? new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        updatedBy: "admin",
      },
      "cardNo",
      () => db.nextNo("MC", db.memberCards, 900, "cardNo"),
    );
    return wait(saved, 400);
  },

  setMemberCardStatus: async (cardNo, status) => {
    const m = db.memberCards.find((x) => x.cardNo === cardNo);
    if (!m) notFound("会员卡", "Membership card", cardNo);
    db.assertTransition(MEMBER_CARD_TRANSITIONS, m.status, status, "会员卡", "Membership card");
    m.status = status;
    m.updatedAt = new Date().toISOString();
    m.updatedBy = "admin";
    return wait(m, 400);
  },

  archiveMemberCard: async (cardNo) => {
    const m = db.memberCards.find((x) => x.cardNo === cardNo);
    if (!m) notFound("会员卡", "Membership card", cardNo);
    // 权益还要继续兑现，归档会让它从所有列表里消失
    if (m.holderCount > 0) fail(`${m.name} 还有 ${m.holderCount} 人持卡，权益还要兑现，不能归档`, `${m.holderCount} people still hold ${m.name} and their benefits must be honoured — it cannot be archived`);
    return wait(db.archiveRow(db.memberCards, "cardNo", cardNo), 400);
  },

  unarchiveMemberCard: async (cardNo) => wait(db.unarchiveRow(db.memberCards, "cardNo", cardNo), 400),
};
