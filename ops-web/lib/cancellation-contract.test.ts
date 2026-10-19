import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";

describe("取消影响OpenAPI合同", () => {
  it("成功及拒绝分支的所有嵌套引用均能从产物解析", () => {
    const doc = parse(readFileSync(new URL("../../docs/api/openapi-ops.yaml", import.meta.url), "utf8"));
    const response = doc.paths["/ops/orders/{orderNo}/cancel-impact"].get.responses["200"]
      .content["application/json"].schema.properties.data;
    expect(response.$ref).toBe("#/components/schemas/CancellationImpact");
    const visited = new Set<string>();
    function walk(value: unknown): void {
      if (!value || typeof value !== "object") return;
      for (const [key, child] of Object.entries(value)) {
        if (key !== "$ref" || typeof child !== "string") { walk(child); continue; }
        if (visited.has(child)) continue;
        visited.add(child);
        expect(child).toMatch(/^#\/components\/schemas\//);
        const target = child.slice(2).split("/").reduce((node, part) =>
          node?.[decodeURIComponent(part).replaceAll("~1", "/").replaceAll("~0", "~")], doc);
        expect(target, `unresolved reference ${child}`).toBeDefined();
        walk(target);
      }
    }
    walk(response);
    expect(visited).toContain("#/components/schemas/CancellationAffectedOrder");
    expect(visited).toContain("#/components/schemas/OrderStatus");
  });
});
