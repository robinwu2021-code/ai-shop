import { http } from "@shared/net/http-client";
import { ENDPOINTS as E, buildPath } from "./endpoints";
import type { SalesInvoiceApi } from "./sales-invoice";

/** 门店由 http 层统一带的 `X-Store-No` 决定，这里不重复传。 */
export const salesInvoiceHttp: SalesInvoiceApi = {
  mSiIssue: (body) => http.post(E.mSiIssue.path, body),
  mSiBuyerPreference: (body) => http.put(E.mSiBuyerPreference.path, body),
  mSiList: (q) => http.get(E.mSiList.path, q),
  mSiDetail: (invoiceNo) => http.get(buildPath(E.mSiDetail.path, { invoiceNo })),
  mSiVoid: (invoiceNo, body) => http.post(buildPath(E.mSiVoid.path, { invoiceNo }), body),
  mSiAllowance: (invoiceNo, body) => http.post(buildPath(E.mSiAllowance.path, { invoiceNo }), body),
  mSiVoidAllowance: (invoiceNo, allowanceNo, body) =>
    http.post(buildPath(E.mSiVoidAllowance.path, { invoiceNo, allowanceNo }), body),
  mSiProviderCheck: (invoiceNo) => http.post(buildPath(E.mSiProviderCheck.path, { invoiceNo }), {}),
  mSiUploadBacklog: () => http.get(E.mSiUploadBacklog.path),
  mSiRetransmit: (invoiceNo) => http.post(buildPath(E.mSiRetransmit.path, { invoiceNo }), {}),
  mSiProfile: () => http.get(E.mSiProfile.path),
  mSiSaveProfile: (body) => http.put(E.mSiSaveProfile.path, body),
  mSiProviders: (q) => http.get(E.mSiProviders.path, q),
  mSiRanges: (q) => http.get(E.mSiRanges.path, q),
  mSiAddRange: (body) => http.post(E.mSiAddRange.path, body),
  mSiVerifyProvider: () => http.post(E.mSiVerifyProvider.path, {}),
  mSiFetchProviderRange: (body) => http.post(E.mSiFetchProviderRange.path, body),
};
