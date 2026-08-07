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
  createProxyOrder: (v) => client.post("/ops/orders/proxy", v),
};
