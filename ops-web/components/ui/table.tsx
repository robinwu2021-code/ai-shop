import * as React from "react";
import { cn } from "@/lib/utils";

export function Table({ className, ...props }: React.HTMLAttributes<HTMLTableElement>) {
  return (
    // min-w：窄视口下**横向滚动**，而不是把每个单元格挤成竖排。
    // 实测 481px 宽时，没有 min-w 的表格会把「邻家便利·锦绣店」拆成 5 行，
    // 整张表高度翻几倍且完全没法扫 —— overflow-x-auto 只有在表比容器宽时才生效，
    // 而 w-full 的表永远不会更宽，所以这条 min-w 是它起作用的前提。
    <div data-surface="table" className="relative w-full overflow-x-auto">
      <table className={cn("w-full min-w-[56rem] caption-bottom txt-body", className)} {...props} />
    </div>
  );
}
export function THead({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  // 表头 = 一条色块（bg-muted），代替下划线。
  // - whitespace-nowrap：表头是短词，折行会让整张表变高；列窄了应该横向滚动（Table 已有 overflow-x-auto）
  // - text-left 用 `:not(.text-end):not(.text-center)` 圈住：**后代选择器会压过列自己身上的
  //   对齐类**（`.thead th` 是 0,2,1，`.text-end` 是 0,1,0）。没有这个 :not 时，
  //   DataTable 的 `numeric` 只对行体生效，表头全部靠左 —— 于是「条数」「实存」这类列
  //   列名与数字各靠一边，扫描时视线要来回跳，而 data-table.tsx 的注释里写的
  //   「表头跟着一起右对齐」一直是**没兑现**的。全站的数值列都中招，不止某一页。
  // - sticky：滚过一屏后列名不能消失。**底色必须不透明**，否则内容会从表头下面透出来
  // - 高度走 --row-h：此前硬写 h-11，与行体的 --row-h 打架，密度切换对列表页近乎无效
  return <thead className={cn("sticky top-0 z-[var(--z-sticky)] bg-muted [&_th]:h-[var(--row-h)] [&_th]:whitespace-nowrap [&_th]:px-3.5 [&_th:not(.text-end):not(.text-center)]:text-left [&_th]:align-middle [&_th]:text-xs [&_th]:font-medium [&_th]:text-muted-foreground", className)} {...props} />;
}
export function TBody({
  className, striped = true, ...props
}: React.HTMLAttributes<HTMLTableSectionElement> & {
  /**
   * 隔行浅色块。**行底色本身带语义时要关掉它**（如平台类目树用底色区分一级/二级）：
   * zebra 是 `tbody tr:nth-child(even)`（特异度 0,2,1），比行上的 `bg-*` 工具类
   * （0,1,0）更强 —— 于是同一种状态的行**奇偶各一个颜色**，看着像随机的。
   * 用 `!important` 压过去也能赢，但那是让两条规则继续打架，只不过换我方赢。
   */
  striped?: boolean;
}) {
  // 无行线，隔行浅色块（zebra）分隔。
  // 垂直内边距故意为 0：行高由 --row-h 决定，`py-3` 会与之打架（实际行高变成 max(两者)），
  // 内容靠 align-middle 居中即可。要更松/更紧，改 [data-density] 而不是改这里。
  // whitespace-nowrap 是**默认**：密集台账里列一窄就逐字换行（"商家"竖成两个字一行），
  // 行高翻几倍且完全没法扫。要换行的列（长文案/地址）显式传 className="whitespace-normal"。
  return <tbody className={cn("[&_td]:h-[var(--row-h)] [&_td]:whitespace-nowrap [&_td]:px-3.5 [&_td]:py-0 [&_td]:align-middle", striped && "[&_tr:nth-child(even)]:bg-muted/45", className)} {...props} />;
}
export function TR({ className, ...props }: React.HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn("transition-colors hover:bg-accent/50", className)} {...props} />;
}
export function TH({ className, ...props }: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className={className} {...props} />;
}
export function TD({ className, ...props }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={className} {...props} />;
}
