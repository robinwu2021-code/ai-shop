import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
const state = vi.hoisted(() => ({ token: "A", logout: vi.fn(), refresh: vi.fn() }));
vi.mock("../auth", () => ({ currentAuth: () => ({ token: state.token }), scopeOf: () => ({ tenantNo: "MAIN" }), refreshPerms: state.refresh, useAuth: { getState: () => ({ logout: state.logout }) } }));
vi.mock("../notify", () => ({ notify: { error: vi.fn() } }));
vi.mock("../stores/locale", () => ({ useLocaleStore: { getState: () => ({ locale: "zh" }) } }));
vi.mock("../i18n", () => ({ translate: (_locale: string, key: string) => key, LOCALE_TAG: { zh: "zh-CN" } }));
import { client } from "./http-client";
beforeEach(() => { state.token = "A"; vi.clearAllMocks(); });
afterEach(async () => { await new Promise(resolve => setTimeout(resolve, 0)); vi.unstubAllGlobals(); });
describe("stale authentication responses", () => {
  it.each([401, 403])("keeps common B intact when old A returns %s", async status => {
    const shared = JSON.stringify({ token: "B" });
    const storage = { getItem: () => shared };
    vi.stubGlobal("window", { sessionStorage: storage });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: status }), { status })));
    await expect(client.get("/ops/auth/me")).rejects.toMatchObject({ code: status });
    expect(state.logout).not.toHaveBeenCalled(); expect(state.refresh).not.toHaveBeenCalled();
    expect(storage.getItem()).toBe(shared);
  });
  it("ignores an old request rejection after the local session changes", async () => {
    let resolve!: (response: Response) => void;
    vi.stubGlobal("window", { sessionStorage: { getItem: () => null } });
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(r => { resolve = r; })));
    const pending = client.get("/ops/auth/me"); state.token = "B";
    resolve(new Response(JSON.stringify({ code: 401 }), { status: 401 }));
    await expect(pending).rejects.toMatchObject({ code: 401 });
    expect(state.logout).not.toHaveBeenCalled();
  });
});
