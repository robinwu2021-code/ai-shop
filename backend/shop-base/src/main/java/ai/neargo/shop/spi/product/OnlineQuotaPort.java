package ai.neargo.shop.spi.product;

/**
 * 「改库存」在接入进销存之后的去处（TDD-商品纳入进销存开关 §8 / §18.6）。
 *
 * <p>接入进销存、且本店已打开库存同步的商品，实物数量只由单据改（进货 / 盘点 / 报损）。
 * 这时店主在商品页改的那个数，含义是<b>线上放多少货</b> —— 存成这件商品在本店的「手动」规则，
 * 不开单据、不动实存。其余情况（没接入、没开同步）照旧直接改商城库存。
 *
 * <p>判据要看进销存与同步开关，而商品域看不到那一侧，所以从这里出去。
 * 没有实现（进销存没开）时调用方按老路走。
 */
public interface OnlineQuotaPort {

    /**
     * @param storeNo 当前门店；{@code null} 表示调用方不知道（单店商家的主体级库存那条路），
     *                由实现自己解析 —— 主体只有一家店才认，多店时返回 {@code false}
     * @return 接住了（已存成线上额度并重算）。{@code false} = 这件商品不归进销存管，按老路改商城库存
     */
    boolean setOnlineQuota(String entityNo, String storeNo, String goodsNo, String skuNo, int qty);
}
