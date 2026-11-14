package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.EntityStoresVO;
import java.util.List;

public interface StoreEntityClaimService {
    record Command(String requestNo,String targetEntityNo,long expectedStoreVersion) {}
    record View(String claimNo,String requestNo,String state,String storeNo,String previousEntityNo,
            String entityNo,List<EntityStoresVO> stores,int revokedStaff,boolean placeholderRetired,String lastError) {}
    View create(String tenant,String owner,String sourceEntity,String storeNo,Command command);
    View byRequest(String tenant,String owner,String requestNo);
    View byNo(String tenant,String owner,String claimNo);
    View resume(String tenant,String owner,String claimNo);
    void recoverPending();
}
