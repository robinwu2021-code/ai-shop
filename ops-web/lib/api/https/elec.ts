// 覆盖范围：电子元器件（P-19）。**elec-svc 的 /elec/ops/**，走第二个基址**（NEXT_PUBLIC_ELEC_BASE，生产同域留空）。
// 导入时改名成 client：openapi-parity 与 gen-openapi 按字面量 `client.` 扫调用
import type {
  ElecAliasResult, ElecAliasRow, ElecHeaderAliasRow, ElecMfrRow, ElecOpsPartDetail, ElecOpsPartRow, ElecOpsQuoteRow, ElecOpsRfq,
  ElecOpsStockRow, ElecOpsSupplierDetail, ElecOpsSupplierRow, ElecStockView, ElecUnknownMfrRow,
} from "@/lib/types";
import { elecClient as client } from "../http-client";
import type { ElecApi } from "../contracts/elec";

const enc = encodeURIComponent;

export const elecHttp: ElecApi = {
  listElecRfqs: (q) => client.get<ElecOpsRfq[]>("/elec/ops/rfq", { page: 1, size: 50, ...q }),
  getElecRfq: (rfqNo) => client.get<ElecOpsRfq>(`/elec/ops/rfq/${enc(rfqNo)}`),
  quoteElecRfq: (rfqNo, req) => client.post<ElecOpsRfq>(`/elec/ops/rfq/${enc(rfqNo)}/quote`, req),
  closeElecRfq: (rfqNo, reason, note) =>
    client.post<ElecOpsRfq>(`/elec/ops/rfq/${enc(rfqNo)}/close`, { reason, note }),
  setElecPriceMode: (rfqNo, mode) => client.put<ElecOpsRfq>(`/elec/ops/rfq/${enc(rfqNo)}/price-mode`, { mode }),
  dispatchElecLine: (rfqNo, lineNo, supplierNos) =>
    client.post<ElecOpsRfq>(`/elec/ops/rfq/${enc(rfqNo)}/line/${lineNo}/dispatch`, { supplierNos }),
  listElecQuotes: (q) => client.get<ElecOpsQuoteRow[]>("/elec/ops/quote", { page: 1, size: 50, ...q }),

  listElecSuppliers: (q) => client.get<ElecOpsSupplierRow[]>("/elec/ops/supplier", { page: 1, size: 50, ...q }),
  getElecSupplier: (no) => client.get<ElecOpsSupplierDetail>(`/elec/ops/supplier/${enc(no)}`),
  listElecSupplierStocks: (no, q) =>
    client.get<ElecStockView[]>(`/elec/ops/supplier/${enc(no)}/stock`, { page: 1, size: 50, ...q }),
  updateElecSupplier: (no, req) => client.put<ElecOpsSupplierDetail>(`/elec/ops/supplier/${enc(no)}`, req),
  suspendElecSupplier: (no, reason) =>
    client.post<ElecOpsSupplierDetail>(`/elec/ops/supplier/${enc(no)}/suspend`, { reason }),
  resumeElecSupplier: (no) => client.post<ElecOpsSupplierDetail>(`/elec/ops/supplier/${enc(no)}/resume`, {}),
  approveElecSupplier: (no) => client.post<ElecOpsSupplierDetail>(`/elec/ops/supplier/${enc(no)}/approve`, {}),

  searchElecParts: (q) => client.get<ElecOpsPartRow[]>("/elec/ops/part", { q }),
  getElecPart: (partNo) => client.get<ElecOpsPartDetail>(`/elec/ops/part/${enc(partNo)}`),
  listElecStocks: (q) => client.get<ElecOpsStockRow[]>("/elec/ops/stock", { page: 1, size: 50, ...q }),

  listElecMfrs: (q) => client.get<ElecMfrRow[]>("/elec/ops/mfr", { q }),
  createElecMfr: (req) => client.post<ElecMfrRow>("/elec/ops/mfr", req),
  renameElecMfr: (code, req) => client.put<ElecMfrRow>(`/elec/ops/mfr/${enc(code)}`, req),
  listElecAliases: (code) => client.get<ElecAliasRow[]>(`/elec/ops/mfr/${enc(code)}/alias`),
  addElecAlias: (code, alias) => client.post<ElecAliasResult>(`/elec/ops/mfr/${enc(code)}/alias`, { alias }),
  listElecUnknownMfrs: (limit) => client.get<ElecUnknownMfrRow[]>("/elec/ops/mfr/unknown", { limit }),
  listElecHeaderAliases: (q) => client.get<ElecHeaderAliasRow[]>("/elec/ops/header-alias", q),
  createElecHeaderAlias: (req) => client.post<ElecHeaderAliasRow>("/elec/ops/header-alias", req),
  updateElecHeaderAlias: (id, req) => client.put<ElecHeaderAliasRow>(`/elec/ops/header-alias/${id}`, req),
};
