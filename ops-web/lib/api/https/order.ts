// 覆盖范围：订单管理（P-4.1）。
import { client } from "../http-client";
import type { OrderApi } from "../contracts/order";

export const orderHttp: OrderApi = {
  listOrders: (q) => client.get("/ops/orders", q),
  getOrder: (orderNo) => client.get(`/ops/orders/${orderNo}`),
  listSiblingOrders: (parentNo) => client.get(`/ops/orders/parent/${parentNo}`),
  listExceptionOrders: (q) => client.get("/ops/orders/exceptions", q),
  interveneOrder: (v) => client.post(`/ops/orders/${v.orderNo}/intervene`, v),
  listOrderInterventions: (orderNo) => client.get(`/ops/orders/${orderNo}/interventions`),
  proxyCancelOrder: (v) => client.post(`/ops/orders/${v.orderNo}/proxy-cancel`, v),
  /*
   * 字段逐个列出来而不是 `{ ...v }`：
   *   · 后端收的是 `fulfillment`，端上叫 `fulfillType` —— 名字不一样，透传会静默丢掉；
   *   · 幂等键放 body（后端两处都收，请求头优先），这个客户端没有逐请求加头的口子；
   *   · reason 显式写着，闸门才看得见它真的发了（ops-reason-required）。
   */
  getProxyLimit: () => client.get("/ops/orders/proxy-limit"),
  saveProxyLimit: (v) => client.post("/ops/orders/proxy-limit", v),
  createProxyOrder: (v) => client.post("/ops/orders/proxy", {
    userNo: v.userNo,
    phone: v.phone,
    merchantNo: v.merchantNo,
    fulfillment: v.fulfillType,
    pickupNo: v.pickupNo,
    payMode: v.payMode,
    items: v.items,
    reason: v.reason,
    idempotencyKey: v.idempotencyKey,
  }),
};
