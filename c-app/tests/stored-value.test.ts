import { describe, expect, it, vi } from "vitest";
import { createStoredValuePager } from "@/shared/stored-value-pager";
import { reportStoredValueSubscription } from "@/shared/stored-value-subscription";
const page = (records: string[], nextCursor: string | null = null) => ({ records, nextCursor, hasMore: !!nextCursor });
describe("储值查询请求顺序与分页", () => {
  it("丢弃慢首屏和慢翻页，翻页失败保留数据并可重试", async () => {
    let finishOld!: (value: ReturnType<typeof page>) => void;
    const fetch = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }))
      .mockResolvedValueOnce(page(["new"], "next"))
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(page(["more"]));
    const pager = createStoredValuePager<string, { type: string }, ReturnType<typeof page>>(fetch);
    const old = pager.reset({ type: "ALL" });
    await pager.reset({ type: "RECHARGE" });
    finishOld(page(["stale"])); await old;
    expect(pager.state.records).toEqual(["new"]);
    await pager.more();
    expect(pager.state.records).toEqual(["new"]);
    expect(pager.state.moreFailed).toBe(true);
    await pager.more();
    expect(fetch).toHaveBeenLastCalledWith({ type: "RECHARGE" }, "next");
    expect(pager.state.records).toEqual(["new", "more"]);
    expect(pager.state.moreFailed).toBe(false);
  });
  it("筛选变化使在途翻页失效，旧finally不覆盖新loading", async () => {
    let finishMore!: (value: ReturnType<typeof page>) => void;
    const fetch = vi.fn().mockResolvedValueOnce(page(["first"], "c"))
      .mockImplementationOnce(() => new Promise(resolve => { finishMore = resolve; }))
      .mockResolvedValueOnce(page(["filtered"]));
    const pager = createStoredValuePager<string, { type: string }, ReturnType<typeof page>>(fetch);
    await pager.reset({ type: "ALL" });
    const more = pager.more();
    await pager.reset({ type: "REDEMPTION" });
    finishMore(page(["wrong"])); await more;
    expect(pager.state.records).toEqual(["filtered"]);
    expect(pager.state.loading).toBe(false);
  });
  it("初载失败不伪装为空态，保存错误码供不可访问处理", async () => {
    const fetch = vi.fn().mockRejectedValue({ code: 10404 });
    const pager = createStoredValuePager<string, {}, ReturnType<typeof page>>(fetch);
    await pager.reset({});
    expect(pager.state.failed).toBe(true);
    expect(pager.state.errorCode).toBe(10404);
  });
});
describe("消费通知真实授权结果", () => {
  it("只上报微信明确的accept/reject/ban，不将未知或失败视为授权", async () => {
    const report = vi.fn();
    expect(await reportStoredValueSubscription("real", { real: "accept" }, report)).toBe("accepted");
    expect(report).toHaveBeenLastCalledWith(["real"], true);
    expect(await reportStoredValueSubscription("real", { real: "reject" }, report)).toBe("rejected");
    expect(report).toHaveBeenLastCalledWith(["real"], false);
    report.mockClear();
    expect(await reportStoredValueSubscription("real", {}, report)).toBe("unknown");
    expect(report).not.toHaveBeenCalled();
  });
});
