import type { PointsTypeConfig } from "@/lib/types";
export interface EarningCategory { categoryNo: string; parentNo: string | null; name: string; path: string }
export interface EarningContext { type: PointsTypeConfig | null; categories: EarningCategory[] }
export interface EarningRule {
  typeCode: string; scope: string; revision: number; activeRevision: number | null;
  mode: "RATIO" | "FIXED"; value: number; activeMode: "RATIO" | "FIXED" | null; activeValue: number | null;
  actor: string; remark: string; at: number; action?: "IMPORT" | "SAVE" | "PUBLISH" | "DISABLE";
}
export interface EarningRulePage { rows: EarningRule[]; total: number; page: number; size: number }
export interface EarningChange { requestId: string; typeCode: string; scope: string; expectedRevision: number; action: "SAVE" | "PUBLISH" | "DISABLE"; ratioBp: number | null; remark: string }
export interface EarningCalculation { matched: { scope: string; revision: number; rule: {mode: "RATIO" | "FIXED"; value: number} }; cashMinor: number; points: number }
/** Decimal text is checked before conversion; no implicit rounding of operator input. */
export function percentToBasisPoints(input: string): number {
  if (!/^\d{1,3}(?:\.\d{1,2})?$/.test(input.trim())) throw new Error("比例需为 0～100%，最多两位小数");
  const [whole, fraction = ""] = input.trim().split(".");
  const value = Number(whole) * 100 + Number(fraction.padEnd(2,"0"));
  if (value > 10000) throw new Error("比例需为 0～100%");
  return value;
}
export function fiatToMinor(input: string, scale: number): number {
  if (!new RegExp(scale === 0 ? "^\\d+$" : `^\\d+(?:\\.\\d{1,${scale}})?$`).test(input.trim())) throw new Error("金额格式或小数位不正确");
  const [whole,fraction=""]=input.trim().split(".");
  const amount=BigInt(whole)*10n**BigInt(scale)+BigInt(fraction.padEnd(scale,"0"));
  if(amount>BigInt(Number.MAX_SAFE_INTEGER)) throw new Error("金额过大");
  return Number(amount);
}
export interface OrderEarningInspection {
 orderNo:string;subOrderNo:string;typeCode:string;currency:string;
 snapshot:{schemaVersion:number;ruleTraces:{itemId:string;categoryNo:string|null;matchedScope:string;revision:number}[];lines:{itemId:string;goodsNo:string;categoryNo:string|null;quantity:number;goodsCashMinor:number;rule:{mode:"RATIO"|"FIXED";value:number}}[]};
 ledger:{state:string;expectedPoints:number;grantedPoints:number;availablePoints:number;pendingPoints:number;reclaimedPoints:number;reason:string|null}|null;
}
