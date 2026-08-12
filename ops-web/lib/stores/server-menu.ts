"use client";

// 服务端下发的菜单（`GET /ops/menu`）。
//
// **它决定三件事**：哪些菜单项可见、哪些是「后端未实现」要灰显，
// 以及**菜单与 tab 的文案/图标/顺序**（2026-08-12 起）。
//
// 结构（有哪些 section、叶子挂在谁下面、权限码）仍来自 `nav.ts`；
// 展示信息以库为准，拿不到才回落本地 —— 取舍见 `nav.ts` 的 `overlayNav()`。
//
// 库里那份本来就是 `gen-perm-seed.mjs` 从 nav.ts 生成的，两边同源；
// 所以「回落」不是降级到另一套文案，只是回落到**生成它的那一份**。
import { useMemo } from "react";
import { create } from "zustand";
import { api } from "@/lib/api";
import { NAV, overlayNav, type NavOverlay, type NavSection } from "@/lib/nav";
import type { MenuPoint } from "@/lib/types";

export interface ServerMenuState {
  /** href → 该菜单项的服务端状态。**未加载完成时为空** */
  byHref: Record<string, MenuPoint>;
  /**
   * 与 {@link byHref} 同源的 href 集合。
   *
   * **单独存一份而不是渲染时现建**：zustand 的 selector 按引用比对，
   * 每次渲染 new Set 会让组件无限重渲染。
   */
  hrefSet: Set<string>;
  /**
   * 是否已拿到服务端菜单。
   *
   * **没拿到时一律回落到静态 nav 的 `can()` 判断** ——
   * 接口抖一下就把所有菜单藏起来，比多显示几项坏得多：
   * 用户看到的是「系统坏了」，而实际上只是一次超时。
   */
  loaded: boolean;
  /**
   * 给 `overlayNav()` 的文案覆盖层：href → { name, group, icon, sort }。
   *
   * **与 {@link byHref} 分开存**：那份是原始 MenuPoint（含 backendStatus 等），
   * 而这份是喂给纯函数的窄接口。合成一份的话，nav.ts 就要认识 MenuPoint 这个
   * 传输结构 —— 一个后端契约变一次、纯函数就得跟着改一次的耦合。
   */
  overlay: NavOverlay;
  load: () => Promise<void>;
  clear: () => void;
}

export const useServerMenu = create<ServerMenuState>()((set) => ({
  byHref: {},
  hrefSet: new Set<string>(),
  overlay: {},
  loaded: false,
  load: async () => {
    try {
      const fns = await api.menu();
      const byHref: Record<string, MenuPoint> = {};
      const overlay: NavOverlay = { sections: {}, leaves: {} };
      for (const f of fns ?? []) {
        // L1：分区自己的名字、图标、顺序也由库驱动
        if (f.href) {
          overlay.sections![f.href] = { name: f.name, icon: f.icon ?? undefined, sort: f.sort };
        }
        for (const p of f.points ?? []) {
          // ACTION 是页面内的按钮级授权，塞进导航会多出几十行看不懂的项
          if (p.pointType === "ACTION" || !p.href) continue;
          byHref[p.href] = p;
          overlay.leaves![p.href] = {
            name: p.name,
            group: p.groupName ?? undefined,
            sort: p.sort,
          };
        }
      }
      set({ byHref, hrefSet: new Set(Object.keys(byHref)), overlay,
            loaded: Object.keys(byHref).length > 0 });
    } catch {
      // 失败不清空已有的：宁可用上一次的结果，也不要突然变成空菜单
      set({ loaded: false });
    }
  },
  clear: () => set({ byHref: {}, hrefSet: new Set<string>(), overlay: {}, loaded: false }),
}));

/** 这一项后端有没有实现。未加载时按「有」处理（回落到静态 nav 的判断） */
export function isPointUnimplemented(byHref: Record<string, MenuPoint>, href: string): boolean {
  return byHref[href]?.backendStatus === "NOT_IMPLEMENTED";
}

/**
 * 当前菜单树 = 静态 nav 叠上服务端文案。组件一律用它，不直接读 `NAV`。
 *
 * `overlayNav` 在 overlay 为空时**返回同一个引用**，所以未登录/接口没回来时
 * 这里的 useMemo 结果是稳定的 `NAV`，不会触发下游重渲染。
 */
export function useNavTree(): NavSection[] {
  const overlay = useServerMenu((s) => s.overlay);
  return useMemo(() => overlayNav(NAV, overlay), [overlay]);
}
