// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), success: vi.fn() }));
vi.mock("@/lib/notify", () => ({ notify: { success: http.success } }));
vi.mock("@/lib/api/http-client", () => ({ client: http }));
vi.mock("@/lib/api", async () => {
  const { iamHttp } = await import("@/lib/api/https/iam");
  return { api: iamHttp };
});
vi.mock("@/lib/use-can", () => ({ useCan: () => () => true }));
vi.mock("@/lib/auth", () => ({ useAuth: (select: (state: { username: string }) => unknown) => select({ username: "admin@neargo.ai" }) }));
vi.mock("@/lib/use-page-tab", () => ({ usePageTab: () => ["staffs", vi.fn()], useNavTabs: () => [] }));
vi.mock("@/lib/use-paging", () => ({ usePaging: () => ({ page: 1, size: 10, setPage: vi.fn(), setSize: vi.fn() }) }));
vi.mock("@/lib/i18n", () => ({ useI18n: () => ({ locale: "zh", t: (key: string) => ({
  "role.CS": "客服", "role.ANALYST": "数据分析", "role.MERCHANT_BD": "商家运营",
}[key] ?? key) }) }));
vi.mock("@/components/ui/tab-header", () => ({ TabHeader: () => null }));
vi.mock("@/components/ui/drawer", () => ({
  Drawer: ({ open, children, footer }: { open: boolean; children: unknown; footer: unknown }) => open ? h("aside", {}, children as never, footer as never) : null,
  Field: ({ children }: { children: unknown }) => h("div", {}, children as never),
}));

import IamPage from "@/app/iam/page";
let host: HTMLDivElement, root: Root, query: QueryClient;
const staff = (staffNo: string, name: string, roles: string[]) => ({ staffNo, username: `${staffNo}@neargo.ai`, realName: name, roles, status: "ACTIVE" });
const role = (roleCode: string, name: string, builtin = false) => ({ roleCode, name, builtin, endCode: "OPS", pointCount: 3, staffCount: 1 });
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  http.get.mockReset(); http.post.mockReset(); http.success.mockReset();
  http.get.mockImplementation(async (path: string) => path === "/ops/staffs" ? {
    records: [staff("review", "审核员", ["STORE_APPLY_REVIEW", "SUPPORT"]), staff("legacy", "旧配置员", ["NP_IT_CONFIG"])], total: 2, page: 1, size: 10,
  } : path === "/ops/perm/roles" ? [role("STORE_APPLY_REVIEW", "入驻审核"), role("SUPPORT", "客服", true)] : []);
  http.post.mockImplementation(async (_path: string, body: { roles: string[] }) => staff("review", "审核员", body.roles));
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });

it("shows custom role names and unknown raw codes while keeping canonical labels and raw multi-role edits", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(IamPage))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)); });
  const rows = [...host.querySelectorAll("tbody tr")];
  expect(rows[0]?.textContent).toContain("入驻审核");
  expect(rows[0]?.textContent).toContain("客服");
  expect(rows[1]?.textContent).toContain("NP_IT_CONFIG");
  expect(host.querySelector("tbody")?.textContent).not.toContain("数据分析");
  await act(async () => [...rows[0]!.querySelectorAll("button")].find((button) => button.textContent === "编辑")!.click());
  // SUPPORT must be selected on opening. Removing it should preserve the custom role unchanged.
  await act(async () => [...host.querySelectorAll("aside button")].find((button) => button.textContent === "客服")!.click());
  await act(async () => [...host.querySelectorAll("aside button")].find((button) => button.textContent === "保存")!.click());
  expect(http.post).toHaveBeenCalledWith("/ops/staffs/review/roles", { roles: ["STORE_APPLY_REVIEW"] });
});

async function createStaffFromPage(initialPassword: string | null, deliveredTo: string | null) {
  http.post.mockResolvedValue({ staff: staff("created", "新审核员", ["STORE_APPLY_REVIEW"]), initialPassword, deliveredTo });
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(IamPage))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)); });
  await act(async () => [...host.querySelectorAll("button")].find((button) => button.textContent === "新建员工")!.click());
  await act(async () => {
    for (const [id, value] of [["ns-username", "created@neargo.ai"], ["ns-name", "新审核员"]]) {
      const input = host.querySelector(`#${id}`) as HTMLInputElement;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }
  });
  await act(async () => [...host.querySelectorAll("aside button")].find((button) => button.textContent === "入驻审核")!.click());
  await act(async () => [...host.querySelectorAll("aside button")].find((button) => button.textContent === "保存")!.click());
  expect(http.post).toHaveBeenCalledWith("/ops/staffs", { username: "created@neargo.ai", realName: "新审核员", roles: ["STORE_APPLY_REVIEW"] });
}

it("shows the one-time password only when response mode actually returns one", async () => {
  await createStaffFromPage("once-only-fixture", null);
  expect(host.querySelector("aside code")?.textContent).toBe("once-only-fixture");
  expect(http.success).not.toHaveBeenCalled();
});

it("mail mode shows the masked destination without opening an empty password drawer", async () => {
  await createStaffFromPage(null, "c***@neargo.ai");
  expect(host.querySelector("aside")).toBeNull();
  expect(http.success).toHaveBeenCalledWith("员工已创建；初始密码邮件已提交发送至 c***@neargo.ai，请本人查收。");
});
