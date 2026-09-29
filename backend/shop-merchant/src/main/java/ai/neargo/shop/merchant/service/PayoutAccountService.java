package ai.neargo.shop.merchant.service;

import java.util.List;
import java.util.Optional;

/**
 * 供应商收款账户（ADR-011 自营供应商模式 · V358）。
 *
 * <p>自营模式下平台按账期把货款转给供应商，这个服务管的是<b>钱打到哪里</b>。
 *
 * <p><b>账号明文只在两个瞬间存在</b>：{@link #submit} 的入参、
 * {@link #decryptAccountNumber} 的返回值。其余任何地方 —— VO、日志、列表、
 * 运营端详情 —— 一律只有掩码。这不是谨慎，是因为账号加户名足以被用来伪造付款指令。
 */
public interface PayoutAccountService {

    /**
     * 商家提交收款账户。落库即 {@code PENDING}，<b>不能立刻用于付款</b>。
     *
     * <p>两道校验：户名必须等于主体名（三流一致），且不能已有在审的账户。
     *
     * @param entityNo 供应商主体
     * @param cmd      提交内容，其中 {@code accountNumber} 是<b>明文</b>，
     *                 方法内加密后即丢弃，不回传、不记日志
     */
    PayoutAccountVO submit(String entityNo, SubmitCommand cmd);

    /** 商家看自己的账户（含历史）。倒序，账号只有掩码。 */
    List<PayoutAccountVO> myAccounts(String entityNo);

    /**
     * 运营审核。
     *
     * <p>通过时把<b>同主体的旧 {@code ACTIVE} 置为 {@code DISABLED}</b> ——
     * 同一主体同时只能有一个生效账户，否则付款时答不出「打给哪张卡」。
     *
     * @param approved 通过或驳回
     * @param remark   驳回<b>必须</b>写原因，原样回商家；通过时可空
     */
    PayoutAccountVO audit(String accountNo, boolean approved, String remark);

    /**
     * 付款用：取这个主体<b>生效中</b>的账户。
     *
     * <p>只返回 {@code ACTIVE}。待审、被驳、已停用的一律取不到 ——
     * 「未审核的账户不该进付款清单」这条闸就落在这里，而不是在导出那一侧过滤。
     */
    Optional<PayoutAccountVO> activeAccount(String entityNo);

    /**
     * 解密账号明文。<b>这是唯一的解密入口</b>，只给「导出付款清单」用。
     *
     * <p>调用方有义务把结果写进导出文件后立即丢弃，<b>不得回前端、不得进日志</b>。
     */
    String decryptAccountNumber(String accountNo);

    /**
     * @param accountNumber <b>明文账号</b>。只在这一次调用中存在
     * @param accountName   户名，必须等于营业执照主体名
     */
    record SubmitCommand(String accountType, String accountName, String accountNumber,
                         String bankName, String bankBranch) {
    }

    /** 对外的账户视图。<b>没有明文账号字段</b> —— 不留字段就不会有人顺手塞进去。 */
    record PayoutAccountVO(String accountNo, String entityNo, String accountType,
                           String accountName, String accountMasked,
                           String bankName, String bankBranch,
                           String status, String auditRemark, Long auditedAt) {
    }
}
