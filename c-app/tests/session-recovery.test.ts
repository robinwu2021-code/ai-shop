import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useUserStore } from "@/stores/user";
import { recoverSession } from "@/shared/session-recovery";

describe("session recovery stays in the current page", () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); });
  it("stale failure never clears a fresh phone session", async () => {
    const user = useUserStore(); user.token = "fresh"; user.user = { phone: "13800138000" } as never;
    const silent = vi.spyOn(user, "silentLogin");
    expect(await recoverSession({ requestToken: "old" })).toBe(true);
    expect(user.token).toBe("fresh"); expect(silent).not.toHaveBeenCalled();
  });
  it("failed restoration clears credentials while preserving the page", async () => {
    const user = useUserStore(); user.token = "dead";
    vi.spyOn(user, "silentLogin").mockResolvedValue(false);
    expect(await recoverSession({ requestToken: "dead" })).toBe(false);
    expect(user.token).toBe(""); expect(uni.navigateTo).not.toHaveBeenCalled();
  });
  it("simultaneous failures share the same restoration", async () => {
    const user = useUserStore(); user.token = "dead";
    let finish!: (value: boolean) => void;
    const silent = vi.spyOn(user, "silentLogin").mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const first = recoverSession({ requestToken: "dead" });
    const second = recoverSession({ requestToken: "" });
    finish(true); expect(await first).toBe(true); expect(await second).toBe(true); expect(silent).toHaveBeenCalledOnce();
  });
});
