"use client";

import { useLocaleStore } from "./stores/locale";
export type PointsLocale = "zh" | "en";
export function pointsText(zh: string, english: string, locale: PointsLocale) {
  return locale === "en" ? english : zh;
}
export function usePointsText() {
  const locale = useLocaleStore((s) => s.locale);
  return { locale, t: (zh: string, english: string) => pointsText(zh, english, locale) };
}
