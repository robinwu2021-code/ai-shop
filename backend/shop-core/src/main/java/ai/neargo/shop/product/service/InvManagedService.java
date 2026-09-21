package ai.neargo.shop.product.service;

import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.spi.product.InvManagedPort;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品记不记库存（TDD-商品纳入进销存开关 §3，第一期）。
 *
 * <p><b>三级取值，判据只有这一份</b>：单品设置（{@code prd_goods.inv_mode}）›
 * 本主体的品类设置（{@code prd_entity_category_inv}）› 平台默认（实物 / 生鲜记，服务 / 券 / 虚拟不记）。
 *
 * <p>这里只管「判与存」。改为不记库存之前的在途单据、库存检查要看进销存，
 * 商品域看不到那一侧，由 shop-app 的编排层做完检查再调这里（见 {@code InvManagedAppService}）。
 */
public interface InvManagedService extends InvManagedPort {

    /** 平台默认：类目模板是实物 / 生鲜才记。查不到类目按「记」—— 与改版前一致 */
    boolean platformDefault(String categoryNo);

    /** 本主体这个类目的生效值：设过用设的，没设用平台默认 */
    boolean categoryManaged(String entityNo, String categoryNo);

    /** 这件商品的生效值 */
    boolean isManaged(PrdGoods g);

    /**
     * 库存设置页的一行。
     *
     * @param isDefault  没设过、用的是平台默认 —— 界面据此写「默认不记」
     * @param goodsCount 这一类下本主体有几件商品 —— 拨开关之前让他知道会动到多少东西
     */
    record CategorySetting(String categoryNo, String name, boolean managed, boolean isDefault,
                           long goodsCount) {
    }

    List<CategorySetting> categorySettings(String entityNo, Collection<String> categoryNos);

    /** 本主体已有商品挂着的类目 —— 类目从门店撤了而货还在，设置页也要能管它 */
    java.util.Set<String> categoryNosWithGoods(String entityNo);

    /**
     * 把这个类目设成 {@code managed} 时，<b>生效值会变</b>的那几件商品：
     * 跟随品类（{@code INHERIT}）且当前生效值与目标不同。单品设过「记 / 不记」的不受品类开关影响。
     */
    List<PrdGoods> goodsAffectedByCategory(String entityNo, String categoryNo, boolean managed);

    /** 存品类设置。只改不删 */
    void saveCategory(String entityNo, String categoryNo, boolean managed, String operator);

    /** 本主体的这件商品；不存在或不是他的返回 {@code null} */
    PrdGoods goodsOf(String entityNo, String goodsNo);

    /** 存单品设置。{@code mode} 必须是 INHERIT / ON / OFF */
    void saveGoodsMode(PrdGoods g, String mode, String operator);

    /** 这几件商品的 SKU（按 skuNo 去重，只取本市场那一行 —— 库存不分市场） */
    List<PrdSku> skusOf(Collection<String> goodsNos);

    /** 发「店主确认过的切换」事件：每个 SKU 一条，进销存据此建 / 恢复 / 停用物料 */
    void publishModeChanged(PrdGoods g, boolean managed);

    /**
     * 商品列表 / 编辑页用的一行。
     *
     * @param mode            单品设置：INHERIT / ON / OFF
     * @param managed         生效值
     * @param categoryManaged 品类那一级的生效值 —— 「跟随品类（记库存）」括号里写的就是它
     */
    record GoodsInvMode(String goodsNo, String mode, boolean managed, boolean categoryManaged) {
    }

    Map<String, GoodsInvMode> modesOf(String entityNo, Collection<String> goodsNos);
}
