package ai.neargo.shop.pay.ledger;

/**
 * 流水账户：按「钱在哪」分（设计 v4 §2.9）。
 *
 * <p>{@link #MERCHANT_CHANNEL} 与 {@link #MERCHANT_SALES} 是<b>平台外的备查账</b>（DIRECT：钱进的是商户自己的渠道账户）；
 * 它们与 {@code PLATFORM_*} 之间<b>不允许出现在同一张凭证里</b>（AC18）—— 出现即说明一笔 DIRECT 的钱被当成了平台的钱。
 */
public enum LedgerAccount {
    /** 商户在渠道那边的钱（DIRECT，平台外备查） */
    MERCHANT_CHANNEL(true),
    /** 商户的销售（DIRECT，平台外备查）：MERCHANT_CHANNEL 的对方科目。设计的账户表里没有，I3 补（偏差 3） */
    MERCHANT_SALES(true),
    /** 平台在渠道的余额（AGGREGATED） */
    PLATFORM_CHANNEL(false),
    /** 平台欠商户、待结（AGGREGATED） */
    MERCHANT_PAYABLE(false),
    /** 商户欠平台的平台费（DIRECT） */
    FEE_RECEIVABLE(false),
    PLATFORM_REVENUE(false),
    CHANNEL_FEE_EXPENSE(false),
    REFUND_CLEARING(false);

    private final boolean offPlatform;

    LedgerAccount(boolean offPlatform) {
        this.offPlatform = offPlatform;
    }

    public boolean offPlatform() {
        return offPlatform;
    }

    public boolean platform() {
        return name().startsWith("PLATFORM_");
    }
}
