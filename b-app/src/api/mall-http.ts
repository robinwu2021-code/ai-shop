import { http } from "@shared/net/http-client";
import { ENDPOINTS as E, buildPath } from "./endpoints";
import type { MallApi } from "./mall";

const p = (path: string, storeNo: string, orderNo?: string) =>
  buildPath(path, orderNo === undefined ? { storeNo } : { storeNo, orderNo });

export const mallHttp: MallApi = {
  mMallStorefront: (storeNo) => http.get(p(E.mMallStorefront.path, storeNo)),
  mMallOfflineQuote: (storeNo, body) => http.post(p(E.mMallOfflineQuote.path, storeNo), body),
  mMallPlaceOrder: (storeNo, body) => http.post(p(E.mMallPlaceOrder.path, storeNo), body),
  mMallOrders: (storeNo) => http.get(p(E.mMallOrders.path, storeNo)),
  mMallOrder: (storeNo, orderNo) => http.get(p(E.mMallOrder.path, storeNo, orderNo)),
  mMallIssueInstructions: (storeNo, orderNo) => http.post(p(E.mMallIssueInstructions.path, storeNo, orderNo), {}),
  mMallInstructions: (storeNo, orderNo) => http.get(p(E.mMallInstructions.path, storeNo, orderNo)),
  mMallReserveEvidence: (storeNo, orderNo) => http.post(p(E.mMallReserveEvidence.path, storeNo, orderNo), {}),
  // 后端收的是请求体原始字节（@RequestBody byte[]），不是 multipart —— 所以不走 uploadFile。
  // assetKey 是查询参数：POST 的 data 进了请求体，只能拼在路径上
  mMallUploadEvidence: (storeNo, orderNo, assetKey, bytes, contentType) =>
    http.post(`${p(E.mMallUploadEvidence.path, storeNo, orderNo)}?assetKey=${encodeURIComponent(assetKey)}`, bytes,
      { "Content-Type": contentType }),
  mMallSubmitClaim: (storeNo, orderNo, body) => http.post(p(E.mMallSubmitClaim.path, storeNo, orderNo), body),
  mMallClaims: (storeNo, orderNo) => http.get(p(E.mMallClaims.path, storeNo, orderNo)),
};
