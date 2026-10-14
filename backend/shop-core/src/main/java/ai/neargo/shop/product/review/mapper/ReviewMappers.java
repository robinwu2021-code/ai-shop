package ai.neargo.shop.product.review.mapper;

import ai.neargo.shop.product.review.entity.RvwAppeal;
import ai.neargo.shop.product.review.entity.RvwReview;
import ai.neargo.shop.product.review.entity.RvwReviewLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 评价域的 Mapper 集合。 */
public final class ReviewMappers {

    private ReviewMappers() {
    }

    public interface ReviewMapper extends BaseMapper<RvwReview> {

        /**
         * 商家评分聚合。
         *
         * <p>{@code mch_entity} 上的 rating / rating_count / score_* 是**派生值**，
         * 真源是这张表 —— 在此之前它们没有数据来源，只能是写死的假数据。
         * 用一条 SQL 聚合而不是把每次写评价都去 UPDATE 商家行：后者在热门商家上是行锁热点。
         */
        @Select("""
                SELECT COUNT(*)                       AS ratingCount,
                       ROUND(AVG(rating), 1)          AS rating,
                       ROUND(AVG(score_goods), 1)     AS scoreGoods,
                       ROUND(AVG(score_service), 1)   AS scoreService,
                       ROUND(AVG(score_fulfillment), 1) AS scoreSpeed
                FROM rvw_review
                WHERE entity_no = #{merchantNo} AND status = 'PASSED' AND deleted = 0
                """)
        java.util.Map<String, Object> aggregateByMerchant(@Param("merchantNo") String merchantNo);
    }

    public interface AppealMapper extends BaseMapper<RvwAppeal> {
    }

    public interface ReviewLikeMapper extends BaseMapper<RvwReviewLike> {
    }
}
