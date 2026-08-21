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

    /**
     * 商品列表。{@code status} 为空表示全部（含下架与审核中）。
     *
     * @param merchantNo 为空 = 跨商家查，运营端"商品池"用这个口径
     * @param categoryNo 按类目筛（通常是二级），为空不筛
     * @param keyword    按标题模糊搜，为空不筛
     */
    PageData<GoodsVO> list(String merchantNo, String categoryNo, String keyword, String status, long page, long size);

    /**
     * 运营端**待审队列**（P-3.2.2）。固定只给 {@code AUDITING}。
     *
     * <p><b>为什么不复用 {@link #list}(null, null, null, "AUDITING", …)</b> ——
     * 它此前正是那么调的，而两者在<b>数据域</b>上必须相反：
     * <ul>
     *   <li>{@link #list} 与 B 端商家商品列表共用。B 端会话的维度是 SELF，
     *       而 {@code prd_goods} 只有 MERCHANT 锚点 —— 接数据域就是
     *       {@code 1=0}，商家的商品列表当场全空（批② 在 mch_entity 上实测过这个形状）</li>
     *   <li>这一条**只有运营调**，所以它要接数据域：配了商家域的审核员
     *       只该看到自己负责那几家的待审商品</li>
     * </ul>
     *
     * <p>不用「参数为空就跳过数据域」那种写法：判权旁路藏在参数语义里，
     * 下一个人在 B 端路径上把参数传漏一次，就把全平台商品开出去了
     * （与 {@link #detailForOps} 单独成方法是同一条理由）。
     */
    PageData<GoodsVO> auditQueue(long page, long size);

    /**
     * 运营端「商品池」列表——与 {@link #list} 的区别在返回形状：这个带多市场价格表
     * 与三语标题原文，{@link GoodsVO} 为了跟 c-app 对齐特意裁掉了这两样（见
     * {@link ai.neargo.shop.product.dto.OpsGoodsListVO} 的类注释）。过滤参数同 {@link #list}。
     */
    PageData<ai.neargo.shop.product.dto.OpsGoodsListVO> listForOps(
            String merchantNo, String categoryNo, String keyword, String status,
            String storeNo, long page, long size);

    /** 我的商品详情。<b>不是我的直接 404</b>，不是 403 —— 别家有没有这个商品也不该被探知。 */
    GoodsVO detail(String merchantNo, String goodsNo);

    /**
     * 运营端商品详情（P-3.2.2b）。<b>单独一个方法而不是给 {@link #detail} 的
     * merchantNo 加 null 语义</b>：归属校验的旁路必须显式——藏在「参数为空」里，
     * 下一个在 biz 路径上把 merchantNo 传漏的人就把全平台商品开给了商家。
     */
    GoodsVO detailForOps(String goodsNo);

    /**
     * 平台强制下架（P-3.2.3，TDD-运营端门店与商品治理 D1）：**撤销过审**。
     * {@code auditStatus → REJECTED} + 原因、主体级下架、店级行全下、撤出社区池。
     * 商家改后走既有的重新提审链路回来。
     *
     * <p>不复用 {@link #toggle}：那是「商家管自己的货」——无原因、无留痕，
     * 且商家可以自己再上架，「强制」名存实亡。
     *
     * @param reason 必填。它会出现在商家 B 端（{@code auditReason}），带「平台强制下架」前缀
     */
    GoodsVO forceOff(String goodsNo, String reason);

    /**
     * 平台<b>压下架</b>（P-3.2.3 的另一半，走 {@code POST /ops/skus/{skuNo}/force-off}）：
     * 主体级下架 + 店级行全下 + 撤社区池 + 记原因，<b>但不撤过审</b>。
     *
     * <p>与 {@link #forceOff} 分成两件事，是因为它们对商家意味着完全不同的下一步：
     * 那个是撤销过审 —— 商家必须改完重新提审；这个只是压下架 ——
     * 问题处理完商家自己点一下就能回来。
     *
     * <p><b>只做一件事就用错的那个</b>：临时压一个规格却整件打回，
     * 商家要为一件本来没问题的商品走完整的重新提审；反过来，
     * 真的违规却只压下架，他自己点一下就回到了 C 端。
     *
     * @param reason 必填。原样进商家 B 端（{@code auditReason}），带「平台下架」前缀
     */
    GoodsVO platformSuspendGoods(String goodsNo, String reason);

    /**
     * 平台压下某门店的货架（TDD D3，给 {@link ai.neargo.shop.spi.product.StoreShelfPort} 用）。
     * 只压当前在售的行并打 {@code platform_suspended} 标记。
     */
    void platformOfflineStore(String entityNo, String storeNo);

    /** 解除：只恢复带标记的行，商家在处置期间的自主下架不受影响。 */
    void platformRestoreStore(String entityNo, String storeNo);

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
     * 设置**这家店**的售价（商品域-优化总方案 批 C）。
     *
     * <p>与门店库存<b>回退方向相反</b>：没有行的店按主体价卖（fail-back）。
     * 照抄库存那套「无行视为 0」的话，一家没配过价的店会把所有货以 ¥0.00 卖出去 ——
     * 页面上看着像 bug，钱已经出去了。
     *
     * @param price 传 {@code null} = <b>取消本店单独定价</b>，回到主体价。
     *              没有这条，商家给某店定过价之后就再也回不去
     */
    GoodsVO saveStorePrice(String merchantNo, String storeNo, String goodsNo,
                           String skuNo, Long price, Long originPrice);

    /**
     * 提交审核：{@code DRAFT → AUDITING}（批 D）。
     *
     * <p><b>为什么要显式一步</b>：此前是「保存即提审」—— 商家填一半点保存，
     * 那件半成品立刻进了运营的待审队列，而他自己看到的是「审核中」，
     * 既不敢改也不知道在等什么。
     *
     * <p>已经在审、已过审、已驳回的商品调它<b>无副作用</b>（幂等）：
     * 端上重复点击是常态，报错只会让商家以为提交失败。
     */
    GoodsVO submitForAudit(String merchantNo, String goodsNo);

    /**
     * 只改截单时间（批 D）。<b>不触发重审</b> ——
     * 生鲜商家的日常是今晚定明天的截单，走 {@code save()} 的话每天都要重审一次，
     * 而重审期间商品是下架的：改一次截单等于停一天生意。
     *
     * <p>额度、价格、类目一概不动：这个入口只放开这一个字段。
     */
    GoodsVO savePresaleCutoff(String merchantNo, String goodsNo, Long cutoffAt, String arrivalDesc);

    /**
     * 各门店的缺货 SKU 数（跨店对比，B-11.12.6）。
     *
     * <p>口径：这家店 {@code prd_store_stock} 里<b>可用量（stock − locked）≤ 0</b> 的行数。
     *
     * <p><b>只数已启用分店库存的 SKU</b> —— 一个 SKU 一条店级行都没有时走主体总量
     * （见 {@link #saveStoreStock}），它对这家店<b>不算缺货</b>：
     * 那件商品在这里照常卖得出去，把它记成缺货会让一家什么都没配过的店
     * 显示「缺货 200 件」，而店主根本没有可做的动作。
     *
     * <p>⚠️ 已知的不对称：某 SKU 已转为店级管理、但<b>这家店没有行</b>时，
     * 实际可售量是 0（那是 {@code PrdStoreStock} 刻意的语义），而这里不计。
     * 保持与「只数已启用的」这句话一致 —— 要把它算进来，得先决定
     * 「没配过的店到底算不算这件商品的经营范围」，那是另一个问题。
     *
     * @param storeNos 门店范围。{@code null} = 全部门店；<b>空集合 = 一家都不看</b>，
     *                 与订单侧同一套 fail-closed 口径
     * @return storeNo → 缺货 SKU 数。<b>没有任何店级行的门店不在 Map 里</b>，调用方兜底 0
     */
    Map<String, Integer> outOfStockCountByStore(String merchantNo,
                                                java.util.Collection<String> storeNos);

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
     * @param categoryNo 平台类目树的节点（一级或二级）。<b>唯一的分类输入</b> ——
     *                   五品类（{@code type}）由它带出来，不再由商家填。
     *                   两者不是重复：类目是数据（运营可增删），品类是代码分支（恒定五条）；
     *                   但让商家各填一遍就会出现「叶菜类目 + 日用品品类」这种没人拦得住的矛盾
     */
    /**
     * @param fulfillments 该商品支持的履约方式，取值见
     *                     {@link ai.neargo.shop.common.Fulfillments}。
     *                     <b>留空 = 不改</b>（新建时默认到店自提）——
     *                     传空数组与不传要分开：前者是「一种都不支持」，那件商品谁也买不了
     */
    /**
     * @param limitPerUser 每人限购，0 = 不限。<b>留空 = 不改</b>
     * @param fresh        生鲜段。<b>留空 = 不改</b>；只在派生出的品类是 FRESH 时写入
     * @param service      服务段。同上，只在 SERVICE 时写入
     * @param groupBuy     拼团档。<b>两个值必须一起给</b>（缺一个开不出团），留空 = 不改
     * @param stdNo        引用的平台标准品；留空 = 自建品。<b>传了它，类目与 optionCode
     *                     以标准品为准</b>（服务端覆盖请求值）—— code 能被改掉的话跨店可比就没了
     */
    record SaveCommand(String goodsNo, String title, String subtitle,
                       Map<String, String> titleI18n, Map<String, String> subtitleI18n,
                       String categoryNo, String cover, List<String> images,
                       List<SpecGroup> specGroups, List<Sku> skus,
                       List<String> fulfillments,
                       Integer limitPerUser, FreshSpec fresh, ServiceSpec service,
                       GroupBuySpec groupBuy,
                       String stdNo,
                       /**
                        * 图文详情正文（纯文本）。<b>不传 = 不改</b>，与其余可选字段同一口径；
                        * 传空串 = 清空。
                        */
                       String detail) {
    }

    /**
     * 生鲜专有属性。<b>此前这几列只有 DevSeeder 写得进去</b> ——
     * {@code PrdGoods} 的类注释写着「差异字段按 type 各用各的」，而商家一个都填不了：
     * 建出来的生鲜没有截单时间、没有产地、不按重结算，
     * 于是「按标称预扣、称重后多退少补」这条链在真实数据上根本跑不起来。
     *
     * @param cutoffAt    当天几点前下单（毫秒时间戳）。与「到点」是两件事，见词典 §12
     * @param arrivalDesc 预计到货描述，如「次日 17:00 前到点」
     * @param weighed     是否按实称多退少补
     * @param origin      产地
     */
    record FreshSpec(Long cutoffAt, String arrivalDesc, Boolean weighed, String origin) {
    }

    /**
     * 服务专有属性。
     *
     * @param durationMin 服务时长（分钟）
     * @param storeName   可核销门店名
     */
    record ServiceSpec(Integer durationMin, String storeName) {
    }

    /**
     * 拼团档。<b>价格存在商品上而不是让开团人填</b>：开团的是用户，定价的必须是商家。
     *
     * <p>配齐两个值才算「能开团」，缺一个都开不出来（{@code GoodsServiceImpl.groupBuyConf}）——
     * 所以这里也要么两个都给，要么都不给。此前这两列没有任何写入路径，
     * 「可开团的商品」那一栏因此**恒为空**。
     *
     * @param minCount   起团人数；一个人不叫团，最小 2
     * @param priceMinor 团购价（最小货币单位）
     */
    record GroupBuySpec(Integer minCount, Long priceMinor) {
    }

    record SpecGroup(String name, List<String> options, List<String> optionCodes,
                     String templateNo) {
    }

    /**
     * @param skuNo        已有 SKU 带原编号 —— 换编号会让历史订单指向一个不存在的 SKU
     * @param priceByMarket 按市场分别定价（B6）。汇率换算出的价没有价格心理学，
     *                      且汇率一动全店价格跟着抖，而商家并没有调价
     */
    /**
     * @param originPrice  划线价（最小货币单位）。派生展示值，不是定价 ——
     *                     此前有列、有契约、**没有写入路径**，于是折扣标永远不出现
     * @param nominalGram  标称重量（克），生鲜按重计价用。同样是「有列没入口」的一条
     */
    record Sku(String skuNo, List<String> optionValues, long price,
               Map<String, Long> priceByMarket, int stock,
               Long originPrice, Integer nominalGram) {
    }

    record SpecOption(String code, String label) {
    }
}
