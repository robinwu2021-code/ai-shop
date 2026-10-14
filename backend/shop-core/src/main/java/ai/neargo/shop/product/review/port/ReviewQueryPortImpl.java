package ai.neargo.shop.product.review.port;

import ai.neargo.shop.product.review.entity.RvwReview;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewMapper;
import ai.neargo.shop.spi.product.ReviewQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** 「这张子单评价过没有」（trade → product）。 */
@Component
public class ReviewQueryPortImpl implements ReviewQueryPort {

    private final ReviewMapper reviewMapper;

    public ReviewQueryPortImpl(ReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Override
    public boolean reviewed(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank()) {
            return false;
        }
        return reviewMapper.exists(Wrappers.<RvwReview>lambdaQuery()
                .eq(RvwReview::getSubOrderNo, subOrderNo));
    }
}
