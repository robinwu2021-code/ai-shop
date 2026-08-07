// 覆盖范围：风控（P-16.2）。
import { client } from "../http-client";
import type { RiskApi } from "../contracts/risk";

export const riskHttp: RiskApi = {
  listRiskEvents: (q) => client.get("/ops/risk-events", q),
  decideRiskEvent: (no, confirmed, verdict) => client.post(`/ops/risk-events/${no}/decide`, { confirmed, verdict }),
  listBlacklists: (q) => client.get("/ops/blacklists", q),
  addBlacklist: (v) => client.post("/ops/blacklists", v),
  decideBlacklistAppeal: (no, accept, verdict) => client.post(`/ops/blacklists/${no}/appeal`, { accept, verdict }),
  listRiskRules: () => client.get("/ops/risk-rules"),
  saveRiskRule: (type, threshold, autoBlock) => client.post(`/ops/risk-rules/${type}`, { threshold, autoBlock }),
};
