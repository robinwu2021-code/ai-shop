// 电子元器件（P-19）—— **独立服务** elec-svc 的 `/elec/ops/**`，不是主系统的 `/ops/**`。
// 判权在 elec-svc（ElecOpsGuard 按权限码），这里的方法与后端端点一一对应。
// 列表一律返回数组（后端是 List，不是分页壳）。
import type {
  ElecAliasResult, ElecAliasRow, ElecHeaderAliasRow, ElecHeaderAliasScope, ElecCloseReason, ElecPriceMode, ElecMfrReq, ElecMfrRow, ElecOpsPartDetail, ElecOpsPartRow,
  ElecOpsQuoteRow, ElecOpsRfq, ElecOpsStockRow, ElecOpsSupplierDetail, ElecOpsSupplierRow, ElecQuoteReq,
  ElecStockFilter, ElecStockView, ElecSupplierReq, ElecSupplierStatus, ElecUnknownMfrRow,
} from "@/lib/types";

export interface ElecApi {
  // ── 询报价（elec:rfq:read / elec:rfq:quote）──
  /** 询价单列表。行里不带「库里谁有货」与各家报价 —— 那两样只在详情里 */
  listElecRfqs(q: { status?: string; page?: number; size?: number }): Promise<ElecOpsRfq[]>;
  /** 询价单详情：买家完整联系方式、每行库里谁有货、每家报了什么（真名、原价） */
  getElecRfq(rfqNo: string): Promise<ElecOpsRfq>;
  /** 录入平台报价。**保存即通知买家**；没列进来的行 = 没找到货 */
  quoteElecRfq(rfqNo: string, req: ElecQuoteReq): Promise<ElecOpsRfq>;
  /** 关单。NO_SOURCE 会通知买家「暂无货源」 */
  closeElecRfq(rfqNo: string, reason: ElecCloseReason, note?: string): Promise<ElecOpsRfq>;
  /** 改报价模式：已有一行选定报价、或单子已接受 / 关掉时后端回 90011 */
  setElecPriceMode(rfqNo: string, mode: ElecPriceMode): Promise<ElecOpsRfq>;
  /** 手工指派：给这一行再派几家（派过的自动跳过，一次最多 20 家） */
  dispatchElecLine(rfqNo: string, lineNo: number, supplierNos: string[]): Promise<ElecOpsRfq>;
  /** 报价记录：全部供应商报价，按时间倒序 */
  listElecQuotes(q: { supplierNo?: string; status?: string; page?: number; size?: number }): Promise<ElecOpsQuoteRow[]>;

  // ── 供应商（elec:supplier:read / elec:supplier:manage）──
  listElecSuppliers(q: { keyword?: string; status?: ElecSupplierStatus; page?: number; size?: number }): Promise<ElecOpsSupplierRow[]>;
  getElecSupplier(supplierNo: string): Promise<ElecOpsSupplierDetail>;
  /** 他的库存（精确数量、原价） */
  listElecSupplierStocks(supplierNo: string, q: { keyword?: string; filter?: ElecStockFilter; page?: number; size?: number }): Promise<ElecStockView[]>;
  /** 改资料（空字段 = 不改） */
  updateElecSupplier(supplierNo: string, req: ElecSupplierReq): Promise<ElecOpsSupplierDetail>;
  /** 暂停。**他的货当场不再给买家看**；理由至少 2 个字，只给平台看 */
  suspendElecSupplier(supplierNo: string, reason: string): Promise<ElecOpsSupplierDetail>;
  resumeElecSupplier(supplierNo: string): Promise<ElecOpsSupplierDetail>;
  approveElecSupplier(supplierNo: string): Promise<ElecOpsSupplierDetail>;

  // ── 料号与库存（elec:part:read）──
  /** 料号搜索：与买家同一套命中，但**不计入搜索需求**。最多 50 条 */
  searchElecParts(q: string): Promise<ElecOpsPartRow[]>;
  /** 某料号谁有货：运营报价时最常看的一屏 */
  getElecPart(partNo: string): Promise<ElecOpsPartDetail>;
  listElecStocks(q: { q?: string; supplierNo?: string; filter?: ElecStockFilter; page?: number; size?: number }): Promise<ElecOpsStockRow[]>;

  // ── 基础数据（elec:base:manage）──
  listElecMfrs(q?: string): Promise<ElecMfrRow[]>;
  /** 加厂牌。**会把自己的代码与名字登成别名**，否则新厂牌永远认不出 */
  createElecMfr(req: ElecMfrReq): Promise<ElecMfrRow>;
  /** 改名（代码建了不能改） */
  renameElecMfr(mfrCode: string, req: ElecMfrReq): Promise<ElecMfrRow>;
  listElecAliases(mfrCode: string): Promise<ElecAliasRow[]>;
  /** 加别名。**当场改认既有库存**：返回改认了多少行、动了几个料号 */
  addElecAlias(mfrCode: string, alias: string): Promise<ElecAliasResult>;
  /** 认不出的厂牌：按出现次数排、带建议 */
  listElecUnknownMfrs(limit?: number): Promise<ElecUnknownMfrRow[]>;
  /** 库存表的表头写法：全局的，或各家学到的（带几家在用） */
  listElecHeaderAliases(q: { scope: ElecHeaderAliasScope; keyword?: string; page?: number; size?: number }): Promise<ElecHeaderAliasRow[]>;
  /** 加一条全局写法，或把学到的提升为全局（同一写法已有全局的：改成这个字段并启用）。当场生效 */
  createElecHeaderAlias(req: { alias: string; field: string }): Promise<ElecHeaderAliasRow>;
  /** 改全局写法认成的字段，或停用 / 启用（status：ACTIVE / DISABLED） */
  updateElecHeaderAlias(id: number, req: { field?: string; status?: string }): Promise<ElecHeaderAliasRow>;
}
