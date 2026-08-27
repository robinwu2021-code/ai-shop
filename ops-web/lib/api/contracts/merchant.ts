// 覆盖范围：商家治理（P-11.1 入驻审核 / 档案 / 认证标 / 封禁）。
// ⚠️ 契约禁止 delete*：下架商家用 archiveMerchant，封禁用 setMerchantStatus。
import type {
  ModeRisk,
  FundsMode,
  Qualification,
  MerchantPlanRow,
  PlanDef,
  PlanUpgradeSignal,
  AdmissionPolicy, AuthCode, AuthCodeSetResult, LegalForm, DepositTxn, DepositTxnType, Merchant, MerchantApply, MerchantDeposit, MerchantStaffRow, MerchantStatus, Page, StoreMode, Violation, ViolationAction, ViolationType, StoreFulfillmentRow } from "@/lib/types";
import type { ApplyQ, MerchantQ } from "../query";

export interface MerchantApi {
  // ── 门店经营模式与弱主体准入 ─────────────────────────────────

  storeModes(merchantNo: string): Promise<StoreMode[]>;
  /** 无照主体 × 自营门店的税务敞口清单。后端按敞口倒序 */
  modeRisk(): Promise<ModeRisk[]>;
  /**
   * 改资金路径。**拒两种**：取值不在枚举里（400）、无照主体要走归集（409）。
   * 后者的理由不是税负偏高，是成本不可税前扣除 —— 走一单亏一单。
   */
  setFundsMode(v: { merchantNo: string; fundsMode: FundsMode }): Promise<Merchant>;

  // ── 资质（P1-7）。后端三个接口早已实现，此前**前端零调用** ─────────
  /** 某商家已登记的资质。上架的两个闸门读的就是这张表 */
  qualifications(merchantNo: string): Promise<Qualification[]>;
  /** 登记或更新。qualNo 为空 = 新增；expireAt 为 null = 长期有效 */
  saveQualification(v: { merchantNo: string } & Partial<Qualification>): Promise<Qualification>;
  /** 撤销。**不物理删** —— 「当初有没有这张证」是要能查的 */
  revokeQualification(qualNo: string): Promise<Qualification>;

  /**
   * 改门店经营模式。
   *
   * **只对新单生效** —— 结算单在生成时就快照了 businessMode，历史账不受影响。
   * 页面必须把这句话显示出来，否则运营会以为改了模式能一并修正历史。
   *
   * 切第三方要求该店有可用收款号；没有会被后端拒（70012）——
   * 不拦的后果不是报错而是**静默欠款**：单照常成交，钱卡在平台侧下不去。
   */
  setStoreBusinessMode(v: { storeNo: string; businessMode: StoreMode["businessMode"] }): Promise<StoreMode>;

  /** 三档准入策略。 */
  admissionPolicies(): Promise<AdmissionPolicy[]>;
  updateAdmissionPolicy(v: { legalForm: LegalForm } & Partial<AdmissionPolicy>): Promise<void>;

  /**
   * 这家商家的员工与门店授权（**只读**）。
   *
   * 契约里刻意没有写的那一半：平台不能改商家的授权 ——
   * 那是商家的雇佣关系，替他决定谁能动他的钱不是运营该有的按钮。
   * 要处置该商家走封禁，那是另一个层级、另一个权限码。
   */
  merchantStaff(merchantNo: string): Promise<MerchantStaffRow[]>;

  /**
   * 商家履约配置（方案 v4，**只读**）：门店 × 送货方式矩阵。
   * 运营接到履约投诉时的第一入口 —— 先看这家店到底开了哪几路，再谈处置。
   * 写入口在 B 端；平台的干预走锁路（P2），不在本契约。
   */
  merchantFulfillment(merchantNo: string): Promise<StoreFulfillmentRow[]>;
  /*
   * 锁路 / 解锁（P2）。用锁不用删：商家配置原样保留，处置结束一键恢复。
   *
   * **一个动作两个端点，所以是两个方法。** 此前是一个 `lockChannel(…, locked)`，
   * 路径里写着 `${locked ? "lock" : "unlock"}` —— 后端那边本来就是两个
   * `@PostMapping`，契约把它们折成一个，等于把「调哪个端点」藏进了一个布尔参数。
   * 直接的后果：规格生成器**一条都抽不到**（它的正则在模板串里的引号处断掉），
   * 于是整份 `openapi-ops.yaml` 生成不出来，而没有任何东西会报。
   */
  lockChannel(storeNo: string, channel: string, reason?: string): Promise<void>;
  unlockChannel(storeNo: string, channel: string): Promise<void>;

  merchantDeposit(merchantNo: string): Promise<MerchantDeposit>;
  depositTxns(merchantNo: string): Promise<DepositTxn[]>;
  /** @param amountMinor 有符号：缴纳为正、扣划为负 */
  addDepositTxn(v: { merchantNo: string; txnType: DepositTxnType; amountMinor: number; reason?: string }): Promise<void>;

  // ── 入驻审核（P-11.1.1）────────────────────────────────────────
  //
  // **申请单与商家主体是两个资源**：通过之前商家不存在，所以审核动作打在
  // applyNo 上而不是 merchantNo 上。这一段已接真后端 `/ops/merchant/apply/**`。

  /**
   * 入驻申请检索。
   *
   * @param q.status 逗号分隔；**不传只给待办两档**（PENDING/REVIEWING）——
   *   运营台默认打开就该是「要我做的事」，已处理的属于历史
   */
  listApplies(q?: ApplyQ): Promise<Page<MerchantApply>>;

  /** 受理：告诉商家「有人在看了」。不改变审核结果，也不是通过的必经步骤 */
  acceptApply(applyNo: string): Promise<void>;

  /**
   * 审核。
   *
   * @param reason       驳回**必填** —— 不写理由等于让对方猜着改
   * @param serviceScope 通过时补/改服务范围；不传沿用申请单上的值
   * @param communityNos 同上。**商家没填时运营必须在这里补** ——
   *   否则商家通过审核、上完架，却对谁都不可见，而这个故障不报错
   * @param grantCodes   通过时授予的经营类目码。**与通过同一个事务** ——
   *   分两步做会留下「通过了但一个码都没授」的状态：商家收到通过通知、
   *   进去建品、上架被拒，而错误说的是「你还没有资质授权」。
   *   空 = 只经营无门槛类目（合法，不是漏填）
   */
  auditApply(
    applyNo: string,
    approved: boolean,
    reason?: string,
    serviceScope?: string,
    communityNos?: string[],
    grantCodes?: string[],
  ): Promise<void>;

  listMerchants(q?: MerchantQ): Promise<Page<Merchant>>;
  getMerchant(merchantNo: string): Promise<Merchant>;
  /** 审核推进（DRAFT→SUBMITTED→REVIEWING→APPROVED/REJECTED），非法迁移抛错。 */
  /**
   * 审核推进。
   *
   * @param communityNos 覆盖社区。**status=APPROVED 且商家按社区经营时必填** ——
   *   不给的话商家审核通过却对谁都不可见（ADR-009：service_scope 默认 COMMUNITY，
   *   一个社区都没覆盖 = C 端任何人都搜不到），而这个故障没有任何报错，
   *   商家和运营都查不出原因。后端会直接拒绝这种提交。
   */
  setMerchantStatus(
    merchantNo: string,
    status: MerchantStatus,
    remark?: string,
    communityNos?: string[],
  ): Promise<Merchant>;
  /** 认证标授予/撤销（P-11.1.2）。 */
  /**
   * 认证标授予/撤销（P-11.1.2）。
   *
   * 只有已过审、且毁约次数未达上限的商家能拿标 —— 认证标是平台的背书，
   * 挂在一个正在毁约的商家身上，赔的是平台的信用。
   */
  setMerchantVerified(merchantNo: string, verified: boolean): Promise<Merchant>;
  archiveMerchant(merchantNo: string): Promise<Merchant>;
  unarchiveMerchant(merchantNo: string): Promise<Merchant>;

  // ── 类目授权（P-11.1.3）────────────────────────────────────────

  /** 授权码目录。按码授权而不是按类目节点：类目树会重构，"能不能卖菜"不会。 */
  listAuthCodes(): Promise<AuthCode[]>;

  /**
   * 改一个商家的类目授权范围。
   *
   * 校验都在 mock 层：
   * - 只有 `APPROVED` 的商家能授权 —— 没过审就授权等于提前放行；
   * - 需要资质的码，商家必须已有对应资质；
   * - **不能把授权撤空**：商家会静默失去上架能力，要停就走封禁/归档，那是明示的动作；
   * - **该码下还有在售商品的不能撤**：撤了架上还挂着那类商品，谁也说不清它算不算违规。
   */
  /**
   * 全量覆盖经营授权码。
   *
   * <p>响应里带 `revoked` 与 `affected`：**撤码时运营要看得见代价** ——
   * 那些在架商品下次上架就会被闸门拒。看不见的话，一次「顺手收紧」会在几天后
   * 变成商家的「我的货怎么上不去了」，而两件事没人会联系起来。
   */
  setMerchantAuthCodes(v: {
    merchantNo: string;
    codes: string[];
    reason: string;
  }): Promise<AuthCodeSetResult>;

  // ── 违规处置与信用档案（P-11.1.4 / 11.1.5）─────────────────────

  listViolations(q?: { merchantNo?: string }): Promise<Violation[]>;

  /**
   * 记一条违规并执行处置。
   *
   * - `detail` 必填：没有事实的处置在申诉时站不住；
   * - `BREACH`（毁约）**只有这一类**计入 `breachCount` —— 别的违规也计的话，
   *   ADR-003 里"毁约达阈值即限制报价"那条规则的阈值就失去意义了；
   * - `SUSPEND` 会真的把商家推到 `SUSPENDED`，走同一张状态机。
   */
  recordViolation(v: {
    merchantNo: string;
    type: ViolationType;
    action: ViolationAction;
    detail: string;
    /**
     * 门店级处置的对象门店。**`STORE_OFFLINE` 必填，其余动作必须为空** ——
     * 处置动作与它作用的对象是同一次提交的两半，分开发就会出现
     * 「压了店但没有处置记录」或反过来。
     */
    storeNo?: string;
  }): Promise<Violation>;

  // ── 增值包与门店额度（P-11.2.2~11.2.6）─────────────────────────
  //
  // 一期**没有支付**：商家点「升级」→ 联系平台 → 运营在这里授予。
  // 所以契约里没有 `payPlan`/`orderPlan` —— 那条链路（通道签约、发票、退订退款）
  // 是独立项目，在验证「有没有人愿意买」之前建它，是拿最贵的一步去赌未验证的假设。

  /**
   * 到期与降级看板。
   *
   * @param q.filter `EXPIRING_7D`（7 天内到期且仍在生效）/ `GRACE`（宽限期中）/
   *   `DOWNGRADED`（已降级）。三个筛选各对应一个动作：去催、去救、去回访。
   *   窗口取 7 天 = 宽限期长度：**催的窗口与救的窗口一样长**，
   *   于是「催过一轮还没续」与「已经掉进宽限期」在时间上刚好接续
   */
  merchantPlans(q?: { filter?: string; keyword?: string; page?: number; size?: number }): Promise<Page<MerchantPlanRow>>;

  /** 升档信号：一个人名下多个主体 = 他已经在多店经营，只是绕过了额度。 */
  planUpgradeSignals(): Promise<PlanUpgradeSignal[]>;

  /**
   * 授予 / 延长。
   *
   * @param months 延长月数；**留空 = 只补缴不延长**，此时不刷新额度快照 ——
   *   他买的是当初那个额度，中途运营下调档位定义不该殃及他
   *
   * 延长的基准是「原到期日与今天里较晚的那个」：一律从今天重算，
   * 会吞掉他已付未用的那几天。
   */
  grantPlan(v: { merchantNo: string; planCode: string; months?: number; reason: string }): Promise<MerchantPlanRow>;

  /**
   * 单商家额度覆盖。**优先于档位快照**，`storeQuota` 传 null = 清除覆盖、回到快照。
   *
   * 为什么需要它：谈下来的条件常常不落在任何一档上（「先给你 5 家，年底再谈」）。
   * 没有这个口子，运营只能去改档位定义 —— 而那会影响这一档之后的所有新订阅。
   */
  overridePlanQuota(v: { merchantNo: string; storeQuota: number | null; staffQuota: number | null; reason: string }): Promise<MerchantPlanRow>;

  /** 档位定义。读挂商家只读码 —— 授予对话框要拿它填下拉。 */
  planDefs(): Promise<PlanDef[]>;

  /**
   * 改档位定义。**只影响之后新订阅的人**，已订阅的用的是自己的额度快照。
   *
   * 权限刻意与授予分开（`system:param:update`）：BD 能给某家授予套餐，
   * 但不能改「套餐是什么」—— 后者影响这一档之后的所有订阅。
   */
  savePlanDef(v: { planCode: string; storeQuota: number; staffQuota: number; crossStoreStats: boolean; trialDays: number; enabled: boolean }): Promise<PlanDef>;
}
