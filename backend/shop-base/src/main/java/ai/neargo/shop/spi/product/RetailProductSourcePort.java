package ai.neargo.shop.spi.product;

import java.time.Instant;
import java.util.List;

/**
 * Original product publication evidence; never a replacement product, price, media or stock owner.
 */
public interface RetailProductSourcePort {
    Facts read(Query query);

    record Query(
            String entityNo,
            String storeNo,
            String goodsNo,
            Integer revisionNo,
            boolean historical) {}

    record Facts(
            Query query,
            int revisionNo,
            String contentHash,
            List<String> publishedSkuNos,
            Instant verifiedAt,
            Instant validUntil,
            String receiptNo) {
        public Facts {
            publishedSkuNos = List.copyOf(publishedSkuNos);
        }
    }
}
