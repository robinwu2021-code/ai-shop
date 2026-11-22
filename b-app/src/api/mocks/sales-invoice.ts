import type { SalesInvoiceApi } from "../sales-invoice";
/** No fake success: fiscal numbers only exist on the real server's number ranges. */
const unavailable = async (): Promise<never> => { throw Error("Sales invoices require the real backend"); };
export const salesInvoiceMock: SalesInvoiceApi = {
  mSiIssue: unavailable, mSiBuyerPreference: unavailable, mSiList: unavailable, mSiDetail: unavailable, mSiVoid: unavailable, mSiAllowance: unavailable, mSiRetransmit: unavailable, mSiUploadBacklog: unavailable, mSiProviderCheck: unavailable, mSiVoidAllowance: unavailable,
  mSiProfile: unavailable, mSiSaveProfile: unavailable, mSiRanges: unavailable, mSiAddRange: unavailable,
  mSiVerifyProvider: unavailable, mSiFetchProviderRange: unavailable, mSiProviders: unavailable, mSiPrintFacts: unavailable,
};
