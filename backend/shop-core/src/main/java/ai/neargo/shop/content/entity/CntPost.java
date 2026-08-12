package ai.neargo.shop.content.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 种草内容。
 *
 * <p><b>{@link #riskHits} 落库而不是每次现算</b>：审核页要按「是否命中」筛选是一方面，
 * 更要紧的是<b>命中结果要与审核决定同时留痕</b> —— 词库改了之后，
 * 「当时是不是命中了」还查得到。现算的话，改一次词库就把历史judgment的依据抹掉了。
 */
@Getter
@Setter
@TableName("cnt_post")
public class CntPost extends BaseEntity {

    public static final String USER = "USER";
    public static final String MERCHANT = "MERCHANT";

    public static final String PENDING = "PENDING";
    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";
    /**
     * 已下架。
     *
     * <p><b>{@code PASSED → OFFLINE} 是单独一条路</b>，不是「改回待审」：
     * 内容已经露出过、被人看过、可能已被引用，退回待审等于假装没发生过。
     */
    public static final String OFFLINE = "OFFLINE";

    private String postNo;
    private String authorType;
    private String authorName;
    private String title;
    private String content;
    private String communityNo;
    private String communityName;
    private String skuNo;
    /** JSON 数组 */
    private String riskHits;
    private String status;
    /** 原样回作者，所以驳回与下架都必须写 */
    private String auditRemark;
    private String auditedBy;
    private Long auditedAt;
    private Integer likeCount;
}
