// 消息与客服规则测试（P-14）。
import { beforeEach, describe, expect, it } from "vitest";
import { messageMock } from "@/lib/api/mocks/message";
import { faqs, msgTemplates, notifyQuota, pushTasks, tickets } from "./message";

const T0 = JSON.parse(JSON.stringify(msgTemplates)) as typeof msgTemplates;
const P0 = JSON.parse(JSON.stringify(pushTasks)) as typeof pushTasks;
const K0 = JSON.parse(JSON.stringify(tickets)) as typeof tickets;
const F0 = JSON.parse(JSON.stringify(faqs)) as typeof faqs;
const Q0 = { ...notifyQuota };
beforeEach(() => {
  msgTemplates.length = 0; msgTemplates.push(...(JSON.parse(JSON.stringify(T0)) as typeof msgTemplates));
  pushTasks.length = 0; pushTasks.push(...(JSON.parse(JSON.stringify(P0)) as typeof pushTasks));
  tickets.length = 0; tickets.push(...(JSON.parse(JSON.stringify(K0)) as typeof tickets));
  faqs.length = 0; faqs.push(...(JSON.parse(JSON.stringify(F0)) as typeof faqs));
  Object.assign(notifyQuota, Q0);
});

describe("触达频控（P-14.1.4）", () => {
  it("0 被拒 —— 等于没有频控但界面上看着像配了", async () => {
    await expect(messageMock.saveNotifyQuota({ dailyPerUser: 0, minIntervalHours: 6 })).rejects.toThrow(/大于 0/);
    await expect(messageMock.saveNotifyQuota({ dailyPerUser: 3, minIntervalHours: 0 })).rejects.toThrow(/大于 0/);
  });

  it("合法配置落库", async () => {
    const q = await messageMock.saveNotifyQuota({ dailyPerUser: 5, minIntervalHours: 12 });
    expect(q).toMatchObject({ dailyPerUser: 5, minIntervalHours: 12 });
  });
});

describe("推送任务（P-14.1.2）", () => {
  it("预估触达 0 不许发 —— 人群是空的，发了等于白发一次", async () => {
    await expect(messageMock.sendPushTask("PT9003")).rejects.toThrow(/预估触达为 0/);
  });

  it("模板停用时不许发", async () => {
    msgTemplates.find((t) => t.templateNo === "TPL_WX_ARRIVED")!.enabled = false;
    await expect(messageMock.sendPushTask("PT9001")).rejects.toThrow(/模板已停用/);
  });

  it("正常任务可发送", async () => {
    const t = await messageMock.sendPushTask("PT9001");
    expect(t.status).toBe("SENT");
  });

  it("已发送不能撤销", async () => {
    await expect(messageMock.cancelPushTask("PT9002")).rejects.toThrow(/无法撤销/);
  });
});

describe("客服工单与代客留痕（P-14.2）", () => {
  it("分派必须指定处理人", async () => {
    await expect(messageMock.assignTicket("TK9001", "  ")).rejects.toThrow(/指定处理人/);
  });

  it("已关闭工单不能再分派", async () => {
    await expect(messageMock.assignTicket("TK9004", "cs02")).rejects.toThrow(/不允许/);
  });

  it("未分派时不能记代客操作（先有责任人再有动作）", async () => {
    await expect(messageMock.addProxyAction("TK9001", "代客退款")).rejects.toThrow(/先分派/);
  });

  it("代客操作留痕带上处理人 —— 没有留痕就查不出是谁做的", async () => {
    await messageMock.assignTicket("TK9001", "cs03");
    const t = await messageMock.addProxyAction("TK9001", "代客修改自提点：P001 → P002");
    expect(t.proxyActions[0]).toContain("cs03");
    expect(t.proxyActions[0]).toContain("P002");
  });

  it("代客操作内容必填", async () => {
    await expect(messageMock.addProxyAction("TK9002", " ")).rejects.toThrow(/内容必填/);
  });
});

describe("帮助中心（P-14.2.4）", () => {
  it("空答案不能上架 —— 用户点进去看到一片空白，比没有这条更糟", async () => {
    await expect(messageMock.setFaqPublished("FQ9004", true)).rejects.toThrow(/答案为空/);
  });

  it("有答案的可以上架", async () => {
    const f = await messageMock.setFaqPublished("FQ9003", false);
    expect(f.published).toBe(false);
  });

  it("新建条目默认不上架（先写完再发）", async () => {
    const f = await messageMock.saveFaq({ faqNo: "", question: "怎么开发票？", answer: "", category: "订单" });
    expect(f.published).toBe(false);
    expect(f.faqNo).toMatch(/^FQ/);
  });
});
