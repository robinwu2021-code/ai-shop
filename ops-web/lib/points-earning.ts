/** Reward rule history includes imported legacy revisions. */
export type EarningRuleAction = "IMPORT" | "SAVE" | "PUBLISH" | "DISABLE";
/** Actions operators may submit; legacy import is server-owned. */
export type EarningChangeAction = Exclude<EarningRuleAction, "IMPORT">;
/** Reward calculation mode; historical fixed rewards remain readable. */
export type EarningRewardMode = "RATIO" | "FIXED";

import type { PointsTypeConfig } from "@/lib/types";
export interface EarningCategory {
  /** Product category identifier; null when no category was assigned. */
  categoryNo: string;
  /** Parent category identifier; null for a root category. */
  parentNo: string | null;
  /** Human-readable name displayed for this business resource. */
  name: string;
  /** Human-readable category hierarchy from its root to this category. */
  path: string }
export interface EarningContext {
  /** Order-default points type; null when no default type is active. */
  type: PointsTypeConfig | null;
  /** Selectable product categories with their hierarchy and display paths. */
  categories: EarningCategory[];
  /** Points types available for independent rule configuration. */
  types: PointsTypeConfig[] }
export interface EarningRule {
  /** Unique points-type code; separate points types never share balances or rules. */
  typeCode: string;
  /** DEFAULT or a category identifier selecting the reward rule's applicability. */
  scope: string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision: number;
  /** Published rule revision; null when no revision is active. */
  activeRevision: number | null;
  /** Calculation or scope mode determining how the associated value and references are interpreted. */
  mode: EarningRewardMode;
  /** Reward value interpreted by its mode: basis points for RATIO, points per item for FIXED. */
  value: number;
  /** Published reward calculation mode; null before publication. */
  activeMode: EarningRewardMode | null;
  /** Published reward value interpreted using activeMode. */
  activeValue: number | null;
  /** Server-recorded operator responsible for the rule revision. */
  actor: string;
  /** Operator-supplied audit note explaining the submitted operation. */
  remark: string;
  /** Time the operation was recorded, as epoch milliseconds. */
  at: number;
  /** Operation recorded for this command or audit entry. */
  action?: EarningRuleAction;
}
export interface EarningRulePage {
  /** Records returned for the current indexed page. */
  rows: EarningRule[];
  /** Total number of records matching the current query. */
  total: number;
  /** One-based page index for the returned or requested result. */
  page: number;
  /** Maximum records per page requested or returned by the server. */
  size: number }
export interface EarningChange {
  /** Stable idempotency identifier; retry the same command with the same identifier. */
  requestId: string;
  /** Unique points-type code; separate points types never share balances or rules. */
  typeCode: string;
  /** DEFAULT or a category identifier selecting the reward rule's applicability. */
  scope: string;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision: number;
  /** Operation recorded for this command or audit entry. */
  action: EarningChangeAction;
  /** Reward percentage in basis points; zero means no reward and null is used outside SAVE. */
  ratioBp: number | null;
  /** Operator-supplied audit note explaining the submitted operation. */
  remark: string }
export interface EarningCalculation {
  /** Points identity, currency precision and exchange ratio used for this calculation. */
  type: {
  /** Unique points-type code; separate points types never share balances or rules. */
  typeCode:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Market code whose currency and business calendar apply. */
  market:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:string;
  /** Currency decimal precision used to convert displayed money to minor units. */
  scale:number;
  /** Points component of the points conversion ratio. */
  ratePoints:number;
  /** Minor-currency-unit component of the points conversion ratio. */
  rateMinor:number};
  /** Published reward rule and revision selected for this calculation. */
  matched: {
  /** DEFAULT or a category identifier selecting the reward rule's applicability. */
  scope: string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision: number;
  /** Immutable calculation rule or versioned activity rule definition. */
  rule: {
  /** Calculation or scope mode determining how the associated value and references are interpreted. */
  mode: EarningRewardMode;
  /** Reward value interpreted by its mode: basis points for RATIO, points per item for FIXED. */
  value: number} };
  /** Cash amount used for the reward calculation, in minor currency units. */
  cashMinor: number;
  /** Calculated reward points after applying the published rule and exchange rate. */
  points: number }
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
  /** Parent order identifier for the reward inspection. */
  orderNo:string;
  /** Suborder identifier whose reward facts are being inspected. */
  subOrderNo:string;
  /** Unique points-type code; separate points types never share balances or rules. */
  typeCode:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:string;
  /** Immutable order reward inputs and matched rule traces. */
  snapshot:{
  /** Version of the immutable rule or order snapshot format. */
  schemaVersion:number;
  /** Per-line matched rule scopes and revisions captured when the order was accepted. */
  ruleTraces:{
  /** Order-line identifier used to correlate the reward snapshot and rule trace. */
  itemId:string;
  /** Product category identifier; null when no category was assigned. */
  categoryNo:string|null;
  /** Category identifier or DEFAULT identifying the matched reward rule. */
  matchedScope:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number}[];
  /** Product lines with their immutable reward-calculation inputs. */
  lines:{
  /** Order-line identifier used to correlate the reward snapshot and rule trace. */
  itemId:string;
  /** Product identifier captured in the order's reward snapshot. */
  goodsNo:string;
  /** Product category identifier; null when no category was assigned. */
  categoryNo:string|null;
  /** Product quantity used by the snapshot or trigger; null when not applicable. */
  quantity:number;
  /** Cash actually paid for this product line, in minor currency units. */
  goodsCashMinor:number;
  /** Immutable calculation rule or versioned activity rule definition. */
  rule:{
  /** Calculation or scope mode determining how the associated value and references are interpreted. */
  mode:EarningRewardMode;
  /** Reward value interpreted by its mode: basis points for RATIO, points per item for FIXED. */
  value:number}}[]};
  /** Posted reward accounting projection; null before a grant is recorded. */
  ledger:{
  /** Current processing projection for the submitted business operation. */
  state:string;
  /** Original expected reward points frozen when the order was accepted. */
  expectedPoints:number;
  /** Reward points actually posted to the account ledger. */
  grantedPoints:number;
  /** Granted points that have become available for use. */
  availablePoints:number;
  /** Granted points still awaiting conversion to an available balance. */
  pendingPoints:number;
  /** Previously granted points subsequently recovered by refunds or reversals. */
  reclaimedPoints:number;
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string|null}|null;
}
