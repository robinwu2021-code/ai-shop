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
  it("app 里没有复制一份库里已有的组件", () => {
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

  /*
   * **两端不许有同名但不同物的件。**
   *
   * 上面那条原本叫「两端不再各自持有一份同名组件」，而它查的只是
   * 「app 里有没有复制一份**库里已有**的件」—— **名字比判据大**。
   * 于是 2026-09-08 量组件清单时才发现：两端各有一个 `biz-region-picker`，
   * 库里没有，闸门一声不吭。而那两个做的是完全不同的事 ——
   * c 端是收货地址的省/市/区三级（69 行逻辑），
   * b 端是经营范围的市›区›街道›小区/村 四级下钻 + 搜索 + 提报（722 行逻辑）。
   *
   * 危害不在「重复」（它们不重复），在**名字**：谁 grep、谁读评审、谁看组件清单
   * 都会把它们当成一个。清单表里 `biz-region-picker` 出现两次、行数差十倍，
   * 混淆当场就发生了。c 端那个已改名为 `biz-address-region`。
   */
  it("两端没有同名的业务件 —— 同名就该是同一个东西", () => {
    const byName = new Map<string, string[]>();
    for (const app of APPS) {
      const dir = join(ROOT, app, "src/components");
      if (!existsSync(dir)) continue;
      for (const f of readdirSync(dir, { recursive: true, encoding: "utf8" })) {
        const base = String(f).split("/").pop() ?? "";
        if (!base.endsWith(".vue")) continue;
        (byName.get(base) ?? byName.set(base, []).get(base)!).push(app);
      }
    }
    /*
     * `app-overlay.vue` 是**有意**两端同名的：`sh-scaffold` 无条件渲染它，
     * 而动态组件（`<component :is>`）小程序端不支持 —— 同名是唯一跨四端成立的写法。
     * 上面「两端都提供 app-overlay.vue」那条闸门**要求**它存在，
     * 所以这里不豁免的话，两条断言会互相打架。
     */
    const BY_DESIGN = new Set(["app-overlay.vue"]);
    const clash = [...byName].filter(([n, apps]) => apps.length > 1 && !BY_DESIGN.has(n))
      .map(([n, apps]) => `${n}  —— ${apps.join(" / ")}`);
    expect(
      clash,
      "两端各有一个同名件。如果它们是同一个东西，抽进 packages/ui；\n" +
        "如果不是（多数情况），改名说清各自挑的是什么 —— 同名会让读的人把两件事当成一件：\n" +
        clash.join("\n"),
    ).toEqual([]);
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
    // ⚠️ **改名要同时改这里。** 2026-09-08 把 c 端的 `biz-region-picker` 改成
    //    `biz-address-region` 之后这条闸门当场变红 —— 而它报的是「这个件不在
    //    小程序块间缝名单上」，与「改名」毫无关系。名单里的字符串是改名的暗礁。
    "biz-cart-fab", "phone-gate", "biz-region-picker", "biz-address-region", "biz-pickup-sheet",
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

/*
 * 《规范·版面》里的表**必须真的说了话**。
 *
 * 这一条不是形式主义。2026-09-06 读那份文档时，「行与列表」的「什么时候用」
 * 一整列是 `—`，「浮层层级」的 z-index 一整列也是 `—` —— 而文档照样生成、
 * 照样有标题有表头有分隔线，`check-generated-docs` 也照样绿（它只比「重跑一遍
 * 产物变不变」，不看产物里有没有内容）。
 *
 * 两处的成因不同，但症状是同一个：
 *   · z-index：组件改用 `var(--sh-z-*)` 之后，生成器还在找字面量 `\d+`，找不到就填 `—`
 *   · 什么时候用：`BLOCK_NOTES` 里没登记，缺省就是 `—`
 *
 * 也就是说**生成器的每一个「取不到就填占位符」都是一个静默丢失点**。
 * 这里把占位符本身变成失败条件：宁可让生成器报错，也不要一份看着完整的空表。
 */
describe("《规范·版面》不许有空格子", () => {
  const doc = readFileSync(join(ROOT, "docs/technical/design/规范-版面.md"), "utf8")
    // 组件表同理：「什么时候用」那一列此前根本不存在，34 个件一句说明都没有 ——
    // 而三个底部弹层能并存，一半原因就是文档从没说过该挑哪个
    + "\n" + readFileSync(join(ROOT, "docs/technical/design/规范-组件.md"), "utf8")
      .split("## 积木")[0];

  it("文档在，且有那几张表", () => {
    for (const h of ["## 容器：四个，页面不自己画", "## 行与列表", "## 浮层层级", "## 组件（"]) {
      expect(doc, `《规范·版面》缺这一节：${h}`).toContain(h);
    }
  });

  it("每张表的第二列都说了话 —— 那一列是「这一行为什么存在」", () => {
    /*
     * **只看第二列**，不是所有列。四张表的第二列分别是
     * 「什么时候用」×3 与「z-index」—— 它们空了这一行就没有信息量。
     *
     * 其余列的 `—` 多半是**真话**，不是占位：`sh-confirm` / `sh-pick` /
     * `sh-prompt` / `app-overlay` 确实**没有 props**（壳由全局 store 驱动），
     * 「别拿它当」那一列也常常空 —— `.sh-seg--on` 没有需要提防的近邻。
     * 第一版把这些一起报了，四条假的立刻压过一条真的，那就又是一个会被加豁免的闸门。
     */
    /*
     * **按表头判，不按位置判。** 第一版写的是「每张表的第二列」——
     * 2026-09-08 加「件的字号与间距足迹」那张表时它当场变红，
     * 而那里的 `—` 是**真话**：`sh-icon` 就是不含文字、不占字阶。
     * 该拦的是「取不到值填的占位符」，判据是**这一列在问什么**。
     */
    const WHY = new Set(["什么时候用", "z-index", "说的是什么"]);
    const bad: string[] = [];
    let col = -1;
    for (const line of doc.split("\n")) {
      if (line.startsWith("| ") && !line.startsWith("| `") && line.includes("|")) {
        const hs = line.split("|").slice(1, -1).map((c) => c.trim());
        col = hs.findIndex((h) => WHY.has(h));                // 表头行：记住「为什么」在第几列
        continue;
      }
      if (!line.startsWith("| `") || col < 0) continue;       // 数据行；这张表没有「为什么」列就跳过
      const cells = line.split("|").slice(1, -1).map((c) => c.trim());
      const why = cells[col];
      if (why === "—" || why === "") bad.push(line.trim());
    }
    expect(
      [...new Set(bad)],
      "这些行的第二列是生成器没取到值填的占位符 —— 修生成器（BLOCK_NOTES / 首行注释 / z-index 解析），别手改文档：\n" +
        [...new Set(bad)].join("\n"),
    ).toEqual([]);
  });
});

/*
 * **库件之间不许有同一个形状。**
 *
 * 前两轮理的都是「页面在各写各的」，而 2026-09-06 补扫了 `packages/ui` 本身 ——
 * 库自己也在。三个底部弹层（`sh-sheet` / `sh-prompt` / `sh-theme-sheet`）
 * 各画了一份面板：`__mask` 逐字节相同，面板是同一套几何（44rpx 上圆角 +
 * `24/36/48` 内边距 + surface 底），抓手条三份 `72×8rpx` 的胶囊
 * ——**而抓手条的下外边距一份是 32、两份是 28**，同一道横条三个数。
 * 另有三个件重画 `.sh-center`、一个件重画 `.sh-row`。
 *
 * 收成 `.sh-mask` / `.sh-panel` / `.sh-grip` 三个积木之后归零，这条守着不回去。
 *
 * <b>扫描面就是结论的边界</b>：这条**只扫 `packages/ui/src/components`**。
 * 页面那一层的同类扫描会报出一批「值一样但不是同一件事」的假阳性
 *（弱色底 + 24rpx 圆角 + 墨色字，同时命中文本域、搜索框、工具条三件事），
 * 拿它当闸门只会训练人去加豁免。页面层归 `check-handrolled-ui.mjs`。
 */
describe("库件之间不许有同一个形状", () => {
  /** 决定「长相」的属性。transition / opacity 之类不算形状，会把不同的东西凑一堆 */
  const SHAPE = new Set(["display", "flex-direction", "align-items", "justify-content", "gap",
    "padding", "background", "background-color", "border", "border-radius", "color", "font-size",
    "font-weight", "width", "height", "min-height", "box-shadow", "text-align",
    "position", "inset", "left", "right", "bottom", "top"]);

  function shapeOf(body: string): string {
    return body.split(";")
      .map((l) => l.split(/:(.*)/s))
      .filter(([p]) => SHAPE.has((p ?? "").trim()))
      .map(([p, v]) => `${p!.trim()}:${(v ?? "").trim().replace(/\s+/g, " ")}`)
      .sort().join(" · ");
  }

  const files = readdirSync(UI).filter((f) => f.endsWith(".vue"));
  const byShape = new Map<string, { file: string; sel: string }[]>();
  for (const f of files) {
    const src = readFileSync(join(UI, f), "utf8");
    const css = [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
      .map((m) => m[1]!).join("\n").replace(/\/\*[\s\S]*?\*\//g, "");
    for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const sel = m[1]!.trim().replace(/\s+/g, " ");
      if (sel.startsWith("@") || sel.includes(",")) continue;
      const shape = shapeOf(m[2]!);
      // 少于三条声明的形状太单薄，凑到一起不说明问题
      if (shape.split(" · ").length < 3) continue;
      (byShape.get(shape) ?? byShape.set(shape, []).get(shape)!).push({ file: f, sel });
    }
  }

  it("扫到了件（否则下面全是空转）", () => {
    expect(files.length).toBeGreaterThan(25);
    expect(byShape.size).toBeGreaterThan(20);
  });

  it("没有两个件画着同一个形状", () => {
    const dupes = [...byShape.entries()]
      .filter(([, v]) => new Set(v.map((x) => x.file)).size >= 2)
      .map(([shape, v]) => `${v.map((x) => `${x.file} ${x.sel}`).join("  ／  ")}\n      ${shape}`);
    expect(
      dupes,
      "两个以上的件画着同一个形状 —— 抽成 base.css 的积木（纯 CSS 无行为），\n" +
        "别让后一个去复用前一个的组件（那样要为它用不上的部分加 prop）：\n" +
        dupes.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **件不许重画 base.css 里已经有的积木。**
 *
 * 上一条只报「两个件画着同一个形状」—— 而**一个**件重画一个已有积木时它是哑的
 *（只有一份，够不上门槛）。这一半才是常态：库里已经有 `.sh-center`，
 * 新写一个件的人不知道，于是又敲一遍那三行。2026-09-06 实测：
 * `sh-cover` / `sh-icon-btn` / `sh-dialog` 三个件各重画一遍 `.sh-center`，
 * `sh-kv` 重画 `.sh-row`。
 *
 * 判据是**逐值命中**：件的某条规则要**覆盖住整个积木** —— 积木的每一条声明，
 * 属性和值都在这条规则里。只对属性名相同不算，那会把所有 flex 容器凑成一堆。
 *
 * 唯一的松口：**没盖住的那几条全是字体属性**（`font-*` / `line-height`）时也算命中。
 * 那是这个仓库的常见写法 —— 调用点挂一个 `.txt-caption` 出字号，规则里只留剩下的
 * （`.pr__hint` / `.sheet__hint` / `.st__l` 三处重画 `.sh-hint` 就是这个形状）。
 *
 * **判据换过一次，值得记**：第一版是「命中 ≥3 条且覆盖积木的多数」。
 * 它把 `sh-sheet` 的 `.sheet__panel--tall`（定高滚动面板）报成重画 `.sh-cells`
 * （密排清单）—— 两者只是恰好共有 `display:flex` / `flex-direction:column` /
 * `overflow:hidden`。更糟的是**这个阈值挂在被测对象的声明条数上**：
 * 我把 `.sh-cells` 的 `background` 挪走之后它从 6 条变 5 条，
 * 原本不过半的 3/6 变成过半的 3/5，同一条假阳性自己回来了。
 * 会随被测对象漂移的阈值，不是判据。
 *
 * 命中之后有两条出路，选哪条看**值一不一样**：
 *   · 一样 → 挂上那个积木的类名，把重复的声明删掉
 *   · 不一样（比如圆角故意小一档）→ 说明它不是那个积木，但要在注释里写清为什么，
 *     并把差异做大到一眼能看出来 —— 差 4rpx 的两个圆角只会让下一个人再画一遍
 */
describe("件不许重画 base.css 的积木", () => {
  const base = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "");

  function declsOf(body: string): Map<string, string> {
    const out = new Map<string, string>();
    for (const line of body.split(";")) {
      const i = line.indexOf(":");
      if (i < 0) continue;
      out.set(line.slice(0, i).trim(), line.slice(i + 1).trim().replace(/\s+/g, " "));
    }
    return out;
  }

  /** base.css 里声明数 ≥3 的积木 —— 太单薄的（只有一条 color）不做模板 */
  const blocks = new Map<string, Map<string, string>>();
  for (const m of base.matchAll(/\n(\.[a-z0-9-]+)\s*\{([^}]*)\}/g)) {
    const d = declsOf(m[2]!);
    if (d.size >= 3) blocks.set(m[1]!, d);
  }

  /**
   * 规则 `sel` 对应的元素，是不是**已经挂着**积木 `blockCls`。
   *
   * 判据是「这个类名的每一处调用点都同时挂着它」—— 有一处没挂就还得报，
   * 那一处正是漏网的。类名只出现在动态 `:class` 里时抓不到调用点，
   * 这时退回文件级判断（宁可漏报也不误报：动态类名的组合是运行期才定的）。
   */
  function carriesBlock(src: string, sel: string, blockCls: string): boolean {
    const own = sel.split(/[\s>+~]/).pop()!.split(".").filter(Boolean)[0];
    if (!own) return false;
    const sites = [...src.matchAll(/class="([^"]*)"/g)]
      .map((m) => m[1]!.split(/\s+/).filter(Boolean))
      .filter((t) => t.includes(own));
    if (!sites.length) return src.includes(blockCls);
    return sites.every((t) => t.includes(blockCls));
  }

  it("读到了积木模板（否则下面全是空转）", () => {
    expect(blocks.size).toBeGreaterThan(10);
    expect([...blocks.keys()]).toContain(".sh-center");
  });

  it("没有件在重画积木", () => {
    const hits: string[] = [];
    for (const f of readdirSync(UI).filter((x) => x.endsWith(".vue"))) {
      const src = readFileSync(join(UI, f), "utf8");
      const css = [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
        .map((m) => m[1]!).join("\n").replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const sel = m[1]!.trim().replace(/\s+/g, " ");
        if (sel.startsWith("@") || sel.includes(",")) continue;
        const own = declsOf(m[2]!);
        for (const [name, tpl] of blocks) {
          // 已经挂着这个积木的元素不算 —— 那正是我们要的写法。
          // ⚠️ **要按元素判，不能按文件判**：文件里任何一处提到 `sh-center`，
          //    就把这一整个文件的规则都豁免掉，等于第二个重画点永远看不见。
          if (carriesBlock(src, sel, name.slice(1))) continue;
          const missing = [...tpl.keys()].filter((k) => own.get(k) !== tpl.get(k));
          const same = tpl.size - missing.length;
          if (same < 3) continue;
          // 盖住整个积木，或者没盖住的全是字号那一族（调用点用 .txt-* 出的）
          const TEXTY = /^(font-|line-height$|letter-spacing$)/;
          if (missing.length && !missing.every((k) => TEXTY.test(k))) continue;
          hits.push(`${f}  ${sel}  ≈ ${name}（逐值命中 ${same}/${tpl.size}）`);
        }
      }
    }
    expect(
      [...new Set(hits)],
      "这些规则与 base.css 的积木逐值相同 —— 挂类名，别再敲一遍：\n" + [...new Set(hits)].join("\n"),
    ).toEqual([]);
  });
});

/*
 * **每个库件都要有一句「什么时候用」，且要归进一个真的分组。**
 *
 * 上面那条守的是**文档里**没有空格子，这条守的是**源头**——
 * `BLOCK_NOTES` / `COMP_NOTES` 没登记时，生成器只能填 `—`，
 * 而新加的件默认就是没登记的那一种。两条一起才闭合：
 * 「文档不许空」拦得住已经渲染出来的表，拦不住一个还没被任何表渲染的新件。
 *
 * 分组也判：没登记的件会落进一个叫「其它」的抽屉，而《规范·组件》按组渲染 ——
 * 2026-09-06 那个抽屉里躺着 14 个件，包括 `.sh-mt-*` 这种有 137 个调用点的。
 * 「其它」不是分类，是「还没分类」。
 */
describe("库件登记齐全", () => {
  const lib = JSON.parse(readFileSync(join(ROOT, "docs/technical/design/ui-lib.json"), "utf8"));

  it("读到了清单（否则下面全是空转）", () => {
    expect(lib.blocks.length).toBeGreaterThan(50);
    expect(lib.components.length).toBeGreaterThan(25);
  });

  it("每个积木都有「什么时候用」", () => {
    const bad = lib.blocks.filter((b: { when?: string }) => !b.when || b.when === "—")
      .map((b: { class: string }) => b.class);
    expect(bad, `这些积木没登记（改 scripts/gen-ui-lib.py 的 BLOCK_NOTES）：\n${bad.join("\n")}`).toEqual([]);
  });

  it("每个组件都有「什么时候用」", () => {
    const bad = lib.components.filter((c: { note?: string }) => !c.note || c.note === "—")
      .map((c: { name: string }) => c.name);
    expect(bad, `这些组件没登记，且首行注释也取不到一句话：\n${bad.join("\n")}`).toEqual([]);
  });

  it("没有积木落在「其它」里 —— 那不是分类，是「还没分类」", () => {
    const bad = lib.blocks.filter((b: { group: string }) => b.group === "其它")
      .map((b: { class: string }) => b.class);
    expect(bad, `这些积木还没归组：\n${bad.join("\n")}`).toEqual([]);
  });
});

/*
 * **投影只许用 `--sh-shadow-*`，尤其不许拿 `--sh-scrim` 当投影色。**
 *
 * 2026-09-06 用户报「底部工具栏的阴影太多」。查下去不是那一处写歪了 ——
 * 是**这套设计语言里根本没有投影档**，于是全仓 5 处投影全都抓了
 * `--sh-scrim` 来当颜色。而 scrim 是**蒙层色**：它的活是把弹层背后的整屏压暗，
 * 所以是 `rgba(10,12,16,.45)`。正常的高度投影是 4%~16%，**差一个数量级**。
 *
 * 症状为什么一直没人改：它不报错、闸门全绿、每一处单看都「有阴影，合理」。
 * 只有把五处并排看，才发现它们共用了一个语义完全不同的 token。
 *
 * 例外只有一类：**复刻 uni 内置件默认外观**的那几条（`uni-switch` 的滑块）——
 * 那不是我们的设计语言，是在补 uni 自己在浅色下丢掉的默认值，照抄它的数值才对。
 */
describe("投影只许用 --sh-shadow-*", () => {
  /** 复刻 uni 内置件默认外观的选择器 —— 那是补 uni 的缺，不归设计语言管 */
  const UNI_BUILTIN = /uni-(switch|checkbox|radio|slider)/;

  /** base.css + 两端 src 下所有 .vue + 库件 —— 这条的扫描面要覆盖所有会写样式的地方 */
  const files = [
    join(ROOT, "packages/ui/src/styles/base.css"),
    ...[...APPS.map((a) => join(ROOT, a, "src")), join(ROOT, "packages/ui/src")].flatMap((dir) =>
      readdirSync(dir, { recursive: true, encoding: "utf8" })
        .filter((f) => f.endsWith(".vue"))
        .map((f) => join(dir, f)),
    ),
  ];

  it("有文件可扫（否则下面全是空转）", () => {
    expect(files.length).toBeGreaterThan(80);
  });

  it("base.css 里两档投影都在 —— 它们是这条规则的唯一落点", () => {
    const css = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
    for (const t of ["--sh-shadow-up", "--sh-shadow-float"]) {
      expect(new RegExp(`${t}\\s*:`).test(css), `base.css 缺 ${t}`).toBe(true);
    }
  });

  it("没有人拿 --sh-scrim 当投影色 —— 那是蒙层的 45%，不是高度的 8%", () => {
    const bad: string[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const css = f.endsWith(".css") ? src
        : [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n");
      // ⚠️ **`filter: drop-shadow()` 也算投影** —— 第一版只认 `box-shadow:`，
      //    于是 `sh-theme-sheet` 的 `.swatch__tick` 用 scrim 画的那道衬底整个漏过去了。
      //    同一天写的闸门自己就留了这个缝，靠「token 落在哪些属性上」那一遍才扫出来。
      const SHADOWY = /(box-shadow|filter|text-shadow):\s*([^;]+);/g;
      for (const m of css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(SHADOWY)) {
        if (!/shadow\(|box-shadow|text-shadow/.test(m[0])) continue;   // filter 也可能是 blur/opacity
        if (/--sh-scrim/.test(m[2]!)) bad.push(`${f.slice(ROOT.length + 1)}  ${m[1]}: ${m[2]!.trim()}`);
      }
    }
    expect(bad, `scrim 是蒙层色（45% 的黑），当投影用会得到一条又黑又宽的带：\n${bad.join("\n")}`).toEqual([]);
  });

  /*
   * ⚠️ 这一条**只管 `box-shadow`**，不管 `filter: drop-shadow()`。
   * 不是漏掉：那两档是**高度**（8% / 16% 的纵深），而全仓唯一的 drop-shadow
   * 是给白色对勾做**可读性衬底**（压在任意皮肤色上），要的浓度完全不同。
   * 一处特例不该建第三档（这个仓库的规矩：只对 1/36 成立的件不该建），
   * 但它**仍然不许借 `--sh-scrim`** —— 上一条管着。
   */
  it("投影一律走档 —— 散写的数值下一处就对不上", () => {
    const bad: string[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const css = f.endsWith(".css") ? src
        : [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n");
      const clean = css.replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of clean.matchAll(/([^{};]*)\{[^{}]*box-shadow:\s*([^;]+);/g)) {
        const sel = m[1]!.trim().split("\n").pop()!.trim();
        const val = m[2]!.trim();
        if (val === "none" || /var\(--sh-shadow-/.test(val)) continue;
        if (UNI_BUILTIN.test(sel)) continue;
        // `0 0 0 Nrpx` 是**描边**不是投影（用 box-shadow 画同心环，见 sh-theme-sheet 的选中态）
        if (/^(0 0 0 [^,]+)(,\s*0 0 0 [^,]+)*$/.test(val)) continue;
        bad.push(`${f.slice(ROOT.length + 1)}  ${sel}  ${val}`);
      }
    }
    expect(bad, `散写的投影 —— 收进 --sh-shadow-up / --sh-shadow-float：\n${bad.join("\n")}`).toEqual([]);
  });
});

/*
 * **想让缝被看见，容器就不能自己上色。**
 *
 * 2026-09-06 我给 `.sh-cells` 写的第一版是：容器 `background: var(--sh-surface)`
 * + `gap: 2rpx`，行（`.sh-cell`）只给内边距、不上色。想的是「那 2rpx 露出页底色，
 * 比画一条线更轻」。**但缝里露出来的是容器自己的白** —— 白压白，缝等于不存在。
 *
 * 骗人的地方在于它看着是对的：`ui-lib.json` 里 gap 明明是 2rpx，浏览器量出来
 * 行间距也确实是 1px，逐页体检、自造件、字阶全绿。只有把两张截图并排看，
 * 才发现「行与行分开」完全是内边距的功劳，那道缝一次都没出现过。
 *
 * 判据：**`gap ≤ 4rpx` 的容器不许自己声明底色**。
 * 4rpx 是分界线 —— 再小的缝不可能是「间距」，只可能是「想让人看见的一道线」；
 * 而 8rpx 以上是真的在拉开距离，容器上色无所谓（`.sh-row` 的 16rpx 就是）。
 * 白底要给到**行**上，缝才露得出它下面的东西。
 */
describe("缝要露得出下面的东西", () => {
  const files = [
    join(ROOT, "packages/ui/src/styles/base.css"),
    ...[...APPS.map((a) => join(ROOT, a, "src")), join(ROOT, "packages/ui/src")].flatMap((dir) =>
      readdirSync(dir, { recursive: true, encoding: "utf8" })
        .filter((f) => f.endsWith(".vue"))
        .map((f) => join(dir, f)),
    ),
  ];

  it("有文件可扫（否则下面全是空转）", () => {
    expect(files.length).toBeGreaterThan(80);
  });

  it("gap ≤ 4rpx 的容器没有自己上色 —— 那道缝里会露出它自己", () => {
    const bad: string[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const css = f.endsWith(".css") ? src
        : [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n");
      for (const m of css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const body = m[2]!;
        const gap = /\bgap:\s*(\d+)rpx/.exec(body);
        const bg = /background(?:-color)?:\s*([^;]+)/.exec(body);
        if (!gap || !bg || Number(gap[1]) > 4) continue;
        // 透明是对的写法 —— 那正是「让下面的东西露出来」
        if (/^(transparent|none|0)$/.test(bg[1]!.trim())) continue;
        bad.push(`${f.slice(ROOT.length + 1)}  ${m[1]!.trim().split("\n").pop()!.trim()}  gap:${gap[1]}rpx + ${bg[1]!.trim()}`);
      }
    }
    expect(
      bad,
      "这些容器的缝里会露出它自己的底色，等于没有缝 —— 底色给到「行」上：\n" + bad.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **行尾箭头只有一个尺寸。**
 *
 * `chevronRight` 是全站被手写得最多的一个图标（2026-09-06 数：35 处 / 17 个文件），
 * 而它有 **5 种尺寸**：22(×20) / 18(×9) / 28 / 20 / 14。同一个字形五个数，
 * 且大小与旁边的字号毫无关系 —— `stats` 用 18 配 `.txt-title`（34rpx），
 * `me` 用 22 配 `.txt-body`（28rpx），越大的字反而配越小的箭头。
 *
 * 归一到 **22**（32 处里 20 处本来就是），包括 `sh-go` 里那一个。
 *
 * <b>豁免的不是「特例」，是另一件事</b>：`transfer` 与 `goods-publish` 那两处
 * 不是「这一行可以点进去」，是**方向指示**（从库位→到库位、旧值→新值）。
 * 它们的大小跟着两侧内容走，跟行尾箭头没关系。
 * 用 `sh-icon` 而不是 `→` 字符是有意的（`goods-publish` 的注释里写着）：
 * 字符伪图标跟着系统字形走，RTL 下也不会自己翻，而 `chevronRight`
 * 在 `sh-icon` 的 DIRECTIONAL 名单里，阿语下自动镜像。
 */
describe("行尾箭头只有一个尺寸", () => {
  /** 方向指示 —— 「从 A 到 B」，不是「点进去」。文件 → 为什么 */
  const DIRECTION: Record<string, string> = {
    "b-app/src/pages/transfer/index.vue": "从库位 → 到库位",
    "b-app/src/pages/goods-publish/index.vue": "旧值 → 新值",
  };
  const SIZE = 22;

  const files = [...APPS.map((a) => join(ROOT, a, "src")), join(ROOT, "packages/ui/src")]
    .flatMap((dir) =>
      readdirSync(dir, { recursive: true, encoding: "utf8" })
        .filter((f) => f.endsWith(".vue"))
        .map((f) => join(dir, f)),
    );

  it("扫得到那些箭头（否则下面全是空转）", () => {
    const n = files.filter((f) => readFileSync(f, "utf8").includes('name="chevronRight"')).length;
    expect(n, "一个 chevronRight 都没扫到，判据大概是写错了").toBeGreaterThan(10);
  });

  it("行尾箭头一律 22rpx", () => {
    const bad: string[] = [];
    for (const f of files) {
      const rel = f.slice(ROOT.length + 1);
      if (rel in DIRECTION) continue;
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/name="chevronRight"[\s\S]{0,120}?:size="(\d+)"/g)) {
        if (Number(m[1]) !== SIZE) bad.push(`${rel}  :size="${m[1]}"`);
      }
    }
    expect(
      bad,
      `行尾箭头只有 ${SIZE}rpx 一档 —— 真的是「从 A 到 B」的方向指示，登记进 DIRECTION 并写清理由：\n${bad.join("\n")}`,
    ).toEqual([]);
  });

  it("豁免名单里的文件还真的有方向箭头 —— 名单会锈", () => {
    for (const [rel, why] of Object.entries(DIRECTION)) {
      const src = readFileSync(join(ROOT, rel), "utf8");
      expect(src.includes('name="chevronRight"'), `${rel}（${why}）里已经没有箭头了，名单该删这一条`).toBe(true);
    }
  });
});

/*
 * **同一行两端的字号，要么相同，要么至少差 4rpx。**
 *
 * 2026-09-07 用户说「我的」页的字体跟别的页不一样。量下来两件事：
 *
 *   · 那一页每行是「标签 `.txt-body`(28) + 值 `.txt-sub`(26)」——
 *     **只差 2rpx（1px）**，小到看不出是有意的，层次全靠颜色扛
 *   · 而全站 104 个两端对齐行，用了 **22 种「标签档 → 值档」组合** ——
 *     根本不存在「常见写法」这回事，「我的」只是碰巧用了第二多的那一种
 *
 * 24 / 26 / 28 三档挤在 4rpx 里，同一行两端各取一档就是这个毛病。
 * 判据不按「档位相邻」也不按百分比 —— 就按**物理像素**：
 * 差 1px 的两个字号没人分得出，只会让人觉得没对齐
 *（与圆角那条同一个口径：「差 4rpx 的两个圆角，没人分得出」）。
 *
 * 合规的两种写法都在用，也都清楚：
 *   同档（靠颜色/字重分）—— 数据行的主流，`caption→caption` 28 次
 *   差 ≥4rpx —— 导航行，`body(28) → caption(24)`
 *
 * <b>两条排除项</b>：
 *   · **只看行的直接子节点** —— 被假阳性逼出来的：`store-scope` 的标签里嵌了
 *     一小段 caption 后缀，那是标签的一部分，不是这一行的值
 *   · **按钮不是「值」**（按钮有自己的档）—— ⚠️ **这一条当前不起作用**：
 *     撤掉它跑一遍，一处都不多报。原因是上面那条已经覆盖了唯一的候选
 *     （`gcard__foot` 的参团按钮外面裹着 `.avatars`，本来就不是直接子节点）。
 *     留着是因为它在定义上是对的 —— 但**别把它当成有验证的排除项**：
 *     哪天真有一行「标签 + 直接子的按钮」，它才第一次生效，而那一刻没人验过它。
 */
describe("同一行两端的字号不许只差 1px", () => {
  const base = readFileSync(join(ROOT, "packages/ui/src/styles/base.css"), "utf8");
  const SIZE = new Map<string, number>();
  for (const m of base.matchAll(/\.txt-([a-z]+)\s*\{([^}]*)\}/g)) {
    const s = /font-size:\s*(\d+)rpx/.exec(m[2]!);
    if (s) SIZE.set(`txt-${m[1]}`, Number(s[1]));
  }
  /** 按钮 / 动作 / 分段 —— 它们有自己的字号档，不参与「标签 vs 值」 */
  const ACTION = /\b(sh-btn|sh-chip|sh-seg|sh-go|[\w-]*btn[\w-]*|[\w-]*action[\w-]*)\b/;

  function offenders(): string[] {
    const bad: string[] = [];
    for (const { app, file, src } of pageFilesAll()) {
      const tpl = src.split("<style")[0]!;
      for (const row of tpl.matchAll(/<(view|label)([^>]*class="[^"]*sh-row--between[^"]*"[^>]*)>/g)) {
        const rest = tpl.slice(row.index! + row[0].length);
        let depth = 1;
        const tiers: string[] = [];
        for (const t of rest.matchAll(TAG_RE)) {
          const [, close, tag, attrs, selfClose] = t as unknown as string[];
          if (close) { if (--depth === 0) break; continue; }
          const here = depth;                                   // 进入前的深度 = 它相对行的层级
          if (!selfClose && !["input", "image", "br"].includes(tag!)) depth++;
          if (here !== 1) continue;                             // 只看直接子节点
          const cm = /class="([^"]*)"/.exec(attrs ?? "");
          const cls = cm ? cm[1]! : "";
          if (ACTION.test(cls) || /^(sh-btn|sh-go|sh-chip)/.test(tag!)) continue;
          const tier = cls.split(/\s+/).find((c) => SIZE.has(c));
          if (tier) tiers.push(tier);
        }
        if (tiers.length < 2) continue;
        const a = SIZE.get(tiers[0]!)!, b = SIZE.get(tiers[tiers.length - 1]!)!;
        if (a !== b && Math.abs(a - b) <= 2) {
          const ln = tpl.slice(0, row.index!).split("\n").length;
          bad.push(`${app}/${file}:${ln}  .${tiers[0]}(${a}) → .${tiers[tiers.length - 1]}(${b})`);
        }
      }
    }
    return bad;
  }

  it("字阶读到了（否则下面全是空转）", () => {
    expect(SIZE.size).toBeGreaterThan(6);
    expect(SIZE.get("txt-body")).toBe(28);
  });

  it("扫到了足够多的两端对齐行", () => {
    // 判据本身要能空转报警：模板结构一改（比如 sh-row--between 换名），这条先红
    let rows = 0;
    for (const { src } of pageFilesAll()) rows += (src.match(/sh-row--between/g) ?? []).length;
    expect(rows, "一个两端对齐行都没扫到，判据大概是写错了").toBeGreaterThan(80);
  });

  it("两端要么同档，要么差 ≥4rpx", () => {
    const bad = offenders();
    expect(
      bad,
      "差 1px 的两个字号没人分得出，只会让人觉得没对齐 —— 要么两端同档（靠颜色/字重分），\n" +
        "要么把值降到 `.txt-caption`（导航行的写法）：\n" + bad.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **间距表必须与真实用量对得上 —— 两个方向都要。**
 *
 * 2026-09-07：`tokens.ts` 声明的是五档 `8/16/28/40/64`，《规范·版面》配着一句
 * 「只用这几档」，而全仓 1292 处间距取值落在那五档上的只有 **39%**：
 * `20rpx` 用了 205 次，而档位里的 `64rpx` 只有 2 次。
 *
 * **这个矛盾早就有人发现，只是修错了方向。** `check-page-spec.py` 的头注写着
 * 「按五档判会报出 361 处…那是判据说多了，不是页面写错了」——
 * 于是放松了闸门，没有回头改声明。三把尺（声明 / 闸门 / 文档）从此各说各的，
 * 而且三者都绿，因为**没有任何东西在比它们**。这条就是那个比。
 *
 * 两个方向缺一不可：
 *   · 表上有、没人用 → 那是一句空话（`64rpx` 曾是「档」，全仓 2 处）
 *   · 用得多、表上没有 → 那才是真的漂移（`20rpx` 用了 205 次却不在档上）
 *
 * 阈值取 **20 处**：再少就是个别页面的局部选择，不该逼着上表；
 * 到了 20 处说明它已经是这套界面的一个节奏了，藏着不认才是问题。
 */
describe("间距表与真实用量对得上", () => {
  const GRID = 4;
  const BUSY = 20;
  const PROPS = /\b(margin|margin-top|margin-bottom|margin-inline|padding|padding-top|padding-bottom|padding-inline|gap|row-gap|column-gap)\s*:\s*([^;]+);/g;

  const declared = new Set(
    Object.values(
      JSON.parse(readFileSync(join(ROOT, "docs/technical/design/ui-lib.json"), "utf8"))
        .tokens.spacing as Record<string, { rpx: string | number }>,
    ).map((v) => Number(String(v.rpx).replace(/\D/g, ""))),
  );

  const used = new Map<number, number>();
  {
    const files = [
      join(ROOT, "packages/ui/src/styles/base.css"),
      ...[...APPS.map((a) => join(ROOT, a, "src")), join(ROOT, "packages/ui/src")].flatMap((dir) =>
        readdirSync(dir, { recursive: true, encoding: "utf8" })
          .filter((f) => f.endsWith(".vue"))
          .map((f) => join(dir, f)),
      ),
    ];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const css = (f.endsWith(".css") ? src
        : [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n"))
        .replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of css.matchAll(PROPS))
        for (const n of m[2]!.matchAll(/(\d+)rpx/g)) {
          const v = Number(n[1]);
          if (v) used.set(v, (used.get(v) ?? 0) + 1);
        }
    }
  }

  it("量到了间距（否则下面全是空转）", () => {
    expect(declared.size).toBeGreaterThan(5);
    expect([...used.values()].reduce((a, b) => a + b, 0)).toBeGreaterThan(500);
  });

  it("每个间距都落在 4rpx 网格上（2rpx 是发丝线单位，豁免）", () => {
    /*
     * **2rpx 不是「半格」，是发丝线本身**：`--sh-hairline` 就是 `2rpx solid`，
     * `.sh-cells` 行与行之间那道缝也是 2rpx（露出下面的底色，比画一条线更轻）。
     * 把它归到 0 会删掉那道缝，归到 4 会让它变成一条明显的灰带 —— 两个都不对。
     *
     * 其余 8 个离格值（5/6/10/14/18/22/26/30，共 81 处）已就近归格，
     * 每处移动 0.5~1px。**先量后做**：改之前逐个数过，最大移动 2rpx。
     */
    const bad = [...used.keys()].filter((v) => v % GRID !== 0 && v !== 2).sort((a, b) => a - b);
    expect(bad, `不在 ${GRID}rpx 网格上：${bad.join(", ")}rpx`).toEqual([]);
  });

  it(`用了 ≥${BUSY} 次的间距都在表上 —— 藏着不认才是漂移`, () => {
    const bad = [...used].filter(([v, n]) => n >= BUSY && !declared.has(v))
      .sort((a, b) => b[1] - a[1]).map(([v, n]) => `${v}rpx ×${n}`);
    expect(bad, `这些数已经是这套界面的节奏了，却不在 tokens.ts 的表上：\n${bad.join("\n")}`).toEqual([]);
  });

  it("表上的每个数都真的有人用 —— 没人用的档是一句空话", () => {
    const bad = [...declared].filter((v) => !(used.get(v) ?? 0)).sort((a, b) => a - b);
    expect(bad, `声明了却零调用点：${bad.join(", ")}rpx`).toEqual([]);
  });
});

/*
 * **每一条断言都要出现在某一份规范里。**
 *
 * 三份规范此前有一个共同的毛病：读者**分不出哪一行有闸门、哪一行只是约定**。
 * 「间距只用这五档」和「间距落在 4rpx 网格上」在纸面上一样重 ——
 * 而前者是一句从来没成立过的话（实际命中率 39%），后者一直有断言守着。
 *
 * 修法是把闸门一节**从测试文件里生成**（`gen-ui-spec.py` 的 `gates()`），
 * 于是「规范里写着的闸门」与「真的存在的断言」不可能对不上。
 *
 * 这一条守的是那个映射的另一头：`GATE_DOMAIN` 是按措辞归类的，
 * 新写一条用词不同的断言就会落进「其它」—— 那它**在三份规范里一个字都不会出现**，
 * 而写的人不会收到任何信号。所以「其它」必须是空的：
 * 要么把措辞对齐，要么给 `GATE_DOMAIN` 加一个域。
 */
describe("断言都归得进规范", () => {
  const gen = readFileSync(join(ROOT, "scripts/gen-ui-spec.py"), "utf8");

  /** 从生成器里读同一份判据 —— 两边各抄一份的话，改一处就会悄悄分叉 */
  function domains(): Record<string, string[]> {
    const blk = /GATE_DOMAIN = \{([\s\S]*?)\n\}/.exec(gen);
    expect(blk, "gen-ui-spec.py 里找不到 GATE_DOMAIN —— 判据改名了？").not.toBeNull();
    const out: Record<string, string[]> = {};
    for (const m of blk![1]!.matchAll(/"([^"]+)":\s*\(([^)]*)\)/g))
      out[m[1]!] = [...m[2]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
    return out;
  }
  const SKIP = /(有东西可扫|有文件可扫|空转|读到了|扫到了|扫得到|量到了|有那几张表|文档在|扫描面|有文件)/;

  it("每个域都对应一份真实存在的规范", () => {
    /*
     * 判据不写死域名 —— 第一版写的是 `["字体","版面","组件"]`，
     * 2026-09-08 加《规范·页面》时它当场变红，而变红的理由与规矩无关，
     * 纯粹是我把清单抄了一份。**域名的真源是 docs 目录里那几份文件。**
     */
    const d = domains();
    expect(Object.values(d).flat().length).toBeGreaterThan(15);
    const missing = Object.keys(d)
      .filter((k) => !existsSync(join(ROOT, `docs/technical/design/规范-${k}.md`)));
    expect(missing, `GATE_DOMAIN 里有域没有对应的规范文件：${missing.join(", ")}`).toEqual([]);
  });

  it("没有断言落在「其它」—— 落进去就等于在规范里消失了", () => {
    const dom = domains();
    const orphan: string[] = [];
    for (const f of ["ui-package", "typography", "safe-area-fallback"]) {
      const src = readFileSync(join(ROOT, `packages/shared/tests/${f}.test.ts`), "utf8");
      let cur = "";
      for (const m of src.matchAll(/\b(describe|it)\("([^"]+)"/g)) {
        if (m[1] === "describe") { cur = m[2]!; continue; }
        const title = m[2]!;
        if (SKIP.test(title)) continue;
        const ok = Object.values(dom).some((kws) => kws.some((k) => title.includes(k)))
          || Object.values(dom).some((kws) => kws.some((k) => cur.includes(k)));
        if (!ok) orphan.push(`${f} · ${cur} → ${title}`);
      }
    }
    expect(
      orphan,
      "这些断言归不进任何一份规范，于是规范里一个字都不会提到它们 ——\n" +
        "要么把措辞对齐，要么给 gen-ui-spec.py 的 GATE_DOMAIN 加一档：\n" + orphan.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **划掉的字只有两种，各有各的件。**
 *
 * 全仓曾有 9 处自写 `text-decoration: line-through`，而它们是**两种语义**：
 *
 *   划线原价（c 端 4 处）  「原价 ¥39.80」—— 折扣前的价，永远是一小段附注
 *   作废 / 失效（b 端 5 处）「这个区域被移出了」「这个标签失效了」「这是旧值」
 *
 * 分成 `.sh-was` / `.sh-void` 两个而不是一个，是因为**它们对字号的态度相反**：
 * 划线原价永远是附注、字号固定（此前 4 处却是 caption ×2 / sub ×2）；
 * 而作废挂在原本就有大小的文本上，只该改装饰与颜色。
 *
 * ⚠️ **迁移时踩过一次**：`chosen__name` / `item__name` / `is-void` 三处的划线是
 * **条件态**（`.is-off` 才划），而第一版把 `sh-void` 无条件加到了调用点上 ——
 * 那会把**每一项**都划掉。改成 `:class="{ 'sh-void': 失效 }"` 直接绑条件。
 * 「同一个装饰」不等于「同一个时机」。
 */
describe("划掉的字走库件", () => {
  const files = [...APPS.map((a) => join(ROOT, a, "src"))].flatMap((dir) =>
    readdirSync(dir, { recursive: true, encoding: "utf8" })
      .filter((f) => f.endsWith(".vue"))
      .map((f) => join(dir, f)),
  );

  it("有文件可扫（否则下面全是空转）", () => {
    expect(files.length).toBeGreaterThan(80);
  });

  it("页面与业务件里没有自写的 line-through", () => {
    const bad: string[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const css = [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
        .map((m) => m[1]!).join("\n").replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of css.matchAll(/([^{}]+)\{[^{}]*text-decoration:\s*line-through/g))
        bad.push(`${f.slice(ROOT.length + 1)}  ${m[1]!.trim().split("\n").pop()!.trim()}`);
    }
    expect(
      bad,
      "划线原价走 `.sh-was`（自带 caption 字号与次要色），不再有效走 `.sh-void`（只改装饰与颜色）：\n"
        + bad.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **空态不许在「还不知道」的时候出现。**
 *
 * 一个拉数据的页面有四种态：加载中 / 有数据 / 确定为空 / 出错。
 * 而全仓 57 个有空态的页面里，只有 16 个把「加载中」从空态条件里排除了 ——
 * 其余 41 页写的是 `v-if="!list.length"`，于是**数据到达前先闪一下「暂无内容」**。
 *
 * <b>症状是实测的，不是推断</b>：2026-09-08 在 `my-memberships` 上逐帧采样，
 * 空态可见约 **175ms**（102ms → 277ms）才被数据顶掉。而那是**本地 mock** ——
 * 真机弱网下这个窗口是 0.5~2 秒，用户看到「暂无会员」，然后它翻成一个列表。
 *
 * 存量 41 页上棘轮（`known-empty-flash.txt`，只准变短）：这 41 页各有各的
 * 加载变量名（`loading` / `pending` / `loaded` / `failed` 四种），逐页要看清
 * 「哪个变量代表首屏到过」，机械替换会把下拉刷新也判成空。**新写的页面直接拦。**
 */
describe("空态不许在还不知道时出现", () => {
  /**
   * 「首屏加载完了没有」的标志位。**后面不许跟 `.`** ——
   * `b-app/delivery` 的 `v-if="!pending.length"` 里 `pending` 是**待处理列表**，
   * 不是加载标志；只按词匹配会把那一页误判成「已经守好了」，
   * 而它恰恰是会闪空态的那一类。判据认的是标志位，不是同名的名词。
   */
  const LOADED = /\b(loading|loaded|pending|inited|ready|firstLoad)\b(?!\s*\.)/;
  const baseline = new Set(
    readFileSync(join(ROOT, "known-empty-flash.txt"), "utf8")
      .split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#")),
  );

  function offenders(): string[] {
    const bad: string[] = [];
    for (const app of APPS) {
      const dir = join(ROOT, app, "src/pages");
      for (const rel of readdirSync(dir, { recursive: true, encoding: "utf8" })) {
        if (!rel.endsWith("index.vue")) continue;
        const tpl = readFileSync(join(dir, rel), "utf8").split("<style")[0]!;
        const conds = [...tpl.matchAll(/<sh-empty[^>]*v-(?:if|else-if)="([^"]+)"/g)].map((m) => m[1]!);
        if (!conds.length || conds.some((c) => LOADED.test(c))) continue;
        bad.push(`${app}/${rel.split("/")[0]}`);
      }
    }
    return bad;
  }

  it("扫得到页面（否则下面全是空转）", () => {
    let n = 0;
    for (const app of APPS)
      n += readdirSync(join(ROOT, app, "src/pages"), { recursive: true, encoding: "utf8" })
        .filter((f) => f.endsWith("index.vue")).length;
    expect(n).toBeGreaterThan(80);
    expect(baseline.size).toBeGreaterThan(10);
  });

  it("没有新增的「会闪空态」页面", () => {
    const neu = offenders().filter((p) => !baseline.has(p));
    expect(
      neu,
      "空态要排除「还没加载完」：`v-if=\"loaded && !list.length\"`。\n" +
        "用 `loaded` 不用 `loading` —— 前者是「首屏到过没有」，后者含下拉刷新，\n" +
        "刷新时不该把列表换成空态：\n" + neu.join("\n"),
    ).toEqual([]);
  });

  it("棘轮不许锈：名单里修好的页面要及时删掉", () => {
    const live = new Set(offenders());
    const stale = [...baseline].filter((p) => !live.has(p));
    expect(
      stale,
      "这些页面已经修好了，但还留在 known-empty-flash.txt 里 —— 留着它们就永远免检：\n" + stale.join("\n"),
    ).toEqual([]);
  });
});

/*
 * **件不自己写 `font-size` —— 字号由 `.txt-*` 带出来。**
 *
 * 2026-09-08 把「每个件用了哪几档字阶、哪几个间距」算进清单时露出来的：
 * 34 个件里只有 `sh-uploader` 自己写了一处 `font-size: 48rpx`。
 * 看过是**对的** —— 那是 `sh-cover` 拿到 emoji 时按文字排的兜底字号，
 * 而且 48 在字阶上。
 *
 * 所以这条不是「发现了缺陷」，是**趁只有一处的时候立止血线**：
 * `typography.test.ts` 只管「字号必须落在字阶上」，管不住「件绕过 `.txt-*` 自己写」——
 * 而后者一旦有第二处、第三处，字阶在件这一层就名存实亡了。
 *
 * 名单是**止血线型**（不是待办）：里面那一条是经过判断保留的，不该被「修掉」。
 * 要加新的一条，得先说清为什么这个件的字号不能由调用点给。
 */
describe("件不自己写 font-size", () => {
  /** 允许自写字号的件 → 理由。**加一条要写清理由** */
  const ALLOW: Record<string, string> = {
    "sh-uploader.vue": "sh-cover 拿到 emoji 时按文字排，这里给兜底字号（48rpx 在字阶上）",
  };

  const found = new Map<string, number[]>();
  for (const f of readdirSync(UI).filter((x) => x.endsWith(".vue"))) {
    const css = [...readFileSync(join(UI, f), "utf8").matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
      .map((m) => m[1]!).join("\n").replace(/\/\*[\s\S]*?\*\//g, "");
    const sizes = [...css.matchAll(/font-size:\s*(\d+)rpx/g)].map((m) => Number(m[1]));
    if (sizes.length) found.set(f, sizes);
  }

  it("扫到了件（否则下面全是空转）", () => {
    expect(readdirSync(UI).filter((x) => x.endsWith(".vue")).length).toBeGreaterThan(25);
  });

  it("没有新的件自己写字号", () => {
    const bad = [...found].filter(([f]) => !(f in ALLOW))
      .map(([f, s]) => `${f}  ${s.join("/")}rpx`);
    expect(
      bad,
      "字号由 `.txt-*` 带出来。确实该由件自己定的（比如给 emoji 兜底），\n" +
        "登记进 ALLOW 并写清「为什么这个件的字号不能由调用点给」：\n" + bad.join("\n"),
    ).toEqual([]);
  });

  it("名单不许锈：登记了却已经不写字号的要删掉", () => {
    const stale = Object.keys(ALLOW).filter((f) => !found.has(f));
    expect(stale, `这些件已经不自己写字号了，名单该删：${stale.join(", ")}`).toEqual([]);
  });
});
