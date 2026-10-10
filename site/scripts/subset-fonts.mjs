/**
 * 中文字体子集化（TDD-hxmall-site T6）。
 *
 * 全量 Noto Sans SC 是 14 MB —— 中文站的头号性能杀手，比首屏 JS 大两个数量级。
 * 这里只保留**页面上真的出现过的那些字**（当前 795 个，两个字重各约 105 KB）。
 *
 * 三个关键决定：
 *
 * 1. **字符集从源码实算，不手维护清单。** 挂在 prebuild 上，每次构建重算 ——
 *    「改了文案忘了重跑」这件事不会发生。lib/fonts.test.ts 再兜一层。
 *
 * 2. **tsx 要剥注释、content 要跳过 README/sitemap。** 这两处不上页面：
 *    不剥的话字符集从 758 涨到 946，多出来的近 200 个字全是给自己人看的注释里的，
 *    每个字都要占体积。
 *
 * 3. **出两个定重文件，不出一个变量字体。** 变量字体保留 wght 轴要 216 KB，
 *    比 400+600 两份分开还大 —— CJK 的字形插值数据太贵。而只出 400 让浏览器合成粗体，
 *    中文小字号下会糊成一团。
 *
 * 源字体（14 MB，OFL）不进仓库：缺失时自动下载到 fonts/src/（已 gitignore）。
 * 定重母版也缓存在那里 —— instancer 跑一次要几十秒，不缓存等于每次构建多等两分钟。
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const SITE = join(dirname(fileURLToPath(import.meta.url)), "..");
const SRC_DIR = join(SITE, "fonts/src");
const SRC_FONT = join(SRC_DIR, "NotoSansSC-VF.otf");
const SRC_URL =
  "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/OTF/Subset/NotoSansSC-VF.otf";
const OUT_DIR = join(SITE, "public/fonts");
const COVERAGE = join(SITE, "fonts/coverage.json");

/** 正文 400 / 标题与强调 600。再多一档不值 —— 每档都是一整份字形 */
const WEIGHTS = [400, 600];

/** 单档预算。795 字实测 ~105 KB；留一点余量，涨过头要么是收多了字，要么是加了字重 */
const BUDGET_KB = 130;

/**
 * 兜底字符：标点不一定出现在当前文案里，但下一句就可能用到。
 * 漏一个标点的表现是「这一个符号换了字体」，比缺字更难被发现。
 */
const ALWAYS = "，。、；：？！…—～·「」『』（）《》〈〉“”‘’０１２３４５６７８９％￥";

const isCjk = (cp) =>
  (cp >= 0x4e00 && cp <= 0x9fff) ||
  (cp >= 0x3400 && cp <= 0x4dbf) ||
  (cp >= 0x3000 && cp <= 0x303f) ||
  (cp >= 0xff00 && cp <= 0xffef) ||
  (cp >= 0x2018 && cp <= 0x201d) ||
  cp === 0x2026 ||
  cp === 0x2014;

/** 只留会渲染的部分：注释是给自己人看的，不该占字体体积 */
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*$/gm, "$1");

/** content/ 下的说明文档不上页面 */
const INTERNAL_DOCS = new Set(["README.md", "sitemap.md"]);

function walk(dir, test, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === "node_modules" || name === "out" || name === ".next") continue;
      walk(p, test, out);
    } else if (test(p)) out.push(p);
  }
  return out;
}

export function collectChars() {
  const chars = new Set([...ALWAYS]);
  const add = (text) => {
    for (const ch of text) if (isCjk(ch.codePointAt(0))) chars.add(ch);
  };
  for (const f of walk(join(SITE, "content"), (p) => p.endsWith(".md"))) {
    if (!INTERNAL_DOCS.has(f.split("/").pop())) add(readFileSync(f, "utf8"));
  }
  for (const dir of ["app", "components", "lib"]) {
    for (const f of walk(join(SITE, dir), (p) => /\.tsx?$/.test(p) && !p.includes(".test."))) {
      add(stripComments(readFileSync(f, "utf8")));
    }
  }
  return [...chars].sort();
}

/* 作为脚本跑时才动文件；被测试 import 时只用上面的 collectChars */
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const chars = collectChars();

  /**
   * 已经是最新就跳过。
   *
   * 产物进仓库、源字体不进 —— 于是**构建机不必装 fontTools、也不必联网**，
   * 只要文案没动就直接用现成的。文案动了才会往下走，那时缺工具就该响亮地失败：
   * 悄悄用旧子集出包，等于让新写的那几个字在页面上换一种字体。
   */
  const fresh =
    existsSync(COVERAGE) &&
    WEIGHTS.every((w) => existsSync(join(OUT_DIR, `hx-sc-${w}.woff2`))) &&
    (() => {
      const have = new Set(JSON.parse(readFileSync(COVERAGE, "utf8")).chars);
      return chars.every((c) => have.has(c));
    })();
  if (fresh && !process.env.FORCE_SUBSET) {
    process.stdout.write(`· 中文子集已是最新（${chars.length} 字），跳过\n`);
    process.exit(0);
  }

  if (!existsSync(SRC_FONT)) {
    mkdirSync(SRC_DIR, { recursive: true });
    process.stdout.write("↓ 下载源字体 NotoSansSC-VF.otf（约 14 MB · OFL）…\n");
    const res = await fetch(SRC_URL);
    if (!res.ok) throw new Error(`取源字体失败：${res.status} ${SRC_URL}`);
    writeFileSync(SRC_FONT, Buffer.from(await res.arrayBuffer()));
  }

  mkdirSync(OUT_DIR, { recursive: true });
  const textFile = join(SRC_DIR, ".chars.txt");
  writeFileSync(textFile, chars.join(""));

  for (const w of WEIGHTS) {
    const master = join(SRC_DIR, `NotoSansSC-${w}.otf`);
    if (!existsSync(master)) {
      process.stdout.write(`· 生成 ${w} 定重母版（首次约 40s，之后走缓存）…\n`);
      execFileSync("python3", ["-m", "fontTools.varLib.instancer", SRC_FONT, `wght=${w}`, "-o", master], {
        stdio: ["ignore", "ignore", "inherit"],
      });
    }
    const out = join(OUT_DIR, `hx-sc-${w}.woff2`);
    execFileSync(
      "python3",
      [
        "-m",
        "fontTools.subset",
        master,
        `--text-file=${textFile}`,
        `--output-file=${out}`,
        "--flavor=woff2",
        "--no-hinting",
        "--desubroutinize",
        "--layout-features=",
        "--name-IDs=",
      ],
      { stdio: ["ignore", "ignore", "inherit"] },
    );
    const kb = statSync(out).size / 1024;
    process.stdout.write(`✓ hx-sc-${w}.woff2 ${kb.toFixed(1)} KB · ${chars.length} 字\n`);
    if (kb > BUDGET_KB) {
      throw new Error(`hx-sc-${w}.woff2 ${kb.toFixed(1)} KB 超出 ${BUDGET_KB} KB 预算`);
    }
  }

  writeFileSync(COVERAGE, `${JSON.stringify({ count: chars.length, chars: chars.join("") })}\n`);
}
