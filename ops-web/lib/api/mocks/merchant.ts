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
  M901: "NATURAL_PERSON",
};

/** 订阅行。没有行不是「未订阅」而是数据缺失 —— 真后端会兜底建一行 FREE。 */
function findPlan(merchantNo: string) {
  const r = db.merchantPlans.find((x) => x.merchantNo === merchantNo);
  if (!r) notFound("套餐订阅", "Plan subscription", merchantNo);
  return r;
}

export const merchantMock: MerchantApi = {
  // ── 门店经营模式与弱主体准入 ─────────────────────────────────

  storeModes: async (merchantNo) => wait(db.storeModes.filter((s) => s.merchantNo === merchantNo)),
  // 无照 × 自营。mock 里从 storeModes 与商家档案现算，**不另建一份数据** ——
  // 另建的话它会和 setStoreBusinessMode 的写入脱节，页面上改完模式清单不变
  modeRisk: async () => wait(
    db.storeModes
      .filter((s) => s.businessMode === "SELF_OPERATED")
      .flatMap((s) => {
        const m = db.merchants.find((x) => x.merchantNo === s.merchantNo);
        if (!m || m.legalForm !== "NATURAL_PERSON") return [];
        return [{
          merchantNo: m.merchantNo, merchantName: m.name, legalForm: m.legalForm,
          storeNo: s.storeNo, storeName: s.storeName, businessMode: s.businessMode!,
          settledBills: 0, settledMinor: 0,
        }];
      })),

  setFundsMode: async ({ merchantNo, fundsMode }) => {
    const m = find(merchantNo);
    // 无照主体不得走归集：平台按全额确认收入，而他开不出进项票 ——
    // 那笔支出不得税前扣除。农业生产者例外（平台可自开收购发票）
    if (fundsMode === "AGGREGATED" && m.legalForm === "NATURAL_PERSON" && !m.agriProducer) {
      fail("无营业执照的主体不能走归集路径（自产农产品除外）",
        "Unlicensed entities cannot use the aggregated funds path (self-produced agricultural goods excepted)");
    }
    m.fundsMode = fundsMode;
    return wait(m, 400);
  },

  // ── 资质 ───────────────────────────────────────────────
  // mock 侧真存一份，写完能读回 —— 只返回空数组的话，
  // 「登记完列表还是空」这种 bug 在开发期永远看不到
  qualifications: async (merchantNo) => wait(db.qualifications.filter((q) => q.entityNo === merchantNo)),

  saveQualification: async ({ merchantNo, qualNo, ...patch }) => {
    if (qualNo) {
      const q = db.qualifications.find((x) => x.qualNo === qualNo);
      if (!q) notFound("资质", "Qualification", qualNo);
      Object.assign(q!, patch);
      return wait(q!, 400);
    }
    if (!patch.qualType || !patch.qualName) {
      fail("资质类型与名称必填", "Qualification type and name are required");
    }
    const created = {
      qualNo: `Q${db.qualifications.length + 1}`, entityNo: merchantNo,
      qualType: patch.qualType!, qualName: patch.qualName!,
      qualNumber: patch.qualNumber, imageUrl: patch.imageUrl,
      expireAt: patch.expireAt ?? null, status: "VALID",
    };
    db.qualifications.push(created);
    return wait(created, 400);
  },

  revokeQualification: async (qualNo) => {
    const q = db.qualifications.find((x) => x.qualNo === qualNo);
    if (!q) notFound("资质", "Qualification", qualNo);
    // 不物理删：「当初有没有这张证」是要能查的
    q!.status = "REVOKED";
    return wait(q!, 400);
  },

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
      // ⚠️ 这条校验**只有 mock 有**：真实后端 MerchantAuthCodeServiceImpl.setCodes
      // 不比对 requiredQualification，直调接口即可绕过。前端的 disabled 也只是装饰。
      // 补后端拦截要等资质数据真的存在（入驻转存 mch_qualification）—— 现在补等于全禁。
      if (ac.requiredQualification && !(m.qualifications ?? []).includes(ac.requiredQualification)) {
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

  recordViolation: async ({ merchantNo, type, action, detail, storeNo }) => {
    const m = find(merchantNo);
    if (!detail.trim()) fail("必须写清事实与证据出处 —— 没有事实的处置在申诉时站不住", "State the facts and where the evidence sits — an action with no facts does not hold up on appeal");

    /*
     * 门店级处置：动作与它作用的对象必须**同一次提交**。
     * 分成两步（先记违规、再去门店页压下）的话，中间任何一次失败都留下
     * 「压了店但没有处置记录」或反过来 —— 而申诉时拿不出记录的处置站不住。
     *
     * 反向也拦：`storeNo` 只跟着 STORE_OFFLINE 走。主体级处置带着门店号，
     * 读记录的人会以为只压了那一家店。
     */
    if (action === "STORE_OFFLINE" && !storeNo?.trim()) {
      fail("门店强制下线必须指定门店", "A forced store offline must name the store");
    }
    if (action !== "STORE_OFFLINE" && storeNo?.trim()) {
      fail("只有「门店强制下线」可以指定门店，主体级处置作用于全部门店", "Only a forced store offline may name a store — the other actions hit the whole merchant");
    }

    // SUSPEND 走同一张状态机：已封禁的再封一次会在这里抛错，而不是静默重复
    if (action === "SUSPEND") db.assertTransition(MERCHANT_TRANSITIONS, m.status, "SUSPENDED", "商家", "Merchant");

    const store = action === "STORE_OFFLINE"
      ? db.stores.find((s) => s.storeNo === storeNo!.trim())
      : undefined;
    if (action === "STORE_OFFLINE") {
      if (!store) notFound("门店", "Store", storeNo!.trim());
      if (store.merchantNo !== merchantNo) fail("这家门店不属于该商家", "That store does not belong to this merchant");
      if (store.status === "SUSPENDED") fail("该门店已被强制下线", "That store is already forced offline");
    }

    // 只有毁约计入 breachCount：别的违规也计，ADR-003 那条阈值规则就失去意义了
    if (type === "BREACH") m.breachCount += 1;
    if (action === "SUSPEND") {
      m.status = "SUSPENDED";
      m.auditRemark = detail.trim();
    }
    // 压下那一刻就落库：真副作用，不是记一笔就完（解除走 restoreStore）
    if (store) store.status = "SUSPENDED";

    const v = {
      violationNo: db.nextNo("VL", db.violations, 900, "violationNo"),
      merchantNo, merchantName: m.name, storeNo: store?.storeNo ?? null, type, action,
      detail: detail.trim(), operator: "admin", at: new Date().toISOString(),
    };
    db.violations.unshift(v);
    return wait(v, 400);
  },
  // ── 增值包与门店额度（P-11.2.2~11.2.6）─────────────────────────
  //
  // 校验逐条对齐后端 `MerchantPlanServiceImpl`：理由必填、停售档位不能新授、
  // 额度非负、续费顺延而不是从今天重算。**mock 宽于后端的地方，就是上线才发现的地方。**

  merchantPlans: async (q) => {
    const now = Date.now();
    const GRACE_MS = 7 * 86_400_000;
    const rows = db.merchantPlans.filter((r) => {
      if (q?.filter === "GRACE") return r.status === "GRACE";
      if (q?.filter === "DOWNGRADED") return r.downgradedAt != null;
      if (q?.filter === "EXPIRING_7D") {
        // 「快到期」只对还在生效的有意义 —— 已进宽限期的归上一个筛选
        return r.status === "ACTIVE" && r.expireAt != null
          && r.expireAt >= now && r.expireAt <= now + GRACE_MS;
      }
      return true;
    }).filter((r) => {
      const k = q?.keyword?.trim();
      return !k || r.merchantName.includes(k) || r.merchantNo.includes(k);
    });
    // 与后端同序：按到期日升序 —— 最急的在最上面，这个列表就是一张待办
    const sorted = [...rows].sort((a, b) => (a.expireAt ?? Infinity) - (b.expireAt ?? Infinity));
    return wait(db.paginate(sorted, q?.page, q?.size));
  },

  planUpgradeSignals: async () =>
    // mock 里只有一个人名下有两个主体。真后端按 owner_user_no 分组，
    // 这里没有 owner 列，用固定一行代表那个形状 —— 页面要的是「怎么渲染」，不是真数据
    wait([{
      ownerUserNo: "U-9001",
      entityNos: ["M901", "M904"],
      entityNames: ["阿姨家的菜摊", "社区鲜奶站"],
      entityCount: 2,
    }]),

  grantPlan: async ({ merchantNo, planCode, months, reason }) => {
    if (!reason?.trim()) fail("请填写授予理由", "A reason is required");
    const row = findPlan(merchantNo);
    const def = db.planDefs.find((d) => d.planCode === planCode);
    // 停售的档位不能新授（已订阅的照常用到到期，那才是 enabled 的语义）
    if (!def || !def.enabled) fail("该档位已停售，不能新授", "That plan tier is retired");
    const now = Date.now();
    const extending = !!months && months > 0;
    // 换档或续费才刷新快照；**只补缴不延长不动快照** ——
    // 他买的是当初那个额度，中途下调档位定义不该殃及他
    if (planCode !== row.planCode || extending) {
      row.planCode = planCode;
      row.storeQuota = def.storeQuota;
      row.staffQuota = def.staffQuota;
      row.crossStoreStats = def.crossStoreStats;
      row.quotaSource = "PLAN";
    }
    if (extending) {
      // 还在生效期内就从原到期日接着算 —— 一律从今天重算会**吞掉他已付未用的那几天**，
      // 而提前续费正是我们希望他做的事
      const base = row.expireAt != null && row.expireAt > now ? row.expireAt : now;
      row.expireAt = base + months! * 30 * 86_400_000;
      row.startAt ??= now;
    }
    row.status = "ACTIVE";
    row.grantedBy = "PLATFORM";
    row.downgradedAt = null;
    // 恢复被降级压下的门店（真后端只回 plan_suspended=1 的那批；
    // mock 里 ST004 就是被压的那家，商家自停的不在这张表上）
    for (const s of db.stores) {
      if (s.merchantNo === merchantNo && s.status === "READONLY") s.status = "ACTIVE";
    }
    return wait(row, 400);
  },

  overridePlanQuota: async ({ merchantNo, storeQuota, staffQuota, reason }) => {
    if (!reason?.trim()) fail("请填写覆盖理由", "A reason is required");
    if ((storeQuota ?? 0) < 0 || (staffQuota ?? 0) < 0) fail("额度不能为负", "Quota cannot be negative");
    const row = findPlan(merchantNo);
    const def = db.planDefs.find((d) => d.planCode === row.planCode);
    // null = 清除覆盖、回到档位快照。**不是把 0 写进额度** ——
    // 那两件事在界面上长得一样，而后者会让这家商家一家店都开不了
    row.storeQuota = storeQuota ?? def?.storeQuota ?? row.storeQuota;
    row.staffQuota = staffQuota ?? def?.staffQuota ?? row.staffQuota;
    row.quotaSource = storeQuota == null && staffQuota == null ? "PLAN" : "OVERRIDE";
    return wait(row, 400);
  },

  planDefs: async () => wait(db.planDefs),

  savePlanDef: async ({ planCode, storeQuota, staffQuota, crossStoreStats, trialDays, enabled }) => {
    const def = db.planDefs.find((d) => d.planCode === planCode);
    if (!def) notFound("套餐档位", "Plan tier", planCode);
    if (storeQuota < 1) fail("门店额度至少为 1", "Store quota must be at least 1");
    Object.assign(def, { storeQuota, staffQuota, crossStoreStats, trialDays, enabled });
    // **刻意不动 db.merchantPlans 的任何一行**：已订阅的用的是自己的快照。
    // 这里顺手改掉他们的额度，就把「老用户保护」这条规则在 mock 里演示反了
    return wait(def, 400);
  },
};
