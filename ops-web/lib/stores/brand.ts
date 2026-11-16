"use client";

// 加载一次 brand.json（公开、免登录），按当前语言给出品牌。读不到就用构建默认值，不阻塞任何页面。
import { create } from "zustand";
import { BUILD_BRAND, resolveBrand, type Brand } from "@/lib/brand";
import { useLocaleStore } from "@/lib/stores/locale";

interface BrandState {
  file: unknown;
  loaded: boolean;
  load: () => Promise<void>;
}

const BRAND_URL = `${process.env.NEXT_PUBLIC_BASE_PATH ?? ""}/brand.json`;

export const useBrandStore = create<BrandState>()((set, get) => ({
  file: null,
  loaded: false,
  load: async () => {
    if (get().loaded) return;
    set({ loaded: true });
    try {
      const res = await fetch(BRAND_URL, { cache: "no-store" });
      if (res.ok) set({ file: await res.json() });
    } catch {
      // 网络或 JSON 出错：保持构建默认值
    }
  },
}));

export function useBrand(): Brand {
  const file = useBrandStore((s) => s.file);
  const locale = useLocaleStore((s) => s.locale);
  return file ? resolveBrand(file, locale) : BUILD_BRAND;
}
