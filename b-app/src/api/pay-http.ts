import { http } from "@shared/net/http-client";
import { ENDPOINTS as E, buildPath } from "./endpoints";
import type { PayApi } from "./pay";

export const payHttp: PayApi = {
  mPayCollect: (v) => http.post(E.mPayCollect.path, v),
  mPayRefunds: () => http.get(E.mPayRefunds.path),
  mPayRefund: (v) => http.post(E.mPayRefund.path, v),
  mPayStatement: (day) => http.get(E.mPayStatement.path, day ? { day } : undefined),
  mPayQrCodes: () => http.get(E.mPayQrCodes.path),
  mPayCreateQrCode: (v) => http.post(E.mPayCreateQrCode.path, v),
  mPayDisableQrCode: (qrCode) => http.put(buildPath(E.mPayDisableQrCode.path, { qrCode })),
  mPayAppGrants: () => http.get(E.mPayAppGrants.path),
  mPayGrantApp: (v) => http.post(E.mPayGrantApp.path, v),
  mPayRevokeGrant: (id) => http.del(buildPath(E.mPayRevokeGrant.path, { id })),
};
