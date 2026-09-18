// 每个 .vue **都要能被 SFC 编译器读懂**。
//
// 这条是补一次真实事故：`biz-address-form.vue` 少了一个 `</template>`，
// 而 `vue-tsc --noEmit` 与 173 条单测**全绿**，代码就这么推上去了 ——
// 到部署那一步 `uni build` 才炸，报的是
//   [vite:vue] Element is missing end tag.
// 那时前端已经上了两个、这一个没上，线上三端版本不一致。
//
// 为什么 vue-tsc 放它过去：它检的是 script 与模板表达式的**类型**，
// 标签闭合是 SFC 解析那一层的事，解析不出来它就当这个文件没有模板。
// 「工具跑了、没报错」在这里是一个假信号，与 `npx tsc` 不检 .vue 同一类。
//
// 为什么不直接在 pre-push 跑 `uni build`：那要一分多钟，而这一条几百毫秒 ——
// 它只解析，不打包，正好卡在「能不能读懂」这一层，而那正是漏掉的那一层。
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { parse } from "@vue/compiler-sfc";

const SRC = join(import.meta.dirname, "../src");

function vueFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...vueFiles(p));
    else if (name.endsWith(".vue")) out.push(p);
  }
  return out;
}

describe("每个 .vue 都要能被编译器读懂", () => {
  it("★★★ SFC 解析零报错 —— 标签没闭合这类错，vue-tsc 与单测都看不见", () => {
    const files = vueFiles(SRC);
    // 对照量：扫描面本身要非零。扫到 0 个文件时下面那条断言恒绿
    expect(files.length, "一个 .vue 都没扫到 —— 这条守卫量的是空集").toBeGreaterThan(20);

    const broken: string[] = [];
    for (const f of files) {
      const { errors } = parse(readFileSync(f, "utf8"), { filename: f });
      for (const e of errors) {
        broken.push(`${f.slice(SRC.length + 1)}: ${"message" in e ? e.message : String(e)}`);
      }
    }
    expect(broken, `这些 .vue 编译器读不懂（uni build 会在这里炸）：\n${broken.join("\n")}`)
      .toEqual([]);
  });
});
