import { describe, expect, it, vi } from "vitest";
import { acceptLoginResult } from "./login-result";
import type { LoginResp } from "../types";
const identity: LoginResp = { username: "finance", role: "FINANCE", perms: ["settlement:view"], token: "verified-token" };
describe("two-stage operations login", () => {
  it("never commits a token or navigates while a challenge is pending", () => {
    const challenge = vi.fn(); const commitAndReturn = vi.fn();
    acceptLoginResult({ ...identity, token: "", challenge: "C1", challengeTo: "a***@example.test" }, challenge, commitAndReturn);
    expect(challenge).toHaveBeenCalledWith("C1", "a***@example.test");
    expect(commitAndReturn).not.toHaveBeenCalled();
  });
  it("requires a non-empty session token before sharing or returning", () => {
    const commitAndReturn = vi.fn();
    expect(() => acceptLoginResult({ ...identity, token: "" }, vi.fn(), commitAndReturn)).toThrow();
    expect(commitAndReturn).not.toHaveBeenCalled();
  });
  it("commits the verified identity only after a completed direct or second-factor response", () => {
    const commitAndReturn = vi.fn(); const challenge = vi.fn();
    acceptLoginResult(identity, challenge, commitAndReturn);
    expect(commitAndReturn).toHaveBeenCalledWith(identity);
    expect(challenge).not.toHaveBeenCalled();
  });
});
