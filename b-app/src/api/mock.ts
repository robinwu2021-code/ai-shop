// B 端 mock 实现。
//
// 与 C 端共用同一份 `@shared/mock/db` 的**代码与种子数据**：订单/商品/评价的结构与初始
// 数据只有一处定义，两端不会漂移。
//
// ⚠️ 但**运行时状态不共享**：mock 落盘走 uni storage，H5 下即 localStorage，按 origin 隔离。
// 两端跑在不同端口就是两份状态，所以「B 端核销 → C 端看到已完成」在 H5 双服务器下
// 验证不了 —— 那要等接真后端（见 TDD-b-app §4.4）。
//
// 约定同 C 端：真改 db、状态机强制、非法迁移抛错、写后 persist。
import {
  allGoods,
  assertTransition,
  db,
  delay,
  findGoodsSeed,
  buildGroupBuy,
  nextNo,
  paginate,
  pick,
  toGroupRequest,
  persist,
  pushMessage,
  toGoods,
  toCommunity,
} from "@shared/mock/db";
import { currentCurrency, money } from "@shared/utils/money";
import { CATEGORY_TYPE, POINTS, SETTLE, REVIEW_RULES } from "@shared/utils/constants";
import { ensureDemoOrders } from "./demo-orders";
import type { GoodsDraft, MerchantApi } from "./contract";

/** 本店积分开关。mock 内存态，真实实现在 usr_merchant.points_enabled */
let pointsEnabled = true;

/**
 * 发分服务费明细：一单一条，真实数据来自 `stl_bill.points_fee_minor`。
 * mock 里按已有订单折算，让 B 端能看到「一单一条」的形状。
 */
function pointsFeeRecords() {
  return db.orders.slice(0, 8).map((o) => ({
    settleNo: `ST${o.orderNo.slice(-8)}`,
    subOrderNo: o.orderNo,
    points: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio),
    feeMinor: Math.round((o.amount?.payableMinor ?? 0) * POINTS.defaultEarnRatio / POINTS.perMinor),
    period: "202608",
    at: o.createdAt,
  }));
}

function pointsAccount() {
  const expense = pointsFeeRecords().reduce((s, r) => s + r.feeMinor, 0);
  return {
    periodExpenseMinor: expense,
    period: "2026-08",
    enabled: pointsEnabled,
    disabledReason: pointsEnabled ? undefined : "本店未开启积分",
    forced: false,
  };
}
import type { StaffLogRow } from "@shared/mock/db";
import type {
  CurrencyCode,
  Goods,
  I18nText,
  MarketingCampaign,
  MerchantStaff,
  Order,
  PickingRow,
  SpecTemplate,
  StaffRole,
  VerifyBatchResult,
} from "@shared/types";

/** 当前登录商家；未入驻时抛错，页面据此引导去入驻 */
function requireMerchant(): string {
  if (!db.merchant.merchantNo) throw new Error("尚未入驻");
  return db.merchant.merchantNo;
}

function findOrder(orderNo: string): Order {
  const o = db.orders.find((x) => x.orderNo === orderNo);
  if (!o) throw new Error(`订单不存在：${orderNo}`);
  return o;
}

/**
 * 退款落账。与 C 端 `settleRefund` 同一套规则：订单置 REFUNDED + 收回已发积分 + 返还抵扣积分。
 * 两端各写一份是因为 mock 分端，真实后端只会有一处。
 */
function settleRefund(o: Order, label: string) {
  assertTransition(o.status, "REFUNDED");
  o.status = "REFUNDED";
  if (o.afterSale) {
    o.afterSale.status = "REFUNDED";
    o.afterSale.updatedAt = Date.now();
  }
  pushTimeline(o, label);
  pushMessage(
    "TRADE",
    "退款已到账",
    "款项已原路退回，到账时间以支付渠道为准",
    `/pages/order/index?orderNo=${o.orderNo}`,
  );
}

function pushTimeline(order: Order, label: string) {
  order.timeline.push({ status: order.status, label, at: Date.now() });
}

/**
 * 订单是否属于本商家。
 *
 * 拆单落地后（E3）**一单只属于一个商家**，直接比 `order.merchantNo` 即可 ——
 * 之前的「含即算」是没拆单时的将就：跨商家的单会同时出现在两家的列表里，
 * 各自都看到不属于自己的商品与金额。
 *
 * 兼容：拆单之前建的历史单没有 merchantNo，回退到按商品判断，避免旧数据凭空消失。
 */
function belongsToMerchant(o: Order, merchantNo: string): boolean {
  if (o.merchantNo) return o.merchantNo === merchantNo;
  return o.items.some((it) => {
    try {
      return findGoodsSeed(it.goodsNo).merchantNo === merchantNo;
    } catch {
      return false;
    }
  });
}

function myGoods(): Goods[] {
  const merchantNo = db.merchant.merchantNo;
  return allGoods().filter((g) => g.merchant.merchantNo === merchantNo);
}

/** 规格名与选项仍是单语录入（模板本身跨语言，见 M8 未覆盖项），照旧抄三语 */
function toI18n(text: string) {
  return { "zh-CN": text, en: text, ar: text };
}

/**
 * 商品文案三语落库。**留空的语言回落中文，但不假装它被翻译过** ——
 * 机翻的商品名会直接出现在下单页与小票上，错了没人兜底；
 * 回落至少是诚实的，而且平台端能按「未翻译」筛出来补。
 */
function fillI18n(text: I18nText): I18nText {
  const zh = text["zh-CN"].trim();
  return {
    "zh-CN": zh,
    en: text.en.trim() || zh,
    ar: text.ar.trim() || zh,
  };
}

/** 按售后单号取订单。售后是独立资源，mock 里仍存在 Order 上 —— 寻址方式与契约一致即可 */
function findOrderByAfterSale(afterSaleNo: string): Order {
  const o = db.orders.find((x) => x.afterSale?.afterSaleNo === afterSaleNo);
  if (!o) throw new Error("售后单不存在");
  return o;
}

/** 取「待处理」的售后单 —— 同意与驳回的前置校验完全相同，抽出来免得两处各写一遍 */
function takePendingAfterSale(afterSaleNo: string): Order {
  const o = findOrderByAfterSale(afterSaleNo);
  // 判据是**售后单**的状态，不是订单的 —— 订单在售后期间保持原状态
  if (o.afterSale!.status !== "APPLIED") throw new Error("该售后已处理过");
  return o;
}

function requireStore(storeNo: string) {
  const s = db.stores.find((x) => x.storeNo === storeNo);
  if (!s) throw new Error("门店不存在");
  return s;
}

function requireStaff(mchAccountNo: string) {
  const s = db.staff.find((x) => x.mchAccountNo === mchAccountNo);
  if (!s) throw new Error("员工不存在");
  return s;
}

/**
 * 记一条员工与授权的变更（B-11.10.3）。
 *
 * mock 里的操作人恒为老板 —— 演示会话就是老板，而这条日志的价值在于
 * **它是真的在写**：页面上看到的每一行都来自刚才那次操作，不是种子数据。
 */
function logStaff(
  target: MerchantStaff,
  action: string,
  storeName?: string,
  role?: StaffRole,
  detail?: string,
) {
  const owner = db.staff.find((x) => x.isOwner);
  db.staffLogs.unshift({
    targetAccountNo: target.mchAccountNo,
    // 认人用姓名 —— 审计里一列号码，三个月后谁也想不起那是谁
    actor: owner?.displayName || owner?.loginPhone,
    targetName: target.displayName || target.loginPhone,
    action,
    storeName,
    role,
    detail,
    at: Date.now(),
  });
}

/** 有几个人持有这个角色 —— 删除按钮的依据 */
function usersOfRole(roleCode: string) {
  return db.staff.filter((s) => s.roles.some((r) => r.role === roleCode)).length;
}

/** 权限码 → 中文。**取自 db.permLabels（后端下发的那份）**，不在页面里再抄一遍 */
/** 角色码 → 显示名。审计那行字是给老板看的，他没见过 `MANAGER`，更没见过 `R-MOCK-1` */
function roleName(code: string) {
  return db.roles.find((r) => r.roleCode === code)?.name ?? code;
}

function permLabel(code: string) {
  return db.permLabels[code] ?? code;
}

/**
 * 自定义角色不能带 `biz:store:admin` —— 与后端同一条边界。
 *
 * mock 也要拦：只有后端拦的话，开发期能建出一个「副老板」角色，
 * 连上真后端才发现建不了，而那时界面已经按「能建」画好了。
 */
function assertAssignable(perms: string[]) {
  const bad = perms.filter((p) => p === "biz:store:admin" || p === "*");
  if (bad.length) throw new Error("管员工的权限不能授给自定义角色");
  if (!perms.length) throw new Error("至少勾一项权限");
  return [...perms];
}

function logStaffRole(action: string, roleCode: string, detail: string) {
  // role 在类型上是 StaffRole（预置码的联合），而自定义角色码是运行期生成的业务键。
  // 审计里存的是「哪个角色」而不是「哪个预置角色」—— 这里显式放宽，
  // 与后端一致（那边 mch_staff_log.role 也只是一列字符串）
  const owner = db.staff.find((x) => x.isOwner);
  db.staffLogs.unshift({
    targetAccountNo: "",
    actor: owner?.displayName || owner?.loginPhone,
    action,
    role: roleCode as StaffLogRow["role"],
    detail,
    at: Date.now(),
  });
}

/**
 * 脱敏 —— **只用在审计文案里**（与后端同一处口径）。
 *
 * 员工档案上的号码不脱敏：它就是登录用户名，老板要能核对、能改。
 * 但日志是长期留存、可能被导出的文本，那里不需要一个完整号码。
 */
function maskPhone(phone: string) {
  return phone.length < 7 ? phone : `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

export const mockApi: MerchantApi = {
  // ---------------------------------------------------------------- 账号与入驻
  async mLogin(req) {
    // 注册的合规前置：没勾协议不建号。真实后端要把同意时间与协议版本号一起留痕
    if (!req.agreed) throw new Error("请先阅读并同意协议");

    // 手机号是商家账号的主标识；第三方登录拿到的是 code，手机号由服务端换取后回填。
    // mock 无服务端换号能力，这里用占位号让流程能继续，并在 profile 上标出待补绑。
    const isPhone = req.grantType === "PHONE_OTP";
    if (isPhone && !/^\d{11}$/.test(req.principal)) throw new Error("手机号格式不对");
    db.merchant.phone = isPhone ? req.principal : db.merchant.phone || "";
    db.merchant.loginBy = req.grantType;
    persist();
    return delay({ token: `mock-b-token-${Date.now()}`, merchant: { ...db.merchant } });
  },

  async mSendOtp(phone: string) {
    if (!/^\d{11}$/.test(phone)) throw new Error("手机号格式不对");
    await delay(undefined);
  },

  async mStaffLogin(payload) {
    /*
     * mock 也照「非在职员工返回 403」来：恒成功的话，
     * 「输错号码时该显示什么」这段永远走不到，而它是员工登录最常见的一次失败。
     */
    const staff = db.staff.find(
      (x) => x.status === "ACTIVE" && x.loginPhone === payload.phone,
    );
    if (!staff) throw new Error("该手机号不是本店员工");
    return delay({ token: "demo-staff-token", merchant: { ...db.merchant } });
  },

  async mProfile() {
    return delay({ ...db.merchant });
  },

  async mApply(payload) {
    // 一份记录同时承载内容与进度 —— 后端 usr_merchant_apply 就是一行
    db.merchantApply = {
      ...payload,
      applyNo: db.merchantApply?.applyNo || nextNo("MA"),
      status: "PENDING",
      createdAt: Date.now(),
    };
    db.merchant = {
      ...db.merchant,
      // 提交后是 APPLYING（已交，等着）而不是 REVIEWING（有人在看）——
      // 此刻还没有任何人受理，报 REVIEWING 是替运营做了一个没发生的承诺
      merchantNo: db.merchant.merchantNo || nextNo("M"),
      name: payload.name,
      subject: payload.subject,
      status: "APPLYING",
    };
    persist();
    return delay({ ...db.merchant });
  },

  async mApplyDraft() {
    return delay(db.merchantApply ? { ...db.merchantApply } : null);
  },

  // ---------------------------------------------------------------- 店铺与获客
  async mStore() {
    return delay({ ...db.store });
  },

  async mMasterData() {
    /*
     * mock 的行业白名单要**带一个不允许小微的行业**（线上服务），
     * 否则「行业决定能不能选小微」这条联动在 mock 下永远看不出效果，
     * 而它正是选错主体导致进件被拒的地方。
     */
    return delay({
      industries: [
        { industry: "FRESH", name: "生鲜果蔬", microAllowed: true },
        { industry: "GROCERY", name: "粮油日用", microAllowed: true },
        { industry: "BAKERY", name: "烘焙熟食", microAllowed: true },
        { industry: "ONLINE_SERVICE", name: "线上服务", microAllowed: false },
      ],
      subjects: [
        { subjectType: "MICRO" as const, name: "小微商户", needLicense: false,
          industryGated: true, settleAccountType: "PERSONAL_OPENID" as const },
        { subjectType: "INDIVIDUAL" as const, name: "个体工商户", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
        { subjectType: "ENTERPRISE" as const, name: "企业", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
      ],
      channels: [{ payChannel: "WECHAT", name: "微信支付", enabled: true, payMethods: ["JSAPI"] }],
      /*
       * **只给两档，与一期真实配置一致**（自营模式下 PLATFORM 没开）。
       * mock 里把三档全给上的话，「端上照下发的档位渲染」这件事就演示不出来 ——
       * 界面看着和写死三档完全一样，而真环境里第三档点下去会被拒。
       */
      serviceScopes: ["COMMUNITY", "CITY"] as const,
    });
  },

  async mPayments() {
    return delay([{ ...db.payment }]);
  },

  async mSubmitPayment(payload) {
    /*
     * mock 也走「资料齐了才通过」这条规则：恒成功的 mock 会让端上
     * 「缺什么就说缺什么」那段界面永远走不到，而它正是商家最需要的一段。
     */
    if (!payload.settleAccount) {
      throw new Error("还差结算账户");
    }
    const tail = payload.settleAccount.slice(-4);
    db.payment = {
      ...db.payment,
      applyStatus: "ACTIVE",
      canReceiveMoney: true,
      payMerchantNo: "PM-MOCK-0001",
      settleAccountType: payload.settleAccountType ?? "MERCHANT_ID",
      // 明文不进本地库 —— mock 也照这条来，免得端上养成读明文的习惯
      settleAccountMasked: `****${tail}`,
      missing: [],
      activatedAt: Date.now(),
    };
    persist();
    return delay({ ...db.payment });
  },

  async mRefreshPayment() {
    return delay({ ...db.payment });
  },

  async mStoreList() {
    return delay(db.stores.map((s) => ({ ...s })));
  },

  async mCreateStore(payload) {
    /*
     * mock 也照额度拒。恒成功的 mock 会让「超额」那段界面永远走不到，
     * 而它是多门店里最常被触发的一条路径 —— FREE 档只能有一家店。
     */
    if (db.stores.length >= db.storeQuota) {
      throw new Error(`当前套餐最多 ${db.storeQuota} 家门店`);
    }
    const store = {
      storeNo: `ST-MOCK-${db.stores.length + 1}`,
      name: payload.name,
      address: payload.address ?? "",
      isDefault: db.stores.length === 0,
      status: "ACTIVE" as const,
      payReady: true,
      staffCount: 0,
    };
    db.stores.push(store);
    persist();
    return delay({ ...store });
  },

  async mRenameStore(storeNo, payload) {
    const s = requireStore(storeNo);
    s.name = payload.name || s.name;
    if (payload.address !== undefined) s.address = payload.address;
    persist();
    return delay({ ...s });
  },

  async mSetStoreStatus(storeNo, active) {
    const s = requireStore(storeNo);
    // 默认店不能停用 —— 停掉之后「这个主体的店在哪」就没有答案了
    if (!active && s.isDefault) throw new Error("默认店不能停用，请先把默认标转给别家");
    s.status = active ? "ACTIVE" : "READONLY";
    persist();
    return delay({ ...s });
  },

  async mSetDefaultStore(storeNo) {
    const s = requireStore(storeNo);
    if (s.status !== "ACTIVE") throw new Error("已停用的店不能设为默认");
    db.stores.forEach((x) => { x.isDefault = x.storeNo === storeNo; });
    persist();
    return delay({ ...s });
  },

  async mSetStorePayment(storeNo, payMerchantNo) {
    const s = requireStore(storeNo);
    // 传空 = 回到主体默认号，是合法操作不是清空错误
    s.payMerchantNo = payMerchantNo || undefined;
    persist();
    return delay({ ...s });
  },

  async mStaffList() {
    return delay(db.staff.map((x) => ({ ...x })));
  },

  async mAddStaff(loginPhone, displayName) {
    if (!/^\d{11}$/.test(loginPhone)) throw new Error("请填 11 位手机号");
    const existing = db.staff.find((x) => x.loginPhone === loginPhone);
    if (existing) {
      // 离职再回来是常事：重新启用而不是报「已存在」
      existing.status = "ACTIVE";
      // 对老板来说这就是「把人加回来」，所以记 STAFF_ADD 而不是 ENABLE ——
      // 审计要还原他做了什么，不是还原代码走了哪个分支
      logStaff(existing, "STAFF_ADD", undefined, undefined,
        `重新启用已存在的员工 ${maskPhone(loginPhone)}`);
      persist();
      return delay({ ...existing });
    }
    const staff = {
      mchAccountNo: `SF-MOCK-${db.staff.length + 1}`,
      displayName: displayName?.trim() || undefined,
      // 号码就是登录用户名，完整存 —— 与后端同口径
      loginPhone,
      isOwner: false,
      status: "ACTIVE" as const,
      roles: [],
    };
    db.staff.push(staff);
    logStaff(staff, "STAFF_ADD", undefined, undefined, `新增员工 ${maskPhone(loginPhone)}`);
    persist();
    return delay({ ...staff });
  },

  async mSetStaffStatus(mchAccountNo, active) {
    const st = requireStaff(mchAccountNo);
    // 老板不能被停用 —— 那是个能把自己锁在门外的按钮
    if (st.isOwner && !active) throw new Error("老板不能被停用");
    st.status = active ? "ACTIVE" : "DISABLED";
    logStaff(st, active ? "STAFF_ENABLE" : "STAFF_DISABLE", undefined, undefined,
      active ? "启用员工" : "停用员工（门店授权保留）");
    persist();
    return delay({ ...st });
  },

  async mBizScope() {
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    // mock 里的演示会话恒为老板 —— 要体验受限角色请连真后端用员工账号登录。
    // 这里不编一个「假的店员」：那会让开发期看到的裁剪结果与真实的不一样
    return delay({
      merchantNo: db.merchant.merchantNo,
      currentStoreNo: home?.storeNo ?? "",
      owner: true,
      storeNos: db.stores.map((s) => s.storeNo),
      pickupNos: db.merchant.isPickupPoint ? ["PP-MOCK-1"] : [],
      groupNos: [],
      staffRoles: ["OWNER"],
      perms: ["*"],
    });
  },

  async mGrantStore(mchAccountNo, storeNo, role, granted) {
    const st = requireStaff(mchAccountNo);
    const store = requireStore(storeNo);
    /*
     * **增量式：只动这一个角色**（一人一店可多角色）。
     *
     * 原先是先把这家店的角色全 filter 掉再 push 一个 —— 那是覆盖式，
     * 老板想「再加一个配送员」会把「店员」冲掉，而且不报错。
     * mock 与后端必须同一套语义，否则开发期看到的是另一个产品。
     */
    const had = st.roles.some((r) => r.storeNo === storeNo && r.role === role);
    st.roles = st.roles.filter((r) => !(r.storeNo === storeNo && r.role === role));
    if (granted !== false) st.roles.push({ storeNo, storeName: store.name, role });
    // 撤销一个他本来就没有的角色是空操作，不留痕 —— 与后端同口径，
    // 否则日志里会出现一串「撤销了店长」而他从来不是店长
    if (granted !== false) {
      logStaff(st, "ROLE_GRANT", store.name, role, `授予 ${store.name} 的 ${roleName(role)}`);
    } else if (had) {
      logStaff(st, "ROLE_REVOKE", store.name, role, `撤销 ${store.name} 的 ${roleName(role)}`);
    }
    persist();
    return delay({ ...st });
  },

  /**
   * 员工与授权的变更记录（B-11.10.3）。倒序 —— 最近做的那一件最可能是要查的。
   */
  /**
   * 角色列表：6 个预置（只读）+ 自定义。
   *
   * 预置那份**与后端 V71 的 seed 同一套语义** —— mock 里编一份不一样的，
   * 开发期看到的角色能力就与真实的不同，而这正是最不该分岔的地方。
   */
  async mRoles() {
    return delay(db.roles.map((r) => ({ ...r, usedBy: usersOfRole(r.roleCode) })));
  },

  /**
   * 可勾的权限点：**db.permLabels 全表减掉 `biz:store:admin`** ——
   * 与后端 `BizPerms.assignableCodes()` 同一条口径（那边也是全表减一条）。
   */
  async mRolePerms() {
    return delay(
      Object.entries(db.permLabels)
        .filter(([code]) => code !== "biz:store:admin")
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([code, label]) => ({ code, label })),
    );
  },

  async mCreateRole(payload) {
    const perms = assertAssignable(payload.perms);
    const role = {
      roleCode: `R-MOCK-${db.roles.length + 1}`,
      name: payload.name.trim(),
      builtin: false,
      perms,
      permLabels: perms.map(permLabel),
      usedBy: 0,
    };
    db.roles.push(role);
    logStaffRole("ROLE_CREATE", role.roleCode, `新建角色「${role.name}」`);
    persist();
    return delay({ ...role });
  },

  async mUpdateRole(roleCode, payload) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    // 预置只读：与后端同口径，要改先复制一份
    if (role.builtin) throw new Error("平台预置角色不可修改，请复制为自定义角色");
    const perms = assertAssignable(payload.perms);
    role.name = payload.name.trim();
    role.perms = perms;
    role.permLabels = perms.map(permLabel);
    logStaffRole("ROLE_UPDATE", roleCode, `角色「${role.name}」权限已更新`);
    persist();
    return delay({ ...role, usedBy: usersOfRole(roleCode) });
  },

  async mDeleteRole(roleCode) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    if (role.builtin) throw new Error("平台预置角色不可删除");
    const used = usersOfRole(roleCode);
    // 还有人在用就不许删 —— 删了那些人的权限凭空消失，而他们看不到任何解释
    if (used > 0) throw new Error(`还有 ${used} 人在用这个角色，先把他们撤下来`);
    db.roles = db.roles.filter((r) => r.roleCode !== roleCode);
    logStaffRole("ROLE_DELETE", roleCode, `删除角色「${role.name}」`);
    persist();
    return delay(undefined as unknown as void);
  },

  async mStaffLogs(mchAccountNo) {
    const all = db.staffLogs ?? [];
    return delay(
      [...all]
        .filter((l) => !mchAccountNo || l.targetAccountNo === mchAccountNo)
        .sort((a, b) => b.at - a.at),
    );
  },

  async mCommunities() {
    return delay(db.communitySeeds.map(toCommunity));
  },

  async mRegions(parent) {
    // 恒定只给启用的 —— 与后端 /biz/regions 同口径（停用的是运营的维护对象）
    return delay(
      db.regionSeeds.filter((r) => r.enabled && (parent ? r.parentCode === parent : !r.parentCode)),
    );
  },

  async mApplyCommunity(payload) {
    const merchantNo = requireMerchant();
    if (db.communityApplies.some((a) => a.name === payload.name && a.status === "PENDING")) {
      // 与后端同口径：重复提报不会让它更快通过，只会让运营的队列里多一条一样的
      throw new Error("这个小区你已经提报过，正在等运营处理");
    }
    const apply = {
      applyNo: `CA${Date.now()}`,
      merchantNo,
      merchantName: (() => {
        const n = db.merchantSeeds.find((m) => m.merchantNo === merchantNo)?.name;
        return n ? pick(n) : merchantNo;
      })(),
      ...payload,
      status: "PENDING" as const,
      submittedAt: Date.now(),
    };
    db.communityApplies.unshift(apply);
    persist();
    return delay({ ...apply });
  },

  async mMyCommunityApplies() {
    const merchantNo = requireMerchant();
    return delay(db.communityApplies.filter((a) => a.merchantNo === merchantNo));
  },

  async mSaveStore(payload) {
    db.store = { ...payload };
    persist();
    return delay({ ...db.store });
  },

  async mStoreQrcode() {
    const merchantNo = requireMerchant();
    // 落地页必须带 merchant_no —— 扫码进店的归因就靠它，进而决定费率档（ADR-004 §6）
    const url = `/pages/store/index?merchantNo=${merchantNo}&from=QR`;
    return delay({ url, printUrl: `${url}&size=print` });
  },

  async mShareKit(goodsNo) {
    const merchantNo = requireMerchant();
    const name = db.merchant.name || "我的小店";
    if (goodsNo) {
      const g = toGoods(findGoodsSeed(goodsNo));
      return delay({
        text: `【${name}】${g.title} ${money(g.price)}，到店自提或送货上门，点开直接下单`,
        posterUrl: "",
      });
    }
    return delay({
      text: `【${name}】开在你家楼下，常买的东西点两下就能再来一单：/pages/store/index?merchantNo=${merchantNo}`,
      posterUrl: "",
    });
  },

  // ---------------------------------------------------------------- 工作台
  async mTodo() {
    const merchantNo = db.merchant.merchantNo;
    const mine = merchantNo ? db.orders.filter((o) => belongsToMerchant(o, merchantNo)) : [];
    const pickupNo = db.merchant.pickupNo;
    const atMyPoint = db.merchant.isPickupPoint
      ? db.orders.filter((o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo))
      : [];
    return delay({
      toShip: mine.filter((o) => o.fulfillment === "EXPRESS" && o.status === "PAID").length,
      toDeliver: mine.filter((o) => o.fulfillment === "MERCHANT_DELIVERY" && o.status === "PAID").length,
      // 待备货按**我的单**算（mine），不是按我的自提点（atMyPoint）——
      // 买家常常选别家的点，两个数因此不相等。后端也是这个口径
      toStock: mine.filter((o) => o.fulfillment === "STORE_PICKUP" && o.status === "PAID").length,
      toVerify: atMyPoint.filter((o) => o.status === "ARRIVED").length,
      toPick: atMyPoint.filter((o) => o.status === "PAID").length,
      afterSale: mine.filter((o) => o.afterSale?.status === "APPLIED").length,
      toReply: db.reviews.filter((r) => r.merchantNo === merchantNo && !r.reply).length,
      quotable: 0, // 求团报价在 M3 批次交付
    });
  },

  async mStats() {
    const merchantNo = db.merchant.merchantNo;
    const mine = db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED",
    );
    const dayStart = new Date().setHours(0, 0, 0, 0);
    const today = mine.filter((o) => o.createdAt >= dayStart);
    const sum = (list: Order[]) => list.reduce((s, o) => s + o.amount.payableMinor, 0);
    const rs = db.reviews.filter((r) => r.merchantNo === merchantNo);
    const owned = mine.filter((o) => o.trafficSource === "MERCHANT_OWNED").length;
    return delay({
      todayOrders: today.length,
      todayGmvMinor: sum(today),
      monthOrders: mine.length,
      monthGmvMinor: sum(mine),
      currency: currentCurrency(),
      rating: rs.length ? Number((rs.reduce((s, r) => s + r.rating, 0) / rs.length).toFixed(1)) : 0,
      ratingCount: rs.length,
      ownedTrafficRate: mine.length ? owned / mine.length : 0,
    });
  },

  // ---------------------------------------------------------------- 商品
  async mGoodsList(q) {
    let list = myGoods();
    if (q.status === "ON_SALE") list = list.filter((g) => g.onSale);
    if (q.status === "OFF_SALE") list = list.filter((g) => !g.onSale);
    return delay(paginate(list, q.page, q.size));
  },

  async mGoodsDetail(goodsNo) {
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  async mSaveGoods(payload) {
    const merchantNo = requireMerchant();
    if (!payload.skus.length) throw new Error("至少要有一个规格");
    // 中文是基准语言：没有它就没有回落目标
    if (!payload.title["zh-CN"].trim()) throw new Error("中文商品名必填");

    // 展示价取最低 SKU 价 —— 列表页「¥12 起」的口径，端上不各算各的
    const price = Math.min(...payload.skus.map((k) => k.price));
    // 商品级也存一份按市场的展示价：各市场分别取该市场下的最低 SKU 价
    const priceByMarket = (["CNY", "USD", "AED"] as const).reduce<
      Partial<Record<CurrencyCode, number>>
    >((acc, cur) => {
      const vals = payload.skus
        .map((k) => k.priceByMarket?.[cur])
        .filter((v): v is number => v !== undefined);
      if (vals.length) acc[cur] = Math.min(...vals);
      return acc;
    }, {});
    const specGroups = payload.specGroups.map((g) => ({
      name: toI18n(g.name),
      options: g.options.map(toI18n),
      // 模板编码要跟着落库：不存就等于没做模板 ——
      // 二期想按规格聚合时，历史商品全是自由文本，只能回头刷数据
      optionCodes: g.optionCodes,
      templateNo: g.templateNo,
    }));
    const buildSkus = (existing: { skuNo: string; optionValues: unknown[] }[] = []) =>
      payload.skus.map((k) => ({
        // 复用原 skuNo：历史订单行、购物车、库存流水都引用它，重新生成等于把它们指向不存在的规格
        skuNo:
          k.skuNo ??
          existing.find(
            (e) => e.optionValues.length === k.optionValues.length && e.skuNo === k.skuNo,
          )?.skuNo ??
          nextNo("SK"),
        optionValues: k.optionValues.map(toI18n),
        price: k.price,
        // 分别定价是真源；只填了当前市场时其余市场留空 = 不在那边卖
        priceByMarket: k.priceByMarket,
        stock: k.stock,
      }));

    if (payload.goodsNo) {
      const seed = findGoodsSeed(payload.goodsNo);
      seed.title = fillI18n(payload.title);
      seed.subtitle = fillI18n(payload.subtitle);
      seed.price = price;
      seed.priceByMarket = priceByMarket;
      seed.specGroups = specGroups as (typeof seed.specGroups);
      seed.skus = buildSkus(seed.skus) as (typeof seed.skus);
      persist();
      return delay(toGoods(seed));
    }

    const goodsNo = nextNo("G");
    db.goodsSeeds.unshift({
      goodsNo,
      merchantNo,
      type: payload.type,
      categoryNo: "",
      title: fillI18n(payload.title),
      subtitle: fillI18n(payload.subtitle),
      cover: "📦",
      images: ["📦"],
      price,
      priceByMarket,
      onSale: true,
      salesCount: 0,
      specGroups,
      skus: buildSkus(),
      promotions: [],
    } as unknown as (typeof db.goodsSeeds)[number]);
    persist();
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  async mToggleGoods(goodsNo, onSale) {
    const seed = findGoodsSeed(goodsNo);
    seed.onSale = onSale;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStock(goodsNo, skuNo, stock) {
    const seed = findGoodsSeed(goodsNo);
    const sku = seed.skus.find((s) => s.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    sku.stock = stock;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStoreStock(goodsNo, skuNo, stock) {
    /*
     * mock 里没有门店维度的库存表 —— 单店是 mock 的默认形态，
     * 而门店级库存要在真后端上才谈得上。这里与 mSaveStock 同行为：
     * 让端上的交互能跑通，真实语义（没设库存的店视为 0）由后端用例守。
     */
    return this.mSaveStock(goodsNo, skuNo, stock);
  },

  // ---------------------------------------------------------------- 图片与识别
  async mUploadImage(tempPath) {
    // mock 直接把端上的临时路径当 URL 用 —— H5 下 blob: 路径能直接显示。
    // 真实环境：小程序走 uni.uploadFile（域名要在白名单），App 无此限制；
    // 服务端返回 CDN URL（E9）
    if (!tempPath) throw new Error("没有选到图片");
    return delay({ url: tempPath }, 400);
  },

  async mRecognizeGoods() {
    // ⚠️ **这是假识别**：mock 里没有模型，按当前时间在几个常见品类里轮换，
    // 只为把「识别 → 预填 → 店主改 → 保存」这条交互链路跑通。
    // 真实实现在服务端（小程序不能跑本地模型），置信度由模型给。
    const guesses: { title: string; type: Goods["type"] }[] = [
      { title: "东北五常大米", type: CATEGORY_TYPE.NORMAL },
      { title: "本地土鸡蛋", type: CATEGORY_TYPE.FRESH },
      { title: "洗衣液 大容量装", type: CATEGORY_TYPE.NORMAL },
    ];
    const g = guesses[db.seq % guesses.length]!;
    return delay({ ...g, confidence: 0.72 }, 700);
  },

  // ---------------------------------------------------------------- 类目
  async mCategoryTree() {
    // 直接给整棵树：类目就几十条且极少变，分层拉取只会让选择器多两次等待
    return delay(db.categories.map((c) => ({ ...c })));
  },

  // ---------------------------------------------------------------- 规格模板
  async mSpecTemplates(categoryType) {
    const merchantNo = db.merchant.merchantNo;
    return delay(
      db.specTemplates.filter((tpl) => {
        // 商家自己存的模板不限类目 —— 他存的时候就是按自己的货存的
        if (tpl.scope === "MERCHANT") return tpl.merchantNo === merchantNo;
        // 平台模板按类目推荐；不传类目就全给
        return !categoryType || tpl.categoryType === categoryType;
      }),
    );
  },

  async mSaveSpecTemplate(payload) {
    const merchantNo = requireMerchant();
    const options = payload.options.map((o) => o.trim()).filter(Boolean);
    if (!payload.name.trim() || !options.length) throw new Error("规格名和选项都要填");

    // 商家自存的模板**不给 code**：code 的意义是跨商家统一口径，
    // 各家自己起的编码互不相通，给了反而制造「看起来能聚合其实不能」的假象
    const created: SpecTemplate = {
      templateNo: nextNo("ST"),
      scope: "MERCHANT",
      merchantNo,
      name: payload.name.trim(),
      options: options.map((label) => ({ label })),
    };
    db.specTemplates.push(created);
    persist();
    return delay({ ...created });
  },

  // ---------------------------------------------------------------- 订单与配送
  async mOrderList(q) {
    const merchantNo = db.merchant.merchantNo;
    let list = merchantNo ? db.orders.filter((o) => belongsToMerchant(o, merchantNo)) : [];
    if (q.status) list = list.filter((o) => o.status === q.status);
    return delay(paginate(list, q.page, q.size));
  },

  async mOrderDetail(orderNo) {
    return delay({ ...findOrder(orderNo) });
  },

  async mShip(orderNo, expressNo) {
    const o = findOrder(orderNo);
    assertTransition(o.status, "SHIPPED");
    o.status = "SHIPPED";
    o.expressNo = expressNo;
    pushTimeline(o, "已发货");
    pushMessage(
      "TRADE",
      "你的订单已发货",
      `运单号 ${expressNo}，可在订单详情查看物流`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mDelivered(orderNo) {
    const o = findOrder(orderNo);
    // 商家自送没有骑手轨迹，老板点一下就是送到了 —— 直接进完成态（ADR-005 §5）
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已送达");
    pushMessage(
      "TRADE",
      "订单已送达",
      "商家已标记送达，有问题可在订单里申请售后",
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mDeliveryRule() {
    return delay({ ...db.deliveryRule });
  },

  async mSaveDeliveryRule(rule) {
    db.deliveryRule = { ...rule };
    persist();
    return delay({ ...db.deliveryRule });
  },

  // ---------------------------------------------------------------- 自提点履约
  /**
   * 履约总览。三个数都从**同一份订单数据**算出来，不另存计数器 ——
   * 计数器与明细分开维护，迟早会出现「总览说 3 单、点进去只有 2 单」。
   */
  async mPickupOverview() {
    const pickupNo = db.merchant.pickupNo;
    const mine = db.orders.filter(
      (o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo),
    );
    const startOfDay = new Date().setHours(0, 0, 0, 0);
    const itemCount = mine
      .filter((o) => o.status === "COMPLETED")
      .reduce((n, o) => n + o.items.reduce((k, it) => k + it.qty, 0), 0);
    return delay({
      pickupNo: pickupNo || "",
      pickupName: db.merchant.name || "",
      pendingVerify: mine.filter((o) => o.status === "ARRIVED").length,
      // 「批次」= 今天标记过到货的单，按到货动作聚合
      arrivedBatches: mine.filter((o) => o.status !== "PAID" && o.createdAt >= startOfDay)
        .length,
      // 服务费按**已完成**的件数算：货还没交到人手上，这笔钱不该先算进来
      serviceFeeMinor: itemCount * SETTLE.fulfillFeePerItemMinor,
    });
  },

  async mPickupOrders() {
    const pickupNo = db.merchant.pickupNo;
    return delay(
      db.orders.filter(
        (o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo),
      ),
    );
  },

  async mPickingList() {
    const pickupNo = db.merchant.pickupNo;
    const map = new Map<string, PickingRow>();
    for (const o of db.orders) {
      if (o.fulfillment !== "STORE_PICKUP") continue;
      if (pickupNo && o.pickupNo !== pickupNo) continue;
      if (!["PAID", "ARRIVED"].includes(o.status)) continue;
      const buyer = o.buyerNickname ?? db.user.nickname;
      for (const it of o.items) {
        const cur = map.get(it.skuNo) ?? {
          goodsNo: it.goodsNo,
          skuNo: it.skuNo,
          title: it.title,
          cover: it.cover,
          spec: it.spec,
          totalQty: 0,
          buyers: [],
        };
        cur.totalQty += it.qty;
        cur.buyers.push({ nickname: buyer, qty: it.qty, orderNo: o.orderNo });
        map.set(it.skuNo, cur);
      }
    }
    return delay([...map.values()].sort((a, b) => b.totalQty - a.totalQty));
  },

  async mMarkArrived(orderNos, _pickupNo) {
    const changed: Order[] = [];
    for (const no of orderNos) {
      const o = db.orders.find((x) => x.orderNo === no);
      if (!o || o.status !== "PAID") continue;
      assertTransition(o.status, "ARRIVED");
      o.status = "ARRIVED";
      pushTimeline(o, "已到自提点，请及时取货");
      pushMessage(
        "TRADE",
        "到货了，记得来取",
        `取货码 ${o.verifyCode ?? ""}，到 ${o.pickupName ?? "自提点"} 报码即可`,
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      changed.push(o);
    }
    persist();
    return delay(changed);
  },

  // ---------------------------------------------------------------- 售后
  async mAfterSaleList() {
    const merchantNo = db.merchant.merchantNo;
    /*
     * 返回**售后单**，不是订单。后端 /biz/after-sale 给的就是 List<AfterSaleVO>，
     * 而这里此前返回的是订单、且按 `o.status === "REFUNDING"` 筛 ——
     * 两个错误叠在一起：订单永远不会是这个状态（那是售后单的状态），
     * 于是商家端「售后」页签恒为空；就算筛出来了，形状也和后端对不上。
     */
    return delay(
      db.orders
        .filter((o) => o.afterSale && belongsToMerchant(o, merchantNo))
        .map((o) => o.afterSale!),
    );
  },

  async mApproveAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    as.merchantReply = reply;
    as.updatedAt = Date.now();

    /**
     * 同意后**按类型分流**，这是售后闭环此前缺的那半段：
     *   · 仅退款   → 直接退款
     *   · 退货退款 → 等用户寄回、商家确认收货**之后**才退款
     * 两者合成一条路的后果是「退款了货没回来」。
     */
    if (as.type === "RETURN_REFUND") {
      as.status = "REFUNDING";
      pushTimeline(o, "商家已同意退货，等待寄回");
      pushMessage(
        "TRADE",
        "退货申请已通过",
        "请寄回商品并在订单里填写退货运单号",
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      persist();
      return delay(o);
    }

    settleRefund(o, "商家已同意退款");
    persist();
    return delay(o);
  },

  async mRejectAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (!reply.trim()) throw new Error("驳回必须填写理由");
    as.merchantReply = reply;
    as.updatedAt = Date.now();
    // 驳回**不改订单状态** —— 用户还得能上升平台，直接置回已完成就把路堵死了
    as.status = "REJECTED";
    pushTimeline(o, `商家驳回：${reply}`);
    pushMessage(
      "TRADE",
      "售后被驳回",
      reply,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mConfirmReturn(afterSaleNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 用户还没寄（没填运单号）就点确认收货，多半是误操作
    // 后端没有独立的「等寄回 / 已收货」两态：同意即 REFUNDING，
    // 是否已寄回看 returnExpressNo 有没有值
    if (as.status !== "REFUNDING") throw new Error("该售后已处理或状态不对");
    if (!as.returnExpressNo) throw new Error("用户还未填写退货运单号");
    as.updatedAt = Date.now();
    pushTimeline(o, "商家已确认收到退货");
    // 确认收货与退款是同一个动作的两半，中间不留悬空态
    settleRefund(o, "退款已发起");
    persist();
    return delay(o);
  },

  // ---------------------------------------------------------------- 团购与报价
  async mGroupList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(
      db.groupSeeds
        .map(buildGroupBuy)
        .filter((g) => g.merchant.merchantNo === merchantNo),
    );
  },

  async mCreateGroup(goodsNo) {
    requireMerchant();
    const goods = toGoods(findGoodsSeed(goodsNo));
    // 商品没配 {起团人数, 团购价} 就不能开团 —— 团价从哪来？（需求 §五之四）
    if (!goods.groupBuy) throw new Error("该商品未配置团购价，先在商品里配置");
    // 截止时间取「团有效期」与「当日截单」的更早者：截单已过就只能开出一个死团
    // （倒计时直接 00:00:00），不如当场说清楚
    if (goods.cutoffAt && goods.cutoffAt <= Date.now()) {
      throw new Error("今日已截单，明天再开团");
    }
    const seed = {
      groupNo: nextNo("GB"),
      goodsNo,
      // 成团单位是自提点：拼的是一车送到一个点的成本，跨点凑人对成本无帮助
      pickupNo: db.merchant.pickupNo ?? db.communitySeeds[0]!.pickups[0]!.pickupNo,
      initiatorNickname: db.merchant.name || "商家",
      initiatorAvatar: db.merchant.logo || "🏪",
      createdAt: Date.now(),
      members: [],
      joined: false,
    };
    db.groupSeeds.unshift(seed as (typeof db.groupSeeds)[number]);
    persist();
    return delay(buildGroupBuy(seed as (typeof db.groupSeeds)[number]));
  },

  async mRequestList() {
    // 商家看得到所有开放中的需求单。初期靠运营人肉指派（P-8.2.2），
    // 这里先全量放出，商家自己挑 —— 需求少的时候人肉和自助没差别
    return delay(db.requests.filter((r) => r.status === "OPEN").map(toGroupRequest));
  },

  async mQuote(requestNo, payload) {
    const merchantNo = requireMerchant();
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求单不存在");
    if (seed.status !== "OPEN") throw new Error("该需求单已不接受报价");

    const exist = seed.quotes.find((q) => q.merchantNo === merchantNo);
    if (exist) {
      // 选定后锁价：加价在技术上做不到，不靠事前审核（ADR-003）
      if (exist.locked) throw new Error("已被选定并锁价，不能再改");
      // 改价留痕。**只公示涨价** —— 降价对邻居是好事，公示反而劝退商家降价
      if (payload.priceMinor > exist.priceMinor) {
        exist.revisions.push({ priceMinor: exist.priceMinor, at: Date.now() });
      }
      exist.priceMinor = payload.priceMinor;
      exist.minCount = payload.minCount;
      exist.desc = payload.desc;
    } else {
      seed.quotes.push({
        quoteNo: nextNo("QT"),
        merchantNo,
        priceMinor: payload.priceMinor,
        minCount: payload.minCount,
        desc: payload.desc,
        validUntil: Date.now() + 3 * 86400_000,
        createdAt: Date.now(),
        chosen: false,
        revisions: [],
        locked: false,
      });
    }
    persist();
    return delay(toGroupRequest(seed));
  },

  // ---------------------------------------------------------------- 评价
  async mReviewList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(db.reviews.filter((r) => r.merchantNo === merchantNo));
  },

  async mReplyReview(reviewNo, reply) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    r.reply = reply;
    persist();
    return delay({ ...r });
  },

  async mAppealReview(reviewNo, reason, images = []) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    // 只有低分可申诉：四星五星开放申诉，等于「凡是不满意的都申诉一遍」，
    // 平台裁决台会被淹掉，真正的恶意差评反而排不上
    if (r.rating > REVIEW_RULES.appealMaxRating) {
      throw new Error(`只有 ${REVIEW_RULES.appealMaxRating} 星及以下的评价可以申诉`);
    }
    if (r.appeal) throw new Error("该评价已申诉过，等待平台裁决");
    if (!reason.trim()) throw new Error("请填写申诉理由");

    r.appeal = {
      appealNo: nextNo("RA"),
      reason: reason.trim(),
      images,
      status: "PENDING",
      submittedAt: Date.now(),
    };
    pushMessage("SYSTEM", "申诉已提交", "平台会在 3 个工作日内给出裁决", "/pages/reviews/index");
    persist();
    return delay({ ...r });
  },

  // ---------------------------------------------------------------- 营销
  async mCampaignList() {
    const merchantNo = db.merchant.merchantNo;
    // 过期的活动自动置 ENDED：靠人手动结束的话，列表里永远挂着一堆「进行中」的死活动
    const now = Date.now();
    db.campaigns.forEach((c) => {
      if (c.status === "RUNNING" && c.endAt <= now) c.status = "ENDED";
    });
    return delay(db.campaigns.filter((c) => c.merchantNo === merchantNo));
  },

  async mSaveCampaign(payload) {
    const merchantNo = requireMerchant();
    if (payload.endAt <= payload.startAt) throw new Error("结束时间要晚于开始时间");
    // 限时特价必须限定商品：全店改价不是「特价」，是调价，走商品编辑
    if (payload.type === "FLASH" && !payload.goodsNos.length) {
      throw new Error("限时特价必须选择参与商品");
    }
    /*
     * 只有满减能限定门店（后端 70005）。判据是活动在哪一刻生效：
     * 满减在算价时生效，那时顾客已选好自提点；限时特价与买赠改的是商品页的展示，
     * 而浏览商品时自提点还没选 —— 会出现「页面 ¥9.90、下单 ¥12.80」。
     * mock 也要拒，否则开发期建得成、连真后端才被打回。
     */
    if (payload.storeNo && payload.type !== "FULL_CUT") {
      throw new Error("只有满减能限定门店");
    }
    if (payload.type === "COUPON" && !payload.totalCount) {
      // 不设上限的券等于开着口子发钱，预算穿了才发现就晚了
      throw new Error("店铺券必须设置发放总量");
    }

    if (payload.campaignNo) {
      const c = db.campaigns.find((x) => x.campaignNo === payload.campaignNo);
      if (!c) throw new Error("活动不存在");
      if (c.status === "ENDED") throw new Error("已结束的活动不能再改");
      Object.assign(c, payload);
      persist();
      return delay({ ...c });
    }

    const created: MarketingCampaign = {
      ...payload,
      campaignNo: nextNo("CP"),
      merchantNo,
      status: payload.startAt <= Date.now() ? "RUNNING" : "DRAFT",
      takenCount: 0,
      usedCount: 0,
      goodsNos: payload.goodsNos,
    };
    db.campaigns.unshift(created);
    persist();
    return delay({ ...created });
  },

  async mToggleCampaign(campaignNo, running) {
    const c = db.campaigns.find((x) => x.campaignNo === campaignNo);
    if (!c) throw new Error("活动不存在");
    // 已结束不可复活：时段已过，再打开只会得到一个立刻又结束的活动
    if (c.status === "ENDED") throw new Error("活动已结束，不能重新开启");
    c.status = running ? "RUNNING" : "PAUSED";
    persist();
    return delay({ ...c });
  },

  // ---------------------------------------------------------------- 客户与复购
  async mCustomers() {
    const merchantNo = db.merchant.merchantNo;
    const DAY = 86400_000;
    const map = new Map<
      string,
      { avatar: string; count: number; spent: number; last: number; owned: number }
    >();

    for (const o of db.orders) {
      if (o.status === "CANCELLED" || !belongsToMerchant(o, merchantNo)) continue;
      const key = o.buyerNickname ?? db.user.nickname;
      const cur = map.get(key) ?? { avatar: "🙂", count: 0, spent: 0, last: 0, owned: 0 };
      cur.count += 1;
      cur.spent += o.amount.payableMinor;
      cur.last = Math.max(cur.last, o.createdAt);
      if (o.trafficSource === "MERCHANT_OWNED") cur.owned += 1;
      map.set(key, cur);
    }

    const rows = [...map.entries()].map(([nickname, v]) => {
      const days = Math.floor((Date.now() - v.last) / DAY);
      return {
        nickname,
        avatar: v.avatar,
        orderCount: v.count,
        totalSpentMinor: v.spent,
        lastOrderAt: v.last,
        daysSinceLast: days,
        // 沉默 = **曾经常来**（买过 ≥2 次）**且**最近没来（超 14 天）。
        // 只看「久没来」会把只买过一次的路人也算进去 —— 那不是流失，是本来就没建立关系
        silent: v.count >= 2 && days >= 14,
        source: v.owned > v.count / 2 ? ("MERCHANT_OWNED" as const) : ("PLATFORM" as const),
      };
    });

    // 沉默的排前面：这是店主唯一能立刻行动的信号，埋在列表底部等于没有
    rows.sort((a, b) => Number(b.silent) - Number(a.silent) || b.orderCount - a.orderCount);
    return delay(rows);
  },

  // ---------------------------------------------------------------- 结算
  /**
   * 费率卡。**费率是万分比整数**（与后端 RateCardVO 一致）：2% 存成 200。
   * 直接当百分数显示会把 2% 显示成 200%，这种错在界面上看着还挺"合理"。
   */
  async mRateCard() {
    const pct = (r: number) => Math.round(r * 10000);
    return delay({
      merchantOwnedRate: pct(SETTLE.commissionRate.MERCHANT_OWNED),
      platformRate: pct(SETTLE.commissionRate.PLATFORM),
      note: "自带客流（扫店铺码进店）零佣金；平台客流按公示费率收取。费率以下单时快照为准，调整不影响历史订单。",
    });
  },

  async mSettleList(allStores) {
    const merchantNo = db.merchant.merchantNo;
    /*
     * **一个子订单一行**，与后端 stl_bill 同形 —— 这里此前造的是一套「按周聚合的账单」
     * （billNo / periodStart / orderCount），后端从来没有过那个模型。
     * 页面照着 mock 写，于是连真后端时字段整片 undefined，而 mock 下一直是绿的。
     */
    const settled = db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && ["COMPLETED", "REFUNDED"].includes(o.status),
    );
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    const scope = allStores ? null : home?.storeNo;

    return delay(
      settled
        .filter(() => !scope || Boolean(home))
        .map((o) => {
          const gross = o.amount.payableMinor;
          // 佣金按客流来源分档：自带客流零佣金（ADR-004 §6）
          const rate = SETTLE.commissionRate[o.trafficSource ?? "PLATFORM"];
          const commission = Math.round(gross * rate);
          // 自提点履约服务费按件。供货方付、承接方收，两个角色都是自己时账面抵消
          const serviceFee =
            o.fulfillment === "STORE_PICKUP"
              ? o.items.reduce((n, it) => n + it.qty, 0) * SETTLE.fulfillFeePerItemMinor
              : 0;
          return {
            settleNo: `SB${o.orderNo}`,
            subOrderNo: o.orderNo,
            orderNo: o.orderNo,
            merchantNo,
            grossMinor: gross,
            commissionMinor: commission,
            serviceFeeMinor: serviceFee,
            netMinor: gross - commission - serviceFee,
            trafficSource: o.trafficSource ?? "PLATFORM",
            commissionRate: Math.round(rate * 10000),
            // 退过款的走回退态：账面上不能出现「退过款还照结」的钱（ADR-002 §3）
            status: o.status === "REFUNDED" ? ("REVERSED" as const) : ("SPLIT" as const),
            createdAt: o.createdAt,
            splitAt: o.status === "REFUNDED" ? undefined : o.createdAt,
            storeNo: home?.storeNo,
            // 门店没单独配号就走主体默认号 —— 那就是合并结算
            payMerchantNo: home?.payMerchantNo ?? "PM-MOCK-ENTITY",
          };
        }),
    );
  },

  // ---------------------------------------------------------------- 到货异常
  async mReportShortage(orderNo, payload) {
    const o = findOrder(orderNo);
    const label = payload.kind === "SHORTAGE" ? "短少" : "破损";
    pushTimeline(o, `自提点上报${label}：${payload.note}`);
    // 只留痕、通知用户，**不自动退款** —— 责任在供货方还是承接方尚未定（矩阵 M4），
    // 自动退等于默认平台兜底
    pushMessage(
      "TRADE",
      `商品${label}已上报`,
      `${payload.note}。我们会尽快处理，你也可以直接申请售后`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mVerify(code) {
    const o = db.orders.find((x) => x.verifyCode === code);
    if (!o) throw new Error("核销码无效");
    if (o.status === "COMPLETED") throw new Error("该订单已核销");
    const pickupNo = db.merchant.pickupNo;
    if (pickupNo && o.pickupNo && o.pickupNo !== pickupNo) throw new Error("这单不在本自提点");
    if (o.status === "PAID") {
      o.status = "ARRIVED";
      pushTimeline(o, "已到自提点");
    }
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已核销完成");
    persist();
    return delay(o);
  },

  /**
   * 批量核销。**逐条尝试、失败逐条回报**，不整批回滚 ——
   * 一张废码不该让另外四单白扫；而「3 成功 2 失败」这种汇总，店主还得自己一个个找出是哪两单。
   * 单条的三校验完全复用，避免两条路的规则各写一遍（那必然漂）。
   */
  async mVerifyBatch(codes) {
    const failed: VerifyBatchResult["failed"] = [];
    let successCount = 0;
    for (const code of codes) {
      try {
        await this.mVerify(code);
        successCount += 1;
      } catch (e) {
        failed.push({ code, reason: (e as Error).message });
      }
    }
    return delay({ successCount, failed });
  },

  /**
   * 按取货码**片段**搜单。输码核销走不通时的最后一条路：
   * 码磨花了、屏幕反光、邻居只记得后四位。
   *
   * 与真后端同口径：**子串匹配**（`contains`），且只在本自提点的单里找 ——
   * 跨点搜出来的单他也核销不了，列出来只会让人以为「明明有这单为什么核不了」。
   */
  async mVerifySearch(keyword) {
    const k = keyword.trim();
    const pickupNo = db.merchant.pickupNo;
    if (!k) return delay([]);
    return delay(
      db.orders.filter(
        (o) =>
          !!o.verifyCode
          && o.verifyCode.includes(k)
          && (!pickupNo || o.pickupNo === pickupNo),
      ),
    );
  },

  // ---- 积分：商家只感知发分服务费与开关（V34）。
  // 抵扣、补差、资金池对他全部不可见 —— 他收到的是订单全额减各项费用。
  async mPointsAccount() {
    return delay(pointsAccount());
  },

  async mPointsRecords() {
    return delay(pointsFeeRecords());
  },

  async mPointsToggle(req) {
    pointsEnabled = req.enabled;
    return delay(pointsAccount());
  },
};
