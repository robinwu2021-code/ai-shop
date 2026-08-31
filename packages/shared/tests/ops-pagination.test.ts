// 运营端列表端点必须返回**分页壳**，不能返回裸数组。
//
// 这条守卫是踩了十次同一个坑换来的（2026-08）：ops-web 所有列表页都按
// `{records,total}` 渲染，后端返回裸数组时页面把它当成「空页」——
// **接口 200、数据几十条、页面显示「暂无数据」，控制台一条错误都没有**。
//
// 十个端点全中，而单元测试、集成测试、E2E-2 全都是绿的：
// 后端测试断言的是自己返回的形状，E2E-2 也照着后端的形状写。
// 只有真的在浏览器里打开那个页面才会发现。
//
// 判定依据取自 ops-web 的契约：声明 `Promise<Page<...>>` 的方法，
// 它对应的后端端点就必须返回 PageData。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { backendModules } from "./backend-modules";

const ROOT = join(import.meta.dirname, "../../..");

/** ops-web 契约里声明返回分页的方法名 */
function pagedMethods(): Set<string> {
  const dir = join(ROOT, "ops-web/lib/api/contracts");
  const out = new Set<string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/^\s*(\w+)\([^)]*\):\s*Promise<Page</gm)) out.add(m[1]!);
  }
  return out;
}

/** 方法名 → 端点路径 */
function pathOf(): Map<string, string> {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const out = new Map<string, string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/(\w+):\s*\([^)]*\)\s*=>\s*client\.(get|post|put|del)\(\s*[`"]([^`"]+)/g)) {
      out.set(m[1]!, m[3]!);
    }
  }
  return out;
}

/** 后端所有 Controller 源码 */
function controllerSources(): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith("Controller.java")) out.push(readFileSync(p, "utf8"));
    }
  };
  for (const m of backendModules(join(ROOT, "backend"))) {
    walk(join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"));
  }
  return out;
}

describe("运营端分页契约", () => {
  const paged = pagedMethods();
  const paths = pathOf();
  const sources = controllerSources();

  /** 找到某个路径的 @GetMapping 之后那段方法签名 */
  function signatureOf(path: string): string | null {
    for (const src of sources) {
      const i = src.indexOf(`@GetMapping("${path}")`);
      if (i < 0) continue;
      // 从注解往后取到方法体的第一个 `{`，签名一定在这一段里
      const seg = src.slice(i, src.indexOf("{", src.indexOf("public", i)));
      return seg;
    }
    return null;
  }

  it("ops-web 声明分页的端点，后端必须返回 PageData（返回裸数组会让页面显示「暂无数据」）", () => {
    const offenders: string[] = [];
    for (const fn of paged) {
      const path = paths.get(fn);
      if (!path) continue;
      const sig = signatureOf(path);
      // 后端还没实现的端点不算违规 —— 那是覆盖率的问题，不是形状的问题
      if (!sig) continue;
      if (!/PageData</.test(sig)) {
        offenders.push(`${path}（${fn}）`);
      }
    }
    expect(
      offenders,
      "这些端点 ops-web 按分页渲染，而后端返回的是裸数组：\n  " +
        offenders.join("\n  ") +
        "\n\n后果不是报错 —— 是**接口 200、数据几十条、页面显示「暂无数据」**，" +
        "而控制台一条错误都没有。\n" +
        "列表本来就要全量算的（类目树、实时视图、几十条量级的主数据）用 " +
        "PageData.ofAll(list, page, size) 包一层即可。",
    ).toEqual([]);
  });
});
