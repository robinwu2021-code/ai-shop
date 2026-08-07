// 覆盖范围：见 contracts/content.ts。
import { client } from "../http-client";
import type { ContentApi } from "../contracts/content";

export const contentHttp: ContentApi = {
  listMaterials: (q) => client.get("/ops/materials", q),
  saveMaterial: (v) => client.post("/ops/materials", v),
  setMaterialPublished: (no, published) => client.post(`/ops/materials/${no}/published`, { published }),
  listPosts: (q) => client.get("/ops/contents/posts", q),
  decidePost: (v) => client.post(`/ops/contents/posts/${v.postNo}/decide`, v),
  batchPassPosts: (postNos) => client.post("/ops/contents/posts/batch-pass", { postNos }),
  listRankings: () => client.get("/ops/contents/rankings"),
  saveRanking: (v) => client.post("/ops/contents/rankings", v),
  setRankingEnabled: (rankNo, enabled) => client.post(`/ops/contents/rankings/${rankNo}/enabled`, { enabled }),
  listQuestions: (q) => client.get("/ops/contents/questions", q),
  answerQuestion: (v) => client.post(`/ops/contents/questions/${v.questionNo}/answer`, v),
  hideQuestion: (v) => client.post(`/ops/contents/questions/${v.questionNo}/hide`, v),
};
