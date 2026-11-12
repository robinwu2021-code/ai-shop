import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({ verify: vi.fn(), login: vi.fn(), logout: vi.fn(), clear: vi.fn(), load: vi.fn(), setState: vi.fn() }));
vi.mock("@/lib/auth", () => ({ useAuth: { setState: mocks.setState, getState: () => ({ login: mocks.login, logout: mocks.logout }) } }));
vi.mock("@/lib/stores/server-menu", () => ({ useServerMenu: { getState: () => ({ clear: mocks.clear, load: mocks.load }) } }));
vi.mock("@/lib/api/https/dashboard", () => ({ verifySharedOpsToken: mocks.verify }));
import { ApiError } from "../api/error";
import { receiveSharedSession } from "./shared-session";

describe("shared identity reception", () => {
  let storage: Map<string, string>;
  beforeEach(() => {
    vi.clearAllMocks();
    storage = new Map();
    vi.stubGlobal("sessionStorage", { getItem: (key: string) => storage.get(key) ?? null });
  });
  it("waits for backend verification and discards forged profile and old caches", async () => {
    storage.set("neargo-ops-session", JSON.stringify({ token: "payment-token", role: "SUPER_ADMIN", perms: ["*"] }));
    let resolve!: (value: unknown) => void;
    mocks.verify.mockImplementation(() => new Promise((r) => { resolve = r; }));
    const clearQueries = vi.fn();
    const promise = receiveSharedSession(clearQueries);
    expect(mocks.verify).toHaveBeenCalledWith("payment-token");
    expect(mocks.login).not.toHaveBeenCalled();
    expect(clearQueries).not.toHaveBeenCalled();
    expect(mocks.setState).not.toHaveBeenCalled();
    const identity = { token: "payment-token", username: "actual-finance", role: "FINANCE", perms: ["settlement:view"] };
    resolve(identity);
    await promise;
    expect(clearQueries).toHaveBeenCalledOnce();
    expect(mocks.clear).toHaveBeenCalledOnce();
    expect(mocks.login).toHaveBeenCalledWith(identity);
    expect(mocks.load).toHaveBeenCalledOnce();
  });
  it("rejects invalid shared sessions without adopting their identity", async () => {
    storage.set("neargo-ops-session", JSON.stringify({ token: "invalid" }));
    mocks.verify.mockRejectedValue(new ApiError(401, "expired"));
    await receiveSharedSession(vi.fn());
    expect(mocks.login).not.toHaveBeenCalled();
    expect(mocks.logout).toHaveBeenCalledOnce();
  });
  it("does not resurrect a session cleared while verification was pending", async () => {
    storage.set("neargo-ops-session", JSON.stringify({ token: "old" }));
    mocks.verify.mockImplementation(async () => { storage.clear(); return { token: "old" }; });
    await receiveSharedSession(vi.fn());
    expect(mocks.login).not.toHaveBeenCalled();
  });
  it("preserves sessions on network failure and allows a successful retry", async () => {
    storage.set("neargo-ops-session", JSON.stringify({ token: "valid" }));
    mocks.verify.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await expect(receiveSharedSession(vi.fn())).rejects.toThrow("Failed to fetch");
    expect(mocks.logout).not.toHaveBeenCalled();
    expect(mocks.setState).not.toHaveBeenCalled();
    expect(storage.get("neargo-ops-session")).toContain("valid");
    mocks.verify.mockResolvedValueOnce({ token: "valid", username: "finance", role: "FINANCE", perms: [] });
    await receiveSharedSession(vi.fn());
    expect(mocks.login).toHaveBeenCalledWith(expect.objectContaining({ token: "valid" }));
  });

});
