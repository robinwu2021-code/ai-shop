"use client";

// 导航偏好：Rail 展开态，localStorage 持久化。
// AppShell 在 hydration 后才渲染导航（ready 门），无 SSR 不一致问题。
// 2026-07-30 删 navMode：L3 呈现只剩 panel 一种（miller 三列逐级已下线），
// 只有单个取值的枚举没有存在意义。老 localStorage 里可能残留 navMode，
// 由 version/migrate 显式丢弃 —— persist 默认的浅合并会把它带回 state
// （类型上并不存在的键），虽不报错，却会一直躺在存储里。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { NAV_PREFS_STORAGE_KEY } from "@/lib/nav";

interface NavPrefs {
  railExpanded: boolean;
  toggleRail: () => void;
  /**
   * 收起 L2/L3 面板（176px）。默认 false —— 面板是主要导航手段，
   * 收起它的前提是 ⌘K 命令面板能替代它找功能（见 components/layout/command-palette.tsx）。
   */
  panelCollapsed: boolean;
  togglePanel: () => void;
}

export const useNavPrefs = create<NavPrefs>()(
  persist(
    (set) => ({
      railExpanded: false,
      toggleRail: () => set((s) => ({ railExpanded: !s.railExpanded })),
      panelCollapsed: false,
      togglePanel: () => set((s) => ({ panelCollapsed: !s.panelCollapsed })),
    }),
    {
      name: NAV_PREFS_STORAGE_KEY,
      version: 2,
      // v0（含 navMode）→ v1：只认 railExpanded。v1 → v2：加 panelCollapsed（老 blob 里没有 → false）。
      migrate: (persisted) => {
        const p = persisted as { railExpanded?: unknown; panelCollapsed?: unknown } | null;
        return {
          railExpanded: Boolean(p?.railExpanded),
          panelCollapsed: Boolean(p?.panelCollapsed),
        };
      },
      // 只写回这两个偏好：光靠 migrate 不够 —— 实测老 blob 里的 navMode 仍会
      // 经默认浅合并回到 state 并被下一次写盘带上，partialize 才真正把它清掉。
      partialize: (s) => ({ railExpanded: s.railExpanded, panelCollapsed: s.panelCollapsed }) as NavPrefs,
    },
  ),
);
