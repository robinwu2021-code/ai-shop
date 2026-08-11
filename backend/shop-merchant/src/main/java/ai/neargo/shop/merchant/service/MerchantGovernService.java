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

    record MerchantProfileVO(String merchantNo, String name, String tier, String status,
                             List<String> communityNos, String contactName, String contactPhone,
                             List<String> categoryCodes, boolean verified, int breachCount,
                             boolean settleAccountReady, long createdAt, String auditRemark,
                             boolean asPickupPoint, String archivedAt) {
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
    List<StoreAuditVO> storeAudits(String status);

    /**
     * 裁决。
     *
     * <p>通过 → 内容**这时才真正生效**（写进门面表）；
     * 驳回 → 必须写原因，它原样出现在商家 B 端，商家据此改。
     */
    StoreAuditVO decideStoreAudit(String auditNo, boolean pass, String reason, String operatorNo);

    /**
     * @param hits 机审命中的词。人审要看到「机器为什么标它」——
     *             否则只能凭感觉判，同一类内容两个人两个结论
     */
    record StoreAuditVO(String auditNo, String merchantNo, String merchantName, String kind,
                        String content, String status, List<String> hits, long submittedAt,
                        String reason) {
    }
}
