"use client";

import { type Locale, useLocaleStore } from "./stores/locale";
import { POINTS_ZH_TW, POINTS_ZH_TW_TEMPLATES } from "./i18n/points-zh-TW";
export type PointsLocale = Locale;
const escapePattern = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const templates = POINTS_ZH_TW_TEMPLATES.map(([source, translated]) => {
  const names: string[] = [];
  const pattern = source.split(/(\{p\d+\})/g).map(part => {
    if (/^\{p\d+\}$/.test(part)) { names.push(part); return "([\\s\\S]*?)"; }
    return escapePattern(part);
  }).join("");
  return { pattern: new RegExp(`^${pattern}$`), names, translated };
});
export function pointsText(zh: string, english: string, locale: PointsLocale) {
  if (locale === "en") return english;
  if (locale !== "zh-TW") return zh;
  if (Object.hasOwn(POINTS_ZH_TW, zh)) return POINTS_ZH_TW[zh];
  for (const { pattern, names, translated } of templates) {
    const match = zh.match(pattern);
    if (match) return translated.replace(/\{p\d+\}/g, key => match[names.indexOf(key) + 1]);
  }
  // Unknown values may be business data; never convert them speculatively.
  return zh;
}
export function usePointsText() {
  const locale = useLocaleStore((s) => s.locale);
  return { locale, t: (zh: string, english: string) => pointsText(zh, english, locale) };
}
