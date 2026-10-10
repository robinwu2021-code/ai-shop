/**
 * 组件自己 `emit('tap')` 的那个 `@tap` 必须带 `.stop`。
 *
 * 小程序里页面在组件上写 `@tap="…"` 会编译成 `bindtap`：组件内部的**原生 tap 会冒泡到这个绑定上**，
 * 组件自己 emit 的 'tap' 又触发一次 —— 回调跑两遍。
 * 真机症状（2026-09-19）：首页点商品，详情页先无动画出现、半秒后又滑进来一个一模一样的，
 * 按一次返回还停在详情页。H5 上 Vue 按组件事件处理、只触发一次，所以浏览器里看不出来。
 * `.stop` 挡住原生冒泡，只留组件发出的那一次。
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const ROOT = resolve(__dirname, "../..");
const DIRS = ["c-app/src", "b-app/src", "packages/ui/src"];

function vueFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...vueFiles(p));
    else if (p.endsWith(".vue")) out.push(p);
  }
  return out;
}

const files = DIRS.flatMap((d) => vueFiles(join(ROOT, d)));
const EMITS_TAP = /@tap(\.[\w.]+)?="[^"]*\bemit\(\s*['"]tap['"]\s*\)[^"]*"/g;

describe("组件发 tap 必须 .stop", () => {
  it("扫描面不是空的（少扫＝恒绿）", () => {
    expect(files.length).toBeGreaterThan(100);
    const hits = files.flatMap((f) => readFileSync(f, "utf8").match(EMITS_TAP) ?? []);
    expect(hits.length, "至少应扫到商品卡等 10 处").toBeGreaterThanOrEqual(10);
  });

  it("★★★ 每一处 emit('tap') 的 @tap 都带 .stop", () => {
    const bad: string[] = [];
    for (const f of files) {
      for (const m of readFileSync(f, "utf8").matchAll(EMITS_TAP)) {
        if (!(m[1] ?? "").split(".").includes("stop")) bad.push(`${relative(ROOT, f)}  ${m[0]}`);
      }
    }
    expect(bad, `小程序里会触发两次（页面的 bindtap 也收到原生冒泡）：\n${bad.join("\n")}`).toEqual([]);
  });
});
