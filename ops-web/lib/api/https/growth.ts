// 覆盖范围：归因与裂变（P-9）。
import { client } from "../http-client";
import type { GrowthApi } from "../contracts/growth";

export const growthHttp: GrowthApi = {
  getAttributionRule: () => client.get("/ops/attribution-rule"),
  saveAttributionRule: (v) => client.post("/ops/attribution-rule", v),
  listAttributionTraces: (q) => client.get("/ops/attribution-traces", q),
  listFissionCampaigns: (q) => client.get("/ops/fission-campaigns", q),
  saveFissionCampaign: (v) => client.post("/ops/fission-campaigns", v),
  setFissionEnabled: (no, enabled) => client.post(`/ops/fission-campaigns/${no}/enabled`, { enabled }),
};
