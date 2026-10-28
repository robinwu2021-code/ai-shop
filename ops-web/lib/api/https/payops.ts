// 覆盖范围：支付运营（开发计划 M5a：走业务服务 /ops/payments/**，与其它运营端同一套运营会话与权限码；
// 原先直连支付的 /pay-ops/v1 过渡一个版本后删）。
import { client } from "../http-client";
import type { PayOpsApi } from "../contracts/payops";

const enc = encodeURIComponent;

export const payOpsHttp: PayOpsApi = {
  payOpsOrders: (q) => client.get("/ops/payments/orders", q),
  payOpsOrder: (no) => client.get(`/ops/payments/orders/${enc(no)}`),
  payOpsRefunds: (q) => client.get("/ops/payments/refunds", q),
  payOpsReviewRefund: (no, v) => client.post(`/ops/payments/refunds/${enc(no)}/review`, v),
  payOpsNotifies: (q) => client.get("/ops/payments/notifies", q),
  payOpsResendNotify: (id) => client.post(`/ops/payments/notifies/${id}/resend`),
  payOpsExceptions: (q) => client.get("/ops/payments/exceptions", q),
  payOpsRefundException: (id) => client.post(`/ops/payments/exceptions/${id}/refund`),
  payOpsIgnoreException: (id, note) => client.post(`/ops/payments/exceptions/${id}/ignore`, { note }),
  payOpsDisableQrCode: (qrCode) => client.put(`/ops/payments/qr-codes/${enc(qrCode)}/disabled`),
};
