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
    return wait(m, 400);
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
    return wait(f, 400);
  },
};
