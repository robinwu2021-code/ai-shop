// 商城金额（`{currency, minor}`，minor 为整数字符串）的显示与输入。
//
// **全程不经过 number 浮点**：`699.5 * 100` 在 JS 里是 69950.00000000001，
// 而 minor 可以到 19 位，number 在 2^53 之后就开始丢位。所以显示靠字符串切分，
// 输入靠正则拆整数/小数两段再拼，相加用 BigInt —— 都是整数运算。
import type { MallMoney } from "@/lib/types";

const MINOR_RE = /^(0|[1-9][0-9]{0,18})$/;

/** 小数位数。未列出的币种按 2 位（ISO 4217 的绝大多数） */
const EXPONENT: Record<string, number> = { TWD: 2, CNY: 2, USD: 2, HKD: 2, JPY: 0, KRW: 0 };
const SYMBOL: Record<string, string> = { TWD: "NT$", CNY: "¥", USD: "US$", HKD: "HK$" };

export const exponentOf = (currency: string) => EXPONENT[currency] ?? 2;

/** minor 是否是后端接受的整数文本（无前导零、非负、≤19 位） */
export const isMinor = (s: string) => MINOR_RE.test(s);

function group(int: string): string {
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** 「NT$699.00」。非法 minor 原样露出并带问号 —— 不把坏数据渲染成看似正常的金额 */
export function formatMoney(m: MallMoney | null | undefined): string {
  if (!m) return "—";
  const sym = SYMBOL[m.currency];
  const prefix = sym ?? `${m.currency} `;
  if (!isMinor(m.minor)) return `${m.currency} ?${m.minor}`;
  const exp = exponentOf(m.currency);
  if (exp === 0) return `${prefix}${group(m.minor)}`;
  const padded = m.minor.padStart(exp + 1, "0");
  const int = padded.slice(0, -exp);
  const frac = padded.slice(-exp);
  return `${prefix}${group(int)}.${frac}`;
}

/**
 * 把运营敲的「699」「699.5」「1,000」解析成 minor 字符串；不合法返回 null。
 * 小数位超过币种精度**直接拒绝**，不四舍五入 —— 金额被悄悄改掉比报错坏得多。
 */
export function parseMajor(input: string, currency: string): string | null {
  const s = input.trim().replace(/,/g, "");
  const exp = exponentOf(currency);
  const re = exp === 0 ? /^(\d{1,19})$/ : new RegExp(`^(\\d{1,17})(?:\\.(\\d{0,${exp}}))?$`);
  const m = s.match(re);
  if (!m) return null;
  const int = m[1]!;
  const frac = exp === 0 ? "" : (m[2] ?? "").padEnd(exp, "0");
  const raw = (int + frac).replace(/^0+(?=\d)/, "");
  return isMinor(raw) ? raw : null;
}

/** minor → 输入框里的文本（parseMajor 的逆） */
export function toMajorInput(m: MallMoney): string {
  const exp = exponentOf(m.currency);
  if (exp === 0) return m.minor;
  const padded = m.minor.padStart(exp + 1, "0");
  return `${padded.slice(0, -exp)}.${padded.slice(-exp)}`;
}

/** 同币种相加（BigInt，整数运算）；币种不同直接抛 —— 跨币种相加没有意义 */
export function addMoney(a: MallMoney, b: MallMoney): MallMoney {
  if (a.currency !== b.currency) throw new Error(`currency mismatch: ${a.currency} vs ${b.currency}`);
  return { currency: a.currency, minor: (BigInt(a.minor) + BigInt(b.minor)).toString() };
}

/** 同币种比较：-1 / 0 / 1 */
export function compareMinor(a: MallMoney, b: MallMoney): number {
  if (a.currency !== b.currency) throw new Error(`currency mismatch: ${a.currency} vs ${b.currency}`);
  const x = BigInt(a.minor), y = BigInt(b.minor);
  return x === y ? 0 : x < y ? -1 : 1;
}

export const moneyEquals = (a: MallMoney, b: MallMoney) => a.currency === b.currency && a.minor === b.minor;

export const zeroMoney = (currency: string): MallMoney => ({ currency, minor: "0" });
