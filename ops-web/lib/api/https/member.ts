// 覆盖范围：运营侧会员与新模型营销（P8）。
import { client } from "../http-client";
import type { MemberApi } from "../contracts/member";

export const memberHttp: MemberApi = {
  listOpsMembers: (q) => client.get("/ops/members", q),
  getOpsPerson: (personNo) => client.get(`/ops/persons/${personNo}`),
  // POST 而不是 GET：它有副作用（写一条审计），而且理由放 body ——
  // 放查询串上会被日志、代理、浏览器历史各留一份
  revealMemberPhone: (personNo, reason) =>
    client.post(`/ops/persons/${personNo}/reveal-phone`, { reason }),
  listReachStats: (days) => client.get("/ops/members/reach-stats", { days }),
  listOpsPromoCoupons: (entityNo) => client.get("/ops/promotion/coupons", { entityNo }),
  listOpsPromoActivities: (entityNo) => client.get("/ops/promotion/activities", { entityNo }),
  stopOpsActivity: (activityNo, reason) =>
    client.post(`/ops/promotion/activities/${activityNo}/stop`, { reason }),
};
