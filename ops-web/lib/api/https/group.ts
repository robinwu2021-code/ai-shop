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
  // 后端字段是 unitPriceMinor，不是 price —— 名字不对时后端收到的是 0，
  // 而 0 会被「改价必须 > 0」挡下，表现为一个说不通的「参数有误」
  changeQuotePrice: (no, price, reason) =>
    client.post(`/ops/quotes/${no}/price`, { unitPriceMinor: price, reason }),
  markQuoteBreached: (no) => client.post(`/ops/quotes/${no}/breach`),
};
