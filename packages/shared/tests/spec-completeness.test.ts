// 契约的**完整性**：路径齐了不代表契约能用。
//
// 这两条断言各自对应一次真实事故，两次都是静默的 —— 没有报错、没有红灯，
// spec 看上去完好，问题要到后端照着它写完、联调时才浮出来：
//
// 1. 平台端 178 个操作的响应统统是 `Result<unknown>`，描述里写着「data 的结构见
//    对应契约方法的 TS 返回类型」。要读 TS 才能实现的东西不叫契约，那是一张路径表。
//    而返回类型**一直就写在契约签名里**，只是生成器没去取。
//
// 2. 根目录 `gen:api` 只跑 c-app 和 b-app，漏了 ops-web。于是 docs 里那份平台端 spec
//    停在 151 个端点，实际契约已经 178 个 —— 谁也没察觉，因为漏跑不会报错，
//    只会让文件保持旧的样子。
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";

interface Schema {
  properties?: Record<string, { description?: string }>;
}

const ROOT = join(import.meta.dirname, "../../..");
const SPECS = [
  ["C 端", "docs/api/openapi.yaml"],
  ["B 端", "docs/api/openapi-b.yaml"],
  ["平台端", "docs/api/openapi-ops.yaml"],
] as const;

describe("契约完整性", () => {
  it.each(SPECS)("%s：没有 unknown 占位的响应", (_label, file) => {
    const src = readFileSync(join(ROOT, file), "utf8");
    // `Result<unknown>` 经 URL 编码后是 `Result%3Cunknown%3E`
    expect(
      src.includes("Result%3Cunknown%3E") || src.includes("Result<unknown>"),
      "响应指向 unknown 占位类型 —— 后端拿这份 spec 写不出 DTO。" +
        "生成器应当从契约签名的 Promise<X> 取真实返回类型",
    ).toBe(false);
  });

  it.each(SPECS)("%s：每个操作都声明了响应内容", (_label, file) => {
    const src = readFileSync(join(ROOT, file), "utf8").split("\n");
    // 一个操作一个 operationId；responses 段里必须出现 content
    const opCount = src.filter((l) => /^\s+"?operationId"?:/.test(l)).length;
    const contentCount = src.filter((l) => /^\s+"?application\/json"?:/.test(l)).length;
    expect(opCount).toBeGreaterThan(0);
    expect(
      contentCount,
      `${opCount} 个操作但只有 ${contentCount} 个响应体 —— 有操作没声明返回什么`,
    ).toBeGreaterThanOrEqual(opCount);
  });

  // 覆盖率是一次性补上来的（1386 个字段里当时只有 509 个有说明）。没有这道闸，
  // 下一批新增字段会照旧不写注释，几周后又回到 60% —— 而那时再补一次的成本一样大。
  it.each(SPECS)("%s：每个字段都有说明", (_label, file) => {
    const spec = parse(readFileSync(join(ROOT, file), "utf8")) as {
      components?: { schemas?: Record<string, Schema> };
    };
    const undocumented: string[] = [];
    for (const [name, schema] of Object.entries(spec.components?.schemas ?? {})) {
      // 映射类型（`Record<Lang, string>`）的键由类型参数生成，源码里无处挂 JSDoc
      if (/^(Partial[_<])?Record[_<]/.test(name)) continue;
      for (const [field, prop] of Object.entries(schema.properties ?? {})) {
        if (!prop.description) undocumented.push(`${name}.${field}`);
      }
    }
    expect(
      undocumented,
      "这些字段没有说明。后端与评审读的是生成出来的 API 详情文档，" +
        "字段没解释就只能去猜语义或回头翻前端源码：\n  " +
        undocumented.join("\n  ") +
        "\n→ 在 TS 类型定义处补 JSDoc，文档会自动带出来",
    ).toEqual([]);
  });


  // 响应格式规范.md §3 手抄了 ErrorCode.java 的全部错误码。手抄必然漂移 ——
  // 后端加一个码、文档没更新，评审看到的分段表就是旧的。这里锁住双向一致。
  it("错误码分段表与 ErrorCode.java 一致", () => {
    const java = join(ROOT, "backend/shop-common/src/main/java/ai/neargo/shop/common/ErrorCode.java");
    if (!existsSync(java)) return; // 只装前端的场景
    const src = readFileSync(java, "utf8");
    const codes = [...src.matchAll(/^\s{4}(\w+)\((\d+),/gm)].map((m) => ({ name: m[1]!, code: m[2]! }));
    expect(codes.length, "ErrorCode.java 一条都没解析到 —— 枚举写法变了？").toBeGreaterThan(0);

    const doc = readFileSync(join(ROOT, "docs/api/响应格式规范.md"), "utf8");
    const missing = codes.filter((c) => !doc.includes(`\`${c.code}\` ${c.name}`));
    expect(
      missing.map((c) => `${c.code} ${c.name}`),
      "这些错误码在 ErrorCode.java 里有、响应格式规范.md §3 没有（或写法不一致，应为 `code` NAME）",
    ).toEqual([]);

    // 反向：文档里的码必须真实存在，防止删码后文档还留着
    const docCodes = [...doc.matchAll(/\`(\d{5})\` (\w+)/g)].map((m) => `${m[1]} ${m[2]}`);
    const real = new Set(codes.map((c) => `${c.code} ${c.name}`));
    const stale = docCodes.filter((x) => !real.has(x));
    expect(stale, "这些码只在文档里、ErrorCode.java 已没有").toEqual([]);
  });

  // 领域模型对齐清单是生成的，但**它的映射表是人维护的**：新增契约实体而没在
  // ENTITY_MAP / VIEW_TYPES / NO_TABLE 里登记，会静默落进「未定性」——
  // 报告照常生成、照常好看，只是漏了一个域。这里让漏登记当场失败。
  it("领域模型对齐清单已生成且无未定性条目", () => {
    const doc = join(ROOT, "docs/api/领域模型对齐清单.md");
    if (!existsSync(doc)) return; // 后端源码不在的场景
    const src = readFileSync(doc, "utf8");
    expect(src, "清单是空的 —— 生成器没跑？").toContain("## 摘要");
    expect(
      src.includes("未定性，需要人看一眼"),
      "有契约实体既没映射到表、也没登记为聚合视图或已知缺表 —— " +
        "在 scripts/gen-model-align.mjs 的 ENTITY_MAP / VIEW_TYPES / NO_TABLE 里给它定性，" +
        "然后重跑 npm run gen:model-align",
    ).toBe(false);
  });

  it("根 gen:api 覆盖全部三份契约 —— 漏一个就会让它无声地陈旧", () => {
    const pkg = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"));
    const cmd: string = pkg.scripts["gen:api"] ?? "";
    for (const ws of ["ai-shop-c-app", "ai-shop-b-app", "ai-shop-ops-web"]) {
      expect(
        cmd.includes(ws),
        `gen:api 没有跑 ${ws}：它那份 spec 会停留在上一次手动生成的样子，且不会有任何提示`,
      ).toBe(true);
    }
  });
});
