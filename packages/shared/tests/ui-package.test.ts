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
  const pageFiles = () =>
    APPS.flatMap((app) =>
      readdirSync(join(ROOT, app, "src/pages"), { recursive: true, encoding: "utf8" })
        .filter((f) => String(f).endsWith(".vue"))
        .map((f) => ({ app, file: String(f), src: readFileSync(join(ROOT, app, "src/pages", String(f)), "utf8") })),
    );

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
