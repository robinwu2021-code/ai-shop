package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.posting.PostingResult;

/** Approved operational money actions still go through the single accounting writer. */
public interface PoolService {
    PostingResult applyApproved(PoolRequest request);
}
