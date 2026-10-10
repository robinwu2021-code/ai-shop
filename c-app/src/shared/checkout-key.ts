// 结算页的幂等键：**同一次结算，不管提交几次，都是同一个键**。
//
// 此前每次 submit() 都现生成一个随机键，于是「重复点击」之外的重复一个都拦不住：
// 2026-09-18 真机上，绑完手机号 → 自动提交出了一单、跳到支付页 → 他退回来又点了一次购买，
// 13 秒内两张一模一样的待付款单（SO…001389 / SO…005683），前一张永远没人付。
// 后端的幂等本来就在（同键 24 小时内回放同一单），缺的只是端上给同一个键。
//
// 「同一次结算」按**内容**认，不按页面实例认：退出去再进来是新的页面实例，
// 而对用户来说那还是同一单。内容一变（换了件货、换了地址、换了收法）就是新的一单。
//
// 键的寿命不能长过那张单能付的时间：超时的单已经关了，再回放它等于让他去付一张付不了的单。
// 付成功了也要清掉 —— 否则紧接着再买一份一模一样的，会被回放成那张已付的单。
import { idempotencyKey } from "@/api";

const STORE = "shcr_checkout_idem";
/** 还没拿到单的截止时间时，先按这么久算（后端默认付款窗口 15 分钟，留足余量） */
const PROVISIONAL_MS = 10 * 60 * 1000;
/** 离付款截止还剩不到这么久就不再回放 —— 跳过去也来不及付了 */
const DEADLINE_MARGIN_MS = 60 * 1000;

interface Saved {
  fp: string;
  key: string;
  until: number;
}

function read(): Saved | null {
  try {
    const s = uni.getStorageSync(STORE) as Saved | "";
    return s && typeof s === "object" ? s : null;
  } catch {
    return null;
  }
}

function write(s: Saved | null): void {
  try {
    if (s) uni.setStorageSync(STORE, s);
    else uni.removeStorageSync(STORE);
  } catch {
    // 存不住只是退回「每次一个新键」的旧行为，不该让下单失败
  }
}

/**
 * 这次结算该用的键。**在发请求之前就落盘** ——
 * 请求发出去、响应没回来（断网、被杀后台）时再点一次，拿到的也是同一个键。
 */
export function checkoutKey(fingerprint: string): string {
  const s = read();
  if (s && s.fp === fingerprint && Date.now() < s.until) return s.key;
  const key = idempotencyKey();
  write({ fp: fingerprint, key, until: Date.now() + PROVISIONAL_MS });
  return key;
}

/** 单建出来了：把键的寿命收到这张单的付款截止之前 */
export function checkoutKeyBoundTo(fingerprint: string, payDeadlineAt?: number): void {
  const s = read();
  if (!s || s.fp !== fingerprint || !payDeadlineAt) return;
  // 按单上的截止时间算，不取两者较小值：商家把付款窗口调成 30 分钟时，键不该在第 10 分钟就作废
  write({ ...s, until: payDeadlineAt - DEADLINE_MARGIN_MS });
}

/** 付成功了（或单已关）：下一次结算从新键开始 */
export function clearCheckoutKey(): void {
  write(null);
}
