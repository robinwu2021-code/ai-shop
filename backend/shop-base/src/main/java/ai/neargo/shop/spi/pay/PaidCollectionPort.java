package ai.neargo.shop.spi.pay;

import java.util.Optional;

/**
 * 支付域 → 交易：一张收款码 / 付款链接支付单（{@code PYO…}）收款成功后的确认事实（TDD-统一订单与开票资料 AC7）。
 *
 * <p><b>为什么要单开一个口</b>：回调与三个对账器补单时交给交易侧的「订单号」是支付单号（见 {@link PayOrderNumbers}），
 * 交易侧只认得出它不是交易单；门店零售单要按支付单的 {@code biz_no} 找到自己，金额也必须取支付域已确认的实收 ——
 * 不能用收银端报的数。只读、不改任何单据。
 *
 * <p>内嵌形态由支付域实现；独立形态（near-pay）没有本地实现，收款事实走已验签的收款推送（{@code PaymentCollectionFact}）。
 */
public interface PaidCollectionPort {

    /** 已成功（含之后部分 / 全额退款）的门店代客收款单；不是这类单、还没成功、或不存在时为空。 */
    Optional<PaidCollection> paidCollection(String payOrderNo);

    /**
     * @param bizNo    下单时带的业务单号（门店零售单的子单号）；没带过时是随机外部单号
     * @param paidMinor 支付域确认的实收（最小货币单位）
     */
    record PaidCollection(String payOrderNo, String storeNo, String bizNo, String currency, long paidMinor,
            String payChannel) {}
}
