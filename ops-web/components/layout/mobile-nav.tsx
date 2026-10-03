"use client";

import { useEffect, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import * as Dialog from "@radix-ui/react-dialog";
import { Menu, X } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { Rail } from "./rail";
import { SecondaryNav } from "./secondary-nav";

/**
 * 手机上的导航入口。
 *
 * <p><b>为什么需要它</b>：`Rail`（L1）与 `SecondaryNav`（L2/L3）的根元素都是
 * `hidden … md:flex` —— 窄屏下**有意隐藏**。但隐藏之后没有任何替代入口，
 * 于是手机上打开运营端只能看当前这一页：切不了模块，也切不了同级的那十几个子功能
 * （实测 375px 下 `[data-shell="nav"]` 宽度为 0）。
 * 响应式做了一半，而缺的那一半没有任何症状 —— 页面不破版、不报错，就是走不出去。
 *
 * <p>企业微信群机器人推的入驻意向通知点进来落的正是这一页，运营多半在手机上看。
 *
 * <p><b>复用那两个组件而不是另写一份菜单</b>：菜单项、权限过滤、i18n、「未实现」
 * 标记全在它们里面，另写一份意味着以后加菜单要改两处，而漏改的那一处不会报错。
 * 抽屉里把 `md:hidden` 反转过来（见下面 wrapper 的注释）。
 */
export function MobileNav() {
  const [open, setOpen] = useState(false);
  const { t } = useI18n();
  const pathname = usePathname();
  const sp = useSearchParams();

  /*
   * 选中任意一项后自动收起 —— 菜单项是 <Link>，点了之后路由变而抽屉不会自己关，
   * 结果是他点完还要再关一次，而那一下很容易点回菜单上的别的项。
   * 依赖里放 searchParams：同一 pathname 下换 tab（?tab=stores）也算导航。
   */
  useEffect(() => {
    setOpen(false);
  }, [pathname, sp]);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger
        aria-label={t("nav.openMenu")}
        title={t("nav.openMenu")}
        className="focus-ring -ms-2 rounded-field p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground md:hidden"
      >
        <Menu className="size-5" />
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-[var(--z-drawer)] bg-black/40 animate-in fade-in md:hidden" />
        <Dialog.Content
          aria-label={t("nav.menu")}
          className="fixed inset-y-0 z-[var(--z-drawer)] flex max-w-full bg-card shadow-pop outline-none md:hidden"
          style={{ insetInlineStart: 0 }}
        >
          {/*
            Rail 与 SecondaryNav 自己带 `hidden md:flex`，在这个抽屉里要反过来：
            **窄屏才显示**。用 `[&>aside]:flex` 把它们的 display 强制回来 ——
            改它们自己的类会把桌面布局也一起改掉，而那一侧现在是对的。
          */}
          <div className="flex min-h-0 [&>aside]:flex">
            <Rail />
            <SecondaryNav />
          </div>
          <Dialog.Close
            aria-label={t("common.close")}
            className="focus-ring absolute top-3 rounded-field p-1 text-muted-foreground hover:bg-accent"
            style={{ insetInlineEnd: 12 }}
          >
            <X className="size-4" />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
