// 快递与运费 mock 数据（P-5.2）。
//
// 快递样本刻意覆盖了三种「运营会去看它」的情形，不是随机造的数：
// - 正常在途：什么都不用做，用来对照
// - 疑难件（EXCEPTION）：轨迹停在异常节点，运营要去联系承运商
// - 已签收：用来验证「已签收不许改运单号」那条规则
//
// 展示渠道也要覆盖到两种 + 一次失败：默认值是 self-map，三条样本都不带的话，
// 抽屉里那一行永远显示同一个字，「渠道落到哪条」这件事在 mock 下根本验不了。
import type { FreightTemplate, Shipment } from "@/lib/types";

export const shipments: Shipment[] = [
  {
    shipmentNo: "SH2026080601", orderNo: "SO2026080504", carrier: "SF", waybillNo: "SF1234567890",
    status: "IN_TRANSIT", receiver: "阿May", region: "浙江省 杭州市",
    createdAt: "2026-08-04T10:00:00Z", updatedAt: "2026-08-05T22:10:00Z",
    displayChannel: "wx-plugin",
    // 微信支付单、订阅订上、token 也换上了：「什么都不用做」的对照样本
    profile: "WX", subState: "DONE", subChannel: "kuaidi100", subAttempts: 1, bindState: "DONE",
    lastEventAt: "2026-08-05T22:10:00Z", receiverPhoneLast4: "8821",
    traces: [
      { at: "2026-08-04T10:20:00Z", text: "快件已揽收", location: "杭州转运中心" },
      { at: "2026-08-05T02:40:00Z", text: "快件已发出", location: "杭州转运中心" },
      { at: "2026-08-05T22:10:00Z", text: "快件已到达", location: "杭州西湖分部" },
    ],
  },
  {
    shipmentNo: "SH2026080602", orderNo: "SO2026080506", carrier: "JD", waybillNo: "JD9988776655",
    status: "EXCEPTION", receiver: "海棠", region: "新疆维吾尔自治区 喀什地区",
    createdAt: "2026-08-03T01:00:00Z", updatedAt: "2026-08-05T09:00:00Z",
    displayChannel: "self-map",
    displayFailReason: "9300559 运单不存在，已落回自建展示",
    // 线下付款单、订阅判死：运营要看到失败原文，再决定换渠道重放
    profile: "SELF", subState: "FATAL", subAttempts: 2, bindState: "NA",
    subError: "kuaidi100 700 快递公司编码不对", lastEventAt: "2026-08-05T09:00:00Z",
    traces: [
      { at: "2026-08-03T01:30:00Z", text: "快件已揽收", location: "杭州分拣中心" },
      { at: "2026-08-04T18:00:00Z", text: "快件已发出", location: "乌鲁木齐转运中心" },
      { at: "2026-08-05T09:00:00Z", text: "疑难件：收件人电话无法接通", location: "喀什网点" },
    ],
  },
  {
    shipmentNo: "SH2026080503", orderNo: "SO2026080505", carrier: "YTO", waybillNo: "YT5566778899",
    status: "DELIVERED", receiver: "梧桐苑 12-3", region: "浙江省 杭州市",
    createdAt: "2026-08-02T03:00:00Z", updatedAt: "2026-08-03T07:40:00Z",
    profile: "SELF", subState: "ENDED", subChannel: "kuaidi100", subAttempts: 1, bindState: "NA",
    signedAt: "2026-08-03T07:40:00Z", lastEventAt: "2026-08-03T07:40:00Z",
    traces: [
      { at: "2026-08-02T03:20:00Z", text: "快件已揽收", location: "杭州网点" },
      { at: "2026-08-03T01:10:00Z", text: "派送中", location: "杭州拱墅分部" },
      { at: "2026-08-03T07:40:00Z", text: "已签收，签收人：本人", location: "杭州拱墅分部" },
    ],
  },
];

export const freightTemplates: FreightTemplate[] = [
  {
    templateNo: "FT901", name: "默认模板（华东）",
    firstWeightGram: 1000, firstFee: 800, addWeightGram: 500, addFee: 200,
    freeThreshold: 9900, isDefault: true,
    outOfRange: [
      { region: "新疆维吾尔自治区", action: "SURCHARGE", surcharge: 2000 },
      { region: "西藏自治区", action: "REJECT", surcharge: 0 },
    ],
    updatedAt: "2026-07-20T08:00:00Z", updatedBy: "ops01",
  },
  {
    templateNo: "FT902", name: "生鲜冷链",
    firstWeightGram: 2000, firstFee: 1800, addWeightGram: 1000, addFee: 600,
    freeThreshold: 0, isDefault: false,
    outOfRange: [
      { region: "新疆维吾尔自治区", action: "REJECT", surcharge: 0 },
      { region: "青海省", action: "REJECT", surcharge: 0 },
    ],
    updatedAt: "2026-07-25T02:30:00Z", updatedBy: "ops01",
  },
];

/**
 * 第三方运力接入（P-5.2.4）。
 *
 * 三家分别对应三种状态：主力（有在途单，停不掉）、备用、以及**没配密钥的**
 * —— 最后这家用来验证「没配密钥不能启用」，没有它这条规则测不出来。
 */
export const carriers: import("@/lib/types").CarrierConfig[] = [
  {
    carrier: "SF", name: "顺丰速运", enabled: true, priority: 1,
    accountMasked: "SF-****-8821", apiKeyConfigured: true,
    pickupCutoff: "17:00", slaHours: 48,
    updatedAt: "2026-06-01T02:00:00Z", updatedBy: "ops01",
    codes: { kuaidi100: "shunfeng", wx: "SF" },
  },
  {
    carrier: "JD", name: "京东物流", enabled: true, priority: 2,
    accountMasked: "JD-****-3390", apiKeyConfigured: true,
    pickupCutoff: "16:30", slaHours: 72,
    updatedAt: "2026-06-01T02:00:00Z", updatedBy: "ops01",
    codes: { kuaidi100: "jd", wx: "JD" },
  },
  {
    carrier: "YTO", name: "圆通速递", enabled: false, priority: 3,
    accountMasked: "—", apiKeyConfigured: false,
    pickupCutoff: "18:00", slaHours: 96,
    updatedAt: "2026-07-12T08:00:00Z", updatedBy: "ops01",
    codes: { kuaidi100: "yuantong", wx: "YTO", yto: "YTO" },
  },
];

/**
 * 物流渠道总览（O4）。样本覆盖三种：全可用、凭据没配、订阅因推送不可用而被连带关掉 ——
 * 最后一种是圆通眼下的真实状态，「为什么订阅不可用」要能在页面上读出来。
 */
export const logisticsChannels: import("@/lib/types").LogisticsChannel[] = [
  {
    name: "kuaidi100", enabled: true, carriers: ["JD", "SF", "STO", "YTO", "ZTO"],
    routes: ["subscribe.default#1"],
    capabilities: [
      { capability: "SUBSCRIBE", available: true, reason: null },
      { capability: "PUSH", available: true, reason: null },
      { capability: "PROBE", available: true, reason: null },
    ],
  },
  {
    name: "wx", enabled: true, carriers: ["JD", "SF", "STO", "YTO", "ZTO"],
    routes: ["probe.default#0"],
    capabilities: [
      { capability: "PROBE", available: true, reason: null },
      { capability: "BIND", available: true, reason: null },
    ],
  },
  {
    name: "yto", enabled: true, carriers: ["YTO"],
    routes: ["subscribe.default#0"],
    capabilities: [
      { capability: "SUBSCRIBE", available: false, reason: "推送不可用，订阅随之不可用" },
      { capability: "PUSH", available: false, reason: "凭据没配" },
    ],
  },
];
