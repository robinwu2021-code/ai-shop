// 覆盖范围：素材中心（P-15.1）。
import * as db from "@/lib/mock/db";
import { MAX_RANKING_SIZE } from "@/lib/constants";
import { POST_TRANSITIONS } from "@/lib/types";
import type { Material } from "@/lib/types";
import type { ContentApi } from "../contracts/content";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

export const contentMock: ContentApi = {
  listMaterials: (q = {}) =>
    wait(
      db.paginate(db.materials, q.page, q.size, (m) =>
        db.eqHit(q.kind, m.kind) &&
        db.eqHit(q.scope, m.scope) &&
        (!q.published || (q.published === "1") === m.published) &&
        db.kwHit(q.keyword, m.materialNo, m.title, m.content),
      ),
    ),

  saveMaterial: async (v) => {
    if (!v.title?.trim()) fail("素材标题必填", "A material title is required");
    if (!v.content?.trim()) fail("素材内容必填（文案正文或文件地址）", "Material content is required — either the copy itself or a file address");
    // 「投给谁」和素材本身是一件事：范围选了 COMMUNITY/MERCHANT 却不给列表，
    // 等于这份素材谁都看不到，但列表里显示得好好的
    if (v.scope !== "ALL" && v.scopeRefs.length === 0) {
      fail(`可见范围为「${v.scope === "COMMUNITY" ? "指定社区" : "指定商家"}」时，必须选择至少一个对象`, `Scoping to ${v.scope === "COMMUNITY" ? "specific communities" : "specific merchants"} means picking at least one`);
    }
    const saved = db.upsert<Material>(
      db.materials,
      { ...v, published: false, downloads: 0, createdAt: "2026-08-06T00:00:00Z" },
      "materialNo",
      () => db.nextNo("MA", db.materials, 9000, "materialNo"),
    );
    return wait(saved, 400);
  },

  setMaterialPublished: async (materialNo, published) => {
    const m = db.materials.find((x) => x.materialNo === materialNo);
    if (!m) notFound("素材", "Material", materialNo);
    m.published = published;
    return wait(m, 400);
  },

  listPosts: (q = {}) =>
    wait(
      db.paginate(db.posts, q.page, q.size, (p) =>
        db.eqHit(q.status, p.status) &&
        // hasRisk 是"只看命中风险词的"这个诉求，不是一个普通字段筛选
        (q.hasRisk == null || q.hasRisk === "" ||
          (q.hasRisk === "1" ? p.riskHits.length > 0 : p.riskHits.length === 0)) &&
        db.kwHit(q.keyword, p.postNo, p.title, p.authorName),
      ),
    ),

  decidePost: async ({ postNo, to, remark }) => {
    const p = db.posts.find((x) => x.postNo === postNo);
    if (!p) notFound("内容", "Post", postNo);
    // 已露出过的内容退回待审等于假装没发生过，所以状态机里没有那条边
    db.assertTransition(POST_TRANSITIONS, p.status, to, "种草内容", "Post");
    if ((to === "REJECTED" || to === "OFFLINE") && !remark?.trim()) {
      fail("驳回与下架都必须写原因 —— 作者看到的就是这段话", "Rejections and takedowns both need a reason — the author sees exactly this text");
    }
    p.status = to;
    p.auditRemark = remark?.trim() || null;
    p.decidedAt = new Date().toISOString();
    p.decidedBy = "admin";
    return wait(p, 350);
  },

  batchPassPosts: async (postNos) => {
    if (!postNos.length) fail("请先勾选要通过的内容", "Select the posts to approve first");
    const picked = postNos.map((no) => {
      const p = db.posts.find((x) => x.postNo === no);
      if (!p) notFound("内容", "Post", no);
      return p;
    });

    // 命中风险词的直接拒绝整批，而不是"跳过它们"：
    // 静默跳过会让人以为全过了，下次就更敢一次勾一屏
    const risky = picked.filter((p) => p.riskHits.length > 0);
    if (risky.length) {
      fail(`${risky.map((p) => p.postNo).join("、")} 命中风险词，必须逐条查看，不能批量通过`, `${risky.map((p) => p.postNo).join(", ")} hit risk terms and must be read one by one — no bulk approval`);
    }
    for (const p of picked) db.assertTransition(POST_TRANSITIONS, p.status, "PASSED", "种草内容", "Post");

    const at = new Date().toISOString();
    for (const p of picked) {
      p.status = "PASSED";
      p.decidedAt = at;
      p.decidedBy = "admin";
    }
    return wait(picked, 500);
  },

  listRankings: async () => wait(db.rankings),

  saveRanking: async (v) => {
    if (!v.name.trim()) fail("榜单名称不能为空", "The ranking name cannot be empty");
    if (!Number.isInteger(v.size) || v.size < 1) fail("榜单条数必须是正整数", "The number of places must be a positive whole number");
    if (v.size > MAX_RANKING_SIZE) {
      fail(`榜单最多取前 ${MAX_RANKING_SIZE} 名 —— 再往下没人翻，只会拖慢首页`, `A ranking tops out at ${MAX_RANKING_SIZE} places — nobody scrolls past that and it only slows the home page`);
    }

    if (v.kind === "MANUAL") {
      if (!v.manualSkus.length) fail("人工榜必须至少选一个商品", "A hand-picked ranking needs at least one item");
      if (v.manualSkus.length > v.size) {
        fail(`已选 ${v.manualSkus.length} 个商品，超过榜单条数 ${v.size}`, `${v.manualSkus.length} items picked, more than the ${v.size} places available`);
      }
      if (new Set(v.manualSkus).size !== v.manualSkus.length) fail("同一个商品不能重复上榜", "The same item cannot appear twice");
      for (const skuNo of v.manualSkus) {
        const sku = db.skus.find((x) => x.skuNo === skuNo);
        if (!sku) notFound("商品", "Item", skuNo);
        // 下架商品进了榜，用户点进去是空页
        if (sku.status !== "ON_SALE") fail(`${sku.title.zh} 当前不可售，不能上榜`, `${sku.title.en ?? sku.title.zh} is not on sale and cannot be ranked`);
      }
    } else if (v.manualSkus.length) {
      // 传了就是调用方理解错了，拒绝而不是静默清空
      fail(`${v.kind} 榜由数据算出，不接受人工指定的商品`, `A ${v.kind} ranking is computed from data and takes no hand-picked items`);
    }

    const saved = db.upsert(
      db.rankings,
      { ...v, updatedAt: new Date().toISOString(), updatedBy: "admin" },
      "rankNo",
      () => db.nextNo("RK", db.rankings, 900, "rankNo"),
    );
    return wait(saved, 400);
  },

  setRankingEnabled: async (rankNo, enabled) => {
    const r = db.rankings.find((x) => x.rankNo === rankNo);
    if (!r) notFound("榜单", "Ranking", rankNo);
    // 空的人工榜上线就是首页开天窗
    if (enabled && r.kind === "MANUAL" && !r.manualSkus.length) {
      fail("人工榜还没有商品，上线会在首页留出一块空白", "This hand-picked ranking has no items — going live would leave a blank on the home page");
    }
    r.enabled = enabled;
    r.updatedAt = new Date().toISOString();
    r.updatedBy = "admin";
    return wait(r, 350);
  },

  listQuestions: (q = {}) =>
    wait(
      db.paginate(db.questions, q.page, q.size, (x) =>
        db.eqHit(q.status, x.status) && db.kwHit(q.keyword, x.questionNo, x.skuTitle, x.content),
      ),
    ),

  answerQuestion: async ({ questionNo, answer }) => {
    const q = db.questions.find((x) => x.questionNo === questionNo);
    if (!q) notFound("提问", "Question", questionNo);
    if (!answer.trim()) fail("回答不能为空", "The answer cannot be empty");
    // 要改先隐藏，让改动这件事本身留下痕迹
    if (q.status !== "PENDING") fail("该提问已处理，要修改请先隐藏再重新回答", "This question is already handled — to change the answer, hide it and answer again");
    q.answer = answer.trim();
    q.answeredBy = "admin";
    q.answeredAt = new Date().toISOString();
    q.status = "ANSWERED";
    return wait(q, 350);
  },

  hideQuestion: async ({ questionNo, reason }) => {
    const q = db.questions.find((x) => x.questionNo === questionNo);
    if (!q) notFound("提问", "Question", questionNo);
    if (!reason.trim()) fail("隐藏提问必须写原因，否则用户来问时没人说得清", "Hiding a question needs a reason, or nobody can explain it when the shopper asks");
    q.status = "OFFLINE";
    q.hideReason = reason.trim();
    return wait(q, 350);
  },
};
