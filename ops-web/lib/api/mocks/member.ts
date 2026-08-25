// 运营侧会员与新模型营销（P8）。
import * as db from "@/lib/mock/db";
import { paginate } from "@/lib/mock/db/helpers";
import { wait } from "./_wait";
import type { OpsMember } from "@/lib/types";
import type { MemberApi } from "../contracts/member";

export const memberMock: MemberApi = {
  listOpsMembers: (q = {}) =>
    wait(paginate<OpsMember>(db.opsMembers, q.page, q.size, (m) =>
      (!q.entityNo || m.entityNo === q.entityNo)
      // **与后端同一条规矩：只接受恰好四位**。mock 放宽的话，
      // 演示时输三位能查出人，接真后端却被拒 —— 而那时没人记得是哪条拦的
      && (!q.phoneTail || (q.phoneTail.length === 4 && m.phoneTail === q.phoneTail)))),

  getOpsPerson: (personNo) =>
    wait({
      personNo,
      phoneTail: db.opsMembers.find((m) => m.personNo === personNo)?.phoneTail ?? null,
      userNo: personNo === "PS-1002" ? null : "U-" + personNo,
      memberships: db.opsMembers.filter((m) => m.personNo === personNo),
      merges: [],
    }),

  revealMemberPhone: (personNo, reason) => {
    // 与后端同一条：理由太短直接拒。「查一下」等于没有理由
    if (!reason || reason.trim().length < 4) {
      return Promise.reject(new Error("请写清查看理由（至少四个字）"));
    }
    const tail = db.opsMembers.find((m) => m.personNo === personNo)?.phoneTail ?? "0000";
    return wait({ phone: "138****" + tail });
  },

  listReachStats: () => wait(db.reachStats),
  listOpsPromoCoupons: (entityNo) =>
    wait(db.opsPromoCoupons.filter((c) => !entityNo || c.entityNo === entityNo)),
  listOpsPromoActivities: (entityNo) =>
    wait(db.opsPromoActivities.filter((a) => !entityNo || a.entityNo === entityNo)),
  stopOpsActivity: (activityNo, reason) => {
    const a = db.opsPromoActivities.find((x) => x.activityNo === activityNo);
    if (!a) return Promise.reject(new Error("活动不存在"));
    if (!reason || reason.trim().length < 4) {
      return Promise.reject(new Error("请写清停止原因（至少四个字）"));
    }
    a.status = "ENDED";
    a.endedReason = "MANUAL";
    return wait(a);
  },
};
