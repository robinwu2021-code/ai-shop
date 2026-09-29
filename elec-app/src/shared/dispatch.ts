// 求购（派给供应商的那一条）的几句人话：列表与详情共用。
import type { ElecDispatch } from "@shared/types";
import { COND_REQ, DC_REQ, INVOICE, PACKING_REQ } from "./format";

/** 买家的要求，拼成一行。只列他真的提了的 —— 「不限」不必说 */
export function demandLine(d: ElecDispatch): string {
  return [
    d.dcReq !== "ANY" ? DC_REQ[d.dcReq] : "",
    d.condReq && d.condReq !== "ANY" ? COND_REQ[d.condReq] : "",
    d.packingReq && d.packingReq !== "ANY" ? PACKING_REQ[d.packingReq] : "",
    d.needByDays ? `${d.needByDays} 天内到货` : "",
    d.allowAlt ? "可用替代" : "",
    INVOICE[d.needInvoice],
    d.deliverProvince ? `发往${d.deliverProvince}` : "",
  ].filter(Boolean).join(" · ");
}
