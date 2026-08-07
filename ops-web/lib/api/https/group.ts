// 覆盖范围：团购与求团（P-8）。
import { client } from "../http-client";
import type { GroupApi } from "../contracts/group";

export const groupHttp: GroupApi = {
  listGroupCampaigns: (q) => client.get("/ops/groups", q),
  auditGroupCampaign: (no, pass, reason) => client.post(`/ops/groups/${no}/audit`, { pass, reason }),
  setGroupStatus: (no, status) => client.post(`/ops/groups/${no}/status`, { status }),
  listDemands: (q) => client.get("/ops/demands", q),
  listQuotes: (q) => client.get("/ops/quotes", q),
  assignQuote: (v) => client.post(`/ops/demands/${v.demandNo}/quotes`, v),
  changeQuotePrice: (no, price) => client.post(`/ops/quotes/${no}/price`, { price }),
  markQuoteBreached: (no) => client.post(`/ops/quotes/${no}/breach`),
};
