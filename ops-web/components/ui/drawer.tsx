"use client";

import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

// 右侧抽屉（详情/表单）。基于 radix dialog。
export function Drawer({
  open, onOpenChange, title, desc, children, footer, width = "w-[440px]",
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  desc?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  width?: string;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        {/*
          ⚠️ `animate-in`/`fade-in`/`slide-in-from-right` 是 globals.css 里补的**纯 CSS 类**
          （没有走 tailwindcss-animate 插件，也没有注册成 Tailwind @utility）。挂 `data-[state=open]:`
          前缀会让 Tailwind 把整个 token 当成"变体+未知工具类"而丢弃 —— 生成不出任何规则，
          实测 computedStyle.animationName 恒为 none（比"没有动画"更隐蔽：class 都在，就是不生效）。
          Content 只在 open 时才挂载（Radix 默认不 forceMount），故不需要靠 data-state 再选择性触发。
        */}
        <Dialog.Overlay className="fixed inset-0 z-[var(--z-drawer)] bg-black/40 animate-in fade-in" />
        <Dialog.Content
          className={cn(
            "fixed right-0 top-0 z-[var(--z-drawer)] flex h-screen flex-col overflow-hidden rounded-l-sheet bg-card shadow-pop outline-none",
            "animate-in slide-in-from-right",
            width,
          )}
        >
          <div className="flex items-start justify-between bg-muted/50 p-5">
            <div>
              <Dialog.Title className="txt-heading">{title}</Dialog.Title>
              {desc && <Dialog.Description className="mt-0.5 txt-body text-muted-foreground">{desc}</Dialog.Description>}
            </div>
            <Dialog.Close
              className={cn(
                "rounded-field p-1 text-muted-foreground transition-colors hover:bg-accent",
                "focus-ring",
              )}
            >
              <X className="size-4" />
            </Dialog.Close>
          </div>
          <div className="flex-1 overflow-y-auto p-5">{children}</div>
          {footer && <div className="flex justify-end gap-2 bg-muted/40 p-4">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/**
 * 抽屉分段：一条小标题 + 上方分隔线。
 *
 * 长抽屉（订单详情有 8 个字段 + 商品 + 兄弟单，售后裁决有 6 个输入）里，
 * 所有字段等距堆着时读者只能一行行扫。分段是在说「下面这几项是一回事」——
 * 尤其是**要填的**和**只是给你看的**必须分开，否则会去改一个改不动的字段。
 *
 * 第一段不用传（`first` 省掉上分隔线），后续段落自带间距。
 */
export function DrawerSection({
  title, desc, first, children, className,
}: {
  title: string;
  desc?: string;
  /** 抽屉里的第一段：不画上分隔线，也不留上间距 */
  first?: boolean;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={cn(first ? "" : "mt-5 border-t border-[var(--border)] pt-4", className)}>
      <div className="mb-2">
        <h3 className="txt-label text-muted-foreground">{title}</h3>
        {desc && <p className="mt-0.5 txt-caption text-muted-foreground">{desc}</p>}
      </div>
      {children}
    </section>
  );
}

/**
 * 详情栅格：两列摆放只读 `Field`。7 个抽屉在重复 `grid grid-cols-2 gap-x-4`。
 * 里面的 `Field` 记得传 `className="mb-3"`（由栅格给行距，不用 Field 自带的 mb-4）。
 */
export function FieldGrid({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("grid grid-cols-2 gap-x-4", className)}>{children}</div>;
}

/**
 * 详情行：标签在上、值在下。抽屉详情与卡片栅格共用这一份（原先散了 3 份定义）。
 *
 * - 默认带 `mb-4`（抽屉里靠自身间距堆叠）；
 * - 放进 grid/flex 由容器给 gap 时传 `className="mb-0"`（cn 走 tailwind-merge，会覆盖掉 mb-4）。
 *
 * 与 FormDrawer 的 FieldRow **不是**同一件事：那个带必填星号、字数计数、错误态与控件，
 * 是表单行；这个是只读展示行。共同点只有外框间距，合并会把表单关注点塞进展示件。
 */
export function Field({
  label, children, className,
}: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("mb-4", className)}>
      <div className="mb-1 txt-label text-muted-foreground">{label}</div>
      <div className="txt-body">{children}</div>
    </div>
  );
}
