// 覆盖范围：见 contracts/system.ts。
import { client } from "../http-client";
import type { SystemApi } from "../contracts/system";

export const systemHttp: SystemApi = {
  getAppearance: () => client.get("/ops/appearance"),
  saveAppearance: (v) => client.post("/ops/appearance", v),
  listMarkets: () => client.get("/ops/markets"),
  saveMarketRate: (code, rate, enabled) => client.post(`/ops/markets/${code}`, { rate, enabled }),
  getRuleTexts: () => client.get("/ops/rule-texts"),
  saveRuleTexts: (v) => client.post("/ops/rule-texts", v),
  listFeatureFlags: () => client.get("/ops/feature-flags"),
  saveFeatureFlag: (key, enabled, rolloutPercent) => client.post(`/ops/feature-flags/${key}`, { enabled, rolloutPercent }),
};
