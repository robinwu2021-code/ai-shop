import { beforeEach, describe, expect, it, vi } from "vitest";

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@shared/net/http-client", () => ({ http: { post }, clientTag: () => "test" }));
import { httpApi } from "@/api/http";

beforeEach(() => { post.mockReset(); });

describe("统一会员码 HTTP 适配合同", () => {
  it("仅向既有识别路径 POST 扫描凭证与门店，不转发调用方身份字段", async () => {
    post.mockResolvedValue({
      customerRef: "synthetic-opaque-reference",
      maskedName: "测*",
      expiresAt: "2026-10-25T08:05:00Z",
    });
    const input = {
      memberCode: "synthetic-member-code",
      storeNo: "synthetic-store",
      tenantNo: "synthetic-tenant",
      entityNo: "synthetic-entity",
      actorNo: "synthetic-actor",
      userNo: "synthetic-user",
    };

    await httpApi.mResolveMemberCode(input);

    expect(post).toHaveBeenCalledExactlyOnceWith("/biz/member-code/resolve", {
      memberCode: "synthetic-member-code",
      storeNo: "synthetic-store",
    });
  });

  it("保留后端返回的不透明顾客引用、脱敏名称和 ISO UTC 失效时间", async () => {
    post.mockResolvedValue({
      customerRef: "synthetic-opaque-reference",
      maskedName: "测*",
      expiresAt: "2026-10-25T08:05:00Z",
    });

    const result = await httpApi.mResolveMemberCode({
      memberCode: "synthetic-member-code",
      storeNo: "synthetic-store",
    });

    expect(result).toEqual({
      customerRef: "synthetic-opaque-reference",
      maskedName: "测*",
      expiresAt: "2026-10-25T08:05:00Z",
    });
  });

  it("透传传输异常，不伪造顾客引用", async () => {
    const failure = new Error("synthetic transport failure");
    post.mockRejectedValue(failure);

    await expect(httpApi.mResolveMemberCode({
      memberCode: "synthetic-member-code",
      storeNo: "synthetic-store",
    })).rejects.toBe(failure);
    expect(post).toHaveBeenCalledTimes(1);
  });
});
