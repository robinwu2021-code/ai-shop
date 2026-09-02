// 覆盖范围：系统配置（P-17.1）。
import * as db from "@/lib/mock/db";
import { C_END_THEMES } from "@/lib/stores/theme";
import { BASE_CURRENCY } from "@/lib/types";
import type { SystemApi } from "../contracts/system";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

// 只认可下发给 C 端的那几套：business 是运营端专有皮肤，C 端没有
const SKIN_KEYS = C_END_THEMES.map((t) => t.key) as string[];

function findIndustry(industry: string) {
  const row = db.industries.find((x) => x.industry === industry);
  if (!row) notFound("行业", "Industry", industry);
  return row;
}

export const systemMock: SystemApi = {
  listIndustries: async () => wait([...db.industries]),

  listAuthCodeDict: async () => wait(db.authCodeAdmins.map((c) => ({ ...c }))),

  saveAuthCodeDict: async (v) => {
    if (!v.code?.trim() || !v.name?.trim()) fail("授权码与名称都不能为空", "Code and name are required");
    const row = db.authCodeAdmins.find((x) => x.code === v.code);
    if (row) {
      row.name = v.name;
      row.requiredQualification = v.requiredQualification || undefined;
      row.sort = v.sort;
      return wait({ ...row });
    }
    // 新码默认**启用**：建完再点一次启用是纯粹的多余步骤，而漏点的后果是「建了但发不了」
    const created = { ...v, requiredQualification: v.requiredQualification || undefined, enabled: true, merchantCount: 0, categoryCount: 0 };
    db.authCodeAdmins.push(created);
    return wait({ ...created });
  },

  setAuthCodeDictEnabled: async (code, enabled, reason) => {
    if (!reason?.trim()) fail("请填写原因", "A reason is required");
    const row = db.authCodeAdmins.find((x) => x.code === code);
    if (!row) notFound("授权码", "Auth code", code);
    /*
     * 还有在用的类目引用它就不许停：停掉之后那些类目会要求一个已停用的码 ——
     * 也就是永远拒绝所有人，而商家看到的只是「你还没有资质授权」。
     */
    if (!enabled && row.categoryCount > 0) {
      fail("还有类目要求这个授权码，先把它们改到别的码上或归档，再停用",
        "Categories still require this code — reassign or archive them first");
    }
    row.enabled = enabled;
    return wait({ ...row });
  },

  listServiceScopes: async () => wait(db.serviceScopes.map((s) => ({ ...s }))),

  setServiceScopeEnabled: async (scope, enabled, reason) => {
    if (!reason?.trim()) fail("请填写原因", "A reason is required");
    const row = db.serviceScopes.find((s) => s.scope === scope);
    if (!row) notFound("经营范围", "Service scope", scope);
    // 不许全关：白名单空掉之后所有商家保存门店都会被拒，而错误信息说的是
    // 「当前不支持这个经营范围」—— 商家会以为是自己选错了，逐档试一遍，每次都被拒
    if (!enabled && db.serviceScopes.filter((s) => s.enabled).length <= 1) {
      fail("至少要开放一档经营范围 —— 全关等于所有商家都保存不了门店",
        "At least one service scope must stay open");
    }
    row.enabled = enabled;
    return wait(db.serviceScopes.map((s) => ({ ...s })));
  },

  setIndustryMicroAllowed: async (industry, payChannel, allowed, remark) => {
    const row = findIndustry(industry);
    if (payChannel === "ALIPAY") row.alipayMicroAllowed = allowed;
    else row.wechatMicroAllowed = allowed;
    if (remark) row.remark = remark;
    return wait({ ...row });
  },

  setIndustryEnabled: async (industry, enabled) => {
    const row = findIndustry(industry);
    row.enabled = enabled;
    return wait({ ...row });
  },

  setIndustryPointsForced: async (industry, forced) => {
    const row = findIndustry(industry);
    row.pointsForced = forced;
    return wait({ ...row });
  },

  getAppearance: async () => wait(db.appearance),

  saveAppearance: async (v) => {
    // 皮肤取值必须是四套之一：C 端拿到一个不认识的皮肤名会回落到默认，
    // 表现为"配了没生效"，排查起来很费劲
    if (!SKIN_KEYS.includes(v.defaultSkin)) fail(`默认皮肤必须是 ${SKIN_KEYS.join(" / ")} 之一`, `The default skin must be one of ${SKIN_KEYS.join(" / ")}`);
    if (v.festivalSkin && !SKIN_KEYS.includes(v.festivalSkin)) fail("节日皮肤取值非法", "That is not a valid festival skin");
    if (v.festivalSkin && v.festivalFrom && v.festivalTo && new Date(v.festivalTo) <= new Date(v.festivalFrom)) {
      fail("节日皮肤的结束时间必须晚于开始时间", "The festival skin must end after it starts");
    }
    Object.assign(db.appearance, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.appearance, 400);
  },

  listMarkets: async () => wait(db.markets),

  saveMarketRate: async (code, rate, enabled) => {
    const m = db.markets.find((x) => x.code === code);
    if (!m) notFound("市场", "Market", code);
    // 基准货币的汇率是整套换算的原点，改了之后所有价格都错
    if (m.currency === BASE_CURRENCY && rate !== 1) {
      fail(`${BASE_CURRENCY} 是基准货币，汇率恒为 1，不可修改`, `${BASE_CURRENCY} is the base currency — its rate is always 1 and cannot be changed`);
    }
    if (rate <= 0) fail("汇率必须大于 0", "The exchange rate must be greater than 0");
    m.rate = rate;
    m.enabled = enabled;
    await wait(undefined);
  },

  getRuleTexts: async () => wait(db.ruleTexts),

  saveRuleTexts: async (v) => {
    // 这三条 C 端要展示给用户看；留空的话用户在下单页看到的是空白
    const empty = (["refund", "pickup", "weighDiff"] as const).filter((k) => !v[k]?.trim());
    if (empty.length) fail(`规则文案不能为空：${empty.join("、")}`, `These rule texts cannot be empty: ${empty.join(", ")}`);
    Object.assign(db.ruleTexts, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.ruleTexts, 400);
  },

  listFeatureFlags: async () => wait(db.featureFlags),

  saveFeatureFlag: async (key, enabled, rolloutPercent) => {
    const f = db.featureFlags.find((x) => x.key === key);
    if (!f) notFound("开关", "Feature flag", key);
    if (rolloutPercent < 0 || rolloutPercent > 100) fail("灰度比例需在 0–100 之间", "The rollout percentage must be between 0 and 100");
    f.enabled = enabled;
    f.rolloutPercent = rolloutPercent;
    f.updatedAt = "2026-08-06T00:00:00Z";
    await wait(undefined);
  },

  // ── 存储空间治理 ──
  getMediaOverview: async () => {
    const rows = db.mediaStoreUsage;
    const activeBytes = rows.reduce((a, r) => a + r.activeBytes, 0);
    const reclaimableBytes = rows.reduce((a, r) => a + r.reclaimableBytes, 0);
    const reclaimableCount = db.mediaReclaimable.length;
    const totalCount = rows.reduce((a, r) => a + r.count, 0);
    return wait({
      totalBytes: activeBytes + reclaimableBytes,
      totalCount,
      activeBytes,
      activeCount: totalCount - reclaimableCount,
      reclaimableBytes,
      reclaimableCount,
      // mock 里刻意为 false：异常态由页面的 storybook 场景单独试，
      // 让它常驻会让每次打开都顶着一条红条，反而看不出真异常
      abnormal: false,
    });
  },
  listMediaStoreUsage: async () =>
    wait([...db.mediaStoreUsage].sort((a, b) => b.reclaimableBytes - a.reclaimableBytes)),
  listMediaReclaimable: async (q) => {
    let rows = [...db.mediaReclaimable];
    // 证件默认不进清单 —— 与后端同一个默认值，不然 mock 下看到的和真后端不一样
    if (!q?.includeQual) rows = rows.filter((r) => r.bizType !== "QUAL");
    if (q?.storeNo) rows = rows.filter((r) => r.storeNo === q.storeNo);
    if (q?.entityNo) rows = rows.filter((r) => r.entityNo === q.entityNo);
    if (q?.neverUsed === true) rows = rows.filter((r) => r.reason.startsWith("从未"));
    if (q?.neverUsed === false) rows = rows.filter((r) => !r.reason.startsWith("从未"));
    const page = q?.page ?? 1;
    const size = q?.size ?? 20;
    return wait({ records: rows.slice((page - 1) * size, page * size), total: rows.length, page, size });
  },
  listMediaBatches: async () => wait([...db.mediaBatches]),
  getMediaBatch: async (batchNo) => {
    const batch = db.mediaBatches.find((b) => b.batchNo === batchNo);
    if (!batch) notFound("回收批次", "Purge batch", batchNo);
    return wait({ batch, items: [...db.mediaReclaimable] });
  },
  scanMedia: async () => wait({
    total: db.mediaReclaimable.length + 20, referenced: 20,
    marked: db.mediaReclaimable.length, rescued: 0, abnormal: false,
  }),
  backfillMedia: async () => wait({ scanned: 1250, inserted: 3, skipped: 1247 }),
  previewMediaPurge: async (q) => {
    const rows = q?.includeQual ? db.mediaReclaimable
      : db.mediaReclaimable.filter((r) => r.bizType !== "QUAL");
    const picked = q?.storeNo ? rows.filter((r) => r.storeNo === q.storeNo) : rows;
    return wait({
      count: picked.length,
      bytes: picked.reduce((a, r) => a + r.bytes, 0),
      sample: picked.slice(0, 20).map((r) => r.assetKey),
    });
  },
  purgeMedia: async (v) => {
    const keys = v.assetKeys ?? [];
    // 跨页全选那道闸也要在 mock 里成立 —— 否则前端的错误分支永远试不出来
    if (keys.length === 0 && v.expectedCount == null) {
      fail("跨页全选必须带预期数量", "Cross-page selection requires an expected count");
    }
    return wait({ batchNo: "MP" + Date.now() });
  },
};
