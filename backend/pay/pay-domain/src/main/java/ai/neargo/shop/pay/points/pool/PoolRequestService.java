package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.posting.PostingResult;

/** Maker and checker are separate; an approval cannot invent verified bank evidence. */
public interface PoolRequestService {
    void submit(PoolRequest request);
    PostingResult approve(String requestNo, String operatorNo);
}
