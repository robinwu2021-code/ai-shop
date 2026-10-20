package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.posting.PostingResult;

/** Maker and checker are separate; an approval cannot invent verified bank evidence. */
public interface PoolRequestService {
    void submit(PoolRequest request);
    default void submit(PoolRequest request, String reason) { submit(request); }
    PostingResult approve(String requestNo, String operatorNo);
    default PostingResult approve(String requestNo, String operatorNo, String reason) { return approve(requestNo, operatorNo); }
}
