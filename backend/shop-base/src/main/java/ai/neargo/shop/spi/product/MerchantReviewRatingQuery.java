package ai.neargo.shop.spi.product;

import java.util.Collection;
import java.util.Map;

/** 从评价真源批量读取主体评分，保留只挂主体的历史评价。 */
public interface MerchantReviewRatingQuery {
    Map<String, Summary> byMerchant(Collection<String> merchantNos);

    record Summary(int ratingX10, int count) {}
}
