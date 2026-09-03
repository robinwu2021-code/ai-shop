/**
 * 结算页 ⇄ 地址簿之间的一次性交接：**这一单送到哪儿**。
 *
 * <p><b>此前是借「改默认地址」来传的</b>：地址簿在 `picking` 模式下点一条就
 * `setDefaultAddress`，结算页那边读默认。省了一个接口，代价是
 * <b>把一单的选择写成了长期偏好</b> —— 给父母寄一次东西，从此每一单都预填父母家。
 * 这与「生效位置 ≠ 默认地址」是同一类错误，只是错在另一对概念上。
 *
 * <p><b>为什么不是 pinia store</b>：这是一次交接，不是一份状态。
 * 放进 store 就多出「谁负责清掉它」这个问题，而清不干净的表现是
 * 下一次进结算页莫名其妙跳到上次选的那条 —— 一个没人能复现的「灵异」缺陷。
 * {@link takePickedAddress} 读即清，从形状上就没有这个问题。
 */
let pending: string | null = null;

/** 地址簿在 `picking` 模式下选中一条时调用，随后 `navigateBack`。 */
export function offerPickedAddress(addressId: string): void {
  pending = addressId;
}

/**
 * 结算页回到前台时取。**读一次就没了** —— 它只对这一次返回有效。
 *
 * <p>返回 null 是常态：用户点系统返回、没选就退出来，这一页照旧显示原来那条。
 */
export function takePickedAddress(): string | null {
  const picked = pending;
  pending = null;
  return picked;
}
