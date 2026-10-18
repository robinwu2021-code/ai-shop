"use client";

import { useLocaleStore } from "./stores/locale";
import { usePointsLanguage } from "./points-language";
import { POINTS_AR } from "./points-ar";
export type PointsLocale = "zh" | "en" | "ar";
export function pointsText(zh: string, english: string, locale: PointsLocale) {
  return locale === "ar" ? POINTS_AR[english] ?? english : locale === "en" ? english : zh;
}
export function usePointsText() {
  const inherited = useLocaleStore((s) => s.locale);
  const arabic = usePointsLanguage((s) => s.arabic);
  const locale: PointsLocale = arabic ? "ar" : inherited;
  return { locale, t: (zh: string, english: string) => pointsText(zh, english, locale) };
}
