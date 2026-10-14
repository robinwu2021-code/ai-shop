// 登录响应的形状映射。
//
// 这条测试的来历：后端 `/ops/auth/login` 返回 `{token, staff:{username, roles:[…]}}`，
// 而 ops-web 用的是扁平的 `{username, role, token}`。http 层此前**直接把响应当
// LoginResp 返回**，于是 `role` 恒为 undefined —— 而权限判定读的是 `ROLE_PERMS[role]`，
// 连真后端登录后运营端所有按钮都会失效。
//
// mock 下 login 返回的 role 是正常的，所以开发期完全看不出来 ——
// 「mock 好、真后端坏」是这个仓库反复出问题的形状，这里用一条测试把它钉住。
import { describe, expect, it, vi, beforeEach } from "vitest";

vi.mock("../http-client", () => ({
  client: { post: vi.fn(), get: vi.fn() },
}));

import { client } from "../http-client";
import { dashboardHttp } from "./dashboard";

const post = client.post as unknown as ReturnType<typeof vi.fn>;

/** 后端真实响应的形状（照 OpsVOs.LoginResultVO / StaffVO 抄的） */
function backendResponse(roles: string[]) {
  return {
    token: "otk_test",
    staff: {
      staffNo: "ST-1",
      username: "bd",
      realName: "招商小王",
      roles,
      perms: ["merchant:audit", "order:view"],
      status: "ACTIVE",
    },
  };
}

describe("运营登录：后端响应 → ops-web 的 LoginResp", () => {
  beforeEach(() => post.mockReset());

  it("★ 把嵌套的 staff.roles 摊平成 role —— 不摊平的话它恒为 undefined", async () => {
    post.mockResolvedValue(backendResponse(["BD"]));
    const r = await dashboardHttp.login("bd", "bd123");

    expect(r.token).toBe("otk_test");
    expect(r.username).toBe("bd");
    expect(r.role, "role 是 undefined 时 ROLE_PERMS[role] 也是 undefined，全端按钮失效").toBeDefined();
  });

  it("★★ 后端角色码翻译成 ops-web 的 —— 两端不同名", async () => {
    const cases: [string, string][] = [
      ["SUPER_ADMIN", "SUPER_ADMIN"],
      ["BD", "MERCHANT_BD"],
      ["GOODS_OPS", "PRODUCT_OPS"],
      ["SUPPORT", "CS"],
    ];
    for (const [backend, ops] of cases) {
      post.mockResolvedValue(backendResponse([backend]));
      const r = await dashboardHttp.login("x", "y");
      expect(r.role, `后端 ${backend} 应映射成 ${ops}`).toBe(ops);
    }
  });

  it("★★ 认不出的角色落到最小权限，**不是**超管", async () => {
    post.mockResolvedValue(backendResponse(["SOMETHING_NEW"]));
    const r = await dashboardHttp.login("x", "y");
    expect(r.role, "认不出角色时给全权，是这类映射最坏的失败方式").not.toBe("SUPER_ADMIN");
  });

  it("★ 没有 staff 字段也不能炸 —— 后端换形状时要退化，不是白屏", async () => {
    post.mockResolvedValue({ token: "t" });
    const r = await dashboardHttp.login("someone", "pw");
    expect(r.token).toBe("t");
    expect(r.username).toBe("someone");
    expect(r.role).not.toBe("SUPER_ADMIN");
  });
});
