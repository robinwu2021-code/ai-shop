"use client";

import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

/** 底栏插槽：让抽屉里的子组件把动作按钮放进**这个**抽屉的底栏（见 `DrawerFooter`）。 */
const FooterSlot = React.createContext<{ el: HTMLElement | null; claim: () => () => void } | null>(null);

/**
 * 是否已经身处一个打开的抽屉里。抽屉**不叠层**（TDD-支付中心运营页面 §2.2）：
 * 要在抽屉里编辑，用 `FormDrawer inDrawer` 原地展开，或由 `ActionHost` 替换内容；
 * 要确认 / 填理由，用弹框。开发态嵌套时报错，测试里据此断言；生产不拦。
 */
const InDrawer = React.createContext(false);

// 右侧抽屉（详情/表单）。基于 radix dialog。
export function Drawer({
  open, onOpenChange, title, desc, children, footer, width = "w-[440px]", className, headerClassName, bodyClassName, onEscapeKeyDown, onOpenAutoFocus, onCloseAutoFocus, onInteractOutside, titleRef,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  desc?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  width?: string;
  className?: string;
  headerClassName?: string;
  bodyClassName?: string;
  onEscapeKeyDown?: (event: KeyboardEvent) => void;
  onOpenAutoFocus?: React.ComponentProps<typeof Dialog.Content>["onOpenAutoFocus"];
  onCloseAutoFocus?: React.ComponentProps<typeof Dialog.Content>["onCloseAutoFocus"];
  onInteractOutside?: React.ComponentProps<typeof Dialog.Content>["onInteractOutside"];
  titleRef?: React.Ref<HTMLHeadingElement>;
}) {
  const [footerEl, setFooterEl] = React.useState<HTMLDivElement | null>(null);
  const [claims, setClaims] = React.useState(0);
  const claim = React.useCallback(() => {
    setClaims((n) => n + 1);
    return () => setClaims((n) => n - 1);
  }, []);
  const slot = React.useMemo(() => ({ el: footerEl, claim }), [footerEl, claim]);
  // 子组件占用底栏时，自带的 footer 让位 —— 同一条底栏上不出现两组互不相干的动作
  const ownFooter = claims === 0 ? footer : null;
  const nested = React.useContext(InDrawer);
  if (nested && open && process.env.NODE_ENV !== "production") {
    console.error(`[Drawer] nested drawer "${title}": drawers must not stack; replace the content or use a dialog`);
  }
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
          onEscapeKeyDown={onEscapeKeyDown}
          onOpenAutoFocus={onOpenAutoFocus}
          onCloseAutoFocus={onCloseAutoFocus}
          onInteractOutside={onInteractOutside}
          data-surface="drawer"
          className={cn(
            /*
             * `max-w-full` 不是可选的收尾：抽屉是 `fixed right-0` + 固定宽度，
             * 窄屏下多出来的部分会被顶到屏幕左边**外面**去，而页面并不会横向滚
             * （实测 375px 视口下 left=-65，「通过」按钮只剩右边 15px 可点，
             * 「驳回」却完整可见 —— 手机上只驳得回、通不过）。
             * 运营会在手机上打开这一页：企微群机器人推的入驻意向通知点进来就是它。
             */
            "fixed right-0 top-0 z-[var(--z-drawer)] flex h-screen max-w-full flex-col overflow-hidden rounded-l-sheet bg-card shadow-pop outline-none",
            "animate-in slide-in-from-right",
            width, className,
          )}
        >
          <div className={cn("flex items-start justify-between bg-muted/50 p-5", headerClassName)}>
            <div>
              <Dialog.Title ref={titleRef} tabIndex={titleRef ? -1 : undefined} className="txt-heading outline-none">{title}</Dialog.Title>
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
          <FooterSlot.Provider value={slot}>
            <InDrawer.Provider value={true}>
              <div className={cn("flex-1 overflow-y-auto p-5", bodyClassName)}>{children}</div>
            </InDrawer.Provider>
          </FooterSlot.Provider>
          {/* 底栏常驻挂载（插槽要有落点），没有内容时隐藏 */}
          <div ref={setFooterEl} className={cn("flex justify-end gap-2 bg-muted/40 p-4", !ownFooter && claims === 0 && "hidden")}>
            {ownFooter}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/**
 * 把动作按钮放进**外层抽屉**的底栏。
 *
 * 给「抽屉里某一块自带表单」的场景用（如商家详情的支付服务页签）：
 * 表单状态留在那一块里，按钮却要在底栏 —— 否则只能再弹一层抽屉叠上去，
 * 或者把按钮放进会滚走的正文。占用期间外层 `footer` 让位，卸载即归还。
 * 不在抽屉里时退化为原地渲染（单独挂载、单测时）。
 */
export function DrawerFooter({ children }: { children: React.ReactNode }) {
  const slot = React.useContext(FooterSlot);
  const claim = slot?.claim;
  React.useEffect(() => claim?.(), [claim]);
  if (!slot) return <div className="mt-4 flex justify-end gap-2">{children}</div>;
  return slot.el ? createPortal(children, slot.el) : null;
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
