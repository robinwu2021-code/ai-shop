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

export interface PageTab {
  key: string;
  label: string;
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
