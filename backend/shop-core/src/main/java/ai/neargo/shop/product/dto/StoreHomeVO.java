package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 门店主页（C-ST-01）。**游客可访问，不经首页与选社区** —— 扫码/分享直达。
 *
 * <p>{@code hotGoods} 只给前几个：店主的货本来就不多，主页不做分页，
 * 真要找东西走店内搜索。
 */
public record StoreHomeVO(Merchant merchant,
                          /**
                           * 店主自己维护的门面：公告、营业时间、地址。
                           *
                           * <p>此前这里是一个写死成空串的 {@code notice} 和一句写死的
                           * 「每晚 7 点前到货，凭取货码到店自提」——
                           * 店主在 B 端认真填的公告与营业时间<b>一个字都到不了 C 端</b>。
                           * 而契约要的字段名是 {@code store}，页面读 {@code store.announcement}
                           * 直接抛错：<b>门店主页整页空白</b>，而它是这一版的主获客路径（ADR-004）。
                           */
                          StoreFront store,
                          boolean favorited,
                          /** 在售商品。字段名按契约叫 goods —— 端上读的就是这个名字 */
                          List<GoodsVO> goods,
                          /**
                           * 本店货架：**店主自己排的顺序、自己改的名字**。
                           *
                           * <p>此前这一段不存在，于是店主在 B 端「我的类目」里做的事
                           * （摆哪几类、叫什么、什么顺序）<b>一处都到不了买家眼前</b> ——
                           * 那一页的存在感因此接近于零。
                           *
                           * <p>只列<b>本店真的有在售商品</b>的类目：货架上摆着但一件货都没有的类目
                           * 点进去空手而归，比看不到更糟。
                           */
                          List<ShelfVO> categories,
                          /**
                           * 已停业（门店非 ACTIVE：商家自助停用或平台强制下线，V96）。
                           *
                           * <p>是一个标志而不是 404：扫码进来的老客要知道**店关了**，
                           * 不是链接坏了。端上据此盖「已停业」并禁掉加购。
                           */
                          boolean closed) {

    /**
     * 货架上的一类。
     *
     * @param name  店主改过就用他的名字（「本地时鲜」），没改就是平台类目名（「蔬菜」）
     * @param count 本店这一类下的在售件数 —— 端上直接显示，省得他点进去数
     */
    public record ShelfVO(String categoryNo, String name, int count) {
    }

    /** 门面文案。三个字段都不会是 null：端上直接渲染，null 会变成屏幕上的「null」 */
    /**
     * @param latE6 门店坐标（gcj02，E6）。<b>可能为 null</b> —— 商家没在地图上标过点。
     *              买家侧据此决定「导航到这里」显不显示：没有坐标时导航按钮点了只会打开一片空白
     */
    public record StoreFront(String announcement, String openHours, String address,
                             Integer latE6, Integer lngE6) {
    }

    /** 门店主页只需要商家的展示信息，不需要完整详情（那是商家详情页的事）。 */
    public record Merchant(String merchantNo, String name, String logo,
                           double rating, int ratingCount,
                           boolean verified, int breachCount) {
    }
}
