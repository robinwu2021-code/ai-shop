package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 商家治理（P-11.1）：档案查询、经营状态处置、认证标、违规记录。
 *
 * <p><b>这里的「状态」是经营状态，不是审核状态。</b>
 * 审核在申请单上（{@code mch_entity_apply}），两张表两件事 ——
 * 一家已在经营、又提交了第二张执照的商家，在合成一个字段的模型里无法表达。
 */
public interface MerchantGovernService {

    /**
     * 平台侧商家列表。跨商家，按社区/状态/关键词筛。
     *
     * <p><b>返回分页对象而不是裸数组</b>：运营端所有列表页都按 {@code {records,total}} 渲染，
     * 给一个数组的话页面会当成「空页」—— 接口 200、数据 38 条、页面显示「暂无数据」。
     */
    ai.neargo.shop.common.PageData<MerchantProfileVO> list(String status, String communityNo,
                                                           String keyword, long page, long size);

    MerchantProfileVO detail(String merchantNo);

    /**
     * 改经营状态。ACTIVE ⇄ SUSPENDED / FROZEN。
     *
     * @param remark 处置说明。**封禁必填** —— 商家会看到它，「已读不处理」不是一种结果
     */
    MerchantProfileVO setStatus(String merchantNo, String status, String remark, String operatorNo);

    /**
     * 认证标（P-11.1.2）。
     *
     * <p>只给**正常经营中**的商家，且毁约次数未达上限 ——
     * 认证标是平台的背书，挂在正在毁约的商家身上，赔的是平台的信用。
     */
    MerchantProfileVO setVerified(String merchantNo, boolean verified, String operatorNo);

    /** 违规记录列表。不传 {@code merchantNo} 就是全平台。 */
    List<ViolationVO> violations(String merchantNo);

    /**
     * 记一条违规处置。
     *
     * <p>副作用是**处置的一部分**，不是可选项：
     * {@code BREACH} 累加 {@code breachCount}（毁约次数在报价卡上公示，ADR-003）；
     * {@code SUSPEND} 真的把商家推到 SUSPENDED；{@code STORE_OFFLINE} 真的把门店
     * 压到 SUSPENDED 并撤下该店货架 —— 只记录不执行的处置等于没处置。
     *
     * @param storeNo 门店级处置（{@code STORE_OFFLINE}）必填，其余动作必须为空 ——
     *                「对着门店记一条主体级违规」会让申诉时说不清处置对象是谁
     */
    ViolationVO recordViolation(String merchantNo, String storeNo, String type, String action,
                                String detail, String operatorNo);

    // ---------------------------------------------------------------- 门店档案（P-11.2.1）

    /**
     * 跨主体门店检索。传 {@code merchantNo} 就是「该主体的全部门店」（含停用的 ——
     * 治理视角更不能看不见）。**只读**：门店资料、价格、库存运营一律不改，
     * 平台的边界是「裁、定、兜」，不替商家运营。
     */
    ai.neargo.shop.common.PageData<StoreGovernVO> searchStores(String merchantNo, String status,
                                                               String businessMode, String keyword,
                                                               long page, long size);

    /** 门店详情：门面 + 配送规则 + 经营模式 + 收款商户号。 */
    StoreGovernVO storeDetail(String storeNo);

    /**
     * 解除门店强制下线（{@code SUSPENDED → ACTIVE}），并恢复被平台压下的货架行。
     * <b>只有平台能做</b> —— 商家侧的启停对 SUSPENDED 一律拒绝（70021）。
     */
    StoreGovernVO restoreStore(String storeNo, String operatorNo);

    /**
     * @param payMerchantNo 空 = 用主体默认收款号（不是「没配」）
     * @param status ACTIVE / READONLY（商家自助停用）/ SUSPENDED（平台强制下线）
     */
    record StoreGovernVO(String storeNo, String name, String address,
                         String merchantNo, String merchantName,
                         boolean isDefault, String status, String businessMode,
                         String payMerchantNo, String announcement, String openHours,
                         Integer deliveryRadiusM, Long deliveryMinOrderMinor,
                         Long deliveryFeeMinor, Long deliveryFreeThresholdMinor) {
    }

    /**
     * @param status       ACTIVE / SUSPENDED / FROZEN —— **经营状态**
     * @param communityNos 服务的社区。一家店可以服务多个
     * @param contactPhone 已脱敏。完整号码属于越权边界（矩阵 §2.3 / M11）
     */
    // ---------------------------------------------------------------- 资质（P1-7）

    /** 某商家的全部资质。 */
    List<QualificationVO> qualifications(String merchantNo);

    /** 新增或更新一条资质。{@code qualNo} 为空 = 新增。 */
    QualificationVO saveQualification(String merchantNo, SaveQualificationCommand cmd, String operatorNo);

    /** 撤销一条资质（如证件作废）。不物理删——撤销本身是要留痕的事实。 */
    QualificationVO revokeQualification(String qualNo, String operatorNo);

    /**
     * 扫描到期资质：把已过期的置 {@code EXPIRED}，并返回受影响的商家。
     *
     * <p><b>由定时任务调用，不是运营手动点。</b>这是关键——靠人记得去点，
     * 等于回到「没人知道谁过期了」的状态。
     *
     * @return 本次新判定为过期的商家编号（去重）
     */
    java.util.Set<String> expireOverdueQualifications();

    /**
     * 该商家是否存在**已过期**的资质。
     *
     * <p>上架校验用它当场拦一道——定时任务有间隔，而上架是随时发生的。
     * 两道防线针对的是不同时机：定时扫覆盖「已经在架的」，这条覆盖「正要上架的」。
     */
    boolean hasExpiredQualification(String merchantNo);

    record QualificationVO(String qualNo, String entityNo, String qualType, String qualName,
                           String qualNumber, String imageUrl, Long expireAt, String status) {
    }

    record SaveQualificationCommand(String qualNo, String qualType, String qualName,
                                    String qualNumber, String imageUrl, Long expireAt) {
    }

    /**
     * @param legalForm      主体档位 {@code MICRO}/{@code INDIVIDUAL}/{@code ENTERPRISE}。
     *                       <b>准入档位完全由它决定</b>——保证金、限额、禁售品类都按它取策略。
     *                       此前档案里没有它：运营看得到「这家被限额 500」，
     *                       看不到「因为它是小微」，于是只会来问为什么。
     * @param qualifications 已登记且有效的资质<b>证件名</b>（{@code mch_qualification.qual_name}）。
     *                       取名不取类型，因为 {@code sys_auth_code.required_qualification}
     *                       存的就是证件名，运营端要拿它们直接比对。
     *                       <p>
     *                       <b>此前完全没有这个字段</b>，而 ops-web 把它声明成必填数组并直接
     *                       {@code .length} —— 真接口下抛 TypeError，只因前端跑 mock 才没暴露。
     *                       「契约有、后端不发」是字段问题，不是类型问题。
     *                       <p>
     *                       ⚠️ 当前多数商家会拿到<b>空数组</b>：入驻审核通过时不会把申请单里的
     *                       资质转存进 {@code mch_qualification}，那张表实际恒空。补齐转存之前，
     *                       运营端「需资质的类目」会全部呈禁用 —— 这是正确行为，不是新缺陷。
     * @param fundsMode      资金路径（轴②）：{@code AGGREGATED} 归集 / {@code DIRECT} 直连。
     *                       <b>与经营模式（轴③）是两件事</b> —— 这个说钱先进谁的账户，
     *                       那个说谁是销售主体。运营看不到它，就理解不了
     *                       「为什么这家店改不了归集」。
     * @param agriProducer   农业生产者。<b>无照主体走归集的唯一例外</b> ——
     *                       平台可自开农产品收购发票，成本有合法凭证
     */
    record MerchantProfileVO(String merchantNo, String name, String tier, String status,
                             List<String> communityNos, String contactName, String contactPhone,
                             List<String> categoryCodes, boolean verified, int breachCount,
                             boolean settleAccountReady, long createdAt, String auditRemark,
                             boolean asPickupPoint, String archivedAt, String legalForm,
                             List<String> qualifications,
                             String fundsMode, boolean agriProducer) {
    }

    /** @param storeNo 门店级处置时的门店号，主体级处置为 null（V96） */
    record ViolationVO(String violationNo, String merchantNo, String merchantName, String storeNo,
                       String type, String action, String detail, String operator, long at) {
    }

    // ---------------------------------------------------------------- 门面内容审核（P-10.1）

    /**
     * 待人审的店招 / 公告。
     *
     * <p>队列里只有**机审命中**的内容 —— 没命中的直接生效了，不进这里。
     */
    /**
     * 设置门店经营模式（自营 / 第三方）。
     *
     * <p><b>这是补一个「下游已在依赖、上游无人能写」的缺口</b>：
     * {@code mch_store.business_mode} 早已存在，{@code SettleServiceImpl} 每单都读它
     * 决定走哪条结算状态机与开票状态——但在此之前<b>全仓库没有任何一处能写它</b>，
     * 换一家店的经营模式只能手改数据库。
     *
     * <p><b>只开在运营端，不开给商家。</b>自营意味着平台是法律上的销售主体、
     * 承担全部产品责任，这个身份不能由商家自己勾选。
     *
     * <p><b>只对新单生效。</b>{@code stl_bill.business_mode} 在生成时已落快照，
     * 所以历史账单不会被改动——这不是要额外实现的东西，而是要在这里说明的事实，
     * 否则运营会以为改了模式能一并修正历史。
     *
     * @param mode {@link ai.neargo.shop.merchant.entity.MchStore#SELF_OPERATED}
     *             / {@link ai.neargo.shop.merchant.entity.MchStore#THIRD_PARTY}
     */
    StoreModeVO setBusinessMode(String storeNo, String mode, String operatorNo);

    /** 门店经营模式一览，运营要能一眼看出哪些店是自营。 */
    List<StoreModeVO> storeModes(String merchantNo);

    /**
     * <b>无营业执照的主体 × 自营门店</b>的风险清单。
     *
     * <p><b>为什么这是一份要专门做出来的清单</b>：自营意味着平台是销售主体，
     * 要取得进项发票才能列支成本 —— 而没有执照的主体开不出票。
     * 这笔支出<b>不得在企业所得税前扣除</b>，不是「多交一点税」，
     * 是账面上凭空多出等额利润。
     *
     * <p>而这个组合<b>是默认会发生的</b>，不是谁配错了：
     * {@code mch_store.business_mode} 的默认值就是自营，且全仓没有任何一处
     * 校验「无照不得自营」。所以第一步不是拦，是<b>先看见它有多大</b>。
     *
     * <p><b>本轮刻意不加硬拦截。</b>拦了会同时打断两件事：存量商户当场停业，
     * 以及农产品供应商 —— 农户正是「无照 + 自营采购」，那是法规为农产品
     * 单开的合规路径（平台自开收购发票）。区分它们要靠农业生产者标记，
     * 而那个标记还不存在。
     */
    List<ModeRiskVO> modeRiskStores();

    /**
     * 设置主体的<b>资金路径</b>（轴②：钱先进谁的账户）。
     *
     * <p><b>能走哪条由商户类型决定，不是平台自选</b>（ADR-017 §3.2）：
     * 自然人开不出票 → 平台按全额确认收入而成本不可税前扣除 → 禁归集；
     * 自产农产品例外（{@code is_agri_producer=1}），平台可自开农产品收购发票。
     *
     * <p><b>只拦新写入，不回溯存量。</b>库里 99 家无照主体现在都是归集（默认值），
     * 全量拦截会让他们当场停业 —— 而项目尚未上生产，这批是测试数据。
     * 存量的处置走第五步的经营资格分类（{@code GRANDFATHERED} 人工核）。
     *
     * @param mode {@code AGGREGATED} / {@code DIRECT}
     */
    MerchantProfileVO setFundsMode(String merchantNo, String mode, String operatorNo);

    /**
     * @param legalForm     主体档位。无执照的那一档 —— 取值随主体模型改造变化，
     *                      判据统一走 {@code sys_legal_form.need_license}，不在代码里写死取值
     * @param settledBills  该主体已产生的<b>自营</b>结算单数。
     *                      0 表示「查过了，没有」—— 与「还没查」要在界面上区分开
     * @param settledMinor  累计商家实得（分）。<b>这就是不可税前扣除的成本规模</b>
     */
    record ModeRiskVO(String merchantNo, String merchantName, String legalForm,
                      String storeNo, String storeName, String businessMode,
                      long settledBills, long settledMinor) {
    }

    /**
     * @param payMerchantNo 该店的收款号；<b>为空是第三方模式的硬阻塞</b>——
     *                      钱直接进商家账户的前提是那个账户存在
     */
    record StoreModeVO(String storeNo, String storeName, String merchantNo,
                       String businessMode, String payMerchantNo) {
    }

    List<StoreAuditVO> storeAudits(String status);

    /**
     * 裁决。
     *
     * <p>通过 → 内容**这时才真正生效**（写进门面表）；
     * 驳回 → 必须写原因，它原样出现在商家 B 端，商家据此改。
     */
    StoreAuditVO decideStoreAudit(String auditNo, boolean pass, String reason, String operatorNo);

    /**
     * @param hits    机审命中的词。人审要看到「机器为什么标它」——
     *                否则只能凭感觉判，同一类内容两个人两个结论
     * @param display 人话版的 content。kind=SERVICE_AREA 时是「浙江省 / 杭州市 / 西湖区」，
     *                其余 kind 与 content 相同。
     *                <b>读的时候拼，不在提交时定死</b>：区划改名之后，
     *                单据要显示当前的名字 —— 而运营是照着这个名字做判断的
     */
    record StoreAuditVO(String auditNo, String merchantNo, String merchantName, String kind,
                        String content, String status, List<String> hits, long submittedAt,
                        String reason, String display) {
    }
}
