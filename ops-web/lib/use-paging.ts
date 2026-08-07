"use client";

// 列表分页状态：页码 + 每页条数。
//
// **为什么绑在一起**：换了每页条数还停在第 5 页，很可能落到一个不存在的页 ——
// 表格空白，看着像"筛没了"。`setSize` 内部一定把页码复位到 1，调用方想漏都漏不掉。
import * as React from "react";
import { PAGE_SIZE } from "./constants";

export function usePaging(initialSize: number = PAGE_SIZE) {
  const [page, setPage] = React.useState(1);
  const [size, setSizeState] = React.useState(initialSize);
  const setSize = React.useCallback((n: number) => {
    setSizeState(n);
    setPage(1);
  }, []);
  return { page, setPage, size, setSize };
}
