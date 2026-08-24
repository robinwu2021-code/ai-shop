/**
 * 打开小程序即登录。
 *
 * <p>`wx.login` 换 openid 微信侧不要用户确认，所以「静默」名副其实 ——
 * 老用户命中 openid 直接认出来，新用户建号，两种情况他都不知道发生过什么。
 *
 * <p>三条边界都容易做错，所以都钉住：
 * <ol>
 *   <li><b>已有 token 就不做</b> —— 会话 30 天，每次打开换一次 token 是白费，
 *       而且旧 token 会被 rotation 作废，别的端正开着的会话跟着掉线</li>
 *   <li><b>失败静默</b> —— 这只是「顺手认出他」，失败了他照样能逛</li>
 *   <li><b>其它端不做</b> —— H5/App 没有这个能力，那里必须由用户主动登录</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const login = vi.fn();
const silentPayload = vi.fn();

vi.mock("@/api", () => ({ api: { login: (...a: unknown[]) => login(...a) } }));
vi.mock("@shared/ports/auth", () => ({ silentLoginPayload: () => silentPayload() }));
vi.mock("@shared/ports/push", () => ({ getPushDevice: () => Promise.resolve(null) }));

import { useUserStore } from "@/stores/user";

describe("打开即登录", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    login.mockResolvedValue({ token: "T1", user: { nickname: "邻居1234" } });
    silentPayload.mockResolvedValue({ grantType: "WX_MINI", principal: "code-abc" });
  });

  it("★★ 没有会话 → 静默换一个，用户无感", async () => {
    const user = useUserStore();
    expect(await user.silentLogin()).toBe(true);
    expect(login).toHaveBeenCalledWith({ grantType: "WX_MINI", principal: "code-abc" });
    expect(user.token).toBe("T1");
  });

  it("★★★ 已有会话 → **不再换** —— 换会把别处正开着的会话踢掉（token rotation）", async () => {
    const user = useUserStore();
    user.token = "OLD";
    expect(await user.silentLogin()).toBe(true);
    expect(login).not.toHaveBeenCalled();
    expect(user.token).toBe("OLD");
  });

  it("★★★ force=true → 忽略手里那个 token，强制换新（401 处理器要用）", async () => {
    const user = useUserStore();
    user.token = "DEAD";

    /*
     * 「有 token」不等于「登录着」：注销之后、服务端重启之后、会话被踢之后，
     * 手里那个 token 都还在，但它已经死了。
     * 不强制换的话，401 处理器调 silentLogin 会直接返回 true，
     * 401 被吞掉 —— 人卡在拿着死令牌的假登录态里，界面还显示着旧账号。
     * 真机实测撞到过：注销后重开小程序仍然「是」那个已注销的账号，新账号根本没建。
     */
    expect(await user.silentLogin(true)).toBe(true);
    expect(login).toHaveBeenCalled();
    expect(user.token).toBe("T1");
  });

  it("★★ 端上拿不到 code（非小程序端）→ 什么都不做，不报错", async () => {
    silentPayload.mockResolvedValue(null);
    const user = useUserStore();
    expect(await user.silentLogin()).toBe(false);
    expect(login).not.toHaveBeenCalled();
  });

  it("★★ 后端拒绝 → 静默失败，不抛给启动流程", async () => {
    login.mockRejectedValue(new Error("boom"));
    const user = useUserStore();
    await expect(user.silentLogin()).resolves.toBe(false);
    expect(user.token).toBe("");
  });
});
