// 金额格式化。
// 内部一律「最小货币单位」整数流转（分/美分/菲尔），只在展示层转成带符号的字符串。
// 不用 Intl.NumberFormat —— 小程序基础库对 Intl 的支持不稳定，手写才能跨端一致。
//
// 当前货币由 stores/market 设定；这里保留一个模块级 holder，
// 让非组件上下文（mock、格式化函数）也能拿到，避免把 store 依赖倒灌进 shared/。
import { CURRENCIES, DEFAULT_MARKET, MARKETS } from "@shared/utils/constants";
import type { CurrencyCode } from "@shared/types";

const defaultCurrency = (MARKETS.find((m) => m.id === DEFAULT_MARKET)?.currency ??
  "CNY") as CurrencyCode;

let current: CurrencyCode = defaultCurrency;

export function setCurrentCurrency(code: CurrencyCode): void {
  current = code;
}

export function currentCurrency(): CurrencyCode {
  return current;
}

/** 千分位分组 */
function group(intPart: string): string {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/**
 * 最小单位 → 展示字符串。
 * @param minor 最小货币单位整数
 * @param code  不传则用当前市场货币
 */
export function money(minor: number, code: CurrencyCode = current): string {
  const c = CURRENCIES[code];
  const sign = minor < 0 ? "-" : "";
  const abs = Math.abs(Math.round(minor));
  const factor = 10 ** c.minorUnits;
  const intPart = group(String(Math.floor(abs / factor)));
  const frac = c.minorUnits > 0 ? `.${String(abs % factor).padStart(c.minorUnits, "0")}` : "";
  const num = `${sign}${intPart}${frac}`;
  return c.symbolAfter ? `${num} ${c.symbol}` : `${c.symbol}${num}`;
}

/** 展示用「元」数值，不带符号（用于输入框回填） */
export function toMajor(minor: number, code: CurrencyCode = current): string {
  const c = CURRENCIES[code];
  return (minor / 10 ** c.minorUnits).toFixed(c.minorUnits);
}

/** 「元」→ 最小单位（下单提交前用） */
export function toMinor(major: number | string, code: CurrencyCode = current): number {
  const c = CURRENCIES[code];
  return Math.round(Number(major) * 10 ** c.minorUnits);
}
