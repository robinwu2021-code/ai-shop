// 覆盖范围：商家治理（P-11.1）。写操作真改 db.merchants（重开能读回），状态机在此强制。
import * as db from "@/lib/mock/db";
import { MERCHANT_TRANSITIONS, type Merchant } from "@/lib/types";
import { MAX_MERCHANT_BREACH } from "@/lib/constants";
import type { MerchantApi } from "../contracts/merchant";
import type { LegalForm } from "@/lib/types";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findApply(applyNo: string) {
  const a = db.applies.find((x) => x.applyNo === applyNo);
  if (!a) notFound("入驻申请", "Application", applyNo);
  return a;
}

function find(merchantNo: string): Merchant {
  const m = db.merchants.find((x) => x.merchantNo === merchantNo);
  if (!m) notFound("商家", "Merchant", merchantNo);
  return m;
}

// ⚠️ 会抛错的方法一律写成 **async**：非 async 的箭头函数里 `throw` 是**同步抛出**，
// 调用方拿不到一个 rejected promise（`api.x().catch()` 根本来不及挂上），
// 与真实后端「网络返回错误码」的行为不一致 —— react-query 的 onError 也就不会触发。
/**
 * mock 侧的主体档位。
 *
 * 真实后端现在会在商家档案里下发 legalForm（见 MerchantProfileVO），
 * 但 mock 的 merchantDeposit 要按档位算限额，而它拿到的只有 merchantNo，
 * 所以这张表还留着 —— 与 db.merchants 上的 legalForm 保持一致。
 */
const MOCK_LEGAL_FORM: Record<string, LegalForm> = {
  M901: "MICRO",
};

export const merchantMock: MerchantApi = {
  // ── 门店经营模式与弱主体准入 ─────────────────────────────────

  storeModes: async (merchantNo) => wait(db.storeModes.filter((s) => s.merchantNo === merchantNo)),

  // 没有种子的商家给空数组，不是 404 —— 「这家店只有老板一个人」是常态
  merchantStaff: async (merchantNo) => wait(db.merchantStaff[merchantNo] ?? []),

  setStoreBusinessMode: async ({ storeNo, businessMode }) => {
    const s = db.storeModes.find((x) => x.storeNo === storeNo);
    if (!s) fail("门店不存在", "Store not found");
    /*
     * 切第三方要求该店有可用收款号。不拦的后果不是报错，而是**静默欠款**：
     * 订单照常成交、账单照常生成，只是钱卡在平台侧下不去，
     * 等发现时已经积了一批单。自营不需要这一条 —— 自营的钱本来就先进平台。
     */
    if (businessMode === "THIRD_PARTY" && !s!.payMerchantNo) {
      fail("该门店尚无可用收款账户，无法切换为第三方经营模式",
        "This store has no active payment account; cannot switch to third-party mode");
    }
    s!.businessMode = businessMode;
    return wait(s!, 400);
  },

  admissionPolicies: async () => wait([...db.admissionPolicies]),

  updateAdmissionPolicy: async ({ legalForm, ...patch }) => {
    const p = db.admissionPolicies.find((x) => x.legalForm === legalForm);
    // 三档已锁定，凭空多出一档只可能是笔误 —— 静默新建会让笔误变成一条永不生效的策略
    if (!p) fail("主体档位不存在", "Unknown legal form");
    Object.assign(p!, patch);
    return wait(undefined, 400);
  },

  merchantDeposit: async (merchantNo) => {
    const txns = db.depositTxns[merchantNo] ?? [];
    const paid = txns.filter((t) => t.txnType !== "FREEZE" && t.txnType !== "UNFREEZE")
      .reduce((n, t) => n + t.amountMinor, 0);
    const frozen = txns.reduce(
      (n, t) => n + (t.txnType === "FREEZE" ? t.amountMinor : t.txnType === "UNFREEZE" ? -t.amountMinor : 0), 0);
    /*
     * ⚠️ **运营端的商家档案里没有主体类型**（Merchant 上没有 legalForm，
     * 后端 /ops/merchants 也不返回），而准入档位完全由它决定。
     * 真实后端是在服务端算好 requiredMinor / 两个限额再下发的，页面用不到它；
     * 只有 mock 得自己推，所以这里用一张本地映射兜着。
     * 这个缺口值得单独补 —— 运营看不到「这家是小微」，就理解不了它为什么被限额。
     */
    const form = MOCK_LEGAL_FORM[merchantNo] ?? "ENTERPRISE";
    const policy = db.admissionPolicies.find((x) => x.legalForm === form);
    // 判「够不够」用**可用**而非实缴：冻结中的钱不能同时用来撑准入，
    // 否则同一笔保证金被两处重复计数
    const available = paid - frozen;
    return wait({
      merchantNo, paidMinor: paid, frozenMinor: frozen, availableMinor: available,
      requiredMinor: policy?.requiredDepositMinor ?? 0,
      sufficient: available >= (policy?.requiredDepositMinor ?? 0),
      singleOrderLimitMinor: policy?.singleOrderLimitMinor ?? 0,
      dailyAmountLimitMinor: policy?.dailyAmountLimitMinor ?? 0,
    });
  },

  depositTxns: async (merchantNo) => wait([...(db.depositTxns[merchantNo] ?? [])].reverse()),

  addDepositTxn: async ({ merchantNo, txnType, amountMinor, reason }) => {
    const list = (db.depositTxns[merchantNo] ??= []);
    const paid = list.filter((t) => t.txnType !== "FREEZE" && t.txnType !== "UNFREEZE")
      .reduce((n, t) => n + t.amountMinor, 0);
    // 符号方向必须与类型一致：缴纳只能是正，退还与扣划只能是负。
    // 不校验的后果不是报错而是账反了 —— 「退还」把余额加上去，两侧都不报错
    const shouldBeNegative = txnType === "REFUND" || txnType === "DEDUCT";
    if (txnType !== "FREEZE" && txnType !== "UNFREEZE"
      && (amountMinor === 0 || (shouldBeNegative ? amountMinor > 0 : amountMinor < 0))) {
      fail("金额方向与变动类型不符", "The sign of the amount does not match the entry type");
    }
    const after = txnType === "FREEZE" || txnType === "UNFREEZE" ? paid : paid + amountMinor;
    // 扣成负数意味着平台已经垫付，那是另一笔账，不该混在这张表里
    if (after < 0) fail("保证金余额不足，无法扣划", "Deposit balance is not enough for this deduction");
    list.push({
      txnNo: `DP-${list.length + 1}`, txnType, amountMinor,
      balanceAfterMinor: after, reason: reason ?? null, operator: "admin",
      createdAt: new Date().toISOString(),
    });
    return wait(undefined, 400);
  },

  listApplies: (q = {}) => {
    // 不给状态时只给待办两档 —— 与真后端同口径，否则切到真实环境列表会突然变长
    const want = (q.status?.split(",").map((x) => x.trim()).filter(Boolean) ?? [])
      .length
      ? q.status!.split(",").map((x) => x.trim()).filter(Boolean)
      : ["PENDING", "REVIEWING"];
    return wait(
      db.paginate(db.applies, q.page, q.size, (a) =>
        want.includes(a.status) && db.kwHit(q.keyword, a.applyNo, a.name, a.contactName, a.contactPhone),
      ),
    );
  },

  acceptApply: async (applyNo) => {
    const a = findApply(applyNo);
    if (a.status !== "PENDING") fail("只有待审的申请可以受理", "Only pending applications can be accepted");
    a.status = "REVIEWING";
    await wait(undefined);
  },

  auditApply: async (applyNo, approved, reason, serviceScope, communityNos) => {
    const a = findApply(applyNo);
    if (a.status === "APPROVED" || a.status === "REJECTED") {
      fail("这份申请已经审过了", "This application has already been decided");
    }
    if (!approved && !reason?.trim()) {
      // 不写理由的驳回等于让对方猜 —— mock 也要拦，否则这段校验在开发期永远走不到
      fail("驳回必须写理由", "A rejection must carry a reason");
    }
    if (approved) {
      if (serviceScope) a.serviceScope = serviceScope;
      if (communityNos?.length) a.communityNos = [...communityNos];
      /*
       * 「仅本社区」却一个都没选 —— 真后端的 activate 会拒，mock 也拒。
       * 放过去的话商家通过审核、上完架，却对谁都不可见，而这个故障不报错。
       */
      const byCommunity = !a.serviceScope || a.serviceScope === "COMMUNITY";
      if (byCommunity && !a.communityNos?.length) {
        fail("按社区经营必须至少选一个小区", "Pick at least one community for community-scoped merchants");
      }
      a.status = "APPROVED";
      a.merchantNo = `M${applyNo.slice(1)}`;
    } else {
      a.status = "REJECTED";
      a.rejectReason = reason;
    }
    a.auditedAt = Date.now();
    await wait(undefined);
  },

  listMerchants: (q = {}) =>
    wait(
      db.paginate(db.merchants, q.page, q.size, (m) =>
        db.liveHit(m, q.showArchived) &&
        db.scopeHit(q, m) &&
        db.eqHit(q.status, m.status) &&
        db.eqHit(q.tier, m.tier) &&
        db.kwHit(q.keyword, m.merchantNo, m.name, m.contactName),
      ),
    ),

  getMerchant: async (merchantNo) => wait(find(merchantNo)),

  setMerchantStatus: async (merchantNo, status, remark, communityNos) => {
    const m = find(merchantNo);
    db.assertTransition(MERCHANT_TRANSITIONS, m.status, status, "商家", "Merchant");
    /*
     * 这里只改**经营状态**（ACTIVE / SUSPENDED / FROZEN）。
     *
     * 审核（受理 / 通过 / 驳回）不在这条路上 —— 它属于申请单，
     * 走 `/ops/merchant/apply/{applyNo}/audit`。两者曾经合成一个字段，
     * 于是「已在经营、又提交了第二张执照」的商家 status 该填什么无解。
     *
     * 「通过审核必须同时指定覆盖社区」那条规则也跟着搬到了审核那边：
     * 不拦的话商家审核通过 → 上架 → 一个订单都不来（service_scope 默认
     * COMMUNITY 而一个社区都没覆盖 = C 端谁也搜不到），且没有任何报错。
     */
    db.assertTransition(MERCHANT_TRANSITIONS, m.status, status, "商家", "Merchant");
    m.status = status;
    if (remark !== undefined) m.auditRemark = remark;
    if (communityNos?.length) {
      m.communityNos = [...communityNos];
    }
    return wait(m, 400);
  },

  setMerchantVerified: async (merchantNo, verified) => {
    const m = find(merchantNo);
    // 认证标只授予审核通过的商家 —— 这条规则在后端也存在，mock 放行的话
    // 页面就不会去写「先通过再授标」的引导。
    if (verified && m.status !== "ACTIVE") fail("仅正常经营中的商家可授予认证标", "Only merchants in good standing can hold the verified badge");
    // 认证标是平台的背书，挂在正在毁约的商家身上，赔的是平台的信用
    if (verified && m.breachCount >= MAX_MERCHANT_BREACH) {
      fail(`毁约次数已达 ${m.breachCount} 次（上限 ${MAX_MERCHANT_BREACH}），不能授予认证标`, `${m.breachCount} breaches on record (limit ${MAX_MERCHANT_BREACH}) — the verified badge cannot be granted`);
    }
    m.verified = verified;
    return wait(m, 400);
  },

  archiveMerchant: async (merchantNo) => wait(db.archiveRow(db.merchants, "merchantNo", merchantNo), 400),
  unarchiveMerchant: async (merchantNo) => wait(db.unarchiveRow(db.merchants, "merchantNo", merchantNo), 400),

  listAuthCodes: async () => wait(db.authCodes),

  setMerchantAuthCodes: async ({ merchantNo, codes, reason }) => {
    const m = find(merchantNo);
    if (!reason.trim()) fail("改授权范围必须写原因 —— 它决定商家能上架什么", "Changing the granted scope needs a reason — it decides what they may list");
    // 没过审就授权等于提前放行
    if (m.status !== "ACTIVE") fail("仅正常经营中的商家可配置类目授权", "Category permissions are open to merchants in good standing only");
    // 撤空之后商家会静默失去上架能力：要停就走封禁或归档，那是明示的动作
    if (!codes.length) fail("不能把授权撤空 —— 要停止经营请走封禁或归档", "You cannot clear every permission — to stop them trading, ban or archive them");

    for (const code of codes) {
      const ac = db.authCodes.find((x) => x.code === code);
      if (!ac) notFound("授权码", "Permission code", code);
      if (ac.requiredQualification && !m.qualifications.includes(ac.requiredQualification)) {
        fail(`${ac.name} 需要「${ac.requiredQualification}」，该商家尚未上传`, `${ac.name} requires “${ac.requiredQualification}”, which this merchant has not uploaded`);
      }
    }

    // 撤销时：该码下还有在售商品的不能撤 —— 撤了架上还挂着那类商品，
    // 谁也说不清它算不算违规
    const removed = m.categoryCodes.filter((c) => !codes.includes(c));
    for (const code of removed) {
      const live = db.skus.filter(
        (s) => s.merchantNo === merchantNo && s.status === "ON_SALE" &&
          db.categories.find((cat) => cat.categoryNo === s.categoryNo)?.requiredCode === code,
      );
      if (live.length) {
        const ac = db.authCodes.find((x) => x.code === code);
        fail(`${ac?.name ?? code} 下还有 ${live.length} 个在售商品，请先下架再撤销授权`, `${ac?.name ?? code} still has ${live.length} items on sale — take them down before revoking it`);
      }
    }

    m.categoryCodes = [...codes];
    return wait(m, 400);
  },

  listViolations: async (q = {}) =>
    wait(db.violations.filter((v) => db.eqHit(q.merchantNo, v.merchantNo))),

  recordViolation: async ({ merchantNo, type, action, detail }) => {
    const m = find(merchantNo);
    if (!detail.trim()) fail("必须写清事实与证据出处 —— 没有事实的处置在申诉时站不住", "State the facts and where the evidence sits — an action with no facts does not hold up on appeal");

    // SUSPEND 走同一张状态机：已封禁的再封一次会在这里抛错，而不是静默重复
    if (action === "SUSPEND") db.assertTransition(MERCHANT_TRANSITIONS, m.status, "SUSPENDED", "商家", "Merchant");

    // 只有毁约计入 breachCount：别的违规也计，ADR-003 那条阈值规则就失去意义了
    if (type === "BREACH") m.breachCount += 1;
    if (action === "SUSPEND") {
      m.status = "SUSPENDED";
      m.auditRemark = detail.trim();
    }

    const v = {
      violationNo: db.nextNo("VL", db.violations, 900, "violationNo"),
      merchantNo, merchantName: m.name, type, action,
      detail: detail.trim(), operator: "admin", at: new Date().toISOString(),
    };
    db.violations.unshift(v);
    return wait(v, 400);
  },
};
