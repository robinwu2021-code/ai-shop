package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品（SPU）。五品类共用一张表，差异字段按 {@link #type} 各用各的 ——
 * 五张表意味着列表页要 union 五次，而「按社区逛全部商品」是首页的主查询。
 *
 * <p><b>价格不在这张表上</b>，在 {@link PrdSku}（(entity_no, sku_no) 唯一）。
 * 这是双入口同源的落点（TDD-backend §6.3）：同一 SKU 在「逛平台」与「进店」下读的是同一行，
 * 物理上不可能出现「店里 8 块平台 7 块」。
 */
@Getter
@Setter
@TableName("prd_goods")
public class PrdGoods extends BaseEntity {

    private String goodsNo;
    private String entityNo;

    /**
     * 引用的平台标准品；<b>为空 = 自建品</b>。
     *
     * <p>它是**溯源**不是外键：标准品归档了，已经引用它的商品照常在售、照常可编辑。
     *
     * <p>有值时，{@code category_no} 与 {@code spec_groups} 里的 optionCode
     * <b>以标准品为准</b>（服务端覆盖请求值，见 {@code MerchantGoodsServiceImpl#applyStd}）——
     * code 能被商家改掉的话，跨店可比就没了，标准品退化成一个填表助手。
     */
    private String stdNo;

    /** <b>中文权威</b>：C 端搜索、列表、订单快照都读它，所以必须是一个确定的字符串。 */
    private String title;
    private String subtitle;

    /** JSON {@code {"en":"…","ar":"…"}}。译文附件，缺的语言回落 {@link #title}（一期不机翻）。 */
    private String titleI18n;
    private String subtitleI18n;
    private String cover;

    /** JSON 数组。 */
    private String images;

    /**
     * 图文详情正文（纯文本）。轮播图仍在 {@link #images}。
     *
     * <p>存文本不存 HTML：商家侧是手机端输入，而收 HTML 就要在三端各做一次消毒，
     * 漏一处就是 XSS。
     */
    private String detail;

    /**
     * JSON 数组：图文详情区的长图，按顺序全宽竖排。
     *
     * <p>与 {@link #images}（详情页顶部轮播，方图）<b>分开存</b>：两者形状与位置都不同，
     * 合成一个数组之后端上只能靠宽高比猜哪几张该轮播、哪几张该竖排。
     */
    private String detailImages;

    /** NORMAL / FRESH / SERVICE / VIRTUAL / CARD */
    private String type;
    private String categoryNo;

    /** JSON 数组，如 {@code ["STORE_PICKUP","EXPRESS"]}。 */
    private String fulfillments;

    /**
     * 支持哪些支付方式（JSON 数组），取值域见 {@link ai.neargo.shop.common.PayModes}。
     *
     * <p>它是支付方式四层判定的<b>第 ④ 层</b>（类目 → 主体资质 → 门店 → 商品，取交集）。
     * 这一层表达的是「<b>商家愿不愿意</b>」，而不是「够不够格」—— 后者在资质那一层。
     *
     * <p>⚠️ <b>别重蹈 {@code fulfillments} 的覆辙</b>：那一列曾是无取值域的自由 JSON、
     * 建品时被写死、商家改不了，于是「这件商品支持怎么送」在商品侧从没真正表达过。
     * 所以取值域常量、建品可选、下单校验三件事必须一起做完。
     */
    private String payModes;

    /** JSON：规格维度定义 {@code [{name,options[]}]}，单规格商品也有一组。 */
    private String specGroups;

    /**
     * 团购价（分）。<b>为 null 即「未开放拼团」</b> —— C 端开团时据此拒绝。
     * 价格存在商品上而不是让开团人填：开团的是用户，<b>定价的必须是商家</b>。
     */
    private Long groupPriceMinor;

    /** 起团人数；未配时按 2 人起 —— 一个人不叫团。 */
    private Integer groupMinCount;

    /** 商品自身评分 ×10（区别于商家整体评分）。 */
    private Integer rating;
    private Integer ratingCount;
    private Integer sales;

    /** 每人限购，0 = 不限。 */
    private Integer limitPerUser;

    private Boolean onSale;

    /** AUDITING / APPROVED / REJECTED —— 商家商品需平台审核（P-3.2.2）。 */
    private String auditStatus;

    /**
     * 驳回/强制下架原因（V96）。**它是商家能看到的那半边**：
     * 审计日志里的原因只有运营看得到，商家面对 REJECTED 只能猜要改什么。
     * 通过审核时清空 —— 旧原因留着会被当成「还有问题没改完」。
     */
    private String auditReason;

    // ---- FRESH ----
    /**
     * <b>生鲜截单</b>：当天几点前下单（毫秒时间戳）。商家自己填（{@code SaveCommand.fresh}）。
     *
     * <p>⚠️ <b>与 {@code prd_sku.cutoff_at} 同名不同物，别混</b>：
     * <ul>
     *   <li>这一列 = 商家对<b>这件商品</b>的日常截单承诺，展示给买家（「今天 18:00 前下单」）</li>
     *   <li>{@code prd_sku.cutoff_at}（DATETIME）= <b>平台配的预售截单</b>，
     *       是下单闸门的一部分（{@code lockPresale} 的 WHERE 条件），运营在
     *       {@code POST /ops/skus/{no}/presale} 里设</li>
     * </ul>
     *
     * <p>两者曾经**只有 SKU 那一列有人写**，商品这一列有读无写 —— 当时的处置意见是合并。
     * 补上写入路径（2026-08-21）之后它们是两件真实存在的不同事情，
     * 合并反而会把「商家的承诺」与「平台的采购闸门」揉成一个，
     * 而这两者的<b>责任人不同</b>：前者商家改，后者只有运营能改。
     */
    private Long cutoffAt;
    private String arrivalDesc;
    private Boolean weighed;
    private String origin;

    // ---- SERVICE ----
    private Integer durationMin;
    private String storeName;

    /** 单品积分配置（JSON）。为空时按平台兜底比例发放。 */
    private String pointsConfig;

    /** 按端的可售覆盖（JSON），如 iOS 屏蔽某些品类。为空时走 sys_channel_category_rule。 */
    private String sellableOverride;
}
