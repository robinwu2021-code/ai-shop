// 繁体呈现（TDD-运营端页面文案繁体）。
//
// 页面正文的源串是简体，繁体不逐条手写，运行时经 opencc（cn → twp，台湾词汇）转换，
// 再叠一层台湾运营用语修正。与独立支付运营端同一个库、同一个版本，两边切到繁体用词一致。
// 只引 cn2t 子包，不把繁→简的字典一起打进来。
import { Converter } from "opencc-js/cn2t";

const convert = Converter({ from: "cn", to: "twp" });

/**
 * opencc 的 twp 词表偏通用语境，运营后台里有几处要按台湾惯用改回来：
 * 「审核」会被换成「稽核」（稽核在台湾专指查帐），邮箱应称「信箱」，账号/账户用「帳」。
 */
const TW_TERMS: ReadonlyArray<readonly [RegExp, string]> = [
  [/稽核/g, "審核"],
  [/郵箱/g, "信箱"],
  [/賬/g, "帳"],
  [/登錄/g, "登入"],
  // twp 把「类型」译成程序术语「型別」，业务界面应为「類型」
  [/型別/g, "類型"],
];

/** 只做台湾用语修正（用于已是繁体的手写译文，如 messages/zh-TW.ts）。 */
export function twTerms(text: string): string {
  return TW_TERMS.reduce((s, [re, to]) => s.replace(re, to), text);
}

const cache = new Map<string, string>();

/** 简体 → 台湾繁体。占位符 `{name}` 与 ASCII 原样保留；同一串只转一次。 */
export function toTraditional(text: string): string {
  let out = cache.get(text);
  if (out === undefined) {
    out = twTerms(convert(text));
    cache.set(text, out);
  }
  return out;
}

const tables = new WeakMap<object, Record<string, string>>();

/**
 * 整页文案表的繁体版：逐条转换，`overrides` 中人工润色过的条目优先。
 * 按源表引用缓存，组件重渲染、来回切换语言都不重复转换整表。
 */
export function traditionalCopy<T extends Record<string, string>>(zh: T, overrides?: Partial<T>): T {
  const hit = tables.get(zh);
  if (hit && !overrides) return hit as T;
  const out = Object.fromEntries(Object.entries(zh).map(([k, v]) => [k, overrides?.[k] ?? toTraditional(v)])) as T;
  if (!overrides) tables.set(zh, out);
  return out;
}
