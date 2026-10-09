package ai.neargo.shop.spi.trade;

import java.util.Optional;

/**
 * message → trade：一张子单是谁买的、属于哪一单。
 *
 * <p>物流事件只带业务单号（{@code bizRef} = 子单号）—— 物流不认识买家（ADR-032：将来独立成服务，
 * 「通知谁」是交易域的事，不该跟着运单一起登记）。通知要发给人，所以由通知这一侧回头问一次交易域。
 */
public interface SubOrderBuyerPort {

    /** 查不到子单时为空 */
    Optional<Buyer> buyerOf(String subOrderNo);

    record Buyer(String userNo, String orderNo) {
    }
}
