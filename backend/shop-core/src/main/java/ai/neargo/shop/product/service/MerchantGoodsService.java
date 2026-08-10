package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;

import java.util.List;
import java.util.Map;

/**
 * 商家侧商品管理（[API 清单 §3.3]，B-11.3）。
 *
 * <p>与 {@link GoodsService} 分开：那边是<b>买家视角</b> —— 只看得到过审且在售的，
 * 且要按社区可达性裁剪；这边是<b>店主视角</b> —— 下架的、审核中的、被驳回的都要看得到，
 * 尤其是被驳回的：看不到就不知道要改什么。
 *
 * <p>合成一个 Service 的代价是每个方法都要带一个「我是谁」的开关，
 * 而漏判一次就是把别家的商品泄漏到这家店的后台里。
 */
public interface MerchantGoodsService {

    /** 我的商品列表。{@code status} 为空表示全部（含下架与审核中）。 */
    PageData<GoodsVO> list(String merchantNo, String status, long page, long size);

    /** 我的商品详情。<b>不是我的直接 404</b>，不是 403 —— 别家有没有这个商品也不该被探知。 */
    GoodsVO detail(String merchantNo, String goodsNo);

    /**
     * 新建 / 编辑。
     *
     * <p><b>改动后回到待审核</b>：已上架的商品改完标题价格就继续在卖，等于绕开审核。
     * 一期审核是人工的，这条规则让"改成别的东西再卖"这条路走不通。
     */
    GoodsVO save(String merchantNo, SaveCommand cmd);

    /**
     * 上下架。
     *
     * <p><b>未过审的商品不能上架</b> —— 上架接口是商家能自己按的按钮，
     * 如果它能把 AUDITING 的商品直接推到 C 端，那审核就形同虚设。
     */
    GoodsVO toggle(String merchantNo, String goodsNo, boolean onSale);

    /**
     * 改库存。单独一条接口而不是走 {@link #save}：
     * 补货是每天都在做的事，走完整保存意味着<b>每次补货都要重新过一遍审核</b>。
     */
    GoodsVO saveStock(String merchantNo, String goodsNo, String skuNo, int stock);

    /**
     * 设置**某家门店**的库存。
     *
     * <p>⚠️ 第一次为某个 SKU 调用它，就把这个 SKU 整体切换成了「按店管理」——
     * 此后**没有设过库存的门店卖不出这件商品**（视为 0，不是回退到主体总量）。
     * 回退到总量会让没设的店变成无限供应，比不分店更危险；
     * 少卖是可恢复的，超卖不是。
     *
     * <p>所以这个动作在界面上要说清楚，不能像补货那样一按了事。
     */
    GoodsVO saveStoreStock(String merchantNo, String storeNo, String goodsNo, String skuNo, int stock);

    /**
     * 平台审核商品（P-3.2.2）。<b>不带 merchantNo</b> —— 运营审的是全平台的商品。
     *
     * <p>此前只有审核<b>队列</b>没有审核<b>动作</b>：商品录进来就永远停在 AUDITING，
     * 而 {@link #toggle} 又要求过审才能上架 —— 于是商家录的商品<b>一件都上不了架</b>，
     * 且两个接口各自看都是"正常工作"的。
     *
     * @param reason 驳回必须写理由，与商家入驻、售后驳回同一条规矩
     */
    GoodsVO audit(String goodsNo, boolean approved, String reason);

    /** 规格模板：平台模板（按类目推荐）+ 我自己存的。 */
    List<SpecTemplateVO> specTemplates(String merchantNo, String categoryType);

    /** 存为常用规格。<b>只能存成自己的</b>，商家改不了平台模板。 */
    SpecTemplateVO saveSpecTemplate(String merchantNo, String name, List<SpecOption> options);

    /**
     * @param titleI18n    译文附件，中文在 {@code title}。缺的语言由 C 端回落中文
     * @param specGroups   空表示单规格
     * @param skus         单规格商品也有且仅有一条
     */
    /**
     * @param type       五品类，决定履约与合规。平台硬编码
     * @param categoryNo 三级类目树的节点。选填，决定归类与经营准入 —— <b>两个正交维度</b>
     */
    record SaveCommand(String goodsNo, String title, String subtitle,
                       Map<String, String> titleI18n, Map<String, String> subtitleI18n,
                       String type, String categoryNo, String cover, List<String> images,
                       List<SpecGroup> specGroups, List<Sku> skus) {
    }

    record SpecGroup(String name, List<String> options, List<String> optionCodes,
                     String templateNo) {
    }

    /**
     * @param skuNo        已有 SKU 带原编号 —— 换编号会让历史订单指向一个不存在的 SKU
     * @param priceByMarket 按市场分别定价（B6）。汇率换算出的价没有价格心理学，
     *                      且汇率一动全店价格跟着抖，而商家并没有调价
     */
    record Sku(String skuNo, List<String> optionValues, long price,
               Map<String, Long> priceByMarket, int stock) {
    }

    record SpecOption(String code, String label) {
    }
}
