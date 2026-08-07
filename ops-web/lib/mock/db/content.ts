// 素材中心 mock（P-15.1）。可见范围覆盖三种，否则"必须指定范围"这条验不到。
import type { Material } from "@/lib/types";

export const materials: Material[] = [
  { materialNo: "MA9001", title: "生鲜大促主图", kind: "IMAGE", content: "https://cdn.example.com/mat/fresh-banner.jpg", scope: "ALL", scopeRefs: [], langs: [], published: true, downloads: 34, createdAt: "2026-08-01T02:00:00Z" },
  { materialNo: "MA9002", title: "开团文案模板（微信群）", kind: "COPY", content: "【今日团】{商品名} 到货啦！团购价 {价格}，{自提点}自提，明天下午到货～", scope: "ALL", scopeRefs: [], langs: ["zh"], published: true, downloads: 128, createdAt: "2026-07-28T02:00:00Z" },
  { materialNo: "MA9003", title: "梧桐苑开城海报", kind: "POSTER", content: "https://cdn.example.com/mat/wutong-open.png", scope: "COMMUNITY", scopeRefs: ["C003"], langs: ["zh"], published: true, downloads: 12, createdAt: "2026-08-05T02:00:00Z" },
  { materialNo: "MA9004", title: "邻家便利专属短视频", kind: "VIDEO", content: "https://cdn.example.com/mat/m903-intro.mp4", scope: "MERCHANT", scopeRefs: ["M903"], langs: [], published: false, downloads: 0, createdAt: "2026-08-05T08:00:00Z" },
];
