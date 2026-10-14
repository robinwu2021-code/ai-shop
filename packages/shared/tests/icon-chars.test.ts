// 字符当图标：`✕` `✓` `＋` `›` 这些**不是图标**。
//
// 这条守卫的来历（2026-08-27）：清单的判据报「文字当箭头 3 处」，动手才发现
// **它只扫模板，而箭头大多藏在 i18n 词条里** ——
//
//     goApplyWithLicense: "有营业执照？直接走入驻 ›"
//     switchStore: "切换门店 ›"      view: "查看详情 ›"
//
// 10 条词条 × 3 种语言 = 30 处，模板里能看见的只有 3 处。**判据报的数比真实少一个数量级。**
//
// 为什么字符不能当图标：
//   · 跟着系统字形走 —— 同一个 ✕ 在 iOS / Android / H5 上三种粗细
//   · 拿不到 sh-icon 的尺寸与颜色档，也没法只给它上色而不动文字
//   · 翻译要跟着抄标点，漏一个语言就长得不一样
//
// **但字符有一件事做对了**：`›`(U+203A) 是 Unicode 的 bidi-mirrored 字符，
// 阿语下浏览器自己翻。图标不会 —— 所以镜像补在了 sh-icon 的 DIRECTIONAL 名单
// 与 `.sh-root.is-rtl .icon--dir` 上。收编这类字符时**必须一起想到 RTL**。
//
// 放行的是什么：
//   · `×` 当乘号（`{{ spec }} × {{ qty }}`、「200 张 × 5 元」）
//   · `·` 当分隔符（`options.join(" · ")`）
//   · `›` 在**散文里指路**（「系统设置 › 应用 › 权限」）或**面包屑分隔**
//     —— 判据是「不在字符串末尾」：末尾的才是那种「点我」的箭头。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
// ⚠️ **暂时只管 B 端**。C 端还有 10 处模板字符 + 4 条尾部带箭头的词条
// （`groupsMore` / `bindPhone` / `enter` / `view`），那是 C 端那一轮的活。
// 写死成两端会让这道守卫从第一天起就是红的 —— **恒红的闸门是噪声掩体**，
// 它会把真失败一起藏掉。C 端清完把 "c-app/src" 加回来即可。
const APPS = ["b-app/src"];

/** 只该由 sh-icon 画的字符。`×`(U+00D7) 不在其中 —— 它更常当乘号 */
const GLYPHS = "✕✖⨯✓✔＋﹢−↑↓←→";
/** 词条尾部出现就算「点我」箭头。
 *  ⚠️ **`»` 不在里面**：阿语用 `«…»` 当引号，`agreementTitle: "«商家协议与隐私政策»"`
 *  这类以 `»` 收尾的是被引号括起来的标题，不是箭头 —— 这条守卫第一次跑就误报了 16 处。 */
const TRAILING = "›→";

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const at = (src: string, i: number) => src.slice(0, i).split("\n").length;

describe("字符当图标", () => {
  it("★★★ 模板里没有「整个元素就是一个字符」的伪图标", () => {
    const bad: string[] = [];
    // `>✕<`：元素的全部内容就是那个字符；`"✓"`：三元里直接返回一个字符
    const re = new RegExp(`>\\s*([${GLYPHS}])\\s*<|["']\\s*([${GLYPHS}])\\s*["']`, "g");
    for (const app of APPS) {
      for (const f of walk(join(ROOT, app)).filter((p) => p.endsWith(".vue"))) {
        const src = readFileSync(f, "utf8");
        if (!src.includes("<template>")) continue;
        const s = src.indexOf("<template>");
        const tpl = src.slice(s, src.lastIndexOf("</template>"));
        for (const m of tpl.matchAll(re)) {
          bad.push(`${relative(ROOT, f)}:${at(src, s + m.index!)}  ${(m[1] ?? m[2])}`);
        }
      }
    }
    expect(
      bad,
      `这些地方拿字符当图标了，改用 <sh-icon>（close / check / plus / minus / chevron*）：\n${bad.join("\n")}`,
    ).toEqual([]);
  });

  it("★★★ 词条末尾没有箭头 —— 那是判据看不见的地方，一次就漏了 30 处", () => {
    const bad: string[] = [];
    const re = new RegExp(`\\s*[${TRAILING}]\\s*"`, "g");
    for (const app of APPS) {
      const dir = join(ROOT, app, "i18n/locale");
      let files: string[];
      try {
        files = walk(dir).filter((p) => p.endsWith(".ts"));
      } catch {
        continue; // 该端没有 i18n 目录
      }
      for (const f of files) {
        const src = readFileSync(f, "utf8");
        for (const m of src.matchAll(re)) {
          bad.push(`${relative(ROOT, f)}:${at(src, m.index!)}`);
        }
      }
    }
    expect(
      bad,
      `词条末尾带箭头 —— 那不是文案，是控件的一部分，应该由 <sh-go> 或 <sh-icon> 画：\n${bad.join("\n")}`,
    ).toEqual([]);
  });
});
