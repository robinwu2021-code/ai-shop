import type { EInvoiceApi } from "../contracts/invoice";
import type { Page } from "@/lib/types/common";
import type {
  EInvoice, EInvoiceProviderCheck, EInvoiceProviderVerification, EInvoiceRange, EInvoiceSeller, EInvoiceTransmission,
} from "../invoice-types";
import { client } from "../http-client";

const enc = encodeURIComponent;
export const eInvoiceHttp: EInvoiceApi = {
  eInvoiceSellers: (q) => client.get<Page<EInvoiceSeller>>("/ops/sales-invoice-sellers", q),
  eInvoiceVerifyProvider: (entityNo) =>
    client.post<EInvoiceProviderVerification>(`/ops/sales-invoice-sellers/${enc(entityNo)}/provider-verifications`, {}),
  eInvoiceFetchRange: (entityNo, periodCode) =>
    client.post<EInvoiceRange>(`/ops/sales-invoice-sellers/${enc(entityNo)}/provider-ranges`, periodCode ? { periodCode } : {}),
  eInvoiceTransmissions: (q) => client.get<Page<EInvoiceTransmission>>("/ops/sales-invoice-transmissions", q),
  eInvoices: (q) => client.get<Page<EInvoice>>("/ops/sales-invoices", q),
  eInvoiceRetransmit: (invoiceNo) => client.post<EInvoice>(`/ops/sales-invoices/${enc(invoiceNo)}/retransmissions`, {}),
  eInvoiceProviderCheck: (invoiceNo) =>
    client.post<EInvoiceProviderCheck>(`/ops/sales-invoices/${enc(invoiceNo)}/provider-checks`, {}),
};
