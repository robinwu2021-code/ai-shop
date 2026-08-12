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
     * <p>两个副作用是**处置的一部分**，不是可选项：
     * {@code BREACH} 累加 {@code breachCount}（毁约次数在报价卡上公示，ADR-003）；
     * {@code SUSPEND} 真的把商家推到 SUSPENDED —— 只记录不执行的处置等于没处置。
     */
    ViolationVO recordViolation(String merchantNo, String type, String action, String detail,
                                String operatorNo);

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
     * @param legalForm 主体档位 {@code MICRO}/{@code INDIVIDUAL}/{@code ENTERPRISE}。
     *                  <b>准入档位完全由它决定</b>——保证金、限额、禁售品类都按它取策略。
     *                  此前档案里没有它：运营看得到「这家被限额 500」，
     *                  看不到「因为它是小微」，于是只会来问为什么。
     */
    record MerchantProfileVO(String merchantNo, String name, String tier, String status,
                             List<String> communityNos, String contactName, String contactPhone,
                             List<String> categoryCodes, boolean verified, int breachCount,
                             boolean settleAccountReady, long createdAt, String auditRemark,
                             boolean asPickupPoint, String archivedAt, String legalForm) {
    }

    record ViolationVO(String violationNo, String merchantNo, String merchantName, String type,
                       String action, String detail, String operator, long at) {
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
