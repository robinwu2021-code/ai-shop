// 覆盖范围：风控（P-16.2）。
import * as db from "@/lib/mock/db";
import type { BlacklistEntry, RiskEvent } from "@/lib/types";
import type { RiskApi } from "../contracts/risk";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

export const riskMock: RiskApi = {
  listRiskEvents: (q = {}) =>
    wait(
      db.paginate(db.riskEvents, q.page, q.size, (e) =>
        db.eqHit(q.type, e.type) &&
        db.eqHit(q.status, e.status) &&
        db.kwHit(q.keyword, e.eventNo, e.subject, ...e.signals),
      ),
    ),

  decideRiskEvent: async (eventNo, confirmed, verdict) => {
    const e = db.riskEvents.find((x) => x.eventNo === eventNo);
    if (!e) notFound("风险事件", "Risk event", eventNo);
    if (e.status !== "PENDING") fail("该事件已处置，请刷新列表", "This event has already been handled — refresh the list");
    // 排除也要写理由：下次同一主体再命中时，得知道上次为什么放过
    if (!verdict?.trim()) fail("处置结论必填：确认与排除都要写清依据", "A conclusion is required — say what it rests on, whether you confirm or dismiss");
    e.status = confirmed ? "CONFIRMED" : "DISMISSED";
    e.verdict = verdict.trim();
    return wait(e, 400);
  },

  listBlacklists: (q = {}) =>
    wait(
      db.paginate(db.blacklists, q.page, q.size, (b) =>
        db.eqHit(q.subjectType, b.subjectType) &&
        (q.activeOnly !== "1" || b.active) &&
        db.kwHit(q.keyword, b.blackNo, b.subject, b.reason),
      ),
    ),

  addBlacklist: async ({ subjectType, subject, reason, until }) => {
    if (!subject?.trim()) fail("拉黑对象必填", "Name who is being blocked");
    if (!reason?.trim()) fail("拉黑原因必填：申诉时要能看到自己因为什么被拉黑", "A reason is required — on appeal they have to be able to see what it was for");
    // 无期限拉黑没有申诉出口，是产品事故不是风控严格
    if (!until?.trim()) fail("到期时间必填，不允许无期限拉黑", "An expiry is required — no open-ended blocks");
    if (new Date(until).getTime() <= Date.parse("2026-08-06T00:00:00Z")) {
      fail("到期时间必须晚于当前时间", "The expiry must be in the future");
    }
    if (db.blacklists.some((b) => b.subject === subject.trim() && b.active)) {
      fail(`${subject} 已在生效中的黑名单里`, `${subject} is already on the active blocklist`);
    }
    const rec: BlacklistEntry = {
      blackNo: db.nextNo("BL", db.blacklists, 9000, "blackNo"),
      subjectType, subject: subject.trim(), reason: reason.trim(), until,
      appealStatus: "NONE", active: true, createdAt: "2026-08-06T00:00:00Z",
    };
    db.blacklists.unshift(rec);
    return wait(rec, 400);
  },

  decideBlacklistAppeal: async (blackNo, accept, verdict) => {
    const b = db.blacklists.find((x) => x.blackNo === blackNo);
    if (!b) notFound("黑名单记录", "Blocklist entry", blackNo);
    if (b.appealStatus !== "PENDING") fail("该记录没有待裁决的申诉", "This entry has no appeal awaiting a ruling");
    if (!verdict?.trim()) fail("裁决说明必填，被拉黑者会看到", "A ruling note is required — the blocked party sees it");
    b.appealStatus = accept ? "UPHELD" : "REJECTED";
    b.appealVerdict = verdict.trim();
    // 接受申诉 = 解除拉黑；记录本身保留（留痕，不是删除）
    if (accept) b.active = false;
    return wait(b, 400);
  },

  listRiskRules: async () => wait(db.riskRules),

  saveRiskRule: async (type, threshold, autoBlock) => {
    const r = db.riskRules.find((x) => x.type === type);
    if (!r) notFound("规则", "Rule", type);
    if (threshold <= 0) fail("触发阈值必须大于 0（0 等于全量拦截）", "The threshold must be above 0 — at 0 it blocks everything");
    r.threshold = threshold;
    r.autoBlock = autoBlock;
    r.updatedAt = "2026-08-06T00:00:00Z";
    return wait(r, 400);
  },
};
