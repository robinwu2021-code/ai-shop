// 覆盖范围：商家治理（P-11.1）。
import { client } from "../http-client";
import type { MerchantApi } from "../contracts/merchant";

export const merchantHttp: MerchantApi = {
  storeModes: (merchantNo) => client.get(`/ops/merchants/${merchantNo}/store-modes`),
  merchantStaff: (merchantNo) => client.get(`/ops/merchants/${merchantNo}/staff`),
  setStoreBusinessMode: (v) => client.put(`/ops/stores/${v.storeNo}/business-mode`, v),
  admissionPolicies: () => client.get("/ops/admission/policies"),
  updateAdmissionPolicy: (v) => client.put(`/ops/admission/policies/${v.legalForm}`, v),
  merchantDeposit: (merchantNo) => client.get(`/ops/admission/deposits/${merchantNo}`),
  depositTxns: (merchantNo) => client.get(`/ops/admission/deposits/${merchantNo}/txns`),
  addDepositTxn: (v) => client.post(`/ops/admission/deposits/${v.merchantNo}/txns`, v),

  /*
   * 入驻审核这三条是**真后端**（其余仍未实现，见 docs/technical/Ops契约对账.md）。
   * 路径用单数 `/ops/merchant/apply` —— 与 ADR-007 的资源单数规矩一致，
   * 也把「申请单」与「主体」两个资源分开：混用是之前模型对不上的根源。
   */
  listApplies: (q) => client.get("/ops/merchant/apply/search", q),
  acceptApply: (applyNo) => client.post(`/ops/merchant/apply/${applyNo}/accept`),
  auditApply: (applyNo, approved, reason, serviceScope, communityNos) =>
    client.post(`/ops/merchant/apply/${applyNo}/audit`, {
      approved,
      reason,
      serviceScope,
      communityNos,
    }),

  listMerchants: (q) => client.get("/ops/merchants", q),
  getMerchant: (merchantNo) => client.get(`/ops/merchants/${merchantNo}`),
  setMerchantStatus: (merchantNo, status, remark, communityNos) =>
    client.post(`/ops/merchants/${merchantNo}/status`, { status, remark, communityNos }),
  setMerchantVerified: (merchantNo, verified) =>
    client.post(`/ops/merchants/${merchantNo}/verified`, { verified }),
  archiveMerchant: (merchantNo) => client.post(`/ops/merchants/${merchantNo}/archive`),
  unarchiveMerchant: (merchantNo) => client.post(`/ops/merchants/${merchantNo}/unarchive`),
  listAuthCodes: () => client.get("/ops/merchants/auth-codes"),
  setMerchantAuthCodes: (v) => client.put(`/ops/merchants/${v.merchantNo}/auth-codes`, v),
  listViolations: (q) => client.get("/ops/merchants/violations", q),
  recordViolation: (v) => client.post(`/ops/merchants/${v.merchantNo}/violations`, v),
};
