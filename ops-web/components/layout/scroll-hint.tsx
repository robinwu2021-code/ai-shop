"use client";

// 滚动溢出提示：容器上下各一条渐隐，只在那个方向真的还有内容时出现。
//
// **为什么需要**：Rail 与 L2 面板都是 `overflow-y-auto`，一直都能滚 ——
// 但 macOS 的覆盖式滚动条静止时不渲染，于是「下面还有东西」这件事**没有任何提示**。
// 实测 620px 高的窗口下，21 个 L1 里有 8 个落在折线以下；而 Rail 是一条 56px 宽、
// 只有图标的窄条，既没有半截露出来的文字、也没有滚动条，看上去就是「一共这么多」。
// 掉在下面的是风控 / 员工与权限 / 系统配置这几个平台管理域。
//
// 判据是**实测的滚动状态**，不是「元素多了就显示」：内容没超出时一条都不出，
// 免得给一个永远滚不动的假暗示。

import * as React from "react";
import { cn } from "@/lib/utils";

/** 监听一个滚动容器，返回上下两端是否还有内容。 */
export function useScrollHint(ref: React.RefObject<HTMLElement | null>) {
  const [hint, setHint] = React.useState({ top: false, bottom: false });

  React.useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const read = () => {
      // 1px 容差：缩放与小数高度下 scrollHeight 常比 clientHeight 多出零点几
      const bottom = el.scrollHeight - el.clientHeight - el.scrollTop > 1;
      const top = el.scrollTop > 1;
      setHint((p) => (p.top === top && p.bottom === bottom ? p : { top, bottom }));
    };
    read();
    el.addEventListener("scroll", read, { passive: true });

    /*
     * ⚠️ 光有 scroll 与 `ResizeObserver(el)` 是不够的，第一版就是这么哑掉的：
     * 挂载那一刻导航项还没渲染（`visibleSections` 要等 auth / 服务端菜单到手），
     * 容器里空空如也 → 首次 read 得出「没有溢出」；等项目补上来时，容器**自身尺寸没变**
     * （它是 flex 撑满的），RO 不触发，于是提示永远是灭的 —— 而且灭得很合理，
     * 没有任何迹象说明它其实从没量到过内容。
     *
     * 所以：容器尺寸变化用 RO，**内容变化用 MO**（换角色少几个域、收起 Rail、切语言
     * 都会改子节点），两者缺一不可。
     */
    const ro = new ResizeObserver(read);
    ro.observe(el);
    const mo = new MutationObserver(read);
    mo.observe(el, { childList: true, subtree: true });
    return () => { el.removeEventListener("scroll", read); ro.disconnect(); mo.disconnect(); };
  }, [ref]);

  return hint;
}

/**
 * 渐隐条。挂在**滚动容器的定位父级**上（容器自己在滚，遮罩跟着滚就没意义了）。
 * 底色取 `--surface` —— 导航纸的颜色，换肤与明暗都跟着走。
 */
export function ScrollHint({ side, show }: { side: "top" | "bottom"; show: boolean }) {
  return (
    <div
      aria-hidden
      // 把状态挂到 DOM 上：opacity 是 0/1 的动画值，光看它分不清
      // 「算出来是 false」还是「算出来是 true 但样式没生效」——
      // 第一版排查时就卡在这个区别上
      data-hint={show ? "on" : "off"}
      className={cn(
        "pointer-events-none absolute inset-x-0 h-6",
        side === "top" ? "top-0" : "bottom-0",
      )}
      /*
       * 透明度与过渡走**行内 style**，不走 `opacity-0` / `opacity-100`。
       *
       * 不是偏好问题：Tailwind 4 靠扫源文件里的字符串来决定生成哪些类，而本文件是新加的 ——
       * 长时间跑着的 dev server 没重扫它，那两个类**一条都没生成**。
       * 症状极具迷惑性：DOM 上 class 明明写着 `opacity-100`，computed 却是 0，
       * 而样式表里搜不到任何一条设 opacity 的规则。
       * 一个由 JS 算出来的值本来也没有理由绕一圈去要一个类名。
       */
      style={{
        opacity: show ? 1 : 0,
        transition: "opacity var(--dur) var(--ease)",
        background: `linear-gradient(to ${side === "top" ? "bottom" : "top"},
          var(--surface), color-mix(in srgb, var(--surface) 0%, transparent))`,
      }}
    />
  );
}
