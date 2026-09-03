// 营销：活动、券、会员与人群、触达、客户与复购 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, nextNo, persist } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import type { ActivityConflict, CouponIssueBatch, MarketingCampaign, MerchantCoupon, StoreActivity } from "@shared/types";
import { isPhone } from "@shared/utils/validate";
import {
  allMockMembers,
  belongsToMerchant,
  countTag,
  matchSegment,
  mockMemberTags,
  mockMembers,
  mockTags,
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
    return delay({
      records: out.slice((page - 1) * size, page * size),
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
    return delay({ ...db.memberSetting });
  },

  async mSaveMemberSettings(payload) {
    if (payload.memberScope) db.memberSetting.memberScope = payload.memberScope;
    if (payload.autoJoinOnOrder !== undefined) {
      db.memberSetting.autoJoinOnOrder = payload.autoJoinOnOrder;
    }
    persist();
    return delay({ ...db.memberSetting });
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
    const all = allMockMembers();
    const gate = db.reachSentAt[payload.scene] ?? {};
    const minDays = payload.scene === "WAKEUP" ? 14 : payload.scene === "COUPON" ? 7 : 3;
    const now = Date.now();

    let tooSoon = 0;
    let optOut = 0;
    let lead = 0;
    let reachable = 0;
    for (const m of all) {
      if (m.status === "LEAD") { lead++; continue; }          // 线索一律不发
      if (m.reachOptOut) { optOut++; continue; }
      const last = gate[m.memberNo];
      if (last && now - last < minDays * 86400_000) { tooSoon++; continue; }
      reachable++;
    }
    const skips: Array<{ reason: string; count: number }> = [];
    if (tooSoon > 0) skips.push({ reason: "TOO_SOON", count: tooSoon });
    if (optOut > 0) skips.push({ reason: "OPT_OUT", count: optOut });
    if (lead > 0) skips.push({ reason: "LEAD", count: lead });
    return delay({ matched: all.length, reachable, skips });
  },

  async mSendReach(payload) {
    const plan = await this.mPlanReach(payload);
    const now = Date.now();
    const gate = db.reachSentAt[payload.scene] ?? (db.reachSentAt[payload.scene] = {});
    const minDays = payload.scene === "WAKEUP" ? 14 : payload.scene === "COUPON" ? 7 : 3;
    for (const m of allMockMembers()) {
      if (m.status === "LEAD" || m.reachOptOut) continue;
      const last = gate[m.memberNo];
      if (last && now - last < minDays * 86400_000) continue;
      gate[m.memberNo] = now;      // 记下来，第二次发就会被频次闸拦住
    }
    persist();
    return delay({
      taskNo: `RC-${Date.now()}`,
      sent: plan.reachable,
      skipped: plan.matched - plan.reachable,
      skips: plan.skips,
    });
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
    return delay(db.merchantCoupons
        .filter((c) => includeEnded || c.status !== "ENDED")
        .map((c) => ({ ...c })));
  },

  async mCoupon(couponNo) {
    const c = db.merchantCoupons.find((x) => x.couponNo === couponNo);
    if (!c) throw new ApiError(10404, "券不存在");
    return delay({ ...c });
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
  async mIssueCoupon(couponNo, segmentNo) {
    const c = db.merchantCoupons.find((x) => x.couponNo === couponNo);
    if (!c) throw new ApiError(10404, "券不存在");
    if (c.status !== "ACTIVE") throw new ApiError(40014, "这张券已暂停或已结束，发不出去");

    const sg = db.memberSegments.find((x) => x.segmentNo === segmentNo);
    const hit = sg ? matchSegment(sg.rule) : allMockMembers();
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
      benefitText: hit.benefitMode === "CASH" ? `减 ${hit.benefitValue / 100} 元` : "兑换",
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
    return delay(db.couponIssues
        .filter((b) => !couponNo || b.couponNo === couponNo)
        .map((b) => ({ ...b })));
  },
};
