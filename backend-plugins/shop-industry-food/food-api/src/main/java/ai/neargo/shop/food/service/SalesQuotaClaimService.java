package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.SalesQuotaClaimSource;
import ai.neargo.shop.food.dto.SalesQuotaClaimVO;
import java.util.List;

/**
 * Local admission joins the caller's existing FOOD batch transaction. No core RPC belongs inside that transaction.
 * The batch owner freezes input and returned references, including an empty claim list; a frozen retry does not
 * re-evaluate newly created rules. This service deduplicates original source references, then claims every
 * overlapping active quota. It never replenishes a consumed sales limit automatically after refund.
 */
public interface SalesQuotaClaimService {
    List<SalesQuotaClaimVO> reserve(String entityNo, String storeNo, String batchNo, String orderSource,
                                  String fulfillment, List<SalesQuotaClaimSource> sources);
    List<SalesQuotaClaimVO> release(String entityNo, String storeNo, String batchNo);
    List<SalesQuotaClaimVO> consume(String entityNo, String storeNo, String batchNo);
}
