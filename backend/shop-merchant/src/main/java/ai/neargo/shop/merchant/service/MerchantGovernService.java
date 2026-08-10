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

    /** 平台侧商家列表。跨商家，按社区/状态/关键词筛。 */
    List<MerchantProfileVO> list(String status, String communityNo, String keyword);

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
    record MerchantProfileVO(String merchantNo, String name, String tier, String status,
                             List<String> communityNos, String contactName, String contactPhone,
                             List<String> categoryCodes, boolean verified, int breachCount,
                             boolean settleAccountReady, long createdAt, String auditRemark,
                             boolean asPickupPoint, String archivedAt) {
    }

    record ViolationVO(String violationNo, String merchantNo, String merchantName, String type,
                       String action, String detail, String operator, long at) {
    }
}
