"use client";

// 页面 tab 与 URL 的同步。
//
// **为什么需要它**：15 个页面都在重复同一段 8 行：
//
//   const qTab = sp.get("tab");
//   const [tab, setTab] = useState(TABS.some(x => x.key === qTab) ? qTab : TABS[0].key);
//   useEffect(() => { if (qTab && TABS.some(...)) { setTab(qTab); setPage(1); } }, [qTab]);
//
// 复制粘贴的代价不只是行数：漏掉 `setPage(1)` 就会出现"从第 3 页切到另一个 tab，
// 还停在第 3 页但那个 tab 只有 1 页"——列表空白，看着像没数据。
import * as React from "react";
import { useSearchParams } from "next/navigation";
import { navTabs, visibleTabKeys } from "@/lib/nav";
import { useI18n } from "@/lib/i18n";
import { useAuth } from "@/lib/auth";
import { useNavTree, useServerMenu } from "@/lib/stores/server-menu";

export interface PageTab {
  key: string;
  label: string;
}

/**
 * 从 nav.ts 取本页 tab 的标签（**页面不再自己写一份 tab 文案**）。
 *
 * 页面只声明有哪些 tab、什么顺序；名字回 nav.ts 拿，切语言由 tNav 负责。
 * 理由与对齐规则见 {@link navTabs}。
 *
 * 菜单里漏登记这个 tab 时：**开发期抛错**，生产回落成 key 本身。
 * 静默回落一个好看的名字等于把「漏登记」藏起来 —— 而漏登记的后果不只是标题难看，
 * 是这个功能在菜单里根本进不去。
 */
export function useNavTabs(path: string, keys: readonly string[]): PageTab[] {
  const { tNav } = useI18n();
  // tab 名同样以库为准：运营在权限配置页改了功能点名字，这里跟着变
  const nav = useNavTree();
  /*
   * **tab 也要判权，且与菜单同一口径**（2026-08-12）。
   *
   * 此前只有菜单判权，tab 条由页面的 TAB_KEYS 写死 —— 于是没有
   * `merchant:merchant:ban` 的角色照样看得到并点得动「违规处置与封禁」，
   * 只靠接口 403 兜底。实测 `/system` 菜单 3 条而 tab 6 条。
   */
  const perms = useAuth((s) => s.perms);
  const serverHrefs = useServerMenu((s) => s.hrefSet);
  const allowed = React.useMemo(
    () => visibleTabKeys(path, keys, perms, serverHrefs, nav),
    [path, keys, perms, serverHrefs, nav],
  );
  return React.useMemo(
    () => navTabs(path, allowed, nav).map(({ key, label }) => {
      if (!label) {
        const msg = `[nav] ${path} 的 tab "${key}" 在 lib/nav.ts 里没有对应叶子 —— 补一条，否则它在菜单里进不去`;
        if (process.env.NODE_ENV !== "production") throw new Error(msg);
        console.error(msg);
        return { key, label: key };
      }
      return { key, label: tNav(label) };
    }),
    // allowed 已经是 useMemo 的产物；tNav 随 locale 变，必须进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, allowed, tNav, nav],
  );
}

/**
 * @param tabs     本页的 tab 列表（第一个是默认）
 * @param onSwitch 切 tab 时的副作用：**至少要把分页重置到第 1 页**，
 *                 通常还要清空搜索词与筛选（不同 tab 的筛选项往往不通用）
 */
export function usePageTab(tabs: readonly PageTab[], onSwitch?: () => void): [string, (k: string) => void] {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const valid = React.useCallback((k: string | null) => !!k && tabs.some((t) => t.key === k), [tabs]);

  const [tab, setTabState] = React.useState(() => (valid(qTab) ? (qTab as string) : tabs[0].key));

  // URL 变化（从别的页面深链进来、浏览器前进后退）时同步
  React.useEffect(() => {
    if (valid(qTab) && qTab !== tab) {
      setTabState(qTab as string);
      onSwitch?.();
    }
    // onSwitch 通常是就地箭头函数，进依赖会每次渲染都跑；这里只认 qTab 的变化
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qTab]);

  /*
   * **当前 tab 从列表里消失了** —— tab 判权之后这是会真实发生的：
   * 管理员收回了某个权限，60 秒后这一页重算，你正待着的那个 tab 没了。
   * 不处理的话 state 还停在那个 key 上：标题空白、内容却照渲染，
   * 看起来像页面坏了。回落到首个可见 tab。
   */
  React.useEffect(() => {
    if (tabs.length && !valid(tab)) {
      setTabState(tabs[0].key);
      onSwitch?.();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabs]);

  const setTab = React.useCallback(
    (k: string) => {
      setTabState(k);
      onSwitch?.();
    },
    // 同上：onSwitch 每次渲染都是新引用，不进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  return [tab, setTab];
}
