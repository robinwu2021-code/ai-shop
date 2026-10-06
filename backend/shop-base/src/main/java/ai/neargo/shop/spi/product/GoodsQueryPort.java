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
     * 带**门店口径**的批量快照（商品域-优化总方案 批 C）。
     *
     * <p>叠加顺序：门店价是<b>基准价</b>，限时特价仍然覆盖它。所以它必须落在
     * 这里而不是调用方 —— 在外面改价的话会盖掉特价，症状是「活动期间按门店价卖」。
     *
     * @param storeByEntity 主体号 → 该主体这一单的履约门店。**缺的主体回退主体价**
     *                      （fail-back）—— 与库存的「无行视为 0」刻意相反：
     *                      价格视为 0 就是白送
     */
    Map<String, SkuSnapshot> snapshot(List<String> skuNos, Map<String, String> storeByEntity);

    /**
     * 候选门店里，**第一家能把这批货全部发出去的**（TDD-C端商品归属门店与库存校验 AC4/AC5）。
     *
     * <p>「发得出」= 这家店<b>在架卖它</b> ∧ <b>可售量够</b>，两条都按覆盖层语义
     * （没有店级行 → 主体级；有了任意一条 → 没行的店视为未上架 / 零库存）。
     *
     * <p><b>为什么判据要放在 product 而不是 trade</b>：在架与库存都是商品域的状态。
     * 放到 trade 去拼，就会出现第三份覆盖层实现 —— 而那条规则已经因为有两份，
     * 在买家侧漏掉过一整条（显示按主体总量、下单按门店扣）。
     *
     * <p><b>为什么按「第一家」而不是「最优的一家」</b>：候选顺序由调用方按业务优先级排好
     * （门户选中的 → 默认店 → 服务该社区的最近一家）。在这里再挑一次「最优」，
     * 等于把落店规则拆成两处，而订单的 store_no 决定结算归属、门店级活动与跨店报表。
     *
     * @param merchantNo           这一组所属主体（落店本来就是按主体算的）
     * @param storeNosInPreference 候选门店，<b>按优先级排好</b>
     * @param skuQty               这一单在这个主体下要发的 SKU → 件数
     * @return 空 = 一家都发不出（调用方据此拒单，不要退回默认店 —— 少卖可恢复，
     *         把单发给没货的店不可恢复）
     */
    default java.util.Optional<String> firstStoreThatCanFulfil(
            String merchantNo, List<String> storeNosInPreference, Map<String, Integer> skuQty) {
        return storeNosInPreference.stream().findFirst();
    }

    /**
     * 这些 SKU 在这些门店有没有单独定过价。<b>只回真正存在的行</b>。
     *
     * <p>给调用方做「要不要按门店口径重算一遍」的预检：绝大多数商家不分店定价，
     * 无条件走门店分支等于给每次下单加两条查询。
     *
     * @return skuNo → 该店的价；没定过价的 SKU 不出现在结果里
     */
    Map<String, Long> storePrices(Map<String, String> storeByEntity, List<String> skuNos);

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
     * 每个商品有几个 SKU。
     *
     * <p>给限时特价的创建校验用：`mkt_campaign` 的活动价是**商品级**的
     * （goods_nos + flash_price_minor 一个价），多规格商品会被拉成同一个价 ——
     * 实测 10 斤装（¥49.80）与 20 斤装（¥95.80）会一起变成 ¥30.00，**直接亏钱**。
     * 在建活动那一刻拦住，比事后对账发现强得多。
     */
    Map<String, Integer> skuCounts(java.util.Collection<String> goodsNos);

    /**
     * 这几件商品里，<b>有 SKU 正在做预售</b>（{@code presale_quota > 0}）的那些商品号。
     *
     * <p>给集单建活动时拦：预售与集单各有一套截单与到货口径，
     * 叠在一件货上，买家在商品页会看到两个日子（PRD §4.3.6）。
     */
    java.util.Set<String> presaleGoods(java.util.Collection<String> goodsNos);

    /**
     * 这几件商品各自的<b>限购地区</b>（#3/#4①）：不卖到的省级 regionCode 集合。
     *
     * <p>只回**有限购**的那些货（{@code restricted_regions} 非空）；全国可售的不进结果。
     * 下单时用它对收货地址省级码做拦截（{@code OrderServiceImpl} 里命中即拒）。
     */
    Map<String, java.util.Set<String>> restrictedProvincesOf(java.util.Collection<String> goodsNos);

    /**
     * 待审商品的积压情况 —— <b>数量与最久等待，一起给</b>。
     *
     * <p>只给数量答不出该做什么：「194 件待审」既可能是今天涌进来的一批，
     * 也可能是积了两周没人管，而这两件事该做的反应完全不同。
     *
     * <p>2026-09-03 线上待审 <b>194 件</b>，最早那件提交于 08-20 前后。
     * （那次是按提交时间数的；本方法按 `updated_at` 算，见实现里的说明 ——
     * 两种口径下最早那件的天数可能差几天，量级一致。）
     * 运营端此前有「商品审核队列」这个入口，但没有任何地方会说「有 194 件在等你」：
     * 它是一个要人主动点进去才看得到的列表，不是一个会找上门的数。
     */
    AuditBacklog auditBacklog();

    /**
     * @param pending    待审件数
     * @param oldestDays 最早那件等了几天。没有待审时为 0
     */
    record AuditBacklog(long pending, long oldestDays) {
    }

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
    /**
     * @param categoryNo 二级类目。<b>与 categoryType 是两件事</b> —— 那个只有三档，
     *                   而积分规则按二级类目配（生鲜里蔬菜和水果的毛利就不一样）。
     *                   下单时快照进 {@code ord_item.category_no}
     */
    record SkuSnapshot(String skuNo, String goodsNo, String merchantNo,
                       String title, String cover, String spec,
                       String categoryType, String categoryNo,
                       long price, int available, boolean onSale, List<String> fulfillments,
                       Long groupPriceMinor, Integer groupMinCount,
                       /** 销售方式（V340）。空按 NORMAL —— 与迁移默认值同一口径 */
                       String saleMode,
                       /** 每人限购（按商品，终身累计）。null / 0 = 不限 */
                       Integer limitPerUser,
                       /** 规格标称重量（克）。快递运费按它算（TDD-快递100商家寄件 §8）；空 = 商家没填，按首重计 */
                       Integer nominalGram) {

        /** 不带重量的调用点：按「没填重量」处理 */
        public SkuSnapshot(String skuNo, String goodsNo, String merchantNo,
                           String title, String cover, String spec,
                           String categoryType, String categoryNo,
                           long price, int available, boolean onSale, List<String> fulfillments,
                           Long groupPriceMinor, Integer groupMinCount, String saleMode, Integer limitPerUser) {
            this(skuNo, goodsNo, merchantNo, title, cover, spec, categoryType, categoryNo,
                    price, available, onSale, fulfillments, groupPriceMinor, groupMinCount,
                    saleMode, limitPerUser, null);
        }

        /** 老调用点：不带限购 = 不限
        public SkuSnapshot(String skuNo, String goodsNo, String merchantNo,
                           String title, String cover, String spec,
                           String categoryType, String categoryNo,
                           long price, int available, boolean onSale, List<String> fulfillments,
                           Long groupPriceMinor, Integer groupMinCount, String saleMode) {
            this(skuNo, goodsNo, merchantNo, title, cover, spec, categoryType, categoryNo,
                    price, available, onSale, fulfillments, groupPriceMinor, groupMinCount,
                    saleMode, null, null);
        }

        /** 这件货设了每人限购 */
        public boolean limited() {
            return limitPerUser != null && limitPerUser > 0;
        }

        /** 仅活动：此刻有点名它的活动在跑才能买（{@code SaleGatePort}） */
        public boolean activityOnly() {
            return "ACTIVITY_ONLY".equals(saleMode);
        }
    }
}
