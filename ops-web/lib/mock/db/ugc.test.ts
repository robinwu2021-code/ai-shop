// 种草内容 / 榜单 / 问答的规则测试（P-15.2）。
//
// 最要紧的一条：**命中风险词的内容不能批量通过**。批量 + 风险内容 = 事故，
// 而且拒绝整批而不是静默跳过 —— 静默跳过会让人以为全过了。
import { beforeEach, describe, expect, it } from "vitest";
import { contentMock } from "@/lib/api/mocks/content";
import { posts, questions, rankings, skus } from "@/lib/mock/db";
import { MAX_RANKING_SIZE } from "@/lib/constants";

const pSnap = posts.map((p) => ({ ...p, riskHits: [...p.riskHits] }));
const rSnap = rankings.map((r) => ({ ...r, manualSkus: [...r.manualSkus] }));
const qSnap = questions.map((q) => ({ ...q }));
const sSnap = skus.map((s) => ({ ...s }));

beforeEach(() => {
  posts.splice(0, posts.length, ...pSnap.map((p) => ({ ...p, riskHits: [...p.riskHits] })));
  rankings.splice(0, rankings.length, ...rSnap.map((r) => ({ ...r, manualSkus: [...r.manualSkus] })));
  questions.splice(0, questions.length, ...qSnap.map((q) => ({ ...q })));
  skus.splice(0, skus.length, ...sSnap.map((s) => ({ ...s })));
});

describe("种草内容审核（P-15.2.1）", () => {
  it("**命中风险词的不能批量通过**，而且是拒绝整批 —— 静默跳过会让人以为全过了", async () => {
    const clean = posts.find((p) => p.status === "PENDING" && !p.riskHits.length)!;
    const risky = posts.find((p) => p.status === "PENDING" && p.riskHits.length)!;
    await expect(contentMock.batchPassPosts([clean.postNo, risky.postNo])).rejects.toThrow(/命中风险词/);
    // 整批被拒：干净的那条也不能悄悄过掉
    expect(posts.find((p) => p.postNo === clean.postNo)!.status).toBe("PENDING");
  });

  it("全是干净内容时批量通过生效", async () => {
    const clean = posts.filter((p) => p.status === "PENDING" && !p.riskHits.length);
    expect(clean.length).toBeGreaterThan(1); // 样本不足这条就是空转
    const r = await contentMock.batchPassPosts(clean.map((p) => p.postNo));
    expect(r.every((p) => p.status === "PASSED")).toBe(true);
    expect(r.every((p) => p.decidedBy === "admin")).toBe(true);
  });

  it("空勾选要报错，而不是「成功通过 0 条」", async () => {
    await expect(contentMock.batchPassPosts([])).rejects.toThrow(/先勾选/);
  });

  it("驳回必须写原因（原样回作者）", async () => {
    const p = posts.find((x) => x.status === "PENDING")!;
    await expect(contentMock.decidePost({ postNo: p.postNo, to: "REJECTED" })).rejects.toThrow(/必须写原因/);
  });

  it("**已通过的不能改回待审** —— 内容已经露出过，退回待审等于假装没发生过", async () => {
    const passed = posts.find((p) => p.status === "PASSED")!;
    await expect(contentMock.decidePost({ postNo: passed.postNo, to: "PENDING" })).rejects.toThrow(/不允许从/);
  });

  it("已通过的只能走下架，且下架也要写原因", async () => {
    const passed = posts.find((p) => p.status === "PASSED")!;
    await expect(contentMock.decidePost({ postNo: passed.postNo, to: "OFFLINE" })).rejects.toThrow(/必须写原因/);
    const p = await contentMock.decidePost({
      postNo: passed.postNo, to: "OFFLINE", remark: "关联商品已下架，内容同步下线",
    });
    expect(p.status).toBe("OFFLINE");
  });

  it("按「只看命中风险词」筛选", async () => {
    const risky = await contentMock.listPosts({ hasRisk: "1", size: 100 });
    expect(risky.records.length).toBeGreaterThan(0);
    expect(risky.records.every((p) => p.riskHits.length > 0)).toBe(true);
  });
});

describe("榜单（P-15.2.2）", () => {
  const manual = () => {
    const r = rankings.find((x) => x.kind === "MANUAL")!;
    return { ...r, manualSkus: [...r.manualSkus] };
  };
  const auto = () => {
    const r = rankings.find((x) => x.kind !== "MANUAL")!;
    return { ...r, manualSkus: [...r.manualSkus] };
  };

  it("**算出来的榜带 manualSkus 要报错**，而不是静默清空 —— 传了就是调用方理解错了", async () => {
    await expect(
      contentMock.saveRanking({ ...auto(), manualSkus: ["SKU1001"] }),
    ).rejects.toThrow(/不接受人工指定/);
  });

  it("人工榜必须至少选一个商品", async () => {
    await expect(contentMock.saveRanking({ ...manual(), manualSkus: [] })).rejects.toThrow(/至少选一个/);
  });

  it("人工榜的商品数不能超过榜单条数", async () => {
    await expect(
      contentMock.saveRanking({ ...manual(), size: 1, manualSkus: ["SKU1001", "SKU1102"] }),
    ).rejects.toThrow(/超过榜单条数/);
  });

  it("同一个商品不能重复上榜", async () => {
    await expect(
      contentMock.saveRanking({ ...manual(), manualSkus: ["SKU1001", "SKU1001"] }),
    ).rejects.toThrow(/重复上榜/);
  });

  it("**下架商品不能上榜** —— 用户点进去是空页", async () => {
    const off = skus.find((s) => s.status !== "ON_SALE")!;
    await expect(
      contentMock.saveRanking({ ...manual(), manualSkus: [off.skuNo] }),
    ).rejects.toThrow(/不可售/);
  });

  it(`榜单最多取前 ${MAX_RANKING_SIZE} 名`, async () => {
    await expect(
      contentMock.saveRanking({ ...auto(), size: MAX_RANKING_SIZE + 1 }),
    ).rejects.toThrow(/最多取前/);
  });

  it("空的人工榜不能上线 —— 首页会开天窗", async () => {
    const r = rankings.find((x) => x.kind === "MANUAL")!;
    r.manualSkus = [];
    await expect(contentMock.setRankingEnabled(r.rankNo, false)).resolves.toBeTruthy(); // 下线随时可以
    await expect(contentMock.setRankingEnabled(r.rankNo, true)).rejects.toThrow(/留出一块空白/);
  });
});

describe("问答（P-15.2.3）", () => {
  it("回答不能为空", async () => {
    const q = questions.find((x) => x.status === "PENDING")!;
    await expect(contentMock.answerQuestion({ questionNo: q.questionNo, answer: " " })).rejects.toThrow(/不能为空/);
  });

  it("**已回答的不能再答** —— 要改先隐藏，让改动这件事本身留下痕迹", async () => {
    const answered = questions.find((x) => x.status === "ANSWERED")!;
    await expect(
      contentMock.answerQuestion({ questionNo: answered.questionNo, answer: "换个答案" }),
    ).rejects.toThrow(/已处理/);
  });

  it("正常回答落库并留痕", async () => {
    const q = questions.find((x) => x.status === "PENDING")!;
    const r = await contentMock.answerQuestion({ questionNo: q.questionNo, answer: "当天清晨采摘，下午到点。" });
    expect(r.status).toBe("ANSWERED");
    expect(r.answeredBy).toBe("admin");
  });

  it("隐藏要写原因", async () => {
    const q = questions.find((x) => x.status === "PENDING")!;
    await expect(contentMock.hideQuestion({ questionNo: q.questionNo, reason: "" })).rejects.toThrow(/原因/);
    const r = await contentMock.hideQuestion({ questionNo: q.questionNo, reason: "站外导流" });
    expect(r.status).toBe("OFFLINE");
    expect(r.hideReason).toBe("站外导流");
  });
});
