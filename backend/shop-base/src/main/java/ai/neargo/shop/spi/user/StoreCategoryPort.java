package ai.neargo.shop.spi.user;

import java.util.List;

/**
 * 门店经营类目（TDD-品类约束全链路）。<b>product → merchant 走 Port，不直连</b>（ArchUnit 守着）。
 *
 * <p>商品域需要它回答两件事，仅此而已：
 * <ol>
 *   <li>上架时 —— 「这家店的经营类目里有没有这一类」（闸二）</li>
 *   <li>建品时 —— 「把这一类加进这家店的货架」（商家选了本店还没加的类目时自动加入）</li>
 * </ol>
 *
 * <p>Port 只返回<b>调用方需要的最小结构</b>：返回实体会让商品域顺手用上门店域的字段，
 * 那边改一列这边就炸。
 */
public interface StoreCategoryPort {

    /**
     * 这家店的经营类目。<b>空集合有歧义，所以不要拿它判「有没有配过」</b> ——
     * 新店本来就一个都没有，而那时应当放行（由 {@link #ensure} 自动加入），
     * 不是拦住。判据写在 {@code MerchantGoodsServiceImpl} 那一侧。
     */
    List<String> categoryNosOf(String storeNo);

    /**
     * 这家店的货架**连同店主排的顺序与改的名字**。
     *
     * <p>与 {@link #categoryNosOf} 分成两个方法而不是加字段：那一个是上架闸门在用的，
     * 每次上架都要跑，只需要一组编号；这一个是买家侧店铺页在用的，要的是
     * 「店主怎么摆的」—— 两个调用的频率与形状都不同，合成一个会让闸门顺带背上两列。
     *
     * <p>为什么买家侧非要它不可：店主在 B 端「我的类目」里排的顺序、改的显示名
     * （「本地时鲜」而不是「蔬菜」），此前<b>一处都到不了买家眼前</b> ——
     * 那一页的存在感因此接近于零。
     */
    List<Shelf> shelvesOf(String storeNo);

    /**
     * @param displayName 店主改的名；<b>空 = 用平台类目名</b>（由调用方补，商家域不读类目表）
     * @param sort        店主排的序，小的在前
     */
    record Shelf(String categoryNo, String displayName, int sort) {
    }

    /**
     * 把一个类目加进这家店的货架；已经有了就什么都不做。
     *
     * <p><b>幂等</b>：建品是高频动作，每次都先查再插会写出竞态；
     * 这里靠唯一键 {@code (store_no, category_no)} 兜底。
     */
    void ensure(String entityNo, String storeNo, String categoryNo);
}
