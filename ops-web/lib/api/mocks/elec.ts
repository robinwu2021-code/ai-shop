// 电子元器件（P-19）的内存 mock。数据**刻意覆盖几种状态**：待报价 / 已报价 / 已关单的询价，
// 正常与已暂停的供应商，认不出的厂牌 —— 只放「一切正常」的数据，看不出这几页是干什么的。
import type {
  ElecAliasRow, ElecMfrRow, ElecOpsPartRow, ElecOpsQuoteRow, ElecOpsRfq, ElecOpsSource, ElecOpsSupplierDetail,
  ElecOpsSupplierRow, ElecStockView, ElecUnknownMfrRow,
} from "@/lib/types";
import type { ElecApi } from "../contracts/elec";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

const src = (o: Partial<ElecOpsSource> & Pick<ElecOpsSource, "supplierNo" | "companyName" | "qty">): ElecOpsSource => ({
  contactPhone: "13900002222", stockNo: `ES${o.supplierNo}${o.qty}`, dateCode: "2338", moq: null, spq: null,
  tiers: o.priceE6 ? [{ minQty: 1, priceE6: o.priceE6 }] : [], priceE6: null, currency: "CNY", taxIncluded: true,
  packing: "REEL", cond: "ORIGINAL", leadDays: 0, region: "深圳", validUntil: "2026-10-30", ...o,
});

const suppliers: ElecOpsSupplierDetail[] = [
  {
    supplierNo: "SP001", companyName: "深圳甲电子有限公司", kind: "TRADER", city: "深圳", contactName: "王工",
    contactPhone: "13900002222", maskCode: "S-3F7K", status: "ACTIVE", suspendReason: null, suspendedAt: null,
    onCount: 1832, expiringCount: 126, expiredCount: 40, lastUploadAt: "2026-09-27T10:02:00",
    registerNotified: true, createdAt: "2026-09-20T09:00:00",
    dispatch: { days: 30, sent: 42, viewed: 38, responded: 31, quoted: 22, accepted: 6 },
  },
  {
    supplierNo: "SP002", companyName: "深圳乙电子", kind: "AGENT", city: "深圳", contactName: "李经理",
    contactPhone: "13700003333", maskCode: "S-8Q2M", status: "SUSPENDED", suspendReason: "报价后连续三次无货",
    suspendedAt: "2026-09-28T16:00:00", onCount: 0, expiringCount: 0, expiredCount: 210,
    lastUploadAt: "2026-09-10T08:00:00", registerNotified: true, createdAt: "2026-09-05T11:00:00",
    dispatch: { days: 30, sent: 12, viewed: 12, responded: 3, quoted: 3, accepted: 0 },
  },
  {
    supplierNo: "SP003", companyName: null, kind: "OTHER", city: null, contactName: null, contactPhone: "13600004444",
    maskCode: "S-Z1PA", status: "ACTIVE", suspendReason: null, suspendedAt: null, onCount: 0, expiringCount: 0,
    expiredCount: 0, lastUploadAt: null, registerNotified: false, createdAt: "2026-09-30T08:00:00",
    dispatch: { days: 30, sent: 0, viewed: 0, responded: 0, quoted: 0, accepted: 0 },
  },
];
const rowOf = (d: ElecOpsSupplierDetail): ElecOpsSupplierRow => ({
  supplierNo: d.supplierNo, companyName: d.companyName, kind: d.kind, city: d.city, contactName: d.contactName,
  contactPhone: d.contactPhone, maskCode: d.maskCode, status: d.status, onCount: d.onCount,
  expiringCount: d.expiringCount, lastUploadAt: d.lastUploadAt, createdAt: d.createdAt,
});

const rfqs: ElecOpsRfq[] = [
  {
    rfqNo: "EQ20260930091201", status: "SUBMITTED", createdAt: "2026-09-30T09:12:00", lineCnt: 3,
    contactName: "王工", contactPhone: "13800005210", company: "某某科技", needInvoice: "VAT_SPECIAL", dcReq: "Y2",
    condReq: "ORIGINAL", packingReq: "ANY", needByDays: 7, allowAlt: false, deliverCity: "深圳", remark: "要原装，急用",
    quotedAt: null, quotedBy: null, quoteValidUntil: null, quoteNote: null, buyerNotified: false, closeReason: null,
    dispatchCnt: 2, respondedCnt: 1, offerCnt: 1,
    lines: [
      {
        lineNo: 1, partNo: "EP001", mpn: "STM32F103C8T6", mfr: "ST", qty: 2000, targetE6: 6_500_000, quote: null,
        sources: [src({ supplierNo: "SP001", companyName: "深圳甲电子有限公司", qty: 5000, priceE6: 6_200_000 })],
        offers: [{
          dispatchNo: "ED001", supplierNo: "SP001", companyName: "深圳甲电子有限公司", contactPhone: "13900002222",
          via: "AUTO_MATCH", dispatchStatus: "QUOTED", declineReason: null, notifiedAt: "2026-09-30T09:12:05",
          respondedAt: "2026-09-30T09:40:00", quoteNo: "EQT001", priceE6: 6_300_000, currency: "CNY", taxIncluded: true,
          buyerPriceE6: 6_804_000, qtyAvailable: 2000, dateCode: "2338", leadDays: 0, cond: "ORIGINAL", packing: "REEL",
          moq: null, validUntil: "2026-10-03", remark: "可以再便宜 2 分", quoteStatus: "ACTIVE",
        }],
      },
      {
        lineNo: 2, partNo: "EP004", mpn: "TPS54331DR", mfr: "TI", qty: 500, targetE6: null, quote: null,
        sources: [src({ supplierNo: "SP001", companyName: "深圳甲电子有限公司", qty: 1200, priceE6: 2_800_000 })],
        offers: [{
          dispatchNo: "ED002", supplierNo: "SP001", companyName: "深圳甲电子有限公司", contactPhone: "13900002222",
          via: "AUTO_MATCH", dispatchStatus: "VIEWED", declineReason: null, notifiedAt: "2026-09-30T09:12:05",
          respondedAt: null, quoteNo: null, priceE6: null, currency: null, taxIncluded: null, buyerPriceE6: null,
          qtyAvailable: null, dateCode: null, leadDays: null, cond: null, packing: null, moq: null, validUntil: null,
          remark: null, quoteStatus: null,
        }],
      },
      // 库里没人有货 → 公开成求购；一家在大厅里自己认领报了价（via OPEN）
      {
        lineNo: 3, partNo: null, mpn: "CH340N", mfr: null, qty: 1000, targetE6: null, quote: null, sources: [],
        publicAt: "2026-09-30T09:12:04",
        offers: [{
          dispatchNo: "ED009", supplierNo: "SP002", companyName: "深圳乙电子", contactPhone: "13700003333",
          via: "OPEN", dispatchStatus: "QUOTED", declineReason: null, notifiedAt: null,
          respondedAt: "2026-09-30T10:05:00", quoteNo: "EQT009", priceE6: 1_050_000, currency: "CNY", taxIncluded: true,
          buyerPriceE6: 1_134_000, qtyAvailable: 1000, dateCode: "2425", leadDays: 2, cond: "NEW", packing: null,
          moq: null, validUntil: "2026-10-03", remark: null, quoteStatus: "ACTIVE",
        }],
      },
    ],
  },
  {
    rfqNo: "EQ20260929150010", status: "QUOTED", createdAt: "2026-09-29T15:00:10", lineCnt: 1,
    contactName: "赵先生", contactPhone: "13500006666", company: null, needInvoice: "VAT_NORMAL", dcReq: "ANY",
    condReq: null, packingReq: null, needByDays: null, allowAlt: true, deliverCity: "东莞", remark: null,
    quotedAt: "2026-09-29T17:20:00", quotedBy: "ops:zhang", quoteValidUntil: "2026-10-02", quoteNote: "原装现货，含税含运",
    buyerNotified: true, closeReason: null, dispatchCnt: 0, respondedCnt: 0, offerCnt: 0,
    lines: [{
      lineNo: 1, partNo: "EP003", mpn: "CH340N", mfr: "WCH", qty: 3000, targetE6: 2_400_000,
      quote: { priceE6: 2_450_000, qty: 3000, dcYear: 2024, leadDays: 0, cond: "ORIGINAL", packing: "REEL", note: null },
      sources: [], offers: [],
    }],
  },
  {
    rfqNo: "EQ20260927100000", status: "CLOSED", createdAt: "2026-09-27T10:00:00", lineCnt: 1,
    contactName: "钱工", contactPhone: "13300007777", company: "某某自动化", needInvoice: "NONE", dcReq: "ANY",
    condReq: null, packingReq: null, needByDays: null, allowAlt: false, deliverCity: "苏州", remark: null,
    quotedAt: null, quotedBy: null, quoteValidUntil: null, quoteNote: null, buyerNotified: true,
    closeReason: "NO_SOURCE", dispatchCnt: 0, respondedCnt: 0, offerCnt: 0,
    lines: [{ lineNo: 1, partNo: null, mpn: "LM2596S-5.0", mfr: null, qty: 300, targetE6: null, quote: null, sources: [], offers: [] }],
  },
];

const parts: ElecOpsPartRow[] = [
  { partNo: "EP001", mpn: "STM32F103C8T6", mfrCode: "ST", mfrName: "意法半导体", mfrNameRaw: null, pkg: "LQFP-48",
    status: "ACTIVE", match: "EXACT", supplierCnt: 1, totalQty: 5000, buyerPriceFromE6: 6_696_000 },
  { partNo: "EP004", mpn: "TPS54331DR", mfrCode: "TI", mfrName: "德州仪器", mfrNameRaw: null, pkg: "SOIC-8",
    status: "ACTIVE", match: "EXACT", supplierCnt: 1, totalQty: 1200, buyerPriceFromE6: 3_024_000 },
  { partNo: "EP009", mpn: "AMS1117-3.3", mfrCode: "UNKNOWN", mfrName: null, mfrNameRaw: "AMS Advanced", pkg: "SOT-223",
    status: "ACTIVE", match: "EXACT", supplierCnt: 2, totalQty: 40000, buyerPriceFromE6: null },
];

const stockOf = (mpn: string, qty: number, status = "ON"): ElecStockView => ({
  stockNo: `ES-${mpn}`, mpn, mfr: "ST", qty, dateCode: "2338", packageName: "LQFP-48", moq: null, spq: null,
  tiers: [{ minQty: 1, priceE6: 6_200_000 }], priceE6: 6_200_000, currency: "CNY", taxIncluded: true, packing: "REEL",
  cond: "ORIGINAL", leadDays: 0, region: "深圳", validUntil: status === "ON" ? "2026-10-30" : "2026-09-20", status,
});

const mfrs: ElecMfrRow[] = [
  { mfrCode: "ST", nameEn: "STMicroelectronics", nameCn: "意法半导体", status: "ACTIVE", mergedInto: null, aliasCnt: 6, partCnt: 1 },
  { mfrCode: "TI", nameEn: "Texas Instruments", nameCn: "德州仪器", status: "ACTIVE", mergedInto: null, aliasCnt: 5, partCnt: 1 },
  { mfrCode: "UNKNOWN", nameEn: "Unknown", nameCn: "厂牌不明", status: "ACTIVE", mergedInto: null, aliasCnt: 0, partCnt: 1 },
];
const aliases: ElecAliasRow[] = [
  { aliasNorm: "ST", mfrCode: "ST", source: "SEED", createdAt: null, createdBy: null },
  { aliasNorm: "STMICROELECTRONICS", mfrCode: "ST", source: "SEED", createdAt: null, createdBy: null },
  { aliasNorm: "意法", mfrCode: "ST", source: "SEED", createdAt: null, createdBy: null },
  { aliasNorm: "TI", mfrCode: "TI", source: "SEED", createdAt: null, createdBy: null },
];
const unknown: ElecUnknownMfrRow[] = [
  { aliasNorm: "STMICRO", sample: "ST Micro", rowCnt: 127, supplierCnt: 3, partCnt: 88, suggestCode: "ST", suggestName: "意法半导体" },
  { aliasNorm: "TEXASINSTRUMENT", sample: "TEXAS INSTRUMENT", rowCnt: 89, supplierCnt: 2, partCnt: 60, suggestCode: "TI", suggestName: "德州仪器" },
  { aliasNorm: "AMSADVANCED", sample: "AMS Advanced", rowCnt: 12, supplierCnt: 1, partCnt: 1, suggestCode: null, suggestName: null },
];

const quotes: ElecOpsQuoteRow[] = [
  { quoteNo: "EQT001", rfqNo: "EQ20260930091201", lineNo: 1, mpn: "STM32F103C8T6", qtyWanted: 2000, supplierNo: "SP001",
    companyName: "深圳甲电子有限公司", priceE6: 6_300_000, currency: "CNY", taxIncluded: true, buyerPriceE6: 6_804_000,
    qtyAvailable: 2000, leadDays: 0, validUntil: "2026-10-03", status: "ACTIVE", createdAt: "2026-09-30T09:40:00" },
  { quoteNo: "EQT000", rfqNo: "EQ20260925080000", lineNo: 1, mpn: "GD32F303RCT6", qtyWanted: 800, supplierNo: "SP002",
    companyName: "深圳乙电子", priceE6: 9_800_000, currency: "CNY", taxIncluded: true, buyerPriceE6: 10_584_000,
    qtyAvailable: 800, leadDays: 3, validUntil: "2026-09-28", status: "EXPIRED", createdAt: "2026-09-25T08:30:00" },
];

const rfqOr404 = (no: string) => rfqs.find((r) => r.rfqNo === no) ?? notFound("询价单", "RFQ", no);
const supOr404 = (no: string) => suppliers.find((s) => s.supplierNo === no) ?? notFound("供应商", "supplier", no);
/** 列表不带「库里谁有货」与各家报价 —— 与后端一致，详情才带 */
const brief = (r: ElecOpsRfq): ElecOpsRfq => ({ ...r, lines: r.lines.map((l) => ({ ...l, sources: null, offers: null })) });

export const elecMock: ElecApi = {
  listElecRfqs: (q) => wait(rfqs.filter((r) => !q.status || r.status === q.status).map(brief)),
  getElecRfq: (no) => wait(structuredClone(rfqOr404(no))),
  quoteElecRfq: (no, req) => {
    const r = rfqOr404(no);
    if (r.status !== "SUBMITTED" && r.status !== "QUOTED") fail("这张单的状态不能报价", "This RFQ can't be quoted now");
    for (const l of req.lines) {
      const line = r.lines.find((x) => x.lineNo === l.lineNo);
      if (line) line.quote = { priceE6: l.priceE6, qty: l.qty ?? null, dcYear: l.dcYear ?? null, leadDays: l.leadDays ?? null, cond: null, packing: null, note: l.note ?? null };
    }
    Object.assign(r, { status: "QUOTED", quotedAt: new Date().toISOString(), quotedBy: "ops:mock", quoteNote: req.note ?? null,
      quoteValidUntil: new Date(Date.now() + (req.validDays ?? 3) * 86_400_000).toISOString().slice(0, 10), buyerNotified: true });
    return wait(structuredClone(r), 400);
  },
  closeElecRfq: (no, reason) => {
    const r = rfqOr404(no);
    Object.assign(r, { status: "CLOSED", closeReason: reason });
    return wait(structuredClone(r), 400);
  },
  setElecPriceMode: (no, mode) => {
    const r = rfqOr404(no);
    if (r.status !== "SUBMITTED" && r.status !== "QUOTED") fail("这张单的状态不能改报价模式", "This RFQ's price mode can't be changed now");
    r.priceMode = mode;
    return wait(structuredClone(r), 300);
  },
  dispatchElecLine: (no) => wait(structuredClone(rfqOr404(no)), 400),
  listElecQuotes: (q) => wait(quotes.filter((x) => (!q.supplierNo || x.supplierNo === q.supplierNo) && (!q.status || x.status === q.status))),

  listElecSuppliers: (q) => wait(suppliers
    .filter((s) => !q.status || s.status === q.status)
    .filter((s) => !q.keyword || `${s.companyName ?? ""}${s.contactPhone ?? ""}${s.maskCode}`.includes(q.keyword))
    .map(rowOf)),
  getElecSupplier: (no) => wait(structuredClone(supOr404(no))),
  listElecSupplierStocks: (no, q) => {
    const s = supOr404(no);
    const all = s.onCount ? [stockOf("STM32F103C8T6", 5000), stockOf("GD32F303RCT6", 800), stockOf("LM358DR", 300, "EXPIRED")] : [];
    return wait(all.filter((x) => q.filter === "EXPIRED" ? x.status === "EXPIRED" : q.filter === "ALL" || !q.filter || x.status === "ON"));
  },
  updateElecSupplier: (no, req) => {
    const s = supOr404(no);
    Object.assign(s, Object.fromEntries(Object.entries(req).filter(([, v]) => v !== undefined && v !== "")));
    return wait(structuredClone(s), 350);
  },
  suspendElecSupplier: (no, reason) => {
    if (reason.trim().length < 2) fail("暂停要写理由（至少 2 个字）", "A reason is required (2+ characters)");
    const s = supOr404(no);
    Object.assign(s, { status: "SUSPENDED", suspendReason: reason, suspendedAt: new Date().toISOString() });
    return wait(structuredClone(s), 400);
  },
  resumeElecSupplier: (no) => {
    const s = supOr404(no);
    s.status = "ACTIVE";
    return wait(structuredClone(s), 400);
  },

  searchElecParts: (q) => wait(parts.filter((p) => p.mpn.toUpperCase().includes(q.trim().toUpperCase()))),
  getElecPart: (partNo) => {
    const p = parts.find((x) => x.partNo === partNo) ?? notFound("料号", "part", partNo);
    const srcs = rfqs.flatMap((r) => r.lines).filter((l) => l.partNo === partNo).flatMap((l) => l.sources ?? []);
    return wait({ part: p, description: null, qtyBand: "B1K", sourceBand: p.supplierCnt > 1 ? "FEW" : "ONE", sources: srcs });
  },
  listElecStocks: (q) => wait(suppliers.filter((s) => s.onCount && (!q.supplierNo || s.supplierNo === q.supplierNo))
    .flatMap((s) => [stockOf("STM32F103C8T6", 5000)].map((stock) => ({
      supplierNo: s.supplierNo, companyName: s.companyName, supplierStatus: s.status, partNo: "EP001", stock })))
    .filter((r) => !q.q || r.stock.mpn.includes(q.q.toUpperCase()))),

  listElecMfrs: (q) => wait(mfrs.filter((m) => !q || `${m.mfrCode}${m.nameEn}${m.nameCn ?? ""}`.toUpperCase().includes(q.toUpperCase()))),
  createElecMfr: (req) => {
    const code = (req.mfrCode ?? "").trim().toUpperCase();
    if (!/^[A-Z0-9]{2,32}$/.test(code)) fail("厂牌代码要 2–32 位大写字母或数字", "Code must be 2-32 uppercase letters/digits");
    if (mfrs.some((m) => m.mfrCode === code)) fail("厂牌代码已存在", "Manufacturer code already exists");
    const row: ElecMfrRow = { mfrCode: code, nameEn: req.nameEn, nameCn: req.nameCn ?? null, status: "ACTIVE", mergedInto: null, aliasCnt: 2, partCnt: 0 };
    mfrs.push(row);
    return wait(row, 350);
  },
  renameElecMfr: (code, req) => {
    const m = mfrs.find((x) => x.mfrCode === code) ?? notFound("厂牌", "manufacturer", code);
    Object.assign(m, { nameEn: req.nameEn, nameCn: req.nameCn ?? m.nameCn });
    return wait(m, 350);
  },
  listElecAliases: (code) => wait(aliases.filter((a) => a.mfrCode === code)),
  addElecAlias: (code, alias) => {
    const norm = alias.toUpperCase().replace(/[\s\p{P}]+/gu, "");
    const taken = aliases.find((a) => a.aliasNorm === norm && a.mfrCode !== code);
    if (taken) fail(`这个写法已经指向 ${taken.mfrCode}`, `This alias already points to ${taken.mfrCode}`);
    aliases.push({ aliasNorm: norm, mfrCode: code, source: "OPS", createdAt: new Date().toISOString(), createdBy: "ops:mock" });
    const u = unknown.findIndex((x) => x.aliasNorm === norm);
    const moved = u >= 0 ? unknown.splice(u, 1)[0]! : null;
    return wait({ aliasNorm: norm, mfrCode: code, movedRows: moved?.rowCnt ?? 0, touchedParts: moved?.partCnt ?? 0 }, 400);
  },
  listElecUnknownMfrs: (limit) => wait(unknown.slice(0, limit ?? 50)),
};
