"use client";

// 主题色（只换主色，中性色与语义色在 globals.css 里恒定）。
// 持久化 key = "shop-ops-theme"，与 app/layout.tsx 首帧脚本 + globals.css 的 [data-theme] 一致。
//
// 前四套（不含 business）与 C 端 packages/shared/src/design/tokens.ts 的 SKINS **同名同色**
// （fresh/promo/mono 对应 生鲜绿/促销橙/黑白灰），两端截图放一起是同一个产品。
// ⚠️ 色值是复制而非 import：shared 用 rpx 与小程序运行时改色，运营端用 CSS 变量，
//    两边 token 体系不同，只能靠这条注释与 tokens.ts 的值对齐（改一处要改两处）。
import { create } from "zustand";
import { persist } from "zustand/middleware";

// 只留 key 与色值：显示名走 i18n 的 `theme.<key>`（中英各一份），
// 免得这里存一份中文、翻译时再存一份，两处对不上。
export const THEMES = [
  { key: "mono", color: "#0f1218" },
  { key: "business", color: "#1b3b6f" },
  { key: "fresh", color: "#00b578" },
  { key: "promo", color: "#d24600" },
  { key: "blue", color: "#2c69ff" },
] as const;

/**
 * `business` 是**整套**配色（连画布与中性阶一起换），其余四套只换主色。
 * 它是运营端专有的，C 端没有对应皮肤，所以不受"与 shared 同名同色"那条约束。
 * 见 `app/globals.css` 的 `[data-theme="business"]` 块。
 */
export const FULL_SCHEME_THEMES: ThemeKey[] = ["business"];

/**
 * **可以下发给 C 端的皮肤**（矩阵 P-17.1.1）。
 * 排除 business：C 端没有这套皮肤，把它当默认皮肤下发过去，用户那边只会回落到默认值 ——
 * 界面上却显示"已下发商务蓝"，是最难查的一类不一致。所以在类型与校验两处都挡住。
 */
export const C_END_THEMES = THEMES.filter((t) => !FULL_SCHEME_THEMES.includes(t.key));

export type ThemeKey = (typeof THEMES)[number]["key"];

// 运营端默认黑白灰（C 端默认是生鲜绿）：这是两端**刻意的差异** —— 运营端是密集表格，
// 主色会出现在每个链接/激活态/主按钮上，频率远高于手机端，彩色主色会显得跳。
export const DEFAULT_THEME: ThemeKey = "mono";
export const THEME_STORAGE_KEY = "shop-ops-theme";

/** 立即把主题写到 <html data-theme>（供点击时即时生效）。 */
export function applyTheme(key: ThemeKey) {
  if (typeof document !== "undefined") document.documentElement.dataset.theme = key;
}

/**
 * 明暗。写在 `<html class="dark">` 上（globals.css 的 `.dark` 块整套 token 都在那里）。
 *
 * **不跟随系统**：运营端常见的用法是一整天开着这一个页面，系统在傍晚自动切暗色时
 * 界面跟着翻脸会很突兀，何况这里满屏是要对着念的数字。给一个显式开关，记住选择。
 */
export function applyDark(dark: boolean) {
  if (typeof document !== "undefined") document.documentElement.classList.toggle("dark", dark);
}

interface ThemeState {
  themeKey: ThemeKey;
  dark: boolean;
  setTheme: (k: ThemeKey) => void;
  setDark: (d: boolean) => void;
}

export const useTheme = create<ThemeState>()(
  persist(
    (set) => ({
      themeKey: DEFAULT_THEME,
      dark: false,
      setTheme: (k) => {
        applyTheme(k);
        set({ themeKey: k });
      },
      setDark: (d) => {
        applyDark(d);
        set({ dark: d });
      },
    }),
    {
      name: THEME_STORAGE_KEY,
      // 皮肤增删后，老 localStorage 里可能存着已删除的 key —— 不清洗的话选择器
      // 一个都不高亮，用户以为换肤坏了。水合时把无效值落回默认并同步写回 <html>。
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        const valid = THEMES.some((t) => t.key === state.themeKey);
        if (!valid) state.themeKey = DEFAULT_THEME;
        applyTheme(state.themeKey);
        applyDark(state.dark);
      },
    },
  ),
);
