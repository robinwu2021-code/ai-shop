package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品（SPU）。五品类共用一张表，差异字段按 {@link #type} 各用各的 ——
 * 五张表意味着列表页要 union 五次，而「按社区逛全部商品」是首页的主查询。
 *
 * <p><b>价格不在这张表上</b>，在 {@link PrdSku}（(merchant_no, sku_no) 唯一）。
 * 这是双入口同源的落点（TDD-backend §6.3）：同一 SKU 在「逛平台」与「进店」下读的是同一行，
 * 物理上不可能出现「店里 8 块平台 7 块」。
 */
@Getter
@Setter
@TableName("prd_goods")
public class PrdGoods extends BaseEntity {

    private String goodsNo;
    private String merchantNo;

    private String title;
    private String subtitle;
    private String cover;

    /** JSON 数组。 */
    private String images;

    /** NORMAL / FRESH / SERVICE / VIRTUAL / CARD */
    private String type;
    private String categoryNo;

    /** JSON 数组，如 {@code ["STORE_PICKUP","EXPRESS"]}。 */
    private String fulfillments;

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

    // ---- FRESH ----
    private Long cutoffAt;
    private String arrivalDesc;
    private Boolean weighed;
    private String origin;

    // ---- SERVICE ----
    private Integer durationMin;
    private String storeName;
}
