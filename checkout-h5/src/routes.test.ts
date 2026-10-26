import { describe, expect, it } from "vitest";
import { match, ROUTES } from "./routes";

describe("路由", () => {
  it("★★ 五条路由各自命中，参数解码；多一段少一段都不命中", () => {
    expect(match("/c/abc_-123", "")?.route.view).toBe("pay");
    expect(match("/c/abc", "")?.params.token).toBe("abc");
    expect(match("/checkout/PYO1", "?t=tok")?.query.get("t")).toBe("tok");
    expect(match("/q/QR1", "")?.params.code).toBe("QR1");
    expect(match("/result/PYO1", "")?.route.view).toBe("result");
    expect(match("/return/PYO1", "")?.route.view).toBe("return");
    expect(match("/c", "")).toBeNull();
    expect(match("/c/a/b", "")).toBeNull();
    expect(match("/", "")).toBeNull();
  });

  it("★ 每条路由有清单用的页名（gen-ui-catalog.py 读这张表）", () => {
    for (const r of ROUTES) expect(r.title.length).toBeGreaterThan(0);
  });
});
