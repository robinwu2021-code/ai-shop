// @vitest-environment happy-dom
// 支付中心页签冒烟（TDD-支付中心运营页面 P1）：每个页签对着 mock 渲染出页头、表格行与行操作；
// 页签按权限码过滤；第一个行操作（查看）打开的是单层抽屉。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PAY_CENTER_TABS } from "@/lib/pay-ops/center-tabs";

let tab = "orders";
const replace = vi.fn((href: string) => { tab = new URL(href, "http://x").searchParams.get("tab") ?? ""; });
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(`tab=${tab}`),
  usePathname: () => "/payment-center/",
}));

const { useAuth } = await import("@/lib/auth");
const { default: PaymentCenterPage } = await import("@/app/payment-center/page");

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
  window.history.replaceState(null, "", "/payment-center/");
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); document.body.innerHTML = ""; });

async function render(perms: string[]) {
  useAuth.setState({ token: "t", username: "admin", role: "SUPER_ADMIN", perms } as never);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await act(async () => root.render(h(QueryClientProvider, { client: qc }, h(PaymentCenterPage))));
  // mock 有 150ms 延迟
  for (let i = 0; i < 40 && !host.querySelector("button[data-row-action]"); i++) await act(async () => { await new Promise((r) => setTimeout(r, 50)); });
}

describe("支付中心页签", () => {
  it.each(PAY_CENTER_TABS.map((t) => [t.key, t.zh]))("%s：页头、表格行与「查看」", async (key, title) => {
    tab = key;
    await render(["*"]);
    expect(host.querySelector("h1")?.textContent).toBe(title);
    expect(host.querySelectorAll("tbody tr").length).toBeGreaterThan(0);
    const view = host.querySelector('button[data-row-action="view"]') as HTMLButtonElement;
    expect(view).not.toBeNull();
    await act(async () => { view.click(); });
    expect(document.querySelectorAll('[data-surface="drawer"]')).toHaveLength(1);
  });

  it("页签按权限码过滤；请求了无权页签时落到第一个有权的", async () => {
    tab = "data-scopes";
    await render(["pay:order:view"]);
    expect(host.querySelector("h1")?.textContent).toBe("支付订单");
  });

  it("一个支付页面都没权限时给空态，不报错", async () => {
    tab = "orders";
    await render(["merchant:merchant:read"]);
    expect(host.textContent).toContain("没有支付中心任何页面的查看权限");
  });
});
