"use client";

// 服务端下发的菜单（`GET /ops/menu`）。
//
// **它决定两件事**：哪些菜单项可见、哪些是「后端未实现」要灰显。
// 结构（图标、顺序、二级分组、路由）仍来自 `nav.ts` —— 那份是前端路由的真源，
// 而服务端下发的是**授权与实现状态**。两边按 `href` 对齐。
//
// 为什么不干脆整棵树都用服务端的：菜单树同时被路由守卫、面包屑、
// 分区默认落地页读着（`routeLockedPhase` / `breadcrumb` / `sectionDefaultHref`），
// 整体换源是一次大改，而**授权由库驱动**这件事不需要等它。
import { create } from "zustand";
import { api } from "@/lib/api";
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
  load: () => Promise<void>;
  clear: () => void;
}

export const useServerMenu = create<ServerMenuState>()((set) => ({
  byHref: {},
  hrefSet: new Set<string>(),
  loaded: false,
  load: async () => {
    try {
      const fns = await api.menu();
      const byHref: Record<string, MenuPoint> = {};
      for (const f of fns ?? []) {
        for (const p of f.points ?? []) {
          // ACTION 是页面内的按钮级授权，塞进导航会多出几十行看不懂的项
          if (p.pointType === "ACTION" || !p.href) continue;
          byHref[p.href] = p;
        }
      }
      set({ byHref, hrefSet: new Set(Object.keys(byHref)),
            loaded: Object.keys(byHref).length > 0 });
    } catch {
      // 失败不清空已有的：宁可用上一次的结果，也不要突然变成空菜单
      set({ loaded: false });
    }
  },
  clear: () => set({ byHref: {}, hrefSet: new Set<string>(), loaded: false }),
}));

/** 这一项后端有没有实现。未加载时按「有」处理（回落到静态 nav 的判断） */
export function isPointUnimplemented(byHref: Record<string, MenuPoint>, href: string): boolean {
  return byHref[href]?.backendStatus === "NOT_IMPLEMENTED";
}
