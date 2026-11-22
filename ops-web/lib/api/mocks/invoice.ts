import type { EInvoiceApi } from "../contracts/invoice";

// 电子发票没有可模拟的来源：号码、上传、服务商回执都只存在于真实后端。不造假成功，一律失败关闭。
const unavailable = async (): Promise<never> => {
  throw Error("E-invoices require the real backend; mock mode has no invoice data");
};
export const eInvoiceMock: EInvoiceApi = {
  eInvoiceSellers: unavailable, eInvoiceVerifyProvider: unavailable, eInvoiceFetchRange: unavailable,
  eInvoiceTransmissions: unavailable, eInvoices: unavailable, eInvoiceRetransmit: unavailable,
  eInvoiceProviderCheck: unavailable,
};
