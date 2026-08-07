// 种草内容 / 榜单 / 问答 mock 数据（P-15.2）。
//
// 帖子样本刻意放了两条命中风险词的：批量通过必须绕开它们，
// 这是这一域最重要的一条规则，没有样本就测不出来。
import type { Post, Question, Ranking } from "@/lib/types";

export const posts: Post[] = [
  {
    postNo: "PS901", authorType: "USER", authorName: "小满",
    title: "楼下这家菜摊的番茄真的甜", content: "买了两次，孩子当水果吃。老板还会帮忙挑。",
    communityNo: "C001", communityName: "锦绣花园", skuNo: "SKU1001",
    riskHits: [], status: "PENDING", likeCount: 12, createdAt: "2026-08-05T02:00:00Z",
  },
  {
    postNo: "PS902", authorType: "MERCHANT", authorName: "邻家便利",
    title: "全场最低价，全网独家，错过再等一年",
    content: "本店价格全网最低，假一赔十，包治百病。",
    communityNo: "C002", communityName: "阳光里", skuNo: "SKU1102",
    // 极限词与医疗暗示：这条必须有人逐字看过才能过
    riskHits: ["最低价", "全网独家", "包治百病"],
    status: "PENDING", likeCount: 3, createdAt: "2026-08-05T03:10:00Z",
  },
  {
    postNo: "PS903", authorType: "USER", authorName: "老周",
    title: "拼团买的毛豆挺新鲜", content: "三斤刚好够一周，邻居一起拼很划算。",
    communityNo: "C001", communityName: "锦绣花园", skuNo: null,
    riskHits: [], status: "PENDING", likeCount: 5, createdAt: "2026-08-05T05:20:00Z",
  },
  {
    postNo: "PS904", authorType: "USER", authorName: "阿May",
    title: "加我微信有内部价", content: "私信我，微信 xxxxx，比小程序便宜。",
    communityNo: "C002", communityName: "阳光里", skuNo: null,
    // 导流到站外：这类内容过了就等于平台替人拉客
    riskHits: ["微信", "内部价"],
    status: "PENDING", likeCount: 1, createdAt: "2026-08-05T06:00:00Z",
  },
  {
    postNo: "PS905", authorType: "USER", authorName: "海棠",
    title: "苹果很脆", content: "回购第三次了。",
    communityNo: "C001", communityName: "锦绣花园", skuNo: "SKU1004",
    riskHits: [], status: "PASSED", likeCount: 40,
    createdAt: "2026-08-01T02:00:00Z", decidedAt: "2026-08-01T06:00:00Z", decidedBy: "ops01",
  },
];

export const rankings: Ranking[] = [
  {
    rankNo: "RK901", name: "本周热销 Top 10", kind: "SALES", size: 10, manualSkus: [],
    enabled: true, updatedAt: "2026-07-01T00:00:00Z", updatedBy: "ops01",
  },
  {
    rankNo: "RK902", name: "好评榜 Top 20", kind: "RATING", size: 20, manualSkus: [],
    enabled: true, updatedAt: "2026-07-01T00:00:00Z", updatedBy: "ops01",
  },
  {
    rankNo: "RK903", name: "编辑精选", kind: "MANUAL", size: 5,
    manualSkus: ["SKU1001", "SKU1102"],
    enabled: false, updatedAt: "2026-07-25T08:00:00Z", updatedBy: "ops01",
  },
];

export const questions: Question[] = [
  {
    questionNo: "QA901", skuNo: "SKU1001", skuTitle: "本地小番茄 500g",
    content: "是当天摘的吗？", askedBy: "小满",
    status: "PENDING", createdAt: "2026-08-05T01:00:00Z",
  },
  {
    questionNo: "QA902", skuNo: "SKU1102", skuTitle: "抽纸 3 层 12 包",
    content: "一包多少抽？", askedBy: "老周",
    answer: "每包 120 抽，共 12 包。", answeredBy: "ops01", answeredAt: "2026-08-04T07:00:00Z",
    status: "ANSWERED", createdAt: "2026-08-04T06:00:00Z",
  },
  {
    questionNo: "QA903", skuNo: "SKU1004", skuTitle: "冰糖心苹果 5 斤",
    content: "加我微信，给你便宜点", askedBy: "某用户",
    status: "PENDING", createdAt: "2026-08-05T04:00:00Z",
  },
];
