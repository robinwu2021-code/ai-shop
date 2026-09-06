// 共享组件库的边界。
//
// 这些断言守的是**抽取之后最容易悄悄退回去的几件事**：
// 组件被复制回某个 app、库里冒出 `@/` 把自己绑死在 C 端、
// 约定组件缺一份导致另一端外壳渲染报错。
// 它们都不会让类型检查失败，H5 上也可能看不出来。
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const APPS = ["c-app", "b-app"];
const UI = join(ROOT, "packages/ui/src/components");

/**
 * 标签扫描。**属性值里可能有 `>`**（`v-if="list.length > 1"`），
 * 所以不能用 `[^>]*?` —— 那会在那儿断掉，后面的 class 就抓不到了。
 */
const TAG_RE = /<(\/?)([a-zA-Z][\w:-]*)((?:[^>"']|"[^"]*"|'[^']*')*)(\/?)>/g;

/** 两端所有页面（跨 describe 共用）。 */
function pageFilesAll() {
  return APPS.flatMap((app) =>
    readdirSync(join(ROOT, app, "src/pages"), { recursive: true, encoding: "utf8" })
      .filter((f) => String(f).endsWith(".vue"))
      .map((f) => ({
        app,
        file: String(f),
        src: readFileSync(join(ROOT, app, "src/pages", String(f)), "utf8"),
      })),
  );
}

describe("共享组件库 packages/ui", () => {
  it("两端不再各自持有一份同名组件", () => {
    const shared = readdirSync(UI);
    const dup: string[] = [];
    for (const app of APPS) {
      const dir = join(ROOT, app, "src/components");
      if (!existsSync(dir)) continue;
      for (const f of readdirSync(dir, { recursive: true, encoding: "utf8" })) {
        if (shared.includes(String(f).split("/").pop() ?? "")) dup.push(`${app}/src/components/${f}`);
      }
    }
    expect(dup, `以下组件在组件库里已有一份，复制回 app 会立刻开始漂移：\n${dup.join("\n")}`).toEqual(
      [],
    );
  });

  it("库里不出现 `@/` 别名（那是各 app 自己的 src）", () => {
    const offenders: string[] = [];
    for (const f of readdirSync(join(ROOT, "packages/ui/src"), {
      recursive: true,
      encoding: "utf8",
    })) {
      const p = join(ROOT, "packages/ui/src", String(f));
      if (!/\.(ts|vue)$/.test(p)) continue;
      // 只认**真正的 import 语句**：注释里引用这个写法（说明文字里出现这种写法是正常的）
      // 不该被判为违规 —— 上一版就误伤了它自己的说明文字
      if (/^\s*import[^\n]*from ["']@\//m.test(readFileSync(p, "utf8"))) offenders.push(String(f));
    }
    expect(
      offenders,
      `组件库引用了 app 私有路径，等于绑死在某一端：\n${offenders.join("\n")}`,
    ).toEqual([]);
  });

  it("两端都提供 app-overlay.vue（sh-scaffold 无条件渲染它）", () => {
    // 缺一份的后果：那一端每个页面都报组件未找到。
    // 不能用动态组件代替 —— `<component :is>` 小程序端编译不过（ADR-008）
    for (const app of APPS) {
      expect(
        existsSync(join(ROOT, app, "src/components/app-overlay.vue")),
        `${app} 缺少 src/components/app-overlay.vue`,
      ).toBe(true);
    }
  });

  it("页面里不直接调 uni.pageScrollTo（桌面端打不着）", () => {
    // 桌面 H5 的滚动条属于应用框而非 window，pageScrollTo 会静默无效：
    // 手机上正常、PC 上失灵，属于最难发现的那类差异。统一走 @ai-shop/ui/scroll
    const offenders: string[] = [];
    for (const app of APPS) {
      for (const f of readdirSync(join(ROOT, app, "src/pages"), {
        recursive: true,
        encoding: "utf8",
      })) {
        const p = join(ROOT, app, "src/pages", String(f));
        if (!p.endsWith(".vue")) continue;
        if (/uni\.pageScrollTo/.test(readFileSync(p, "utf8"))) offenders.push(`${app}/${f}`);
      }
    }
    expect(offenders, `改用 scrollToTop()：\n${offenders.join("\n")}`).toEqual([]);
  });
});

describe("两端独立：共用底层，但不共用运行时", () => {
  // 这组断言的来历：曾把两端 H5 合成一个站点（B 端挂 /m/）。同源之后 localStorage 是同一份，
  // 登录态、皮肤、语言、连 mock 的整个「数据库」都串在一起 —— 商家端读到消费者的订单，
  // 而两端的路由路径又完全同名（都有 #/pages/home/index），来回跳还会串页。
  // 现在两端各自独立部署，前缀是第二道保险。
  it("两端的存储命名空间不同", () => {
    const ns = APPS.map((app) => {
      const env = readFileSync(join(ROOT, app, ".env"), "utf8");
      const m = env.match(/^VITE_APP_NS=(\S+)/m);
      expect(m, `${app}/.env 缺少 VITE_APP_NS`).toBeTruthy();
      return m![1];
    });
    expect(new Set(ns).size, `两端的 VITE_APP_NS 撞了：${ns.join(" / ")}`).toBe(2);
  });

  it("存储 key 不写死前缀（必须走 STORAGE / MOCK_DB_KEY）", () => {
    const offenders: string[] = [];
    for (const app of APPS) {
      for (const f of readdirSync(join(ROOT, app, "src"), { recursive: true, encoding: "utf8" })) {
        const p = join(ROOT, app, "src", String(f));
        if (!/\.(ts|vue)$/.test(p)) continue;
        // 写死 "sh_xxx" 会绕过命名空间，两端同域时又串回去
        if (/["'`]sh[cb]?_[a-z_]+["'`]/.test(readFileSync(p, "utf8"))) offenders.push(`${app}/${f}`);
      }
    }
    expect(offenders, `写死了存储 key：\n${offenders.join("\n")}`).toEqual([]);
  });
});

describe("版心宽度：三处必须是同一个数", () => {
  // 版心宽度同时出现在三处：CSS 变量 --sh-app-max、宽屏断点、uni 的 rpx 换算基准。
  // 对不齐的后果不是「差一点」而是一整段宽度里**缩放与版心错位** ——
  // 按 A 缩放却铺 B 宽，字号与留白全都对不上，肉眼只会觉得「有点怪」，很难定位。
  it("版心 = rpxCalcBaseDeviceWidth，断点 = rpxCalcMaxDeviceWidth + 1", () => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    const column = Number(css.match(/--sh-app-max:\s*(\d+)px/)![1]);

    const pages = APPS.map((app) =>
      JSON.parse(readFileSync(join(ROOT, app, "src/pages.json"), "utf8")),
    );
    for (const [i, p] of pages.entries()) {
      // 版心宽度就是「超过手机宽度之后按多少渲染」，两者是同一件事
      expect(p.globalStyle?.rpxCalcBaseDeviceWidth, `${APPS[i]}: 版心与 rpx 基准不一致`).toBe(column);
    }
    const phoneMax = pages[0].globalStyle.rpxCalcMaxDeviceWidth;
    expect(pages[1].globalStyle.rpxCalcMaxDeviceWidth, "两端的手机自适应上限不一致").toBe(phoneMax);
    // 手机段必须真的比版心宽：否则 430 的 Pro Max 会被当成桌面，出现「手机上也有灰边」
    expect(phoneMax, "手机自适应上限必须大于版心宽度").toBeGreaterThan(column);

    for (const src of [css, readFileSync(join(UI, "sh-scaffold.vue"), "utf8")]) {
      for (const m of src.matchAll(/@media \(min-width:\s*(\d+)px\)/g)) {
        expect(
          Number(m[1]),
          `断点 ${m[1]}px 与手机自适应上限 ${phoneMax}px 不匹配（应为 ${phoneMax + 1}）`,
        ).toBe(phoneMax + 1);
      }
    }
  });
});

describe("抽出去的公共件不许再各写一份", () => {
  // 这个仓库已经因为「复制一份更快」漂移过好几次（空态在 27 个页面里 padding 各不相同、
  // 筛选条同时存在 chip 与方块两套实现）。抽完就得有东西守着，否则下一个页面照旧复制。
  const pageFiles = pageFilesAll;

  it("空态走 sh-empty，不再自定义 .empty 样式", () => {
    // 例外：带标题与主按钮的「引导型空态」是页面自己的结构，不是通用空态那一行灰字。
    // 判据不是类名而是**内容**：含 sh-btn 才算引导型
    const offenders = pageFiles()
      .filter(({ src }) => /^\.empty \{/m.test(src) && !/class="sh-btn[^"]*"[^>]*>\s*\{\{/.test(src))
      .map(({ app, file }) => `${app}/${file}`);
    expect(offenders, `改用 <sh-empty>：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("筛选条走 sh-tabs，不再自定义 .tabs__item", () => {
    const offenders = pageFiles()
      .filter(({ src }) => /^\.tabs__item/m.test(src))
      .map(({ app, file }) => `${app}/${file}`);
    expect(offenders, `改用 <sh-tabs>：\n${offenders.join("\n")}`).toEqual([]);
  });
});

/*
 * 小程序上「块与块之间那道缝」的覆盖面。
 *
 * base.css 的 `.sh-scaffold > * + *` 在小程序上用不了（WXSS 不认 `*`，上传直接
 * 被拒），那里改成逐个标签列。**而两端的节点树形状是不同的**：
 *
 *   H5    `<script setup>` 组件没有宿主节点，`<sh-tabs>` 就是它自己的根 view
 *         → `> * + *` 命中，缝有
 *   小程序 宿主节点是真节点（wxml 里就写着 `<sh-tabs>`）
 *         → 名单里没这个标签就一条规则都不命中，缝没有
 *
 * 单看小程序只是「整体挤了一点」，没人会想到去比对另一个产物 ——
 * 2026-09-06 补这条断言时两端共 70 处（c-app 13 / b-app 57）都在这个坑里。
 */
describe("小程序的块间缝：顶层组件也要在名单上", () => {
  const BUILTIN = new Set(["view", "text", "scroll-view", "image", "button", "navigator",
    "swiper", "form", "input", "textarea", "picker", "slot", "block", "template",
    "movable-view", "cover-view", "video", "canvas", "map", "web-view", "checkbox", "radio",
    "switch", "label", "rich-text", "progress", "icon"]);

  /** 浮层：`position: fixed`，给宿主加外边距会把浮层本身推下去。
   *  H5 那边因为没有宿主节点不会发生 —— 列进名单才是真把两端做出差别。 */
  const OVERLAY = new Set(["sh-actionbar", "sh-sheet", "sh-dialog", "sh-savebar", "sh-fab",
    "sh-tabbar", "sh-theme-sheet", "sh-prompt", "sh-confirm", "sh-pick", "app-overlay",
    "biz-cart-fab", "phone-gate", "biz-region-picker", "biz-pickup-sheet",
    "biz-item-picker", "biz-supplier-picker"]);

  /** base.css 里**所有** `#ifdef MP-WEIXIN` 段拼起来 —— 不能只取第一段：
   *  2026-09-06 在块间缝那段之前又插了一段（button::after 重置），
   *  只取第一段的话名单当场变空，而断言看上去还是绿的（幸好有「有东西可扫」那条）。 */
  const mpBlock = (() => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    const out: string[] = [];
    let i = css.indexOf("/* #ifdef MP-WEIXIN */");
    while (i >= 0) {
      const j = css.indexOf("/* #endif */", i);
      out.push(css.slice(i, j < 0 ? undefined : j));
      i = css.indexOf("/* #ifdef MP-WEIXIN */", j < 0 ? css.length : j);
    }
    return out.join("\n");
  })();
  const listed = new Set(
    [...mpBlock.matchAll(/\.sh-scaffold > ([a-z][\w-]*)/g)].map((m) => m[1]!),
  );

  /** `<sh-scaffold>` 的直接子标签。`<template>` 是编译期包裹，不产生节点，穿透它。 */
  function topChildren(src: string): string[] {
    const i = src.indexOf("<sh-scaffold");
    if (i < 0) return [];
    const from = src.indexOf(">", i) + 1;
    const stack: string[] = [];
    const out: string[] = [];
    // ⚠️ `[^>]*?` 会在**属性值里的 `>`** 处把标签截断（`v-if="a.length > 1"`），
    // 于是那个标签后面的 class 抓不到 —— 2026-09-06 量版面时才发现，
    // 六个顶层块因此被误报成「没有身份」。跳过引号内的内容。
    for (const m of src.slice(from).matchAll(TAG_RE)) {
      const [, close, tag, , selfClose] = m as unknown as string[];
      if (close) {
        if (tag === "sh-scaffold" && stack.length === 0) break;
        if (stack[stack.length - 1] === tag) stack.pop();
        continue;
      }
      if (stack.filter((t) => t !== "template").length === 0 && tag !== "template") out.push(tag!);
      if (!selfClose && !["input", "img", "br", "image"].includes(tag!)) stack.push(tag!);
    }
    return out;
  }

  const tops = pageFilesAll().flatMap(({ app, file, src }) =>
    topChildren(src.replace(/<!--[\s\S]*?-->/g, "")).map((tag) => ({ app, file, tag })),
  );

  it("有东西可扫（顶层子节点数为 0 的话，下面那条断言是空转的）", () => {
    expect(tops.length).toBeGreaterThan(200);
    expect(listed.size).toBeGreaterThan(8);
  });

  it("页面顶层的在流组件，都在小程序那份名单里", () => {
    const offenders = tops
      .filter(({ tag }) => !BUILTIN.has(tag) && !OVERLAY.has(tag) && !listed.has(tag))
      .map(({ app, file, tag }) => `${app}/${file}  <${tag}>`);
    expect(
      [...new Set(offenders)],
      "这些组件放在 sh-scaffold 顶层，而 base.css 的 MP 分支没列它们的标签 ——\n" +
        "H5 上有块间缝、小程序上没有，且只有把两个产物摆在一起才看得出来。\n" +
        "补进 base.css 的 `#ifdef MP-WEIXIN` 那两条选择器；是浮层的话补进本文件的 OVERLAY。\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 调用点传给组件的 class，**在小程序上会落两遍**。
 *
 * `mergeVirtualHostAttributes`（两端 manifest.json，2026-09-06 打开）让 class 也
 * 合并到组件根上 —— 那正是我们要的：没有它，`<sh-cover class="hero__emoji">` 的
 * 宽高与圆角在小程序上一条都到不了组件根，13 个封面全是错尺寸 + 直角，而 H5 与
 * 原型上都对。代价是 class **同时**留在宿主节点上：`padding` / `border` 这类
 * 画在盒子上的声明会被宿主与根各吃一遍（外面一圈内边距、两条分隔线）。
 *
 * `margin` 不在名单里：父子相邻外边距会合并，取的是 max，不会翻倍。
 */
describe("传给组件的 class 不许带内边距与描边", () => {
  // `border-radius` 不算：宿主与根各圆一次是同一个视觉，而它正是这个开关要修的东西之一
  const BOXY = /^padding(-|$)|^border(-(width|style|color|top|right|bottom|left|inline|block))?$/;

  function scopedRules(src: string): Map<string, string[]> {
    const out = new Map<string, string[]>();
    if (!src.includes("<style")) return out;
    const css = src.slice(src.indexOf("<style")).replace(/\/\*[\s\S]*?\*\//g, "");
    for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      for (const c of (m[1] ?? "").trim().matchAll(/^\.([\w-]+)$/g)) {
        out.set(c[1]!, [...(out.get(c[1]!) ?? []), m[2] ?? ""]);
      }
    }
    return out;
  }

  const passed = pageFilesAll().flatMap(({ app, file, src }) => {
    const tpl = (src.includes("<style") ? src.slice(0, src.indexOf("<style")) : src)
      .replace(/<!--[\s\S]*?-->/g, "");
    const rules = scopedRules(src);
    const out: { where: string; tag: string; cls: string; props: string[] }[] = [];
    for (const m of tpl.matchAll(/<((?:sh|biz|app)-[\w-]+)([^>]*)>/g)) {
      const cm = /\bclass="([^"]*)"/.exec(m[2] ?? "");
      if (!cm) continue;
      for (const cls of cm[1]!.split(/\s+/).filter(Boolean)) {
        for (const body of rules.get(cls) ?? []) {
          const props = body.split(";").map((d) => d.split(":")[0]!.trim()).filter(Boolean);
          out.push({ where: `${app}/${file}`, tag: m[1]!, cls, props });
        }
      }
    }
    return out;
  });

  it("有东西可扫", () => {
    expect(passed.length).toBeGreaterThan(10);
  });

  it("没有一个传出去的 class 画盒子", () => {
    const offenders = passed
      .filter((p) => p.props.some((x) => BOXY.test(x)))
      .map((p) => `${p.where}  <${p.tag} class="${p.cls}">  ${p.props.filter((x) => BOXY.test(x)).join(", ")}`);
    expect(
      offenders,
      "这些声明在小程序上会被宿主节点与组件根各吃一遍：\n" +
        "要么把它包到外层 <view> 上，要么就用组件自己的档位（多半是后者 —— 覆盖库件的内边距\n" +
        "本来就是两端不一致的来源）。\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 模板里挂了一个**不存在**的库件类名。
 *
 * 这一类不会报错、不会红、类型检查也看不到 —— 页面上只是少了一条样式。
 * 2026-09-06 扫出四处，其中三处是真视觉缺陷：
 *   · `b-app/plan` 的「联系我们」写的是 `sh-btn--ghost`（库里没有这一档），
 *     于是它渲染成**实心主按钮**，就贴在真正的主操作旁边 —— 两个主按钮并排。
 *   · `b-app/apply` 的两个资质输入框写的是 `sh-input`（应为 `field__input`），
 *     一点样式都没有，夹在一列正常输入框中间。
 *   · `c-app/biz-merchant-bar` 的自营标识写的是 `sh-chip--accent`，渲染成灰底，
 *     而同一枚标识在 `biz-goods-card` 上是主色 tint —— 同一个字两种长相。
 * 第四处 `sh-btn--primary`（5 个调用点）是空转：`.sh-btn` 本身就是主按钮。
 *
 * 判据只管 `sh-` / `txt-` / `field-` / `is-` 这四个前缀 —— 那是库件的命名空间，
 * 页面自己的类名不在其中。
 */
describe("挂上去的库件类名必须真的存在", () => {
  const NS = /^(sh|txt|field|is)-/;

  const defined = (() => {
    const out = new Set<string>();
    const add = (src: string) => {
      for (const m of src.matchAll(/\.([\w-]+)/g)) out.add(m[1]!);
    };
    add(readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8"));
    for (const dir of ["packages/ui/src/components", "c-app/src/components", "b-app/src/components"]) {
      const d = join(ROOT, dir);
      if (!existsSync(d)) continue;
      for (const f of readdirSync(d, { recursive: true, encoding: "utf8" })) {
        if (!String(f).endsWith(".vue")) continue;
        const src = readFileSync(join(d, String(f)), "utf8");
        if (src.includes("<style")) add(src.slice(src.indexOf("<style")));
      }
    }
    for (const f of ["packages/ui/src/uno.config.ts", "c-app/uno.config.ts", "b-app/uno.config.ts"]) {
      const p = join(ROOT, f);
      if (!existsSync(p)) continue;
      for (const m of readFileSync(p, "utf8").matchAll(/["'`]((?:sh|txt|field|is)-[\w-]+)["'`]/g))
        out.add(m[1]!);
    }
    return out;
  })();

  /** 模板里出现的类名：静态 `class="…"` 与 `:class="{ 'x': cond }"` 两种写法 */
  function usedClasses(src: string): string[] {
    const tpl = (src.includes("<style") ? src.slice(0, src.indexOf("<style")) : src)
      .replace(/<!--[\s\S]*?-->/g, "");
    const out: string[] = [];
    // `\b` 在 `:class="` 前面也成立（`:` 是非词字符）—— 不排除的话对象写法会被
    // 当成静态类名切一遍，切出 `txt-primary':` 这种带引号的碎片
    for (const m of tpl.matchAll(/(?<![:\w-])class="([^"]*)"/g)) out.push(...m[1]!.split(/\s+/));
    // 对象写法的键：`'sh-chip--primary': cond` / `"is-on": cond`
    for (const m of tpl.matchAll(/:class="\{([^}]*)\}"/g))
      for (const k of m[1]!.matchAll(/['"]([\w-]+)['"]\s*:/g)) out.push(k[1]!);
    return out.filter(Boolean);
  }

  const files = [
    ...pageFilesAll(),
    ...APPS.flatMap((app) => {
      const d = join(ROOT, app, "src/components");
      if (!existsSync(d)) return [];
      return readdirSync(d, { recursive: true, encoding: "utf8" })
        .filter((f) => String(f).endsWith(".vue"))
        .map((f) => ({ app, file: `components/${f}`, src: readFileSync(join(d, String(f)), "utf8") }));
    }),
  ];

  it("有东西可扫，且已知类名不是空集", () => {
    expect(files.length).toBeGreaterThan(90);
    expect(defined.size).toBeGreaterThan(100);
  });

  it("没有挂到不存在的 sh-/txt-/field-/is- 类上", () => {
    const offenders: string[] = [];
    for (const { app, file, src } of files) {
      const own = src.includes("<style")
        ? new Set([...src.slice(src.indexOf("<style")).matchAll(/\.([\w-]+)/g)].map((m) => m[1]!))
        : new Set<string>();
      for (const c of usedClasses(src)) {
        if (!NS.test(c) || defined.has(c) || own.has(c)) continue;
        offenders.push(`${app}/${file}  .${c}`);
      }
    }
    expect(
      [...new Set(offenders)],
      "这些类名一个定义都没有 —— 不报错，只是那一条样式没了：\n" + offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 块与块之间那道缝**只许由 `--sh-gap-block` 说了算**。
 *
 * `sh-scaffold` 已经给了（`> * + *`，默认 20rpx）。页面再在顶层块上写一份
 * `margin-top: 20rpx`，今天看不出区别 —— 外边距会合并，取的是 max。
 * 但那个 token 从此是**装饰品**：改它只会改掉没写死的那几处，一页紧一页松。
 * 2026-09-06 清掉 13 处（两端顶层块自己写的、与 token 同值的那一份）。
 *
 * 只判「这个类在本页**只出现在 scaffold 顶层**」的情况 —— 里层也用到的那些，
 * 20rpx 是它自己的版面，不是这道缝。
 */
describe("块间缝归 --sh-gap-block", () => {
  const GAP = (() => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    const m = /--sh-gap-block:\s*(\d+)rpx/.exec(css);
    return Number(m?.[1] ?? 0);
  })();

  /** scaffold 顶层出现过的类 / 更里层出现过的类 */
  function classDepths(tpl: string): [Set<string>, Set<string>] {
    const top = new Set<string>();
    const deep = new Set<string>();
    const i = tpl.indexOf("<sh-scaffold");
    if (i < 0) return [top, deep];
    const stack: string[] = [];
    for (const m of tpl.slice(tpl.indexOf(">", i) + 1).matchAll(TAG_RE)) {
      const [, close, tag, attrs, selfClose] = m as unknown as string[];
      if (close) {
        if (tag === "sh-scaffold" && stack.length === 0) break;
        if (stack[stack.length - 1] === tag) stack.pop();
        continue;
      }
      const visible = stack.filter((t) => t !== "template").length;
      const cm = /\bclass="([^"]*)"/.exec(attrs ?? "");
      if (cm) for (const c of cm[1]!.split(/\s+/).filter(Boolean))
        (visible === 0 && tag !== "template" ? top : deep).add(c);
      if (!selfClose && !["input", "img", "br", "image"].includes(tag!)) stack.push(tag!);
    }
    return [top, deep];
  }

  it("token 读得到（读不到的话下面那条是空转的）", () => {
    expect(GAP).toBeGreaterThan(0);
  });

  it("顶层块不自己写一份与 token 同值的上边距", () => {
    const offenders: string[] = [];
    for (const { app, file, src } of pageFilesAll()) {
      if (!src.includes("<sh-scaffold") || !src.includes("<style")) continue;
      const cut = src.indexOf("<style");
      const [top, deep] = classDepths(src.slice(0, cut).replace(/<!--[\s\S]*?-->/g, ""));
      const css = src.slice(cut).replace(/\/\*[\s\S]*?\*\//g, "");
      for (const c of top) {
        if (deep.has(c)) continue;
        const m = new RegExp(`\\n\\.${c.replace(/[-]/g, "\\-")}\\s*\\{([^}]*)\\}`).exec(css);
        if (m && new RegExp(`margin-top:\\s*${GAP}rpx`).test(m[1]!))
          offenders.push(`${app}/${file}  .${c}`);
      }
    }
    expect(
      offenders,
      `这道缝 sh-scaffold 已经给了（--sh-gap-block = ${GAP}rpx）。再写一份今天看不出区别\n` +
        "（外边距合并取 max），但那个 token 从此改不动任何东西 —— 删掉页面里这一行：\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 可点元素的手指尺寸。
 *
 * **视觉尺寸与该有的点按面积不是一回事。** 2026-09-06 量了一遍，全站 12 个可点元素
 * 的盒子小于 88rpx（= 44px，Apple HIG 与微信小程序设计指南取的是同一个下限），
 * 最小的 40rpx 只有 20px —— 而它们全在最常点的地方：购物车与商品页的加减号、
 * 列表里的「＋」、上传格子的删除叉。**这类缺陷不会有人报**：点空了人只会再点一次。
 *
 * 判据只看**显式写了宽高**的那些：靠内边距撑开的行、整行可点的列表项本来就够大，
 * 量它们只会报一堆假的。补救办法是库里的 `.sh-hit`（`::after` 各边扩 24rpx = 48rpx），
 * 所以挂了它的按 +48rpx 算。
 */
describe("可点元素的点按面积", () => {
  const MIN = 88;   // rpx
  const HIT = 48;   // .sh-hit 扩出来的总量（各边 24rpx）

  const all = [
    ...pageFilesAll(),
    ...[["lib", "packages/ui/src/components"],
        ...APPS.map((a) => [a, `${a}/src/components`] as const)]
      .flatMap(([app, dir]) => {
        const d = join(ROOT, dir as string);
        if (!existsSync(d)) return [];
        return readdirSync(d, { recursive: true, encoding: "utf8" })
          .filter((f) => String(f).endsWith(".vue"))
          .map((f) => ({ app: app as string, file: String(f), src: readFileSync(join(d, String(f)), "utf8") }));
      }),
  ];

  /** 模板里挂了 @tap 的元素上的类名 → 是否同时挂了 sh-hit */
  function tappable(tpl: string): Map<string, boolean> {
    const out = new Map<string, boolean>();
    for (const m of tpl.matchAll(/<[a-zA-Z][^>]*>/g)) {
      const tag = m[0];
      if (!/@tap|@click/.test(tag)) continue;
      const cm = /(?<![:\w-])class="([^"]*)"/.exec(tag);
      if (!cm) continue;
      const classes = cm[1]!.split(/\s+/).filter(Boolean);
      const hit = classes.includes("sh-hit");
      for (const c of classes) out.set(c, (out.get(c) ?? false) || hit);
    }
    return out;
  }

  const findings = all.flatMap(({ app, file, src }) => {
    if (!src.includes("<style")) return [];
    const cut = src.indexOf("<style");
    const taps = tappable(src.slice(0, cut).replace(/<!--[\s\S]*?-->/g, ""));
    const css = src.slice(cut).replace(/\/\*[\s\S]*?\*\//g, "");
    const out: string[] = [];
    for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const one = /^\.([\w-]+)$/.exec((m[1] ?? "").trim());
      if (!one || !taps.has(one[1]!)) continue;
      const w = /\bwidth:\s*(\d+)rpx/.exec(m[2] ?? "");
      const h = /\bheight:\s*(\d+)rpx/.exec(m[2] ?? "");
      if (!w || !h) continue;
      const grow = taps.get(one[1]!) ? HIT : 0;
      const ew = Number(w[1]) + grow;
      const eh = Number(h[1]) + grow;
      if (ew < MIN || eh < MIN)
        out.push(`${app}/${file}  .${one[1]}  ${w[1]}×${h[1]}rpx${grow ? " (+sh-hit)" : ""} → ${ew}×${eh}rpx`);
    }
    return out;
  });

  it("有东西可扫（扫不到可点元素的话，下面那条是空转的）", () => {
    const anyTap = all.some(({ src }) => /@tap/.test(src));
    expect(anyTap).toBe(true);
    expect(all.length).toBeGreaterThan(100);
  });

  it("没有小于 88rpx（44px）的可点元素", () => {
    expect(
      findings,
      `点按面积不足 ${MIN}rpx（= 44px，Apple HIG 与微信取同一个下限）：\n` +
        "挂 `.sh-hit` 把 ::after 扩出去（元素自己的盒不动，排版不会被顶开），\n" +
        "或者直接把元素做大到 88rpx。\n" +
        findings.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 浮层层级只许用档。
 *
 * 《规范·版面》上那张表只有四层，而 2026-09-06 数下来代码里跑着**九个数**
 *（200 / 150 / 100 / 90 / 60 / 50 / 40 / 20 / 10）。多出来的几个不是新层，
 * 是「当时随手挑了一个看起来够大的数」，其中两处是真缺陷：
 *   · `biz-pickup-sheet` 与 b 端 `biz-region-picker` 取 60，**低于 sh-sheet 的 100**
 *   · `goods-edit` 的类目遮罩取 20，**低于 sh-actionbar 的 40** —— 而那一页正好有贴底通栏
 * 现在一律走 `--sh-z-*`。
 *
 * 例外：**卡片内部**的小层叠（≤ 5）不算浮层 —— 那是「这个角标压在这张图上」，
 * 与「这个面板压在哪一层」不是一件事，硬收进档里只会给档表添两个没人用的名字。
 */
describe("浮层层级只许用 --sh-z-*", () => {
  const LOCAL_MAX = 5;

  const zTokens = (() => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    return new Set([...css.matchAll(/(--sh-z-[\w-]+)\s*:/g)].map((m) => m[1]!));
  })();

  const files = [
    ...pageFilesAll(),
    ...[["lib", "packages/ui/src/components"],
        ...APPS.map((a) => [a, `${a}/src/components`] as const)]
      .flatMap(([app, dir]) => {
        const d = join(ROOT, dir as string);
        if (!existsSync(d)) return [];
        return readdirSync(d, { recursive: true, encoding: "utf8" })
          .filter((f) => String(f).endsWith(".vue"))
          .map((f) => ({ app: app as string, file: String(f), src: readFileSync(join(d, String(f)), "utf8") }));
      }),
  ];

  it("档表读得到（读不到的话下面那条是空转的）", () => {
    expect(zTokens.size).toBeGreaterThan(4);
  });

  it("没有裸写的层级数", () => {
    const offenders: string[] = [];
    for (const { app, file, src } of files) {
      if (!src.includes("<style")) continue;
      const css = src.slice(src.indexOf("<style")).replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of css.matchAll(/z-index:\s*(\d+)/g)) {
        if (Number(m[1]) <= LOCAL_MAX) continue;
        offenders.push(`${app}/${file}  z-index: ${m[1]}`);
      }
    }
    expect(
      offenders,
      "浮层的先后关系是全局的事，不该由各文件各挑一个数决定。\n" +
        `用 base.css 里的档：${[...zTokens].join(" / ")}。\n` +
        `卡片内部的小层叠（≤ ${LOCAL_MAX}）不在此列。\n` +
        offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 动效只许用档。
 *
 * 色、圆角、间距、字阶都有档，**唯独动效没有** —— 2026-09-06 数下来跑着七个时长
 *（0.18 / 0.2 / 0.22 / 0.3 / 0.32 / 0.42 / 0.62s）。与当初圆角出问题是同一个形状：
 * 没有档，下一个人就照着「感觉差不多」再挑一个数，而两个差 0.02s 的过渡没人分得出。
 *
 * 例外只有一处，写在 `app-overlay` 里：加购小球的飞行轨迹。那不是状态过渡 ——
 * 时长由「从手指飞到购物车图标」这段距离定。判据认它是因为它**在名单上**，
 * 不是因为它长得特别。
 */
describe("动效只许用 --sh-t-*", () => {
  /** 明账：不走档的地方，一处一行，写清楚为什么 */
  const ALLOW = new Map<string, string>([
    ["c-app/src/components/app-overlay.vue", "加购小球的飞行轨迹：时长由距离定，不是状态过渡"],
  ]);

  const tTokens = (() => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    return new Set([...css.matchAll(/(--sh-t-[\w-]+)\s*:/g)].map((m) => m[1]!));
  })();

  const files = [
    ...pageFilesAll(),
    ...[["lib", "packages/ui/src/components"],
        ...APPS.map((a) => [a, `${a}/src/components`] as const)]
      .flatMap(([app, dir]) => {
        const d = join(ROOT, dir as string);
        if (!existsSync(d)) return [];
        return readdirSync(d, { recursive: true, encoding: "utf8" })
          .filter((f) => String(f).endsWith(".vue"))
          .map((f) => ({
            app: app as string,
            file: String(f),
            path: `${dir}/${f}`.replace(/\\/g, "/"),
            src: readFileSync(join(d, String(f)), "utf8"),
          }));
      }),
  ].map((x) => ({ ...x, path: (x as { path?: string }).path ?? `${x.app}/src/pages/${x.file}` }));

  it("档表读得到（读不到的话下面那条是空转的）", () => {
    expect(tTokens.size).toBeGreaterThan(2);
  });

  it("没有裸写的过渡时长", () => {
    const offenders: string[] = [];
    for (const { path, src } of files) {
      if (ALLOW.has(path) || !src.includes("<style")) continue;
      const css = src.slice(src.indexOf("<style")).replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of css.matchAll(/(?:transition|animation)[^;{}]*?(\d*\.?\d+)s/g))
        offenders.push(`${path}  ${m[1]}s`);
    }
    expect(
      offenders,
      `动效时长走档：${[...tTokens].join(" / ")}，回弹曲线用 --sh-ease-spring。\n` +
        "确实不该走档的（比如一段飞行轨迹），加进本测试的 ALLOW 并写清楚为什么。\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});

/*
 * 版面：**页面可以排版，不可以自己画容器。**
 *
 * 「白卡」与「提示条」是库件的两样东西（`.sh-card` / `.sh-block` / `.sh-cells` /
 * `.sh-notice`）。页面在 `sh-scaffold` 顶层自己写一个 `background: var(--sh-surface)`
 * 或 `var(--sh-*-tint)` 的块，就是把它们又画了一遍 —— 而画出来的每一份圆角和内边距
 * 都不一样：2026-09-06 量到 14 处，圆角 16/24/32、内边距十几种。
 *
 * 判据只盯这两类底色：
 *   · `--sh-surface` / `--sh-elev` —— 那是「卡」
 *   · `--sh-*-tint`               —— 那是「提示条」或「选中态」
 * **`--sh-faint` 不在内**：它还兼着占位图的底、输入框的底、禁用态，收进来会报一堆假的。
 *
 * 挂了容器类之后再覆盖底色不算违规 —— 那是换皮肤（会员卡就是一张 tint 的卡），
 * 不是重画一个容器。
 */
describe("版面：页面不自己画容器", () => {
  const CONTAINER = new Set(["sh-card", "sh-block", "sh-cells", "sh-notice", "sh-chip",
    "sh-btn", "sh-seg", "sh-scrollx", "sh-searchbox", "sh-empty"]);
  const PAINT = /background(-color)?:\s*var\(--sh-(surface|elev|primary-tint|warning-tint|danger-tint|success-tint)\)/;

  /** `sh-scaffold` 的直接子节点及其 class（`<template>` 透明） */
  function topChildren(tpl: string): { tag: string; cls: string[] }[] {
    const i = tpl.indexOf("<sh-scaffold");
    if (i < 0) return [];
    const stack: string[] = [];
    const out: { tag: string; cls: string[] }[] = [];
    for (const m of tpl.slice(tpl.indexOf(">", i) + 1).matchAll(TAG_RE)) {
      const [, close, tag, attrs, selfClose] = m as unknown as string[];
      if (close) {
        if (tag === "sh-scaffold" && stack.length === 0) break;
        if (stack[stack.length - 1] === tag) stack.pop();
        continue;
      }
      if (stack.filter((t) => t !== "template").length === 0 && tag !== "template") {
        const cm = /(?<![:\w-])class="([^"]*)"/.exec(attrs ?? "");
        out.push({ tag: tag!, cls: cm ? cm[1]!.split(/\s+/).filter(Boolean) : [] });
      }
      if (!selfClose && !["input", "img", "br", "image"].includes(tag!)) stack.push(tag!);
    }
    return out;
  }

  const findings = pageFilesAll().flatMap(({ app, file, src }) => {
    if (!src.includes("<sh-scaffold") || !src.includes("<style")) return [];
    const cut = src.indexOf("<style");
    const tpl = src.slice(0, cut).replace(/<!--[\s\S]*?-->/g, "");
    const css = src.slice(cut).replace(/\/\*[\s\S]*?\*\//g, "");
    const out: string[] = [];
    for (const { tag, cls } of topChildren(tpl)) {
      if (tag.includes("-")) continue;                       // 组件
      if (cls.some((c) => CONTAINER.has(c))) continue;       // 已经有容器身份
      for (const c of cls) {
        const m = new RegExp(`\\n[ \\t]*\\.${c.replace(/-/g, "\\-")}[ \\t]*\\{([^}]*)\\}`).exec(css);
        if (m && PAINT.test(m[1]!)) out.push(`${app}/${file}  .${c}`);
      }
    }
    return out;
  });

  it("有东西可扫", () => {
    expect(pageFilesAll().filter((f) => f.src.includes("<sh-scaffold")).length).toBeGreaterThan(80);
  });

  it("顶层块没有自己画的卡或提示条", () => {
    expect(
      [...new Set(findings)],
      "白卡走 `.sh-card` / `.sh-block` / `.sh-cells`，提示条走 `.sh-notice`（四个语义档）。\n" +
        "确实需要换个底色的，先挂容器类再覆盖 —— 那是皮肤，不是重画一个容器。\n" +
        findings.join("\n"),
    ).toEqual([]);
  });
});
