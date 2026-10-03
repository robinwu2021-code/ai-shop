package ai.neargo.shop.spi.user;

/**
 * 给商家记一笔欠款（跨域入口）。实现是 {@code DebtService#incur} 的薄转发。
 *
 * <p>单独一个 Port 而不是塞进 {@link MerchantQueryPort}：那个是只读的，
 * 这个会动商家的账 —— 读的人多，写的人应该一眼数得过来。
 */
public interface MerchantDebtPort {

    /** 快递代下单的运费（TDD-快递100商家寄件 AC5） */
    String SOURCE_EXPRESS = "EXPRESS";

    /**
     * <b>幂等，键是 sourceNo</b>：同一个来源号重复调用不会多记。
     *
     * @return 记账后的欠款余额（分）
     */
    long incur(String entityNo, long amountMinor, String sourceType, String sourceNo, String reason);
}
