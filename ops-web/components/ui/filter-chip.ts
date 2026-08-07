"use client";

// 「生效中的筛选」chip 的登记契约。
//
// **为什么用静态字段而不是在 Toolbar 里 import 各个筛选控件**：Toolbar 在 `components/ui/`，
// 而 `ShowArchivedToggle` 在上一层 `components/`。让 ui 反向 import 上层会把依赖方向拧成环。
// 改成控件自己声明「我怎么变成一个 chip」，Toolbar 只认这个字段，不认具体是谁。
//
// 新增筛选控件时**顺手加上 `toChip`**，否则它的选中态不会出现在回显里 ——
// 用户会以为没筛，然后对着少掉的数据找半天。
import * as React from "react";

export interface FilterChip {
  /** chip 前缀，如「状态」。不给就只显示值 */
  name?: string;
  /** 当前值的可读文案 */
  label: string;
  /** 点 × 或「清空筛选」时调用 */
  clear: () => void;
}

/** 参与筛选回显的组件：挂一个静态 `toChip`，无筛选生效时返回 null。 */
export type Chippable<P> = React.FC<P> & { toChip?: (props: P) => FilterChip | null };

/**
 * 从 Toolbar 的 children 里收集所有生效中的 chip。
 *
 * **必须递归**：页面普遍把筛选器包在条件片段里 —— `{tab === "x" && (<>…</>)}`。
 * 只遍历直接子节点的话，`React.Children.forEach` 拿到的是那个 Fragment，
 * 它没有 `toChip`，于是**整页的 chip 静默消失**（实测 iam / groups / marketing
 * 等多 tab 页全都不出 chip，而 orders / merchants 这种直接挂的页面是好的 ——
 * 这种"一半页面好使"的失效最难发现）。
 */
export function chipsFrom(children: React.ReactNode): FilterChip[] {
  const out: FilterChip[] = [];
  const walk = (node: React.ReactNode) => {
    React.Children.forEach(node, (child) => {
      if (!React.isValidElement(child)) return;
      const toChip = (child.type as Chippable<unknown>)?.toChip;
      if (typeof toChip === "function") {
        const chip = toChip(child.props);
        if (chip) out.push(chip);
        return;
      }
      // Fragment / 其它容器：往里找。深度有限（工具条不会嵌很多层），不设上限
      const kids = (child.props as { children?: React.ReactNode })?.children;
      if (kids != null) walk(kids);
    });
  };
  walk(children);
  return out;
}
