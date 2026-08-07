// 覆盖范围：素材中心（P-15.1）、种草内容审核与榜单问答（P-15.2）。
import type { Material, Page, Post, PostStatus, Question, Ranking } from "@/lib/types";
import type { MaterialQ, PageQ } from "../query";

export interface ContentApi {
  listMaterials(q?: MaterialQ): Promise<Page<Material>>;
  /**
   * 保存素材（P-15.1.1–15.1.4）。
   * 必须指定可见范围；指定社区/商家时列表不能为空 —— 「投给谁」和素材本身是一件事。
   */
  saveMaterial(v: Pick<Material, "materialNo" | "title" | "kind" | "content" | "scope" | "scopeRefs" | "langs">): Promise<Material>;
  setMaterialPublished(materialNo: string, published: boolean): Promise<Material>;

  // ── 种草内容审核（P-15.2.1）──────────────────────────────────

  listPosts(q?: PageQ & { status?: string; hasRisk?: string }): Promise<Page<Post>>;

  /**
   * 裁决一条种草内容。
   *
   * - 驳回与下架**都必须写原因**：原样回作者，不写等于让人猜；
   * - `PASSED → OFFLINE` 是单独一条路，不能"改回待审" ——
   *   内容已经露出过、可能已被引用，退回待审等于假装没发生过。
   */
  decidePost(v: { postNo: string; to: PostStatus; remark?: string }): Promise<Post>;

  /**
   * 批量通过。
   *
   * ⚠️ **命中风险词的内容一律不进批量** —— 批量 + 风险内容 = 事故。
   * 传进来的单子里若含命中项，直接抛错而不是"跳过它们"：
   * 静默跳过会让人以为全过了。
   */
  batchPassPosts(postNos: string[]): Promise<Post[]>;

  // ── 榜单与问答（P-15.2.2 / 15.2.3）──────────────────────────

  listRankings(): Promise<Ranking[]>;

  /**
   * 保存榜单。
   *
   * `MANUAL` 与算出来的三类校验路径完全不同：
   * - `MANUAL` 必须有条目，且条目数不超过 `size`，商品必须**在售**
   *   （下架商品进了榜，用户点进去是空页）；
   * - 非 `MANUAL` 带了 `manualSkus` 直接抛错 —— 传了就是调用方理解错了。
   */
  saveRanking(v: Omit<Ranking, "updatedAt" | "updatedBy" | "rankNo"> & { rankNo?: string }): Promise<Ranking>;
  setRankingEnabled(rankNo: string, enabled: boolean): Promise<Ranking>;

  listQuestions(q?: PageQ & { status?: string }): Promise<Page<Question>>;

  /** 回答。**已回答的不能再答** —— 要改先隐藏，让改动这件事本身留下痕迹。 */
  answerQuestion(v: { questionNo: string; answer: string }): Promise<Question>;
  /** 隐藏提问（如导流、辱骂）。同样要写原因。 */
  hideQuestion(v: { questionNo: string; reason: string }): Promise<Question>;
}
