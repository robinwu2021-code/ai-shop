"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

// The shared Ops shell currently supports zh/en. Arabic is scoped to the points workspace.
export const usePointsLanguage = create<{ arabic: boolean; setArabic: (value: boolean) => void }>()(
  persist((set) => ({ arabic: false, setArabic: (arabic) => set({ arabic }) }), {
    name: "shop-ops-points-language",
    storage: createJSONStorage(() => typeof window === "undefined" ? { getItem: () => null, setItem: () => {}, removeItem: () => {} } : window.localStorage),
  }),
);
export function pointsAcceptLanguage(path: string, inherited: string) {
  return path.startsWith("/ops/points/") && usePointsLanguage.getState().arabic ? "ar" : inherited;
}
