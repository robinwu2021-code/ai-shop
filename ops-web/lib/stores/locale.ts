"use client";

// 语言偏好，localStorage 持久化。
// 中/EN 两种。加语言时在这里和 lib/i18n 各加一处。
// 与 app/layout.tsx 首帧脚本一致：把 lang/dir 写到 <html>。
import { create } from "zustand";
import { persist } from "zustand/middleware";

export type Locale = "zh" | "en";
export const LOCALE_DIR: Record<Locale, "ltr" | "rtl"> = { zh: "ltr", en: "ltr" };
export const DEFAULT_LOCALE: Locale = "zh";
export const LOCALE_STORAGE_KEY = "shop-ops-locale";

/** 即时把 locale 写到 <html lang dir>（点击切换时生效）。 */
export function applyLocale(locale: Locale) {
  if (typeof document === "undefined") return;
  document.documentElement.lang = locale === "zh" ? "zh-CN" : locale;
  document.documentElement.dir = LOCALE_DIR[locale];
}

interface LocaleState {
  locale: Locale;
  setLocale: (l: Locale) => void;
}

export const useLocaleStore = create<LocaleState>()(
  persist(
    (set) => ({
      locale: DEFAULT_LOCALE,
      setLocale: (l) => {
        applyLocale(l);
        set({ locale: l });
      },
    }),
    { name: LOCALE_STORAGE_KEY },
  ),
);
