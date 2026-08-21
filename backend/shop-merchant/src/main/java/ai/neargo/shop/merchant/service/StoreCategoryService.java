package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 门店经营类目：商家给自己的店摆货架（TDD-品类约束全链路 §三）。
 *
 * <p>可选范围 = <b>无门槛类目 ∪ 主体已授权码覆盖的类目</b>。
 * 选到主体没授权的类目<b>直接拒</b>，且报错里要说得出缺哪张证 ——
 * 让他填完整个表单再告诉他不行，是最差的一种拒绝。
 *
 * <p>⚠️ 这张货架与 {@code mch_entity.category_codes} 是两件事：那是<b>平台批的证</b>
 * （能不能卖这类），这是<b>商家的货架</b>（店里怎么摆）。责任人不同。
 */
public interface StoreCategoryService {

    /** 这家店的货架，按商家拖出来的顺序。 */
    List<StoreCategoryVO> list(String merchantNo, String storeNo);

    /**
     * 整份替换这家店的类目（勾选式界面的天然形状）。
     *
     * <p><b>删掉一个底下还有商品的类目 → 拒绝</b>：不拦的话那些商品会挂在一个
     * 这家店已经不存在的货架上，店铺页里就此消失，而商家在商品列表里还看得到它们。
     */
    List<StoreCategoryVO> replace(String merchantNo, String storeNo, List<Item> items);

    /**
     * 建店时初始化。<b>第二家店默认复制默认店的</b> ——
     * 多门店商家开分店卖的多半是同一批货，从零勾选是纯负担。
     *
     * @param categoryNos     建店表单里勾的；为空则复制 {@code copyFromStoreNo} 那家店的
     * @param copyFromStoreNo 复制来源（通常是默认店）；为空则这家店先空着
     */
    void initForNewStore(String merchantNo, String storeNo,
                         List<String> categoryNos, String copyFromStoreNo);

    /**
     * @param displayName 空 = 用平台类目名。它只是<b>皮</b>：`categoryNo` 不变，聚合不受影响
     */
    record Item(String categoryNo, String displayName, Integer sort) {
    }

    /**
     * @param name         展示名：`displayName` 有就用它，否则用平台类目名
     * @param goodsCount   这个货架上有几件商品 —— 删之前商家要看得见代价
     */
    record StoreCategoryVO(String categoryNo, String name, String platformName,
                           String displayName, int sort, int goodsCount) {
    }
}
