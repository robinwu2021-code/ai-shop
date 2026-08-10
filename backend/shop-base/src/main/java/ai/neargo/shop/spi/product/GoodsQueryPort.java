package ai.neargo.shop.spi.product;

import java.util.List;
import java.util.Map;

/**
 * trade → product：下单/购物车需要的 SKU 快照。
 *
 * <p>返回的是**下单要用的字段**，不是商品实体：交易域拿到实体就会顺手用上不该用的字段，
 * 将来商品域改一列，交易域跟着炸。
 */
public interface GoodsQueryPort {

    /**
     * 批量取 SKU 快照。
     *
     * @return skuNo → 快照；查不到的 SKU 不出现在结果里（下架/删除都属于这种情况）
     */
    Map<String, SkuSnapshot> snapshot(List<String> skuNos);

    /**
     * 按**商品**取一份快照（取它的首个 SKU）。
     *
     * <p>为什么需要它：开团给的是 goodsNo（用户是在商品页点「开团」的），
     * 而 {@link #snapshot(List)} 收的是 skuNo。此前开团直接把 goodsNo 传进 snapshot，
     * 于是**永远查不到**，所有开团请求一律返回「商品不存在」——
     * 而那个报错看起来像商品下架了，没人会怀疑是参数传错了一层。
     *
     * @return 商品不存在、或它一个 SKU 都没有时为空
     */
    java.util.Optional<SkuSnapshot> snapshotOfGoods(String goodsNo);

    /**
     * @param price        **当前**售价（分）。购物车不存价，每次都读实时价，
     *                     否则用户会看到「加购时 8 块、结算时 10 块」的跳变而没有任何提示
     * @param available    可售 = 总库存 - 已锁定
     * @param onSale       是否在售。false 的行进 c-app 的失效区
     * @param fulfillments 该商品支持的履约方式，决定拆单后每个子单能选什么
     */
    /**
     * @param groupPriceMinor 团购价（分）。<b>为 null 即该商品未开放拼团</b> ——
     *                        C 端开团时据此拒绝。定价权在商家，开团人只是把它开出来
     * @param groupMinCount   起团人数；未配时按 2 人起（一个人不叫团）
     */
    record SkuSnapshot(String skuNo, String goodsNo, String merchantNo,
                       String title, String cover, String spec, String categoryType,
                       long price, int available, boolean onSale, List<String> fulfillments,
                       Long groupPriceMinor, Integer groupMinCount) {
    }
}
