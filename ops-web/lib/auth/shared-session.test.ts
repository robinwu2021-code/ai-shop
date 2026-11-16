import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({ verify: vi.fn(), login: vi.fn(), logout: vi.fn(), clear: vi.fn(), load: vi.fn(), setState: vi.fn(), token: "" }));
vi.mock("@/lib/auth", () => ({ useAuth: { setState: mocks.setState, getState: () => ({ login: mocks.login, logout: mocks.logout, token: mocks.token }) } }));
vi.mock("@/lib/stores/server-menu", () => ({ useServerMenu: { getState: () => ({ clear: mocks.clear, load: mocks.load }) } }));
vi.mock("@/lib/api/https/dashboard", () => ({ verifySharedOpsToken: mocks.verify }));
import { ApiError } from "../api/error";
import { onSharedSessionChange, receiveSharedSession } from "./shared-session";

const KEY = "neargo-ops-auth";
describe("统一会话的接收（两端共用 neargo-ops-auth）", () => {
  let local: Map<string, string>;
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.token = "";
    local = new Map();
    const store = { getItem: (k: string) => local.get(k) ?? null, setItem: (k: string, v: string) => local.set(k, v), removeItem: (k: string) => local.delete(k) };
    vi.stubGlobal("window", {});
    vi.stubGlobal("localStorage", store);
    vi.stubGlobal("sessionStorage", { getItem: () => null, setItem: () => {}, removeItem: () => {} });
  });
  it("另一端换了人：先经后端核验再采用，伪造的角色与权限一律丢弃", async () => {
    mocks.token = "mine";
    local.set(KEY, JSON.stringify({ token: "payment-token", role: "SUPER_ADMIN", perms: ["*"] }));
    let resolve!: (value: unknown) => void;
    mocks.verify.mockImplementation(() => new Promise((r) => { resolve = r; }));
    const clearQueries = vi.fn();
    const promise = receiveSharedSession(clearQueries);
    expect(mocks.verify).toHaveBeenCalledWith("payment-token");
    expect(mocks.login).not.toHaveBeenCalled();
    const identity = { token: "payment-token", username: "actual-finance", role: "FINANCE", perms: ["settlement:view"] };
    resolve(identity);
    await promise;
    expect(clearQueries).toHaveBeenCalledOnce();
    expect(mocks.login).toHaveBeenCalledWith(identity);
    expect(mocks.load).toHaveBeenCalledOnce();
  });
  it("令牌与本页相同：不再请求", async () => {
    mocks.token = "same";
    local.set(KEY, JSON.stringify({ token: "same" }));
    await receiveSharedSession(vi.fn());
    expect(mocks.verify).not.toHaveBeenCalled();
  });
  it("共用键已被删除（另一端退出）：本页缓存作废", async () => {
    mocks.token = "stale";
    const clearQueries = vi.fn();
    await receiveSharedSession(clearQueries);
    expect(mocks.logout).toHaveBeenCalledOnce();
    expect(clearQueries).toHaveBeenCalledOnce();
  });
  it("核验被拒（401）：不采用并退出", async () => {
    local.set(KEY, JSON.stringify({ token: "invalid" }));
    mocks.verify.mockRejectedValue(new ApiError(401, "expired"));
    await receiveSharedSession(vi.fn());
    expect(mocks.login).not.toHaveBeenCalled();
    expect(mocks.logout).toHaveBeenCalledOnce();
  });
  it("网络失败保留会话，重试成功后采用", async () => {
    local.set(KEY, JSON.stringify({ token: "valid" }));
    mocks.verify.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await expect(receiveSharedSession(vi.fn())).rejects.toThrow("Failed to fetch");
    expect(mocks.logout).not.toHaveBeenCalled();
    expect(local.get(KEY)).toContain("valid");
    mocks.verify.mockResolvedValueOnce({ token: "valid", username: "finance", role: "FINANCE", perms: [] });
    await receiveSharedSession(vi.fn());
    expect(mocks.login).toHaveBeenCalledWith(expect.objectContaining({ token: "valid" }));
  });
  it("跨标签页：另一端退出 → 清缓存回登录；换了人 → 整页重载；无关的键不理会", () => {
    mocks.token = "mine";
    const clearQueries = vi.fn(), toLogin = vi.fn(), reload = vi.fn();
    onSharedSessionChange({ key: "theme" } as StorageEvent, clearQueries, toLogin, reload);
    expect(clearQueries).not.toHaveBeenCalled();
    onSharedSessionChange({ key: KEY } as StorageEvent, clearQueries, toLogin, reload);
    expect(clearQueries).toHaveBeenCalledOnce();
    expect(toLogin).toHaveBeenCalledOnce();
    local.set(KEY, JSON.stringify({ token: "someone-else" }));
    onSharedSessionChange({ key: KEY } as StorageEvent, clearQueries, toLogin, reload);
    expect(reload).toHaveBeenCalledOnce();
  });
});
