// 存储空间治理 mock 数据（TDD-图片存储与空间回收 §L3-7）。
//
// 这份数据刻意把**三类垃圾**各摆一条，因为页面上最要紧的一列是「可回收理由」，
// 而只有真的存在这三种理由，页面才试得出来：
//   · 从未被引用   商家传了图没点保存
//   · 被替换掉     商品图从 A 换成 B
//   · 主体级存量   门店维度出现之前传的，归属不明
import type { MediaPurgeBatch, MediaReclaimable, MediaStoreUsage } from "@/lib/types";

export const mediaStoreUsage: MediaStoreUsage[] = [
  // 已下线很久的店：待回收占了绝大部分 —— 这一行就是这个页面要找的东西
  { storeNo: "ST202601001", entityNo: "M202601001", count: 1204, activeBytes: 96_000_000, reclaimableBytes: 612_000_000 },
  { storeNo: "ST202601002", entityNo: "M202601002", count: 3882, activeBytes: 1_400_000_000, reclaimableBytes: 381_000_000 },
  // 主体级：证件，以及门店维度出现之前的存量图
  { storeNo: "_ENTITY", entityNo: "M202601001", count: 46, activeBytes: 22_000_000, reclaimableBytes: 3_100_000 },
];

export const mediaReclaimable: MediaReclaimable[] = [
  {
    assetKey: "M202601001/ST202601001/goods/202603/9f2c1b7e4a51d0c3.png",
    entityNo: "M202601001", storeNo: "ST202601001", bizType: "GOODS",
    bytes: 412_000, width: 1200, height: 1200,
    uploadedBy: "M202601001", createdAt: "2026-03-12T09:12:00", markedAt: "2026-08-14T03:20:00",
    reason: "曾被「商品 · 主图（G202603001）」引用，2026-06-04T11:02:00 后失去引用",
    status: "RECLAIMABLE",
  },
  {
    assetKey: "M202601001/ST202601001/goods/202607/3ad81f60c2ee4b19.png",
    entityNo: "M202601001", storeNo: "ST202601001", bizType: "GOODS",
    bytes: 288_000, width: 900, height: 900,
    uploadedBy: "M202601001", createdAt: "2026-07-30T16:40:00", markedAt: "2026-08-14T03:20:00",
    reason: "从未被引用",
    status: "RECLAIMABLE",
  },
  {
    assetKey: "M202601001/legacy-7c1e9a20.png",
    entityNo: "M202601001", storeNo: "_ENTITY", bizType: "GOODS",
    bytes: 1_040_000, width: 1600, height: 1200,
    uploadedBy: null, createdAt: "2025-11-02T10:05:00", markedAt: "2026-08-14T03:20:00",
    reason: "从未被引用",
    status: "RECLAIMABLE",
  },
];

export const mediaBatches: MediaPurgeBatch[] = [
  {
    batchNo: "MP2026081003211642", operator: "ST-ADMIN", operatorName: "超级管理员",
    status: "DONE", totalCount: 137, totalBytes: 412_000_000,
    purgedCount: 137, failedCount: 0,
    startedAt: "2026-08-10T03:21:16", finishedAt: "2026-08-10T03:22:04", createdAt: "2026-08-10T03:21:16",
  },
  {
    // 部分失败：失败的那几张仍留在批次里，运营点一下重试
    batchNo: "MP2026080614025518", operator: "ST-ADMIN", operatorName: "超级管理员",
    status: "PARTIAL", totalCount: 42, totalBytes: 88_000_000,
    purgedCount: 40, failedCount: 2,
    startedAt: "2026-08-06T14:02:55", finishedAt: "2026-08-06T14:03:11", createdAt: "2026-08-06T14:02:55",
  },
];
