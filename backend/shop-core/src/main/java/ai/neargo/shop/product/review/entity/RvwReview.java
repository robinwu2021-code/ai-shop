package ai.neargo.shop.product.review.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品评价。
 *
 * <p><b>为什么落在产品域而不是独立模块</b>：评价横跨订单/商品/商家，任何一个域都不完全拥有它。
 * 建独立模块的收益（边界更干净）此刻抵不过成本（多一个 Maven 模块、多一层依赖），
 * 而它读写的主体是商品与商家 —— 所以先挂产品域的 {@code review} 子包。
 * 将来评价治理（刷评识别、申诉裁决）长大到需要独立部署时再拆，那时它已经有清晰的包边界。
 *
 * <p><b>三维分与总分并存不是冗余</b>：老数据没有维度分，列表页也只显示一个星级；
 * 维度分用于评分算法与商家诊断 ——「货好但送得慢」这种问题，只看总分永远看不出来。
 *
 * <p>此前**三端都已实现评价功能而库里没有任何评价表**，
 * 连带 {@code mch_entity} 的 rating / score_* 聚合列没有数据来源，只能是假数据。
 */
@Getter
@Setter
@TableName("rvw_review")
public class RvwReview extends BaseEntity {

    /** C 端只看得到 PASSED。 */
    public static final String PENDING = "PENDING";
    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";

    private String reviewNo;

    /** 一单一商品一评：库里有 (sub_order_no, goods_no) 唯一键挡重复提交。 */
    private String subOrderNo;
    private String orderNo;
    private String goodsNo;
    private String skuNo;
    private String entityNo;

    /**
     * 评价归属门店（V155，ADR-011 决定表第 3 行）。
     *
     * <p>取的是**下单那一刻**子单上的 {@code store_no}，不是「商家现在的默认店」——
     * 顾客评的是当时那家店给他的体验，半年后商家把那家店关了，这条评价不该跟着搬家。
     *
     * <p><b>老评价为空</b>，且不回填成默认店：硬塞给默认店会让那家店的分凭空多出
     * 一批来路不明的评价，而那批顾客从来没去过那家店。空的评价照常计入**主体**分，
     * 只是不计入任何一家门店。
     */
    private String storeNo;

    private String userNo;

    /** 昵称/头像存快照：用户改昵称不该让历史评价跟着变。 */
    private String nickname;
    private String avatar;

    /** 总分 1-5。 */
    private Integer rating;

    private Integer scoreGoods;
    private Integer scoreFulfillment;
    private Integer scoreService;

    private String content;

    /** JSON 数组。 */
    private String images;

    /** 购买规格快照：让人知道这条评价说的是哪个 SKU。 */
    private String spec;

    private Integer likeCount;

    /** 商家回复；一条评价只能回一次。 */
    private String reply;
    private Long repliedAt;

    private String status;

    /** 驳回原因 —— 与门店审核同一条规矩：驳回必须写清楚。 */
    private String rejectReason;

    /**
     * 刷评信号（P-13.1.5），JSON 数组。
     * **是给人审的线索不是结论** —— 命中不等于判定，所以存原始命中项而不是算一个分值：
     * 一旦给了分数，人就会照着分数做决定，而那个分数的口径根本还没定。
     */
    private String riskFlags;
}
