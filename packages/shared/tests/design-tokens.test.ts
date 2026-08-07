// 设计规范测试（E8）。
//
// 这些约束写在注释里已经很久了，但没有任何东西拦着 —— 注释拦不住第 27 个页面。
// 这里把「能机器判定的」全部变成断言：
//   · 色板在 JS 与 CSS 两处声明，必须一致（改一处忘另一处 → 换肤时原生栏颜色不跟着变）
//   · 组件层不许写死颜色（写死了就吃不到 4 套皮肤 × 明暗）
//   · 页面里不许出现条件编译（端差异要下沉到 ports/）
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { radius, SKIN_HEX, SKINS } from "@shared/design/tokens";

const ROOT = join(import.meta.dirname, "../../..");
/** 全局样式基座：皮肤变量的唯一落点（原先两端 App.vue 各一份） */
const BASE_CSS = join(ROOT, "packages/ui/src/styles/base.css");
const APPS = ["c-app", "b-app"];
/** 组件库抽走之后，只扫两个 app 会漏掉共享组件 —— 那才是改动最频繁的地方 */
const VUE_ROOTS = [...APPS, "packages/ui"];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue")) out.push(p);
  }
  return out;
}

function vueFiles(app: string): string[] {
  return walk(join(ROOT, app, "src"));
}

/** 取 <style> 块内容 —— 只审样式，脚本里的十六进制（如常量）不在此列 */
function styleBlocks(src: string): string {
  return [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n");
}

describe("色板：JS 与 CSS 必须同源", () => {
  // 原生 tabBar / 导航栏由客户端渲染，**不吃 CSS 变量**，只能在换肤时用
  // uni.setTabBarStyle 运行时改写 —— 所以同一份色值必须在 JS 侧再写一遍。
  // 两处漂移的症状很隐蔽：页面颜色变了，顶部导航栏和底部栏没变。
  it("每个皮肤的主色在样式基座里有对应的 --sh-primary 声明", () => {
    for (const app of APPS) {
      const css = readFileSync(BASE_CSS, "utf8");
      for (const skin of SKINS) {
        const hex = SKIN_HEX[skin.id].light.toLowerCase();
        // CSS 里按 :root[data-skin="x"] / .sh-root.skin-x 两种选择器声明
        const block = css.match(
          new RegExp(`skin-${skin.id}[^{]*\\{([^}]*)\\}`, "i"),
        );
        expect(block, `${app} 缺少皮肤 ${skin.id} 的 CSS 声明`).toBeTruthy();
        expect(
          block![1]!.toLowerCase().includes(hex),
          `${app} 皮肤 ${skin.id}：JS 是 ${hex}，CSS 里没有这个值`,
        ).toBe(true);
      }
    }
  });

  it("SKINS 与 SKIN_HEX 的条目一一对应", () => {
    expect(SKINS.map((s) => s.id).sort()).toEqual(Object.keys(SKIN_HEX).sort());
  });

  it("样式基座里的主色与 SKIN_HEX **完全一致**（防手改 CSS 造成的漂移）", () => {
    // 「包含」不够：手改 CSS 加一个新值、旧值还在，也能通过包含检查。
    // CSS 是 gen-skins.mjs 生成的，这里断言它没被手动改过。
    for (const app of APPS) {
      const css = readFileSync(BASE_CSS, "utf8");
      for (const [id, color] of Object.entries(SKIN_HEX)) {
        for (const mode of ["light", "dark"] as const) {
          const block = css.match(
            new RegExp(`\\.sh-root\\.skin-${id}\\.mode-${mode}\\s*\\{([^}]*)\\}`),
          );
          expect(block, `${app}: 缺少 skin-${id}.mode-${mode}`).toBeTruthy();
          const primary = block![1]!.match(/--sh-primary:\s*([^;]+);/)![1]!.trim();
          expect(
            primary.toLowerCase(),
            `${app} ${id}/${mode}: CSS 是 ${primary}，SKIN_HEX 是 ${color[mode]} —— 跑 npm run gen:skins`,
          ).toBe(color[mode].toLowerCase());
        }
      }
    }
  });

  it("原生 tabBar 选中色与默认皮肤一致（它不吃 CSS 变量，只能写死）", () => {
    // 漂移的症状很隐蔽：页面颜色变了，底部菜单没变
    for (const app of APPS) {
      const pages = JSON.parse(readFileSync(join(ROOT, app, "src/pages.json"), "utf8"));
      expect(
        pages.tabBar.selectedColor.toLowerCase(),
        `${app}: pages.json 的 tabBar.selectedColor 与默认皮肤不符 —— 跑 npm run gen:skins`,
      ).toBe(SKINS[0]!.color.toLowerCase());
    }
  });
});

describe("组件层不许写死颜色", () => {
  // 写死了就吃不到 4 套皮肤 × 明暗 —— 深色模式下那一块会突兀地留在浅色。
  // 例外：样式基座（base.css）是 token 的**定义处**，色值本来就该出现在那里；
  // 它不是 .vue，本来就不在这轮扫描里。
  const COLOR = /#[0-9a-f]{3,8}\b|\brgba?\(|\boklch\(/gi;

  it("页面与组件的 style 块里没有 hex / rgb / oklch", () => {
    const offenders: string[] = [];
    for (const app of VUE_ROOTS) {
      for (const file of vueFiles(app)) {
        if (file.endsWith("App.vue")) continue;
        const css = styleBlocks(readFileSync(file, "utf8"));
        // 允许 #fff / #ffffff 作为「叠在语义色上的前景白」—— 它不随皮肤变，
        // 例如红色角标上的白字。除此之外一律走 var(--sh-*)。
        const hits = (css.match(COLOR) ?? []).filter(
          (c) => !["#fff", "#ffffff"].includes(c.toLowerCase()),
        );
        if (hits.length) offenders.push(`${file.replace(ROOT, "")}: ${hits.join(", ")}`);
      }
    }
    expect(offenders, `以下文件写死了颜色，应改用 var(--sh-*)：\n${offenders.join("\n")}`).toEqual(
      [],
    );
  });
});

describe("端差异下沉到 ports/，页面里不写条件编译", () => {
  // 页面里一旦出现 #ifdef，端差异就散在几十个文件里；
  // 集中在 ports/ 才能保证「加一个端」只改一处。
  it("pages/ 下没有 #ifdef / #ifndef", () => {
    const offenders: string[] = [];
    for (const app of APPS) {
      for (const file of walk(join(ROOT, app, "src/pages"))) {
        const src = readFileSync(file, "utf8");
        // 只认**真正的条件编译**：`// #ifdef MP-WEIXIN` 这种「整行注释 + 大写平台标记」。
        // 不能光看 #ifdef 三个字 —— 说明性注释里提到它（「页面不写 #ifdef」）会被误判
        if (/^\s*(?:\/\/|<!--)\s*#if n?def\s+[A-Z]/m.test(src.replace(/#if(n?def)/g, "#if $1")))
          offenders.push(file.replace(ROOT, ""));
      }
    }
    expect(offenders, `以下页面出现条件编译，应下沉到 ports/：\n${offenders.join("\n")}`).toEqual(
      [],
    );
  });
});

describe("圆角五档：uno 与 tokens 必须同源", () => {
  // 两处各写一套的后果很隐蔽：`rounded-md`（uno）与 `radius.md`（JS）不是同一个值，
  // 同一个「md」在模板里和在脚本里画出来不一样宽。
  it("uno.config.ts 的 borderRadius 与 design/tokens.ts 的 radius 一致", () => {
    // uno 配置也合并成一份了（两端根目录只剩转发）
    const uno = readFileSync(join(ROOT, "packages/ui/src/uno.config.ts"), "utf8");
    const block = uno.match(/borderRadius:\s*\{([\s\S]*?)\}/)![1]!;
    for (const [k, v] of Object.entries(radius)) {
      const m = block.match(new RegExp(`${k}:\\s*"([^"]+)"`));
      expect(m, `uno.config.ts 缺少圆角档位 ${k}`).toBeTruthy();
      expect(m![1], `圆角 ${k}：tokens 是 ${v}，uno 是 ${m![1]}`).toBe(v);
    }
  });
});

describe("皮肤对比度：可读性是硬约束，不是审美偏好", () => {
  // 这一组断言的来历：扩皮肤时算了一遍才发现，原有 4 套里有 3 套不达标 ——
  // fresh/promo 的主按钮白字只有 2.66 / 3.03（AA 要 4.5），
  // mono 的近黑放在深色底上对比 1.09，主按钮直接和背景糊在一起。
  // 这类问题不会有人「看出来」，只会以用户说「看不清」的形式回来。

  function luminance(hex: string): number {
    const h = hex.replace("#", "");
    const ch = [0, 2, 4].map((i) => {
      const c = parseInt(h.slice(i, i + 2), 16) / 255;
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * ch[0]! + 0.7152 * ch[1]! + 0.0722 * ch[2]!;
  }

  function contrast(a: string, b: string): number {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi! + 0.05) / (lo! + 0.05);
  }

  /** 从样式基座里取某皮肤某模式实际生效的变量值 —— 断言的是**页面真正用的**，不是 JS 常量 */
  function cssVar(app: string, skin: string, mode: "light" | "dark", name: string): string {
    const css = readFileSync(BASE_CSS, "utf8");
    const block = css.match(new RegExp(`\\.sh-root\\.skin-${skin}\\.mode-${mode}\\s*\\{([^}]*)\\}`));
    expect(block, `${app}: 缺少 skin-${skin}.mode-${mode} 的声明`).toBeTruthy();
    const m = block![1]!.match(new RegExp(`${name}:\\s*([^;]+);`));
    expect(m, `${app}: skin-${skin}.mode-${mode} 缺少 ${name}`).toBeTruthy();
    return m![1]!.trim();
  }

  it("主色上的前景文字达 WCAG AA（4.5:1）", () => {
    for (const app of APPS) {
      for (const skin of SKINS) {
        for (const mode of ["light", "dark"] as const) {
          const primary = cssVar(app, skin.id, mode, "--sh-primary");
          const onPrimary = cssVar(app, skin.id, mode, "--sh-on-primary");
          const ratio = contrast(primary, onPrimary);
          expect(
            ratio,
            `${app} ${skin.id}/${mode}: 主色 ${primary} 上的 ${onPrimary} 对比仅 ${ratio.toFixed(2)}`,
          ).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });

  it("主色在各自模式的背景上足够显眼（3:1，UI 组件下限）", () => {
    for (const app of APPS) {
      for (const skin of SKINS) {
        // 品牌锁定的皮肤豁免本条，理由记录在 tokens.ts 上：
        // fresh 是微信绿，与微信生态的观感一致性优先于组件边界指标。
        // **豁免只覆盖这一条** —— 压在主色上的文字仍要达 4.5，由上一个用例守着
        if (SKIN_HEX[skin.id].brandLocked) continue;
        for (const mode of ["light", "dark"] as const) {
          // 背景取该皮肤自己的 --sh-bg：面感不同底色就不同（pure 是纯白，neutral 是灰白），
          // 拿一个写死的背景去比，比的是不存在的组合
          const primary = cssVar(app, skin.id, mode, "--sh-primary");
          const bg = cssVar(app, skin.id, mode, "--sh-bg");
          const ratio = contrast(primary, bg);
          expect(
            ratio,
            `${app} ${skin.id}/${mode}: 主色 ${primary} 在背景 ${bg} 上对比仅 ${ratio.toFixed(2)} —— 按钮会糊进背景`,
          ).toBeGreaterThanOrEqual(3);
        }
      }
    }
  });

  it("品牌锁定是**自觉取舍**，要显式记录而不是悄悄跳过", () => {
    const locked = SKINS.filter((s) => SKIN_HEX[s.id].brandLocked).map((s) => s.id);
    // 只允许 fresh 一个 —— 豁免多了这条断言就失去意义，
    // 每加一个都得先在这里改，逼着人回答「为什么它也能豁免」
    expect(locked).toEqual(["fresh"]);
    // 豁免的皮肤文字对比必须更高，作为补偿：糊在背景里的按钮，字至少要清楚
    for (const id of locked) {
      for (const app of APPS) {
        for (const mode of ["light", "dark"] as const) {
          const primary = cssVar(app, id, mode, "--sh-primary");
          const onPrimary = cssVar(app, id, mode, "--sh-on-primary");
          expect(contrast(primary, onPrimary)).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });

  it("中性面成套：每个皮肤都要给全背景与文字变量", () => {
    // 只换主色不换面，暖色主色配冷灰底会「脏」。这条断言的是「成套」，不是具体色值
    const NEEDED = ["--sh-bg", "--sh-surface", "--sh-elev", "--sh-ink", "--sh-sub", "--sh-faint", "--sh-line"];
    for (const app of APPS) {
      for (const skin of SKINS) {
        for (const mode of ["light", "dark"] as const) {
          for (const name of NEEDED) {
            expect(cssVar(app, skin.id, mode, name)).toMatch(/^#[0-9A-Fa-f]{6}$/);
          }
        }
      }
    }
  });

  it("正文文字在自己的背景上达 AA（4.5:1）", () => {
    // 字体色也是配色的一部分 —— 暖底配冷墨、纯白底配浅灰字，都是这条能挡住的
    for (const app of APPS) {
      for (const skin of SKINS) {
        for (const mode of ["light", "dark"] as const) {
          const bg = cssVar(app, skin.id, mode, "--sh-bg");
          const ink = cssVar(app, skin.id, mode, "--sh-ink");
          const ratio = contrast(ink, bg);
          expect(
            ratio,
            `${app} ${skin.id}/${mode}: 正文 ${ink} 在 ${bg} 上仅 ${ratio.toFixed(2)}`,
          ).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });

  it("SKIN_HEX 的 light 值与 SKINS 预览色一致", () => {
    for (const s of SKINS) {
      expect(s.color.toLowerCase()).toBe(SKIN_HEX[s.id].light.toLowerCase());
    }
  });
});
