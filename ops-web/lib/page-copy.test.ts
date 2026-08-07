// 页面文案（`app/*/copy.ts`）的守卫。
//
// 三条，都是踩过或差点踩到的：
// 1. zh/en key 齐平 —— 漏一条会静默显示中文，界面上只是"某几处还是中文"，很难发现
// 2. en 里不许残留汉字 —— 复制粘贴漏译是最常见的一种
// 3. **已经有 copy.ts 的页面，page.tsx 里不许再出现裸中文** ——
//    否则会出现"改了一半"的页面：一部分走文案表、一部分写死，切 EN 后中英混排
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;
const APP = join(ROOT, "app");
const CJK = /[一-龥]/;

/** 有 copy.ts 的页面。"" 代表根目录的工作台（app/page.tsx + app/copy.ts）。 */
function convertedPages(): string[] {
  const subs = readdirSync(APP, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== "dev")
    .map((d) => d.name)
    .filter((n) => existsSync(join(APP, n, "copy.ts")));
  return existsSync(join(APP, "copy.ts")) ? ["(工作台)", ...subs] : subs;
}

const dirOf = (name: string) => (name === "(工作台)" ? APP : join(APP, name));

function keysOf(src: string, which: "zh" | "en"): string[] {
  // copy.ts 的形状是固定的：`const zh = { … };` / `const en: typeof zh = { … };`
  const re = new RegExp(`const ${which}(?::[^=]+)? = \\{([\\s\\S]*?)\\n\\};`);
  const body = re.exec(src)?.[1] ?? "";
  return [...body.matchAll(/^\s{2}(\w+):/gm)].map((m) => m[1]).sort();
}

describe("页面文案", () => {
  const pages = convertedPages();

  it("至少有一个页面接入了文案表（否则下面的断言全是空转）", () => {
    expect(pages.length).toBeGreaterThan(0);
  });

  it.each(pages)("%s：zh / en 的 key 完全齐平", (name) => {
    const src = readFileSync(join(dirOf(name), "copy.ts"), "utf8");
    const zh = keysOf(src, "zh");
    const en = keysOf(src, "en");
    expect(zh.length, "没解析到 zh 的 key，copy.ts 的写法变了").toBeGreaterThan(0);
    expect(en).toEqual(zh);
  });

  it.each(pages)("%s：英文里不许残留汉字", (name) => {
    const src = readFileSync(join(dirOf(name), "copy.ts"), "utf8");
    const enBody = /const en(?::[^=]+)? = \{([\s\S]*?)\n\};/.exec(src)?.[1] ?? "";
    const bad = [...enBody.matchAll(/^\s{2}(\w+):\s*(.+)$/gm)]
      .filter(([, , v]) => CJK.test(v))
      .map(([, k]) => k);
    expect(bad, `这些 key 的英文还带汉字：\n${bad.join("\n")}`).toEqual([]);
  });

  // 扫**页面目录下的每个 .tsx**，不只是 page.tsx：
  // 页面拆出 xxx-tab.tsx 之后，只看 page.tsx 就等于给裸中文开了一个后门。
  it.each(pages)("%s：目录下的 .tsx 里不许再有裸中文（注释除外）", (name) => {
    const dir = dirOf(name);
    const src = readdirSync(dir)
      // layout.tsx 例外：里面只有 <head> 的 metadata，那是服务端在**渲染之前**就要定的，
      // 拿不到客户端的语言态；浏览器标签页显示中文站名是可接受的。
      .filter((f) => f.endsWith(".tsx") && f !== "layout.tsx")
      .map((f) => `// ==== ${f} ====\n` + readFileSync(join(dir, f), "utf8"))
      .join("\n");
    // 注释里可以写中文（本仓的注释就是中文的），但**必须真的识别多行注释**：
    // 只看行首会把 `{/* 第一行\n 第二行 */}` 的续行误判成违规 —— 第一版就是这么错的。
    const offenders: string[] = [];
    let inBlock = false;
    src.split("\n").forEach((line, i) => {
      let code = line;
      if (inBlock) {
        const end = code.indexOf("*/");
        if (end < 0) return;                  // 整行都在注释里
        code = code.slice(end + 2);
        inBlock = false;
      }
      // 去掉本行内成对的块注释（含 JSX 的 {/* … */}）
      code = code.replace(/\{?\/\*[\s\S]*?\*\/\}?/g, "");
      const open = code.indexOf("/*");
      if (open >= 0) { inBlock = true; code = code.slice(0, open); }
      code = code.replace(/\/\/.*$/, "");     // 行注释
      if (CJK.test(code)) offenders.push(`${i + 1}: ${line.trim().slice(0, 72)}`);
    });
    expect(offenders, `搬进 ${name} 的 copy.ts：\n${offenders.join("\n")}`).toEqual([]);
  });
});
