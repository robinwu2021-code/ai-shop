// 覆盖范围：商家治理（P-11.1）。
import { client } from "../http-client";
import type { MerchantApi } from "../contracts/merchant";

export const merchantHttp: MerchantApi = {
  listMerchants: (q) => client.get("/ops/merchants", q),
  getMerchant: (merchantNo) => client.get(`/ops/merchants/${merchantNo}`),
  setMerchantStatus: (merchantNo, status, remark) =>
    client.post(`/ops/merchants/${merchantNo}/status`, { status, remark }),
  setMerchantVerified: (merchantNo, verified) =>
    client.post(`/ops/merchants/${merchantNo}/verified`, { verified }),
  archiveMerchant: (merchantNo) => client.post(`/ops/merchants/${merchantNo}/archive`),
  unarchiveMerchant: (merchantNo) => client.post(`/ops/merchants/${merchantNo}/unarchive`),
  listAuthCodes: () => client.get("/ops/merchants/auth-codes"),
  setMerchantAuthCodes: (v) => client.put(`/ops/merchants/${v.merchantNo}/auth-codes`, v),
  listViolations: (q) => client.get("/ops/merchants/violations", q),
  recordViolation: (v) => client.post(`/ops/merchants/${v.merchantNo}/violations`, v),
};
