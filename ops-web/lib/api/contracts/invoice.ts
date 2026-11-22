import type { Page } from "@/lib/types/common";
import type {
  EInvoice, EInvoiceProviderCheck, EInvoiceProviderVerification, EInvoiceQuery, EInvoiceRange, EInvoiceSeller,
  EInvoiceSellerQuery, EInvoiceTransmission, EInvoiceTransmissionQuery,
} from "../invoice-types";

/** 运营端 · 电子发票（finance:invoice:read 看；finance:invoice:verify 核验、领号、重传、查单）。 */
export interface EInvoiceApi {
  eInvoiceSellers(q: EInvoiceSellerQuery): Promise<Page<EInvoiceSeller>>;
  /** 代商家核验加值中心账号；通过即解除认证失败造成的上传暂停 */
  eInvoiceVerifyProvider(entityNo: string): Promise<EInvoiceProviderVerification>;
  /** 代商家向加值中心领号段；periodCode 不传 = 当期 */
  eInvoiceFetchRange(entityNo: string, periodCode?: string): Promise<EInvoiceRange>;
  eInvoiceTransmissions(q: EInvoiceTransmissionQuery): Promise<Page<EInvoiceTransmission>>;
  eInvoices(q: EInvoiceQuery): Promise<Page<EInvoice>>;
  /** 代商家重传待人工处理的上传 */
  eInvoiceRetransmit(invoiceNo: string): Promise<EInvoice>;
  /** 代商家向服务商查单并对账 */
  eInvoiceProviderCheck(invoiceNo: string): Promise<EInvoiceProviderCheck>;
}
