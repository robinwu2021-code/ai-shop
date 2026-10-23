/** Rule history action, including imported revisions. */
export type PointsEarningRuleAction = "IMPORT" | "SAVE" | "PUBLISH" | "DISABLE";

/** Rule mutation requested by the operator. */
export type PointsEarningRuleMutation = "SAVE" | "PUBLISH" | "DISABLE";

/** Points earning rule calculation mode. */
export type PointsEarningRuleMode = "RATIO" | "FIXED";

import type { PointsTypeConfig } from "@/lib/types";
export interface EarningCategory {
  /** 商品分类编号 */
  categoryNo: string;
  /** 父分类编号，根节点可为空 */
  parentNo: string | null;
  /** 业务对象显示名称 */
  name: string;
  /** 分类层级路径 */
  path: string }
export interface EarningContext {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type: PointsTypeConfig | null;
  /** 可用商品分类列表 */
  categories: EarningCategory[];
  /** 可用积分类型列表 */
  types: PointsTypeConfig[] }
export interface EarningRule {

  /** 积分类型唯一编码 */
  typeCode: string;
  /** 规则适用范围编码 */
  scope: string;
  /** 当前记录版本号，用于并发控制 */
  revision: number;
  /** 当前生效版本号 */
  activeRevision: number | null;

  /** 规则模式编码 */
  mode: PointsEarningRuleMode;
  /** 当前规则配置值 */
  value: number;
  /** 已生效规则模式 */
  activeMode: PointsEarningRuleMode | null;
  /** 已生效规则值 */
  activeValue: number | null;

  /** 操作人标识 */
  actor: string;
  /** 操作备注 */
  remark: string;
  /** 业务发生时间 */
  at: number;
  /** 本次执行的业务动作 */
  action?: PointsEarningRuleAction;
}
export interface EarningRulePage {
  /** 当前页数据行 */
  rows: EarningRule[];
  /** 匹配条件的总记录数量 */
  total: number;
  /** 当前页码，从1开始 */
  page: number;
  /** 请求的每页记录数量 */
  size: number }
export interface EarningChange {
  /** 调用方生成并在重试时保持不变的请求标识 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 规则适用范围编码 */
  scope: string;
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision: number;
  /** 本次执行的业务动作 */
  action: PointsEarningRuleMutation;
  /** 比例，万分比单位，10000代表100% */
  ratioBp: number | null;
  /** 操作备注 */
  remark: string }
export interface EarningCalculation {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type: {
  /** 积分类型唯一编码 */
  typeCode:string;
  /** 业务对象显示名称 */
  name:string;market:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:string;scale:number;ratePoints:number;rateMinor:number};
  /** 是否命中可用赠送规则 */
  matched: {
  /** 规则适用范围编码 */
  scope: string;
  /** 当前记录版本号，用于并发控制 */
  revision: number; rule: {
  /** 规则模式编码 */
  mode: PointsEarningRuleMode;
  /** 当前规则配置值 */
  value: number} };
  /** 参与计算的现金金额，货币最小单位 */
  cashMinor: number;
  /** 按当前规则计算的积分数量 */
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

  /** 订单内部业务编号 */
  orderNo:string;
  /** 子订单内部业务编号 */
  subOrderNo:string;
  /** 积分类型唯一编码 */
  typeCode:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:string;

  /** 业务处理时冻结的权威快照 */
  snapshot:{schemaVersion:number;ruleTraces:{itemId:string;
  /** 商品分类编号 */
  categoryNo:string|null;matchedScope:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number}[];lines:{itemId:string;goodsNo:string;
  /** 商品分类编号 */
  categoryNo:string|null;quantity:number;goodsCashMinor:number;rule:{
  /** 规则模式编码 */
  mode:PointsEarningRuleMode;
  /** 当前规则配置值 */
  value:number}}[]};

  /** 权威账务查询结果 */
  ledger:{
  /** 服务端处理状态编码 */
  state:string;
  /** 订单预计可获得的积分数量 */
  expectedPoints:number;
  /** 已经确认发放的积分数量 */
  grantedPoints:number;
  /** 当前可用积分数量 */
  availablePoints:number;
  /** 等待确认发放的积分数量 */
  pendingPoints:number;
  /** 退款后已收回的赠送积分数量 */
  reclaimedPoints:number;
  /** 业务原因或阻断说明 */
  reason:string|null}|null;
}
