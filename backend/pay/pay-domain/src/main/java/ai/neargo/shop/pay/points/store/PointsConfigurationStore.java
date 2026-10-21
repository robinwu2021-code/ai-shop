package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PoolRequest;

import java.util.Map;

/** Non-financial configuration/request writes are separate from the posting write port. */
public interface PointsConfigurationStore {
    String rules(String typeCode);

    Map<String, Object> lockRules(String typeCode);

    void saveRules(String typeCode, long expectedVersion, String body, String actor);

    void createType(PointType type, String actor);

    void lockTypeAndPool(String typeCode);

    /** Locks the market's types in a stable order before any individual type lock. */
    void lockMarketTypes(String market);

    /** Changes only future order routing, within the caller's pay transaction. */
    void setOrderDefault(String typeCode, String market, String actor);

    /** Current locking read after the type/pool lock, including under repeatable-read isolation. */
    Map<String, Object> lockRefundPreparation(String preparationNo);

    /** Reads the committed payout outcome after acquiring the shared type/pool lock. */
    Map<String, Object> lockPayoutTicket(String ticketNo);

    Map<String, Object> lockStatement(String statementNo);

    Map<String, Object> lockReceivable(String receivableNo);

    Map<String, Object> lockLifecycle(String typeCode, String subOrderNo);

    Map<String, Object> lockRedeemPosition(String positionNo);

    void changeTypeStatus(
            String typeCode,
            PointType.Status expected,
            PointType.Status next,
            String activeMarket,
            String actor);

    void submit(PoolRequest request, String json);
    default void submit(PoolRequest request, String json, String reason) { submit(request, json); }

    Map<String, Object> lockRequest(String requestNo);

    Map<String, Object> request(String requestNo);

    void putAgreement(
            String no,
            String type,
            String store,
            String activeKey,
            String state,
            String payload,
            String actor);

    Map<String, Object> agreement(String activeKey);

    void terminateAgreement(String activeKey, String actor);

    void rejectRequest(String requestNo, long expectedVersion, String actor);
    default void rejectRequest(String requestNo, long expectedVersion, String actor, String reason) { rejectRequest(requestNo, expectedVersion, actor); }

    void beginApproval(String requestNo, String actor);
    default void beginApproval(String requestNo, String actor, String reason) { beginApproval(requestNo, actor); }

    void approve(String requestNo, String actor, String resultJson);
}
