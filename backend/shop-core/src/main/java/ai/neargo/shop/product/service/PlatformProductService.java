package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.OpsSkuDetailVO;
import ai.neargo.shop.product.dto.OpsSpecTemplateVO;

import java.util.List;

/**
 * 平台端商品治理：**SKU 粒度**的库存与预售（P-3.3）+ 平台规格模板（P-3.4 / E27）。
 *
 * <p>与 {@link MerchantGoodsService} 分开而不是继续往那个类里加方法，理由是<b>粒度</b>：
 * 那份从头到尾按 goods 组织（审核、上下架、社区池都是商品级的事），
 * 而这两块的主语是 SKU 与模板。合进去的结果是每个方法都要带一个「这次是 goods 还是 sku」
 * 的开关 —— 而这类开关漏判一次，就是把一个规格的处置打到了整件商品上。
 *
 * <p>需要 goods 级动作时（审核、压下架）本 Service <b>解析到父商品再委托</b>
 * {@code MerchantGoodsService}，不自己复制一份状态机。
 */
public interface PlatformProductService {

    /**
     * SKU 粒度全量查询（{@code GET /ops/skus}）。过滤维度与商品池一致，都作用在父商品上。
     *
     * @param presaleOnly 只看开了预售的（{@code presale_quota > 0}）。
     *                    <b>必须做在后端</b>：交给前端拉一页再自己过滤的话，
     *                    真实库里预售 SKU 大概率不在第一页，那个 tab 会长期显示为空 ——
     *                    而接口 200、数据也是真的，没有任何东西提示出错
     */
    PageData<OpsSkuDetailVO> listSkus(String merchantNo, String categoryNo, String keyword,
                                      String status, boolean presaleOnly, long page, long size);

    /**
     * 超卖告警（P-3.3.3）：{@code sold_count > presale_quota}。
     *
     * <p><b>只报不处置</b> —— 补货还是退单要人判断，自动关单会把还能补上的那些也关掉，
     * 而那批订单已经收了钱。
     *
     * <p>超卖只可能由平台自己**调小额度**调出来（次日现采临时收紧）：下单闸门是
     * {@code sold_count + qty <= presale_quota}，正常成交超不出去。
     * 所以这张表上的每一行都有人认领。
     */
    List<OpsSkuDetailVO> listOversellSkus();

    /**
     * 预售额度与截单时间（P-3.3.1 / 3.3.2）。
     *
     * <p><b>刻意允许把额度调到已售之下</b>：拦住看着更严谨，实际是把问题藏起来 ——
     * 运营改不动额度只好不改，于是那批已经超出去的订单谁也不知道。
     * 调完这条 SKU 立刻出现在 {@link #listOversellSkus()} 里，有人认领才是重点。
     *
     * @param cutoffAt 截单时间，ISO 串。必须早于到货时间；为空表示不设截单，只靠额度封顶
     * @param arriveAt 到货时间，ISO 串。<b>为空 = 不改</b>（保留原值）——
     *                 与「清空到货时间」要分开：一次只改额度的提交如果顺手把到货时间清了，
     *                 「截单必须早于到货」这条校验下一次就形同虚设
     */
    OpsSkuDetailVO setPresale(String skuNo, int presaleQuota, String cutoffAt, String arriveAt);

    /**
     * SKU 粒度的审核入口（{@code POST /ops/skus/{skuNo}/audit}）。
     *
     * <p><b>解析到父商品再委托</b> {@link MerchantGoodsService#audit}：审核判的是
     * 「这件商品能不能卖」—— 标题、图、类目、资质都挂在 goods 上，SKU 只是规格与价格。
     * 给 SKU 单独一套审核态的话，同一件商品会被审好几遍，
     * 而三个规格审出三个不同结论时，这件商品到底能不能卖没有答案。
     */
    OpsSkuDetailVO auditSku(String skuNo, boolean pass, String reason);

    /**
     * SKU 粒度的强制下架（{@code POST /ops/skus/{skuNo}/force-off}）= **压下架，不撤过审**。
     * 与 {@code POST /ops/goods/{goodsNo}/force-off} 是两件事，见
     * {@link MerchantGoodsService#platformSuspendGoods}。
     */
    OpsSkuDetailVO forceOffSku(String skuNo, String reason);

    // ------------------------------------------------------------ P-3.4 规格模板

    /** 平台模板列表。{@code showArchived=false} 时不含已归档的。 */
    PageData<OpsSpecTemplateVO> listSpecTemplates(String categoryType, String keyword,
                                                  boolean showArchived, long page, long size);

    /** 新建或更新平台模板（{@code templateNo} 为空即新建）。 */
    OpsSpecTemplateVO saveSpecTemplate(SaveTemplateCommand cmd);

    /** 归档：商家侧立刻不再下发。**不是删除** —— 历史商品还要靠 templateNo 解释它的 optionCode。 */
    OpsSpecTemplateVO archiveSpecTemplate(String templateNo);

    OpsSpecTemplateVO unarchiveSpecTemplate(String templateNo);

    /**
     * @param templateNo   空 = 新建
     * @param categoryType 按五品类预置；空 = 不限类目
     * @param options      每一项都必须带 {@code code}，见 {@code ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED}
     */
    record SaveTemplateCommand(String templateNo, String categoryType, String name,
                               List<MerchantGoodsService.SpecOption> options) {
    }
}
