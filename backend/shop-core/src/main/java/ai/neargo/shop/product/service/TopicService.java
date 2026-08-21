package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;

import java.util.List;

/**
 * 主题分类（商品域-优化总方案 批 E）。
 *
 * <p>陈列与类目正交、与活动分开 —— 理由见 {@code PrdTopic} 的类注释。
 */
public interface TopicService {

    /**
     * 主题列表。
     *
     * @param includeArchived 运营端要看得见归档的（否则「它去哪了」没有答案）；
     *                        C 端一律只看在架的
     */
    List<TopicVO> list(boolean includeArchived);

    /** 新建或改。{@code topicNo} 为空 = 新建 */
    TopicVO save(SaveCommand cmd);

    /**
     * 归档 / 取消归档。<b>不物理删</b> —— C 端历史链接、分享出去的海报都还指着它，
     * 删掉之后那些入口进来是 404，而它本来只需要「这个专题结束了」。
     */
    TopicVO setArchived(String topicNo, boolean archived);

    /** 专题里的商品，按 {@code sort} 排。 */
    PageData<GoodsVO> goods(String topicNo, long page, long size);

    /**
     * 整份替换专题里的商品（勾选式界面的天然形状）。
     *
     * <p><b>只收在架商品</b>：把一件下架/待审的货摆进专题，C 端点进去是空位 ——
     * 而运营在后台看到它明明在列表里。
     */
    void setGoods(String topicNo, List<String> goodsNos);

    /**
     * @param onlyLive 只要当下生效的（未归档 且 在档期内）。C 端取的就是它
     */
    record Query(boolean onlyLive) {
    }

    /**
     * @param goodsCount 这个专题里有几件商品 —— 运营列表要看得见空专题，
     *                   一个空专题在 C 端就是一个点进去什么都没有的入口
     */
    record TopicVO(String topicNo, String title, String subtitle, String cover, int sort,
                   Long startAt, Long endAt, String status, int goodsCount) {
    }

    record SaveCommand(String topicNo, String title, String subtitle, String cover,
                       Integer sort, Long startAt, Long endAt) {
    }
}
