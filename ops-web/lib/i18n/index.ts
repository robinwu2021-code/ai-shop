"use client";

// 轻量 i18n。静态导出 SPA，纯 client 端 catalog，不走路由。
//
// **中 / EN 两种界面语言**，顶栏 LangSwitcher 切换，localStorage 持久化。
// 注意：界面语言与**商品多语言文案**（矩阵 P-3.2.5 / P-17.1.2 的 zh/en/ar 字段）
// 是两件事 —— 后者是运营端要管理的数据，不随这里的界面语言变。
// 再加语言（如 ar）要动三处：messages/xx.ts（key 与 zh 齐平，i18n.test.ts 会校验）、
// 本文件的 LOCALES/DIR/LOCALE_TAG、lib/stores/locale.ts 的 Locale 联合类型。
// LangSwitcher 在 LOCALES.length < 2 时自动隐藏，无需改组件。
import { zh, type Messages } from "./messages/zh";
import { en } from "./messages/en";
import { tNav } from "./nav-labels";
import { useLocaleStore, type Locale } from "@/lib/stores/locale";

export type { Locale } from "@/lib/stores/locale";
export { tNav } from "./nav-labels";
export const LOCALES = ["zh", "en"] as const;
export const DIR: Record<Locale, "ltr" | "rtl"> = { zh: "ltr", en: "ltr" };
export const LOCALE_TAG: Record<Locale, string> = { zh: "zh-CN", en: "en-US" };

const CATALOG: Record<Locale, Messages> = { zh, en };

function lookup(obj: unknown, path: string): string | undefined {
  const v = path.split(".").reduce<unknown>((o, k) => (o == null ? undefined : (o as Record<string, unknown>)[k]), obj);
  return typeof v === "string" ? v : undefined;
}

function interpolate(raw: string, params?: Record<string, string | number>): string {
  if (!params) return raw;
  return raw.replace(/\{(\w+)\}/g, (_, k) => (params[k] != null ? String(params[k]) : `{${k}}`));
}

/** 纯函数版翻译（供非 React 处/测试用）。缺失回退 zh，再回退 key 本身。 */
export function translate(locale: Locale, key: string, params?: Record<string, string | number>): string {
  const raw = lookup(CATALOG[locale], key) ?? lookup(CATALOG.zh, key) ?? key;
  return interpolate(raw, params);
}

export type TFn = (key: string, params?: Record<string, string | number>) => string;

export interface I18n {
  locale: Locale;
  dir: "ltr" | "rtl";
  localeTag: string;
  t: TFn;
  tNav: (label: string) => string;
}

/** React hook：订阅当前 locale，返回 t/tNav/dir/localeTag。 */
export function useI18n(): I18n {
  const locale = useLocaleStore((s) => s.locale);
  return {
    locale,
    dir: DIR[locale],
    localeTag: LOCALE_TAG[locale],
    t: (key, params) => translate(locale, key, params),
    tNav: (label) => tNav(label, locale),
  };
}

/** 便捷：仅要 t。 */
export function useT(): TFn {
  return useI18n().t;
}
