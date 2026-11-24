// 台湾电子发票（买方）的 mock。载具存在内存里；「我的发票」不造假数据 ——
// 发票号码只存在于真实后端的字轨号段上，mock 里编一张会让人误以为开票通了。
import type { ShopApi } from "../contract";
import type { InvoiceCarrier } from "../sales-invoice-types";

let carriers: InvoiceCarrier[] = [];
let seq = 0;

function notFound(): never {
  throw Object.assign(new Error("Not found"), { code: 10404 });
}

export const salesInvoiceMock: Pick<ShopApi,
  "invoiceCarriers" | "saveInvoiceCarrier" | "deleteInvoiceCarrier" | "chooseSalesInvoice" | "mySalesInvoices" | "salesInvoiceDetail"> = {
  async invoiceCarriers() {
    return carriers.map((c) => ({ ...c }));
  },
  async saveInvoiceCarrier(body) {
    const same = carriers.find((c) => c.carrierType === body.carrierType && c.carrierId === body.carrierId);
    const makeDefault = !!body.makeDefault || !carriers.length;
    if (makeDefault) carriers = carriers.map((c) => ({ ...c, isDefault: false }));
    const saved: InvoiceCarrier = same
      ? { ...same, label: body.label ?? same.label, isDefault: makeDefault || same.isDefault }
      : { carrierNo: `CR${++seq}`, carrierType: body.carrierType, carrierId: body.carrierId, label: body.label ?? null, isDefault: makeDefault };
    carriers = same ? carriers.map((c) => (c.carrierNo === same.carrierNo ? saved : c)) : [...carriers, saved];
    return { ...saved };
  },
  async deleteInvoiceCarrier(carrierNo) {
    carriers = carriers.filter((c) => c.carrierNo !== carrierNo);
  },
  async chooseSalesInvoice(body) {
    return [{ sourceNo: body.orderNo, outcome: "SAVED" as const, fiscalNumber: null }];
  },
  async mySalesInvoices(page, size) {
    return { records: [], total: 0, page, size };
  },
  async salesInvoiceDetail() {
    return notFound();
  },
};
