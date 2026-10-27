// 覆盖范围：支付运营端（/pay-ops/v1，TDD-支付服务-I5接入层 S9）。
import { client } from "../http-client";
import type { PayOpsApi } from "../contracts/payops";

const enc = encodeURIComponent;

export const payOpsHttp: PayOpsApi = {
  payOpsOrders: (q) => client.get("/pay-ops/v1/orders", q),
  payOpsOrder: (no) => client.get(`/pay-ops/v1/orders/${enc(no)}`),
  payOpsRefunds: (q) => client.get("/pay-ops/v1/refunds", q),
  payOpsReviewRefund: (no, v) => client.post(`/pay-ops/v1/refunds/${enc(no)}/review`, v),
  payOpsNotifies: (q) => client.get("/pay-ops/v1/notifies", q),
  payOpsResendNotify: (id) => client.post(`/pay-ops/v1/notifies/${id}/resend`),
  payOpsExceptions: (q) => client.get("/pay-ops/v1/exceptions", q),
  payOpsRefundException: (id) => client.post(`/pay-ops/v1/exceptions/${id}/refund`),
  payOpsIgnoreException: (id, note) => client.post(`/pay-ops/v1/exceptions/${id}/ignore`, { note }),
  payOpsDisableQrCode: (qrCode) => client.put(`/pay-ops/v1/qr-codes/${enc(qrCode)}/disabled`),
};
