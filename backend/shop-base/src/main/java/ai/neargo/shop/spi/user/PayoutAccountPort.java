package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * 供应商收款账户的对外出口（ADR-011 · V358）。
 *
 * <p>账户本身住在商家域，而用它的是资金侧（导出付款清单）——
 * 跨域读必须经这里，不能直接注入 {@code PayoutAccountService}。
 *
 * <p><b>{@link #decryptAccountNumber} 是整个仓库里唯一能拿到明文账号的出口。</b>
 * 调用方有义务把它写进导出文件后立即丢弃：不回前端的其它接口、不进日志、不入缓存。
 * 其余任何地方一律只用 {@link PayoutAccountBrief#accountMasked()}。
 */
public interface PayoutAccountPort {

    /**
     * 取这个主体<b>生效中</b>的收款账户。
     *
     * <p>只返回 {@code ACTIVE}。待审、被驳、已停用的一律取不到 ——
     * 「未审核的账户不能用于付款」那道闸就落在这里。
     */
    Optional<PayoutAccountBrief> activeAccount(String entityNo);

    /**
     * 解密账号明文。<b>只给导出付款清单用</b>，且只放生效中的账户。
     *
     * @throws ai.neargo.shop.common.BizException 账户不存在或不是生效状态
     */
    String decryptAccountNumber(String accountNo);

    /** 账户摘要。<b>没有明文账号字段</b> —— 不留字段就不会有人顺手塞进去。 */
    record PayoutAccountBrief(String accountNo, String accountType, String accountName,
                              String accountMasked, String bankName, String bankBranch) {
    }
}
