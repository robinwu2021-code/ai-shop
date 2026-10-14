package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PoolRequest;

import java.util.Map;

/** Non-financial configuration/request writes are separate from the posting write port. */
public interface PointsConfigurationStore {
    void createType(PointType type, String actor);
    void lockTypeAndPool(String typeCode);
    void changeTypeStatus(String typeCode, PointType.Status expected, PointType.Status next, String activeMarket, String actor);
    void submit(PoolRequest request, String json);
    Map<String, Object> lockRequest(String requestNo);
    void putAgreement(String no,String type,String store,String activeKey,String state,String payload,String actor);
    Map<String,Object> agreement(String activeKey);
    void terminateAgreement(String activeKey,String actor);
    void beginApproval(String requestNo, String actor);
    void approve(String requestNo, String actor, String resultJson);
}
