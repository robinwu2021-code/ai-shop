package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade / fulfillment / settle → user：查商家的最小必要信息。
 *
 * <p>刻意只暴露<b>下单必需</b>的四个字段，而不是返回整个商家实体 ——
 * Port 一旦返回实体，模块边界就名存实亡：调用方会顺手用上不该用的字段，
 * 将来 user 域改一个列，三个模块跟着炸。
 */
public interface MerchantQueryPort {

    /**
     * @param merchantNo 商家业务键
     * @return 空表示商家不存在
     */
    Optional<MerchantBrief> find(String merchantNo);

    /**
     * @param merchantNo   商家业务键
     * @param merchantName 展示名（下单快照用，商家改名不影响历史订单）
     * @param canSell      是否可上架售卖（审核通过且未封禁）
     * @param canReceive   是否可收款（分账接收方已报备，ADR-002）
     */
    record MerchantBrief(String merchantNo, String merchantName, boolean canSell, boolean canReceive,
                         String logo, double rating, boolean verified, int breachCount) {
    }
}
