package ai.neargo.shop.product.review.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 评价点赞明细 —— {@code rvw_review.like_count} 的真源。
 *
 * <p>不建这张表的话，点赞数只是个可以随便改的数字，
 * 而契约里的 {@code Review.liked}（当前用户是否点过赞）**根本算不出来**，
 * 页面只能永远显示未点赞。
 */
@Getter
@Setter
@TableName("rvw_review_like")
public class RvwReviewLike extends BaseEntity {

    private String reviewNo;
    private String userNo;
}
