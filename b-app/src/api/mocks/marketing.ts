// 营销：活动、券、会员与人群、触达、客户与复购 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, nextNo, persist } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import type { ActivityConflict, BatchPeriod, BatchPeriodDetail, CouponIssueBatch, MarketingCampaign, MerchantCoupon, StoreActivity } from "@shared/types";
import { isPhone } from "@shared/utils/validate";
import {
  allMockMembers,
  belongsToMerchant,
  countTag,
  matchSegment,
  resolveAudienceMock,
  skipReasonMock,
  mockMemberTags,
  mockMembers,
  mockTags,
  addMockReachTask,
  mockReachTasks,
  reachTaskView,
  requireMerchant,
  scopedToStore,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const marketingMock: Pick<MerchantApi,
  "mCampaignList"
  | "mSaveCampaign"
  | "mToggleCampaign"
  | "mCustomers"
  | "mMembers"
  | "mMemberStats"
  | "mMemberDetail"
  | "mEnrollMember"
  | "mPatchMember"
  | "mTagMembers"
  | "mBatchTagMembers"
  | "mAudiencePreview"
  | "mMemberTagUsage"
  | "mMemberSegmentDetail"
  | "mMemberTags"
  | "mCreateMemberTag"
  | "mEditMemberTag"
  | "mMergeMemberTag"
  | "mMemberSettings"
  | "mSaveMemberSettings"
  | "mMemberSegments"
  | "mSaveMemberSegment"
  | "mRemoveMemberSegment"
  | "mPreviewMemberSegment"
  | "mPlanReach"
  | "mSendReach"
  | "mReachTasks"
  | "mReachTask"
  | "mActivities"
  | "mActivity"
  | "mSaveActivity"
  | "mSetActivityStatus"
  | "mActivityConflicts"
  | "mCoupons"
  | "mCoupon"
  | "mSaveCoupon"
  | "mSetCouponStatus"
  | "mIssueCoupon"
  | "mPeekCouponCode"
  | "mRedeemCoupon"
  | "mCouponIssues"
  | "mMarketingSummary"
  | "mPeriods"
  | "mPeriod"
  | "mCutoffPeriod"
  | "mDecidePeriod"
  | "mPeriodPurchaseLines"
> = {
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

    // 顾客也按当前门店（后端 BizDashboardController#customers 走 currentStoreScope）
    for (const o of scopedToStore(db.orders)) {
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

  // ---------------------------------------------------------------- 会员（P1）
  /**
   * 会员名单。mock 里由订单聚合出来，与真库同一口径（**分层先判沉睡**）。
   *
   * <p>没有真的人档，所以手机号后四位由昵称派生 —— 只为让界面有东西显示；
   * 真实环境里它来自 `usr_person.phone_tail`。
   */
  async mMembers(q) {
    const rows = allMockMembers();
    const f = q ?? {};
    let out = rows;
    if (f.level) out = out.filter((m) => m.level === f.level);
    if (f.source) out = out.filter((m) => m.source === f.source);
    if (f.status) out = out.filter((m) => m.status === f.status);
    if (f.tagNos) {
      // 与真库同一条：**取交集**，选两个标签是「都要满足」
      const want = f.tagNos.split(",").filter(Boolean);
      out = out.filter((m) => want.every((t) => (db.memberTagRel[m.memberNo] ?? []).includes(t)));
    }
    if (f.phone) {
      // 与真库同一条规矩：**完整号才匹配**，给一半查不到人
      const full = f.phone;
      out = full.length >= 11 ? out.filter((m) => m.phoneTail === full.slice(-4)) : [];
    }
    const page = f.page ?? 1;
    const size = f.size ?? 20;
    // 与后端同一条：只列在用的商家标签名（停用 / 已合并的名字会让人以为它还在起作用）
    const names = new Map(mockTags().filter((t) => t.status === "ACTIVE").map((t) => [t.tagNo, t.name]));
    const withTags = (m: (typeof out)[number]) => ({ ...m,
      tagNames: (db.memberTagRel[m.memberNo] ?? []).map((no) => names.get(no)).filter((x): x is string => !!x) });
    return delay({
      records: out.slice((page - 1) * size, page * size).map(withTags),
      total: out.length,
      page,
      size,
    });
  },

  async mMemberStats() {
    const rows = allMockMembers();
    const by = (lv: string) => rows.filter((m) => m.level === lv).length;
    return delay({
      newCount: by("NEW"),
      regularCount: by("REGULAR"),
      loyalCount: by("LOYAL"),
      sleepingCount: by("SLEEPING"),
      reachable: rows.filter((m) => m.status === "ACTIVE" && !m.reachOptOut).length,
      newThisMonth: rows.filter((m) => m.joinedAt >= Date.now() - 30 * 86400_000).length,
      // 演示一个非零值：商家一定会拿订单数与会员数对，这一行就是解释差额的地方
      unlinkedBuyers: 3,
      levelComputedAt: todayAt3(),
    });
  },

  async mMemberDetail(memberNo) {
    const m = allMockMembers().find((x) => x.memberNo === memberNo);
    if (!m) throw new ApiError(10404, "会员不存在");
    const stores = db.stores.slice(0, 2).map((s, i) => ({
      storeNo: s.storeNo,
      orderCount: Math.max(1, m.orderCount - i),
      totalSpentMinor: Math.round(m.totalSpentMinor / (i + 1)),
      lastOrderAt: m.lastOrderAt ?? Date.now(),
      isFirstStore: i === 0,
    }));
    return delay({
      member: m,
      stores,
      tags: mockTags().filter((t) => (mockMemberTags()[memberNo] ?? []).includes(t.tagNo)),
      sources: [
        {
          sourceType: m.source, storeNo: stores[0]?.storeNo ?? null, linkNo: null,
          inviterUserNo: m.source === "SHARE" ? "李姐" : null,
          inviterRole: m.source === "SHARE" ? "CUSTOMER" : null,
          operatorNo: null, activityNo: null, isFirst: true,
          occurredAt: m.joinedAt,
        },
      ],
      lastReach: lastReachOf(memberNo),
    });
  },

  async mEnrollMember(payload) {
    // 与真后端同一条判据（`Phones.CN_MOBILE`）。**替身不能比正主松** ——
    // 松了的话在 mock 下录得进去、接真后端一片 400，而 mock 那一遍看着完全正常
    if (!isPhone(payload.phone ?? "")) throw new Error("手机号格式不对，应为 11 位大陆手机号");
    const tail = (payload.phone ?? "").slice(-4);
    const exist = mockMembers().find((m) => m.phoneTail === tail);
    if (exist) {
      // 与真库同一口径：重复录入不报错，把备注并进去
      return delay({ ...exist, remark: payload.remark ?? exist.remark ?? null });
    }
    const m = {
      memberNo: `MB-LEAD-${db.memberLeads.length + 1}`,
      personNo: `PS-LEAD-${db.memberLeads.length + 1}`,
      phoneTail: tail,
      // mock 里没有真的人档，一律当成「本人还没注册」= 线索。
      // 线索**不可触达、不进受众**，这一点端上必须看得出来
      status: "LEAD",
      source: "MANUAL",
      level: "NEW",
      firstStoreNo: payload.storeNo ?? db.stores[0]?.storeNo ?? null,
      orderCount: 0,
      totalSpentMinor: 0,
      d90OrderCount: 0,
      lastOrderAt: null as number | null,
      daysSinceLast: null as number | null,
      reachOptOut: false,
      remark: payload.remark ?? null,
      joinedAt: Date.now(),
    };
    db.memberLeads.push(m);
    if (payload.tagNos?.length) db.memberTagRel[m.memberNo] = [...payload.tagNos];
    persist();
    return delay({ ...m });
  },

  async mPatchMember(memberNo, payload) {
    const m = allMockMembers().find((x) => x.memberNo === memberNo);
    if (!m) throw new ApiError(10404, "会员不存在");
    if (payload.remark !== undefined) m.remark = payload.remark;
    // 线索不能被商家点成正式会员 —— 转正只能由本人绑号触发
    if (payload.status && m.status !== "LEAD") m.status = payload.status;
    persist();
    return delay({ ...m });
  },

  async mTagMembers(payload) {
    for (const no of payload.memberNos) {
      const cur = new Set(db.memberTagRel[no] ?? []);
      for (const t of payload.add ?? []) cur.add(t);
      for (const t of payload.remove ?? []) cur.delete(t);
      db.memberTagRel[no] = [...cur];
    }
    persist();
    return delay(undefined as unknown as void);
  },

  async mMemberTags() {
    return delay(mockTags());
  },

  async mBatchTagMembers(payload) {
    const t = db.memberTags.find((x) => x.tagNo === payload.tagNo);
    if (!t) throw new ApiError(10404, "标签不存在");
    const add = payload.action !== "REMOVE";
    const all = allMockMembers();
    const members = payload.memberNos?.length
      ? all.filter((m) => payload.memberNos!.includes(m.memberNo))
      : matchSegment(payload.rule ?? {});
    let already = 0;
    let full = 0;
    const change: string[] = [];
    for (const m of members) {
      const cur = db.memberTagRel[m.memberNo] ?? [];
      if (add === cur.includes(payload.tagNo)) { already++; continue; }
      // 与后端同一条：满 10 个的跳过并计数，不让一个人拦住整批
      if (add && cur.length >= 10) { full++; continue; }
      change.push(m.memberNo);
    }
    if (payload.confirm) {
      for (const no of change) {
        const cur = new Set(db.memberTagRel[no] ?? []);
        if (add) cur.add(payload.tagNo); else cur.delete(payload.tagNo);
        db.memberTagRel[no] = [...cur];
      }
      persist();
    }
    return delay({ matched: members.length, alreadyInState: already, willChange: change.length,
      skippedFull: full, applied: !!payload.confirm });
  },

  async mAudiencePreview(payload) {
    const items = payload.audiences ?? [];
    if (!items.length && payload.forActivity) return delay({ matched: null, reachable: null, skips: [] });
    if (!items.length) throw new ApiError(70065, "请选择发给谁");
    const hit = resolveAudienceMock(items);
    if (hit == null) return delay({ matched: null, reachable: null, skips: [] });
    if (payload.forActivity) return delay({ matched: hit.length, reachable: null, skips: [] });
    const skips = new Map<string, number>();
    let reachable = 0;
    for (const m of hit) {
      const why = skipReasonMock(m, payload.scene);
      if (why) skips.set(why, (skips.get(why) ?? 0) + 1); else reachable++;
    }
    return delay({ matched: hit.length, reachable,
      skips: [...skips].map(([reason, count]) => ({ reason, count })) });
  },

  async mMemberTagUsage(tagNo) {
    const tag = mockTags().find((t) => t.tagNo === tagNo);
    if (!tag) throw new ApiError(10404, "标签不存在");
    const activities = db.storeActivities
      .filter((a) => a.status !== "ENDED"
        && (a.audiences ?? []).some((x) => x.type === "TAG" && x.value === tagNo))
      .map((a) => ({ kind: "ACTIVITY", refNo: a.activityNo, name: a.name, status: a.status, at: null }));
    const segments = db.memberSegments.filter((sg) => (sg.rule.tagNos ?? []).includes(tagNo))
      .map((sg) => ({ ...sg }));
    // mock 里没有「打标时刻」，本月新增按一个固定的小数演示
    return delay({ tag, newThisMonth: Math.min(tag.count, 3), activities, segments });
  },

  async mMemberSegmentDetail(segmentNo) {
    const sg = db.memberSegments.find((x) => x.segmentNo === segmentNo);
    if (!sg) throw new ApiError(70043, "人群不存在");
    const hit = matchSegment(sg.rule);
    const activities = db.storeActivities
      .filter((a) => a.status !== "ENDED"
        && (a.audiences ?? []).some((x) => x.type === "SEGMENT" && x.value === segmentNo))
      .map((a) => ({ kind: "ACTIVITY", refNo: a.activityNo, name: a.name, status: a.status, at: null }));
    const couponIssues = (db.couponIssues ?? [])
      .filter((b) => b.segmentNo === segmentNo)
      .map((b) => ({ kind: "COUPON_ISSUE", refNo: b.issueNo,
        name: db.merchantCoupons.find((c) => c.couponNo === b.couponNo)?.title ?? null,
        status: null, at: b.issuedAt }));
    return delay({ segment: { ...sg }, matched: hit.length,
      reachable: hit.filter((m) => !skipReasonMock(m)).length, activities, couponIssues });
  },

  async mCreateMemberTag(name) {
    const exist = db.memberTags.find((t) => t.name === name);
    if (exist) return delay({ ...exist, count: countTag(exist.tagNo) });
    const t = { tagNo: `MT-${db.memberTags.length + 1}`, name, tagType: "MCH", status: "ACTIVE" };
    db.memberTags.push(t);
    persist();
    return delay({ ...t, count: 0 });
  },

  async mEditMemberTag(tagNo, payload) {
    const t = db.memberTags.find((x) => x.tagNo === tagNo);
    if (!t) throw new ApiError(10404, "标签不存在");
    if (t.tagType === "SYS") throw new ApiError(70041, "系统标签不能改名或手动打");
    if (payload.enabled !== undefined) t.status = payload.enabled ? "ACTIVE" : "DISABLED";
    else if (payload.name) t.name = payload.name;
    persist();
    return delay({ ...t, count: countTag(tagNo) });
  },

  async mMergeMemberTag(tagNo, payload) {
    const from = db.memberTags.find((x) => x.tagNo === tagNo);
    const into = db.memberTags.find((x) => x.tagNo === payload.intoTagNo);
    if (!from || !into) throw new ApiError(10404, "标签不存在");
    if (from.tagType === "SYS" || into.tagType === "SYS") {
      throw new ApiError(70041, "系统标签不能合并");
    }
    const holders = Object.entries(db.memberTagRel)
      .filter(([, tags]) => tags.includes(tagNo));
    const both = holders.filter(([, tags]) => tags.includes(payload.intoTagNo)).length;
    if (!payload.confirm) {
      // 试算：把影响面摆出来再让他按 —— 合并不可逆
      return delay({ affectedMembers: holders.length, bothTagged: both,
        referencedActivities: 0, applied: false });
    }
    for (const [memberNo, tags] of holders) {
      const next = new Set(tags.filter((x) => x !== tagNo));
      next.add(payload.intoTagNo);
      db.memberTagRel[memberNo] = [...next];
    }
    from.status = "MERGED";
    persist();
    return delay({ affectedMembers: holders.length, bothTagged: both,
      referencedActivities: 0, applied: true });
  },

  // ---------------------------------------------------------------- 口径与人群（P3）
  async mMemberSettings() {
    return delay(memberSettingView());
  },

  async mSaveMemberSettings(payload) {
    if (payload.memberScope) db.memberSetting.memberScope = payload.memberScope;
    if (payload.autoJoinOnOrder !== undefined) {
      db.memberSetting.autoJoinOnOrder = payload.autoJoinOnOrder;
    }
    persist();
    return delay(memberSettingView());
  },

  async mMemberSegments() {
    return delay(db.memberSegments.map((sg) => ({ ...sg })));
  },

  async mSaveMemberSegment(payload) {
    const hit = db.memberSegments.find(
      (x) => x.segmentNo === payload.segmentNo || x.name === payload.name,
    );
    // 与真库同一条：同名视为改同一个，不报重名错 —— 报了他只会存成「…2」
    const count = matchSegment(payload.rule).length;
    if (hit) {
      Object.assign(hit, {
        name: payload.name,
        scopeStoreNo: payload.scopeStoreNo ?? null,
        rule: payload.rule,
        lastCount: count,
        countedAt: Date.now(),
      });
      persist();
      return delay({ ...hit });
    }
    const sg = {
      segmentNo: `SG-${db.memberSegments.length + 1}`,
      name: payload.name,
      scopeStoreNo: payload.scopeStoreNo ?? null,
      rule: payload.rule,
      lastCount: count,
      countedAt: Date.now(),
    };
    db.memberSegments.push(sg);
    persist();
    return delay({ ...sg });
  },

  async mRemoveMemberSegment(segmentNo) {
    const i = db.memberSegments.findIndex((x) => x.segmentNo === segmentNo);
    if (i >= 0) db.memberSegments.splice(i, 1);
    persist();
    return delay(undefined as unknown as void);
  },

  async mPreviewMemberSegment(payload) {
    const hit = matchSegment(payload.rule);
    return delay({
      count: hit.length,
      // 线索会员与退订的人进不了受众 —— 两个数都报，否则商家以为发漏了
      reachable: hit.filter((m) => m.status === "ACTIVE" && !m.reachOptOut).length,
    });
  },

  // ---------------------------------------------------------------- 触达（P7）
  /**
   * 群发试算。**四类跳过与后端同一口径** —— mock 里少算一类，
   * 演示时看到的「能发 12 人」到了真实环境会变成别的数，而没人知道差在哪。
   */
  async mPlanReach(payload) {
    const all = reachTargets(payload);
    const skips = new Map<string, number>();
    let reachable = 0;
    for (const m of all) {
      const why = skipReasonMock(m, payload.scene);
      if (why) skips.set(why, (skips.get(why) ?? 0) + 1); else reachable++;
    }
    return delay({ matched: all.length, reachable,
      skips: [...skips].map(([reason, count]) => ({ reason, count })) });
  },

  async mSendReach(payload) {
    const plan = await this.mPlanReach(payload);
    const now = Date.now();
    const gate = db.reachSentAt[payload.scene] ?? (db.reachSentAt[payload.scene] = {});
    const sentTo: string[] = [];
    for (const m of reachTargets(payload)) {
      if (skipReasonMock(m, payload.scene)) continue;
      gate[m.memberNo] = now;      // 记下来，第二次发就会被频次闸拦住
      sentTo.push(m.memberNo);
    }
    persist();
    const taskNo = `RC-${now}`;
    addMockReachTask({ taskNo, scene: payload.scene, title: payload.title, body: payload.body,
      audienceDesc: payload.audienceDesc || "—", sentAt: now, matched: plan.matched, skips: plan.skips }, sentTo);
    return delay({
      taskNo,
      sent: plan.reachable,
      skipped: plan.matched - plan.reachable,
      skips: plan.skips,
    });
  },

  async mReachTasks() {
    return delay(mockReachTasks().map((t) => reachTaskView(t, false)));
  },

  async mReachTask(taskNo) {
    const t = mockReachTasks().find((x) => x.taskNo === taskNo);
    if (!t) throw new ApiError(10404, "这次触达不存在");
    return delay(reachTaskView(t, true));
  },

  // ---------------------------------------------------------------- 活动（P5）
  async mActivities(includeEnded) {
    return delay(db.storeActivities
        .filter((a) => includeEnded || a.status !== "ENDED")
        .map((a) => ({ ...a })));
  },

  async mActivity(activityNo) {
    const a = db.storeActivities.find((x) => x.activityNo === activityNo);
    if (!a) throw new ApiError(10404, "活动不存在");
    return delay({ ...a });
  },

  /**
   * 建 / 改活动。**三条硬校验与后端一字不差** ——
   * mock 放宽的话，演示时填得过、连真后端被拒，而那时没人记得是哪一条拦的。
   */
  async mSaveActivity(payload) {
    /*
     * **先脱响应式外壳**（同 mSaveStore / mSaveGoods）：`goodsNos` 与 `audiences`
     * 是页面 `form.value` 里的 reactive 代理数组，而 `delay()` 用 structuredClone
     * 返回副本 —— Chrome **拒绝克隆 Proxy**，于是保存活动会弹一句
     * 「Failed to execute 'structuredClone'…」，商家看到的是保存失败，
     * 而他什么也没做错。深拷贝一次 ＝ HTTP 上的 JSON 往返，真实链路里本来就有这一步。
     */
    payload = JSON.parse(JSON.stringify(payload)) as typeof payload;
    if (!payload.name?.trim()) throw new ApiError(10400, "请给活动起个名");
    /*
     * A6：开始了的活动只能改结束时间与上限（与后端 ActivityServiceImpl 同一个判据与字段）。
     * mock 放行而后端拒收，演示时改得了、上线就报错 —— 所以这里一样拦。
     */
    const before = payload.activityNo ? db.storeActivities.find((a) => a.activityNo === payload.activityNo) : undefined;
    if (before && (before.status === "RUNNING" || before.status === "PAUSED")
        && (!before.startAt || before.startAt <= Date.now())) {
      const sig = (x: Record<string, unknown>, goods?: string[], aud?: Array<{ type: string; value: string }>) =>
        JSON.stringify([x.name, x.triggerType ?? "NONE", x.triggerAmountMinor ?? null, x.triggerQty ?? null,
          x.benefitType, x.benefitAmountMinor ?? null, x.benefitQty ?? null, x.scheduleType ?? "ONE_OFF",
          x.startAt ?? null, x.scheduleRule ?? null, x.cutoffTime ?? null, x.groupHours ?? null,
          JSON.stringify(x.rules ?? []),
          [...(goods ?? [])].sort(), (aud ?? []).map((a) => `${a.type}=${a.value}`).sort()]);
      if (sig(before as unknown as Record<string, unknown>, before.goodsNos, before.audiences)
          !== sig(payload as unknown as Record<string, unknown>, payload.goodsNos, payload.audiences)) {
        throw new ApiError(40029, "活动已开始，只能改结束时间和上限；改规则请结束后另建");
      }
    }
    const schedule = payload.scheduleType ?? "ONE_OFF";
    const capped = payload.quota != null || (payload.budgetMinor ?? 0) > 0;
    if (schedule === "ALWAYS_ON" && !capped) {
      throw new ApiError(40018, "长期活动必须设限量或预算，否则没有停下来的那一天");
    }
    const itemCost = payload.benefitType === "PRICE" || payload.benefitType === "GIFT";
    if (itemCost && payload.quota == null) {
      throw new ApiError(40019, "改价和送商品的活动必须设限量");
    }
    if (itemCost && !payload.goodsNos?.length) {
      throw new ApiError(40020, "请选择参加活动的商品");
    }
    if (payload.triggerType === "COMBO") {
      const rs = payload.rules ?? [];
      const ok = rs.some((r) => r.kind === "CONDITION") && rs.some((r) => r.kind === "BENEFIT")
        && rs.every((r) => r.type !== "PERCENT" || ((r.bp ?? 0) >= 1000 && (r.bp ?? 0) < 10000 && (r.capMinor ?? 0) > 0));
      if (!ok) throw new ApiError(10400, "条件与优惠都至少要一项；打折须封顶");
    }
    if (payload.triggerType === "CUTOFF"
        && (!/^([01]\d|2[0-3]):[0-5]\d$/.test(payload.cutoffTime ?? "") || schedule === "RECURRING")) {
      throw new ApiError(10400, "请设置每天几点截单");
    }
    if (schedule === "RECURRING" && !payload.scheduleRule?.includes("weekdays")) {
      throw new ApiError(40021, "请设置周期规则（周几、几点到几点）");
    }

    const exist = payload.activityNo
      ? db.storeActivities.find((x) => x.activityNo === payload.activityNo)
      : undefined;
    if (exist?.status === "ENDED") {
      throw new ApiError(40023, "已结束的活动不能修改或重新开启，请复制一个新的");
    }
    const per = payload.benefitType === "CUT" ? (payload.benefitAmountMinor ?? 0) : 0;
    const row: StoreActivity = {
      activityNo: exist?.activityNo ?? `PT-${db.storeActivities.length + 1}`,
      name: payload.name.trim(),
      goal: payload.goal ?? null,
      storeNo: payload.storeNo ?? null,
      triggerType: payload.triggerType ?? "NONE",
      triggerAmountMinor: payload.triggerAmountMinor ?? null,
      triggerQty: payload.triggerQty ?? null,
      benefitType: payload.benefitType,
      benefitAmountMinor: payload.benefitAmountMinor ?? null,
      benefitQty: payload.benefitQty ?? null,
      benefitRef: payload.benefitRef ?? null,
      scheduleType: schedule,
      startAt: payload.startAt ?? null,
      endAt: payload.endAt ?? null,
      scheduleRule: payload.scheduleRule ?? null,
      quota: payload.quota ?? null,
      quotaUsed: exist?.quotaUsed ?? 0,
      quotaLeft: payload.quota == null ? null : payload.quota - (exist?.quotaUsed ?? 0),
      budgetMinor: payload.budgetMinor ?? null,
      budgetUsedMinor: exist?.budgetUsedMinor ?? 0,
      maxExposureMinor: payload.quota == null ? null : payload.quota * per,
      audiences: payload.audiences ?? [],
      goodsNos: payload.goodsNos ?? [],
      status: exist?.status ?? "RUNNING",
      endedReason: exist?.endedReason ?? null,
      liveNow: (exist?.status ?? "RUNNING") === "RUNNING" && schedule !== "RECURRING",
      cutoffTime: payload.triggerType === "CUTOFF" ? (payload.cutoffTime ?? null) : null,
      pickupOffset: payload.triggerType === "CUTOFF" ? (payload.pickupOffset ?? 1) : null,
      pickupFrom: payload.triggerType === "CUTOFF" ? (payload.pickupFrom ?? null) : null,
      minQty: payload.triggerType === "CUTOFF" ? (payload.minQty ?? null) : null,
      periodQuota: payload.triggerType === "CUTOFF" ? (payload.periodQuota ?? null) : null,
      decideHours: payload.triggerType === "CUTOFF" ? (payload.decideHours ?? null) : null,
      groupHours: payload.triggerType === "GROUP" ? (payload.groupHours ?? null) : null,
      // 自己组合的条件与优惠原样存；其余玩法清空（与后端「从组合改成别的玩法，旧行不留」一致）
      rules: payload.triggerType === "COMBO" ? (payload.rules ?? []) : null,
    };
    if (exist) Object.assign(exist, row);
    else db.storeActivities.unshift(row);
    persist();
    return delay({ ...row });
  },

  async mSetActivityStatus(activityNo, status) {
    const a = db.storeActivities.find((x) => x.activityNo === activityNo);
    if (!a) throw new ApiError(10404, "活动不存在");
    if (a.status === "ENDED") {
      throw new ApiError(40023, "已结束的活动不能修改或重新开启，请复制一个新的");
    }
    a.status = status;
    a.liveNow = status === "RUNNING" && a.scheduleType !== "RECURRING";
    if (status === "ENDED") a.endedReason = "MANUAL";
    persist();
    return delay({ ...a });
  },

  // ---------------------------------------------------------------- 营销入口与社区集单
  async mMarketingSummary() {
    const today = mockDay(0);
    const open = mockPeriods.filter((p) => p.status === "OPEN" && p.periodDate === today);
    return delay({
      monthDiscountMinor: 128_400,
      monthOrders: 96,
      activityRunning: db.storeActivities.filter((a) => a.status === "RUNNING").length,
      couponIssuing: 2,
      periodTodayQty: open.reduce((n, p) => n + p.qty, 0),
      periodTodayCutoffAt: open.length ? Math.min(...open.map((p) => p.cutoffAt)) : null,
      periodsShort: mockPeriods.filter((p) => p.status === "SHORT").length,
      groupsShort: 2,
      quotesPending: 2,
      enrollable: 1, // 与 platform.ts 里那个还能报的「中秋大促」对得上
    });
  },

  async mPeriods(status) {
    return delay(mockPeriods.filter((p) => !status || p.status === status).map((p) => ({ ...p })));
  },

  async mPeriod(periodNo) {
    const p = mockPeriods.find((x) => x.periodNo === periodNo);
    if (!p) throw new ApiError(10404, "这一期不存在");
    const detail: BatchPeriodDetail = {
      period: { ...p },
      byGoods: p.status === "SHORT"
        ? [{ goodsNo: "G0001", title: "东北大米 10 斤", qty: p.qty }]
        : [{ goodsNo: "G0003", title: "秋月梨 5 斤装", qty: 52 }, { goodsNo: "G0004", title: "红心猕猴桃", qty: 34 }],
      byPickup: p.status === "SHORT"
        ? [{ pickupNo: "PP0001", pickupName: "阳光里南门", qty: p.qty }]
        : [{ pickupNo: "PP0001", pickupName: "阳光里南门", qty: 48 }, { pickupNo: "PP0002", pickupName: "总店", qty: 38 }],
    };
    return delay(detail);
  },

  async mCutoffPeriod(periodNo) {
    const p = mockPeriods.find((x) => x.periodNo === periodNo);
    if (!p) throw new ApiError(10404, "这一期不存在");
    if (p.status !== "OPEN") throw new ApiError(40025, "本期状态已变化，请刷新后再试");
    p.cutoffAt = Date.now();
    if (p.minQty != null && p.qty < p.minQty) {
      p.status = "SHORT";
      p.decideDeadline = p.cutoffAt + 14 * 3600_000;
    } else {
      p.status = "CONFIRMED";
    }
    return delay({ ...p });
  },

  async mDecidePeriod(periodNo, action) {
    const p = mockPeriods.find((x) => x.periodNo === periodNo);
    if (!p) throw new ApiError(10404, "这一期不存在");
    if (p.status !== "SHORT") throw new ApiError(40025, "本期状态已变化，请刷新后再试");
    p.status = action === "CANCEL" ? "CANCELLED" : "CONFIRMED";
    return delay({ ...p });
  },

  async mPeriodPurchaseLines(periodNo) {
    const d = await this.mPeriod(periodNo);
    return d.byGoods.map((g) => ({ skuNo: `SK-${g.goodsNo}`, goodsNo: g.goodsNo, title: g.title, spec: null, qty: g.qty }));
  },

  async mActivityConflicts(goodsNos) {
    // 同样先脱代理：这个入参也是页面上的 reactive 数组
    goodsNos = JSON.parse(JSON.stringify(goodsNos)) as string[];
    const out: ActivityConflict[] = [];
    for (const a of db.storeActivities) {
      if (a.status !== "RUNNING") continue;   // 已结束的不算冲突
      for (const g of a.goodsNos) {
        if (goodsNos.includes(g)) {
          out.push({ goodsNo: g, activityNo: a.activityNo, activityName: a.name,
            benefitType: a.benefitType });
        }
      }
    }
    return delay(out);
  },

  // ---------------------------------------------------------------- 券（P4）
  async mCoupons(includeEnded) {
    // 本机早先存下的替身数据没有这两列（真后端一定给）：补 0，别让页面出 NaN
    return delay(db.merchantCoupons
        .filter((c) => includeEnded || c.status !== "ENDED")
        .map((c) => ({ ...c, usedTimes: c.usedTimes ?? 0, spentMinor: c.spentMinor ?? 0 })));
  },

  async mCoupon(couponNo) {
    const c = db.merchantCoupons.find((x) => x.couponNo === couponNo);
    if (!c) throw new ApiError(10404, "券不存在");
    return delay({ ...c, usedTimes: c.usedTimes ?? 0, spentMinor: c.spentMinor ?? 0 });
  },

  /**
   * 建券。**四条硬校验与后端一字不差** —— mock 放宽的话，
   * 演示时填得过、连真后端就被拒，而那时没人记得是哪一条拦的。
   */
  async mSaveCoupon(payload) {
    const mode = payload.benefitMode || "CASH";
    if (!payload.title?.trim()) throw new ApiError(10400, "请填券名");
    if (mode === "PERCENT") {
      const rate = payload.benefitValue ?? 0;
      // 万分比：8500 = 八五折。填 88 表示顾客付 0.88%，等于白送
      if (rate < 1000 || rate >= 10000) throw new ApiError(40011, "折扣要填万分比，如 8500 表示八五折");
      if (!payload.benefitCapMinor) throw new ApiError(40003, "折扣券必须设封顶");
    }
    if (mode === "CASH" && !(payload.benefitValue > 0)) throw new ApiError(10400, "请填面额");
    if (mode === "GIFT" && !payload.benefitRef?.trim()) throw new ApiError(10400, "请填每次兑换什么");
    const itemScoped = payload.scopeType === "CATEGORY" || payload.scopeType === "GOODS";
    if (itemScoped && (payload.redeemMode ?? "ORDER") === "ORDER") {
      throw new ApiError(40012, "下单抵扣的券暂不支持按类目或商品限定，可改成到店核销");
    }
    const issueMode = payload.issueMode ?? "TARGETED";
    if (payload.totalCount == null && issueMode !== "TARGETED") {
      throw new ApiError(40004, "请填发行量");
    }
    const per = mode === "CASH" ? (payload.benefitValue ?? 0)
      : mode === "PERCENT" ? (payload.benefitCapMinor ?? 0) : 0;
    const exposure = payload.totalCount == null ? null : payload.totalCount * per * (payload.timesTotal ?? 1);
    if (payload.budgetMinor && exposure != null && payload.budgetMinor < exposure) {
      throw new ApiError(40005, "预算兜不住发行量 × 单张最大优惠");
    }

    const exist = payload.couponNo
      ? db.merchantCoupons.find((x) => x.couponNo === payload.couponNo)
      : undefined;
    if (exist && payload.totalCount != null && payload.totalCount < exist.receivedCount) {
      throw new ApiError(40013, "发行量不能低于已领张数");
    }
    const row: MerchantCoupon = {
      couponNo: exist?.couponNo ?? `PC-${db.merchantCoupons.length + 1}`,
      title: payload.title.trim(),
      benefitMode: mode,
      benefitValue: payload.benefitValue ?? 0,
      benefitCapMinor: payload.benefitCapMinor ?? null,
      benefitRef: payload.benefitRef ?? null,
      minAmountMinor: payload.minAmountMinor ?? null,
      minQty: payload.minQty ?? null,
      scopeType: payload.scopeType ?? "ALL",
      scopeRefs: payload.scopeRefs ?? [],
      scopeDesc: payload.scopeDesc ?? null,
      validityMode: payload.validityMode ?? "RELATIVE",
      startAt: payload.startAt ?? null,
      endAt: payload.endAt ?? null,
      validDays: payload.validDays ?? 7,
      issueMode,
      redeemMode: payload.redeemMode ?? "ORDER",
      timesTotal: payload.timesTotal ?? 1,
      totalCount: payload.totalCount ?? null,
      receivedCount: exist?.receivedCount ?? 0,
      perUserLimit: payload.perUserLimit ?? 1,
      budgetMinor: payload.budgetMinor ?? null,
      maxExposureMinor: exposure,
      status: exist?.status ?? "ACTIVE",
      usedTimes: exist?.usedTimes ?? 0,
      spentMinor: exist?.spentMinor ?? 0,
    };
    if (exist) Object.assign(exist, row);
    else db.merchantCoupons.push(row);
    persist();
    return delay({ ...row });
  },

  async mSetCouponStatus(couponNo, status) {
    const c = db.merchantCoupons.find((x) => x.couponNo === couponNo);
    if (!c) throw new ApiError(10404, "券不存在");
    if (c.status === "ENDED") throw new ApiError(10400, "已结束的券不能复活");
    c.status = status;
    persist();
    return delay({ ...c });
  },

  /**
   * 定向发券。**三类跳过分开算**，与后端同一口径 ——
   * 只报一个「发放成功」的话，商家会以为人群里每个人都收到了。
   */
  async mIssueCoupon(couponNo, segmentNo, audiences) {
    const c = db.merchantCoupons.find((x) => x.couponNo === couponNo);
    if (!c) throw new ApiError(10404, "券不存在");
    if (c.status !== "ACTIVE") throw new ApiError(40014, "这张券已暂停或已结束，发不出去");

    const sg = db.memberSegments.find((x) => x.segmentNo === segmentNo);
    // 预设人群「@ALL / @NEW / @LOYAL / @SLEEPING」按分层现筛，与后端同一口径
    const level = segmentNo?.startsWith("@") ? segmentNo.slice(1) : null;
    const hit = audiences?.length ? (resolveAudienceMock(audiences) ?? [])
      : sg ? matchSegment(sg.rule)
        : allMockMembers().filter((m) => !level || level === "ALL" || m.level === level);
    const reachable = hit.filter((m) => m.status === "ACTIVE" && !m.reachOptOut);
    const unreachable = hit.length - reachable.length;

    let alreadyHas = 0;
    const targets: string[] = [];
    for (const m of reachable) {
      const held = (db.couponHolders[couponNo] ?? []).filter((x) => x === m.memberNo).length;
      if (held >= c.perUserLimit) { alreadyHas++; continue; }
      targets.push(m.memberNo);
    }

    let soldOut = 0;
    let give = targets;
    if (c.totalCount != null) {
      const left = Math.max(0, c.totalCount - c.receivedCount);
      if (give.length > left) { soldOut = give.length - left; give = give.slice(0, left); }
    }

    const per = c.benefitMode === "CASH" ? c.benefitValue
      : c.benefitMode === "PERCENT" ? (c.benefitCapMinor ?? 0) : 0;
    const amount = give.length * per * c.timesTotal;
    if (c.budgetMinor) {
      // 整批拒绝，不部分发放 —— 页面上那句话必须是真的
      if (c.receivedCount * per + amount > c.budgetMinor) {
        throw new ApiError(40015, "超出剩余预算，整批未发放");
      }
    }

    db.couponHolders[couponNo] = [...(db.couponHolders[couponNo] ?? []), ...give];
    c.receivedCount += give.length;

    const reasons: Array<{ reason: string; count: number }> = [];
    if (unreachable > 0) reasons.push({ reason: "UNREACHABLE", count: unreachable });
    if (alreadyHas > 0) reasons.push({ reason: "ALREADY_HAS", count: alreadyHas });
    if (soldOut > 0) reasons.push({ reason: "SOLD_OUT", count: soldOut });

    const batch: CouponIssueBatch = {
      issueNo: `PI-${db.couponIssues.length + 1}`,
      couponNo,
      segmentNo: segmentNo ?? null,
      planned: hit.length,
      issued: give.length,
      skipped: unreachable + alreadyHas + soldOut,
      skipReasons: reasons,
      amountMinor: amount,
      operatorNo: null,
      issuedAt: Date.now(),
    };
    db.couponIssues.unshift(batch);
    persist();
    return delay({ ...batch });
  },

  /**
   * 到店核销「先看」。mock 里给顾客发的券带一个固定码，方便演示：
   * 真实链路里码在发放时生成（去掉了 0/O/1/I/L —— 店员是手输的）。
   */
  async mPeekCouponCode(code) {
    const hit = db.merchantCoupons.find((c) => c.redeemMode === "STORE_CODE");
    if (!hit || code.trim().toUpperCase() !== "DEMO2345") {
      throw new ApiError(40016, "没找到这张券，确认一下码有没有输错");
    }
    const used = db.couponRedeemed[hit.couponNo] ?? 0;
    const remaining = Math.max(0, hit.timesTotal - used);
    return delay({
      userCouponNo: `PU-DEMO-${hit.couponNo}`,
      couponNo: hit.couponNo,
      title: hit.title,
      benefitText: hit.benefitMode === "CASH" ? `减 ${hit.benefitValue / 100} 元` : `兑换 ${hit.benefitRef ?? ""}`.trim(),
      phoneTail: "1148",
      expireAt: Date.now() + 7 * 86400_000,
      timesTotal: hit.timesTotal,
      timesUsed: used,
      remaining,
      redeemable: remaining > 0,
      reason: remaining > 0 ? null : "USED_UP",
    });
  },

  async mRedeemCoupon(code) {
    const view = await this.mPeekCouponCode(code);
    if (!view.redeemable) throw new ApiError(40002, "这张券不能核销了");
    const hit = db.merchantCoupons.find((c) => c.couponNo === view.couponNo)!;
    /*
     * 3 秒窗口：连点的第二下返回上一次的结果，不扣第二次、也不报错 ——
     * 报错会让店员以为刚才那下没成功，于是再按一次。
     */
    const last = db.couponRedeemedAt[hit.couponNo] ?? 0;
    if (Date.now() - last < 3000) {
      const used = db.couponRedeemed[hit.couponNo] ?? 0;
      return delay({
        userCouponNo: view.userCouponNo,
        timesUsed: used,
        remaining: Math.max(0, hit.timesTotal - used),
        usedUp: used >= hit.timesTotal,
        duplicated: true,
      });
    }
    const used = (db.couponRedeemed[hit.couponNo] ?? 0) + 1;
    db.couponRedeemed[hit.couponNo] = used;
    db.couponRedeemedAt[hit.couponNo] = Date.now();
    persist();
    return delay({
      userCouponNo: view.userCouponNo,
      timesUsed: used,
      remaining: Math.max(0, hit.timesTotal - used),
      usedUp: used >= hit.timesTotal,
      duplicated: false,
    });
  },

  async mCouponIssues(couponNo) {
    // 已用：演示按发出的三成算（mock 里没有真实核销），刚发的那一批为 0
    return delay(db.couponIssues
        .filter((b) => !couponNo || b.couponNo === couponNo)
        .map((b) => {
          const fresh = Date.now() - b.issuedAt < 86400_000;
          const used = fresh ? 0 : Math.floor(b.issued * 0.3);
          return { ...b, usedCount: used, usedAmountMinor: used * 500 };
        }));
  },
};


/** 这个人身上最近的一次触达（批次按新到旧排，取第一条带他的） */
function lastReachOf(memberNo: string) {
  for (const t of mockReachTasks()) {
    const r = t.rows.find((x) => x.memberNo === memberNo);
    if (r) {
      return { taskNo: t.taskNo, scene: t.scene, sentAt: t.sentAt,
        openedAt: r.opened ? t.sentAt + 3600_000 : null, orderedAt: r.orderedAt };
    }
  }
  return null;
}

/** mock 的日期：今天起第 n 天（本地时区），YYYY-MM-DD */
function mockDay(n: number): string {
  const d = new Date(Date.now() + n * 86400_000);
  const pad = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 今天 HH:mm 的毫秒时刻 */
function mockAt(hhmm: string): number {
  const [h = 0, m = 0] = hhmm.split(":").map(Number);
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d.getTime();
}

/** 集单的期：与原型 s31 同一组数据（一期收单中、一期未达起订、一期已成） */
const mockPeriods: BatchPeriod[] = [
  { periodNo: "PD-1", activityNo: "PT-B1", activityName: "每日鲜果", periodDate: mockDay(0),
    cutoffAt: mockAt("20:00"), pickupDate: mockDay(1), pickupFrom: "09:00", status: "OPEN",
    qty: 86, customers: 41, amountMinor: 75_700, minQty: null, periodQuota: 300, decideDeadline: null },
  { periodNo: "PD-2", activityNo: "PT-B2", activityName: "周末粮油", periodDate: mockDay(0),
    cutoffAt: mockAt("20:00"), pickupDate: mockDay(1), pickupFrom: "09:00", status: "SHORT",
    qty: 32, customers: 19, amountMinor: 121_600, minQty: 50, periodQuota: null,
    decideDeadline: mockAt("20:00") + 14 * 3600_000 },
  { periodNo: "PD-3", activityNo: "PT-B1", activityName: "每日鲜果", periodDate: mockDay(-1),
    cutoffAt: mockAt("20:00") - 86400_000, pickupDate: mockDay(0), pickupFrom: "09:00", status: "CONFIRMED",
    qty: 112, customers: 57, amountMinor: 98_400, minQty: null, periodQuota: 300, decideDeadline: null },
];

/** 演示「今天凌晨 3 点按口径重算过」—— 与生产任务的默认时刻一致 */
function todayAt3(): number {
  const d = new Date();
  d.setHours(3, 0, 0, 0);
  return d.getTime();
}

/**
 * 分层口径三个字段是后加的：浏览器里存着的旧 mock 数据没有它们，直接展开会显示 NaN。
 * 先铺平台默认口径再盖上存档 —— 真实后端每次都会返回这三个值。
 */
function memberSettingView() {
  const saved = db.memberSetting;
  return {
    ...saved,
    sleepDays: saved.sleepDays ?? 60,
    loyalD90Orders: saved.loyalD90Orders ?? 6,
    regularD90Orders: saved.regularD90Orders ?? 2,
    levelComputedAt: todayAt3(),
  };
}

/**
 * 发消息圈到的人：新入参给受众项（取或）；旧入参给一个人群号；都没有 = 全部会员。
 * 此前 mock 不论选哪个人群都按全部会员算，演示时「选了沉睡 24 人、能发 118」对不上。
 */
function reachTargets(payload: { segmentNo?: string; audiences?: Array<{ type: string; value: string }> }) {
  if (payload.audiences?.length) return resolveAudienceMock(payload.audiences) ?? [];
  const sg = payload.segmentNo ? db.memberSegments.find((x) => x.segmentNo === payload.segmentNo) : null;
  return sg ? matchSegment(sg.rule) : allMockMembers();
}
