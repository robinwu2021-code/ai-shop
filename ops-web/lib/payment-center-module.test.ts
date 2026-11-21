import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { expect, it } from "vitest";

/**
 * 支付中心是独立模块（TDD-支付中心运营页面 §模块边界）：
 * - 页面、接口客户端（_api/）、组件（_kit/）都在 app/payment-center/ 下；
 * - 模块外只许 import `@/app/payment-center/module`（页签登记、旧书签转发）——
 *   别处要用支付数据就进支付中心，不在商户、订单页里再挂一份；
 * - 模块内不 import 其它业务页（`@/app/<别的>`）。共用的只走 components/ui、lib 这些基础层。
 * `app/dev/` 是组件画廊，可以引模块内部来演示。测试文件不受限。
 */
const ROOT = join(__dirname, "..");
const MODULE = "app/payment-center/";
const PUBLIC = "@/app/payment-center/module";

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (name === "node_modules" || name === "out" || name.startsWith(".")) return [];
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}
const sources = ["app", "components", "lib"].flatMap((d) => walk(join(ROOT, d)))
  .filter((f) => /\.(ts|tsx)$/.test(f) && !/\.test\.ts$/.test(f))
  .map((f) => ({ rel: relative(ROOT, f), text: readFileSync(f, "utf8") }));
const imports = (text: string) => [...text.matchAll(/from\s+["']([^"']+)["']/g)].map((m) => m[1]);

it("outside the payment-center module, only its public module file is imported", () => {
  const offenders = sources
    .filter((s) => !s.rel.startsWith(MODULE) && !s.rel.startsWith("app/dev/"))
    .flatMap((s) => imports(s.text).filter((i) => i.startsWith("@/app/payment-center") && i !== PUBLIC).map((i) => `${s.rel} → ${i}`));
  expect(offenders).toEqual([]);
});

it("the payment-center module does not import other business pages", () => {
  const offenders = sources
    .filter((s) => s.rel.startsWith(MODULE))
    .flatMap((s) => imports(s.text).filter((i) => i.startsWith("@/app/") && !i.startsWith("@/app/payment-center")).map((i) => `${s.rel} → ${i}`));
  expect(offenders).toEqual([]);
});

it("no payment page jumps out to the near-pay ops console any more", () => {
  const offenders = sources
    .filter((s) => s.rel.startsWith(MODULE))
    .filter((s) => /paymentWorkspaceHref|shareOpsToken|PAYMENT_WORKSPACES/.test(s.text))
    .map((s) => s.rel);
  expect(offenders).toEqual([]);
});
