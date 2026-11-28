package ai.neargo.shop.product.review.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.review.entity.RvwReview;
import ai.neargo.shop.product.review.mapper.ReviewMappers.MerchantReviewRatingMapper;
import ai.neargo.shop.spi.product.MerchantReviewRatingQuery;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MerchantReviewRatingQueryImpl implements MerchantReviewRatingQuery {
    private final MerchantReviewRatingMapper mapper;

    public MerchantReviewRatingQueryImpl(MerchantReviewRatingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, Summary> byMerchant(Collection<String> merchantNos) {
        if (merchantNos.isEmpty()) {
            return Map.of();
        }
        var reviews = DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                Wrappers.<RvwReview>lambdaQuery().select(RvwReview::getEntityNo, RvwReview::getRating,
                                RvwReview::getCreatedAt)
                        .eq(RvwReview::getTenantNo, "MAIN")
                        .eq(RvwReview::getStatus, RvwReview.PASSED)
                        .in(RvwReview::getEntityNo, merchantNos)));
        LocalDateTime now = LocalDateTime.now();
        return reviews.stream().collect(Collectors.groupingBy(RvwReview::getEntityNo,
                Collectors.collectingAndThen(Collectors.toList(), rows ->
                        new Summary(ReviewServiceImpl.avgX10(rows, RvwReview::getRating, now), rows.size()))));
    }
}
