// 覆盖范围：见 contracts/system.ts。
import { client } from "../http-client";
import type { SystemApi } from "../contracts/system";

export const systemHttp: SystemApi = {
  // 行业主数据是本文件里**唯一接了真后端**的一组（其余仍走 mock，见 Ops契约对账）
  listIndustries: () => client.get("/ops/industries"),
  setIndustryMicroAllowed: (industry, payChannel, allowed, remark) =>
    client.post(`/ops/industries/${industry}/micro-allowed`, { payChannel, allowed, remark }),
  setIndustryEnabled: (industry, enabled) =>
    client.post(`/ops/industries/${industry}/enabled`, { enabled }),
  setIndustryPointsForced: (industry, forced) =>
    client.post(`/ops/industries/${industry}/points-forced`, { forced }),

  getAppearance: () => client.get("/ops/appearance"),
  saveAppearance: (v) => client.post("/ops/appearance", v),
  listMarkets: () => client.get("/ops/markets"),
  saveMarketRate: (code, rate, enabled) => client.post(`/ops/markets/${code}`, { rate, enabled }),
  getRuleTexts: () => client.get("/ops/rule-texts"),
  saveRuleTexts: (v) => client.post("/ops/rule-texts", v),
  listFeatureFlags: () => client.get("/ops/feature-flags"),
  saveFeatureFlag: (key, enabled, rolloutPercent) => client.post(`/ops/feature-flags/${key}`, { enabled, rolloutPercent }),
};
