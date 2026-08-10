// 评价治理 mock（P-13.1）。刷评信号刻意给了三条不同组合 —— 只给一条的话
// 「命中不等于判定」这个设计意图在页面上体现不出来。
import type { Review, ReviewAppeal, ScoreConfig } from "@/lib/types";

export const reviews: Review[] = [
  {
    reviewNo: "RV9001", orderNo: "SO2026080501", merchantNo: "M903", merchantName: "邻家便利",
    authorNickname: "小满", score: 5, scoreProduct: 5, scoreFulfill: 5, scoreService: 5,
    content: "小番茄很新鲜，到货时间也准，取货很方便。", imageCount: 2,
    status: "PENDING", riskFlags: [], createdAt: "2026-08-05T12:00:00Z",
  },
  {
    reviewNo: "RV9002", orderNo: "SO2026080506", merchantNo: "M902", merchantName: "老张水果店",
    authorNickname: "海棠", score: 1, scoreProduct: 1, scoreFulfill: 2, scoreService: 1,
    content: "苹果有两个是坏的，联系店家不理人。", imageCount: 3,
    status: "PENDING", riskFlags: [], createdAt: "2026-08-05T13:20:00Z",
  },
  {
    reviewNo: "RV9003", orderNo: "SO2026080504", merchantNo: "M903", merchantName: "邻家便利",
    authorNickname: "用户8821", score: 5, scoreProduct: 5, scoreFulfill: 5, scoreService: 5,
    content: "好评好评好评，东西很好，物流很快，服务很好。", imageCount: 0,
    // 同设备 + 文案雷同 + 短时集中：三条信号一起命中，才值得优先看
    status: "PENDING", riskFlags: ["SAME_DEVICE", "TEXT_DUP", "BURST"], createdAt: "2026-08-05T14:02:00Z",
  },
  {
    reviewNo: "RV9004", orderNo: "SO2026080502", merchantNo: "M902", merchantName: "老张水果店",
    authorNickname: "用户8822", score: 5, scoreProduct: 5, scoreFulfill: 5, scoreService: 5,
    content: "好评好评好评，东西很好，物流很快，服务很好。", imageCount: 0,
    status: "PENDING", riskFlags: ["TEXT_DUP", "SAME_IP"], createdAt: "2026-08-05T14:03:00Z",
  },
  {
    reviewNo: "RV9005", orderNo: "SO2026080505", merchantNo: "M905", merchantName: "快修家电服务",
    authorNickname: "梧桐苑 12-3", score: 4, scoreProduct: 4, scoreFulfill: 4, scoreService: 5,
    content: "师傅很专业，清洗后噪音小了不少。", imageCount: 1,
    status: "PASSED", riskFlags: [], createdAt: "2026-08-03T09:00:00Z",
  },
  {
    reviewNo: "RV9006", orderNo: "SO2026080503", merchantNo: "M901", merchantName: "阿姨家的菜摊",
    authorNickname: "老周", score: 2, scoreProduct: 2, scoreFulfill: 3, scoreService: 2,
    content: "毛豆有点老了。另外能不能加个微信，我私下订购便宜点？", imageCount: 0,
    status: "REJECTED", riskFlags: [], createdAt: "2026-08-02T10:00:00Z",
    reason: "含站外交易引导（索要微信），按社区规范下架，评分不计入",
  },
];

export const reviewAppeals: ReviewAppeal[] = [
  {
    appealNo: "AP9001", reviewNo: "RV9002", merchantNo: "M902", merchantName: "老张水果店",
    reason: "顾客反馈的坏果我们当天已全额退款并补发，差评内容里「不理人」与事实不符，附聊天记录。",
    evidenceCount: 3, status: "PENDING", submittedAt: "2026-08-05T15:00:00Z",
  },
  {
    appealNo: "AP9002", reviewNo: "RV9006", merchantNo: "M901", merchantName: "阿姨家的菜摊",
    reason: "这条评价我们已经处理过了，希望恢复显示以便其他顾客看到我们的回复。",
    evidenceCount: 0, status: "REJECTED", submittedAt: "2026-08-03T02:00:00Z",
    verdict: "原评价因含站外交易引导被下架，与商家服务质量无关，不予恢复。",
  },
];

// 单例配置：mock 用可变对象承载，保存后重新读取应拿到新值。
export const scoreConfig: ScoreConfig = {
  weightProduct: 50,
  weightFulfill: 30,
  weightService: 20,
  newMerchantProtectDays: 30,
  decayHalfLifeDays: 90,
  updatedAt: "2026-07-25T03:00:00Z",
  updatedBy: "admin",
};
