// 契约 ↔ HTTP 实现 ↔ OpenAPI 产物 的一致性。
//
// `npm run gen:api` 自己会在不一致时退出，但那要人主动去跑。这条测试把同一个判据
// 挂到 `npm run check` 上：加了契约方法却忘了写 http 实现（或反过来），提交前就红。
//
// ⚠️ 不校验 openapi-ops.yaml 的内容是否最新 —— 那需要在测试里跑生成器（慢且有副作用）。
// 这里只保证**源头两侧一致**；产物过期由 CI 里的 `gen:api && git diff --exit-code` 兜。
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { mockApi } from "./mock";
import { httpApi } from "./http";

const ROOT = new URL("../..", import.meta.url).pathname;

/** 从 https/*.ts 解析出「有 client 调用的方法名」——与生成器同一套判据。 */
function implementedEndpoints(): Set<string> {
  const dir = join(ROOT, "lib/api/https");
  const names = new Set<string>();
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    const re = /(\w+):\s*\([^)]*\)\s*=>\s*(?:\n\s*)?client\.(get|post|put)\(/g;
    let m;
    while ((m = re.exec(src))) names.add(m[1]);
  }
  return names;
}

describe("契约与 HTTP 端点一致", () => {
  it("每个契约方法都有对应的 client 调用（否则切后端时是运行时 undefined）", () => {
    const impl = implementedEndpoints();
    const missing = Object.keys(httpApi).filter((k) => !impl.has(k));
    expect(missing, `这些方法在 https/*.ts 里没有 client 调用：\n${missing.join("\n")}`).toEqual([]);
  });

  it("没有多余的端点实现（说明契约漏声明了）", () => {
    const impl = [...implementedEndpoints()];
    const orphan = impl.filter((k) => !(k in httpApi));
    expect(orphan, `这些端点不在契约里：\n${orphan.join("\n")}`).toEqual([]);
  });

  it("mock 与 http 两侧方法数一致（gen:api 生成的端点数以此为准）", () => {
    expect(Object.keys(mockApi).length).toBe(Object.keys(httpApi).length);
  });

  it("端点路径统一以 /ops 开头（C 端是 /mp，两端不能混）", () => {
    const dir = join(ROOT, "lib/api/https");
    const bad: string[] = [];
    for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
      const src = readFileSync(join(dir, f), "utf8");
      const re = /client\.(?:get|post|put)\(\s*[`"]([^`"]+)/g;
      let m;
      while ((m = re.exec(src))) {
        if (!m[1].startsWith("/ops/")) bad.push(`${f}: ${m[1]}`);
      }
    }
    expect(bad, `路径必须以 /ops/ 开头：\n${bad.join("\n")}`).toEqual([]);
  });
});
