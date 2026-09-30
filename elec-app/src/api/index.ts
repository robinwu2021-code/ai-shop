// 元器件小程序的全部接口。**只认两类路径**：
//   /elec/c/**  买家 · /elec/b/**  供应商      → elec-svc（独立服务）
//   /mp/user/*  登录与我是谁                    → 主系统（同一个账号）
// 生产上同域、nginx 分流；开发期 vite proxy 分流（见 vite.config.mts）。
//
// 类型逐字对着后端 record（packages/shared/src/types/elec.ts 的抬头写了对照位置）。
import { http } from "@shared/net/http-client";
import type {
  ElecBatchPreview, ElecDeclineReq, ElecDispatch, ElecDispatchStatus, ElecImportMode, ElecLookupLine,
  ElecPartHit, ElecRemapReq, ElecRenewResult, ElecRfq, ElecRfqReq, ElecSearchResult, ElecStock,
  ElecStockFilter, ElecSupplier, ElecMe, ElecSupplierQuoteReq, ElecSupplierReq, LoginReq, LoginResp, PhoneCapable, User,
} from "@shared/types";

const enc = encodeURIComponent;

export const api = {
  // ── 登录与手机号（主系统）──
  // C 端**没有匿名的验证码登录**：小程序里先静默登录建号，验证码只用来给已登录的账号绑手机号
  // （/mp/user/otp/send 要会话，见 MpUserController#sendOtp 的注释）。
  login: (req: LoginReq) => http.post<LoginResp>("/mp/user/login", req),
  profile: () => http.get<User>("/mp/user/profile"),
  sendOtp: (phone: string) => http.post<void>("/mp/user/otp/send", { phone }),
  bindPhone: (phone: string, code: string) => http.post<User>("/mp/user/phone/bind", { phone, code }),
  bindPhoneByWx: (code: string) => http.post<User>("/mp/user/phone/wx", { code }),
  phoneCapable: () => http.get<PhoneCapable>("/mp/user/phone/capable"),
  /** 订阅授权上报（同意与拒绝都报）：后端据此记额度、并且不再反复弹窗 */
  subscribeReport: (templateIds: string[], accepted: boolean) =>
    http.post<void>("/mp/message/subscribe", { templateIds, accepted }),

  // ── 账号：买家与供应商两面的身份与红点（只有状态与计数，没有任何一面的内容）──
  me: () => http.get<ElecMe>("/elec/me"),

  // ── 买家：料号 ──
  searchParts: (keyword: string, suggest = false) =>
    http.get<ElecSearchResult>("/elec/c/part", { keyword, suggest }),
  lookup: (text: string) => http.get<ElecLookupLine[]>("/elec/c/part/lookup", { text }),
  partDetail: (partNo: string) => http.get<ElecPartHit>(`/elec/c/part/${enc(partNo)}`),

  // ── 买家：询价 ──
  submitRfq: (req: ElecRfqReq) => http.post<ElecRfq>("/elec/c/rfq", req),
  myRfqs: (page = 1, size = 20) => http.get<ElecRfq[]>("/elec/c/rfq", { page, size }),
  rfqDetail: (rfqNo: string) => http.get<ElecRfq>(`/elec/c/rfq/${enc(rfqNo)}`),
  acceptRfq: (rfqNo: string) => http.post<ElecRfq>(`/elec/c/rfq/${enc(rfqNo)}/accept`),
  acceptOffer: (rfqNo: string, lineNo: number, offerNo: string) =>
    http.post<ElecRfq>(`/elec/c/rfq/${enc(rfqNo)}/line/${lineNo}/accept`, { offerNo }),

  // ── 供应商：档案与库存 ──
  mySupplier: () => http.get<ElecSupplier | null>("/elec/b/supplier"),
  joinSupplier: (req?: ElecSupplierReq) => http.post<ElecSupplier>("/elec/b/supplier", req ?? {}),
  updateSupplier: (req: ElecSupplierReq) => http.put<ElecSupplier>("/elec/b/supplier", req),
  myStocks: (q: { keyword?: string; filter?: ElecStockFilter; page?: number; size?: number }) =>
    http.get<ElecStock[]>("/elec/b/stock", q),
  renewStocks: () => http.post<ElecRenewResult>("/elec/b/stock/renew"),
  /** 表单字段是字符串（multipart）；后端按 @RequestParam 读 mode 与 taxIncluded */
  uploadStock: (filePath: string, mode: ElecImportMode, taxIncluded?: boolean) =>
    http.uploadFile<ElecBatchPreview>("/elec/b/stock/upload", filePath, {
      mode,
      ...(taxIncluded === undefined ? {} : { taxIncluded: String(taxIncluded) }),
    }),
  remapBatch: (batchNo: string, req: ElecRemapReq) =>
    http.post<ElecBatchPreview>(`/elec/b/stock/batch/${enc(batchNo)}/remap`, req),
  applyBatch: (batchNo: string) => http.post<ElecBatchPreview>(`/elec/b/stock/batch/${enc(batchNo)}/apply`),

  // ── 供应商：求购与报价 ──
  myDispatches: (status?: ElecDispatchStatus, page = 1, size = 20) =>
    http.get<ElecDispatch[]>("/elec/b/rfq", { ...(status ? { status } : {}), page, size }),
  dispatchDetail: (dispatchNo: string) => http.get<ElecDispatch>(`/elec/b/rfq/${enc(dispatchNo)}`),
  quoteDispatch: (dispatchNo: string, req: ElecSupplierQuoteReq) =>
    http.post<ElecDispatch>(`/elec/b/rfq/${enc(dispatchNo)}/quote`, req),
  declineDispatch: (dispatchNo: string, req: ElecDeclineReq) =>
    http.post<ElecDispatch>(`/elec/b/rfq/${enc(dispatchNo)}/decline`, req),
};

/** 错误信息给 toast 用 */
export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export function toast(title: string): void {
  uni.showToast({ title, icon: "none" });
}
