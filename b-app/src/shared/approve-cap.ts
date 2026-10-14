/**
 * 商家核定退款的上限（分）= **买家申请额**（后端入库时已保证 ≤ 子单实付）；
 * 老数据没有申请额才回落订单实付。
 *
 * ⚠️ 此前取的是订单实付：买家三件只申请退一件，核定面板却预填整单实付，
 * 商家直接点同意就退了整单（TDD-订单售后三端断点修复 AC10）。
 */
export function approveCap(refundMinor: number | null | undefined, paidMinor: number | null | undefined): number {
  return refundMinor || paidMinor || 0;
}
