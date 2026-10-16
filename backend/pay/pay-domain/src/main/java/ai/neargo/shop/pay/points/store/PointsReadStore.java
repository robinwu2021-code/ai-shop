package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.model.AccountSnapshot;
import ai.neargo.shop.pay.points.model.FundPool;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only shared snapshots keep applications away from ledger mutation. */
public interface PointsReadStore {
    Optional<PointType> type(String typeCode);

    Optional<PointType> activeType(String market);

    List<PointType> types();

    FundPool pool(String typeCode);

    AccountSnapshot currentUser(String typeCode, String userNo);

    AccountSnapshot account(String typeCode, HolderType holder, String holderNo);

    Map<String, Object> voucher(String bizRef);

    Map<String, Object> position(String table, String positionNo);

    Map<String, Object> receivable(String receivableNo);

    Map<String, Object> reserve(String reserveNo);

    List<Map<String, Object>> redemptionsForSub(String subOrderNo);

    List<Map<String, Object>> openRefunds(String typeCode, String subOrderNo);

    List<Map<String, Object>> confirmedRefunds(String typeCode, String subOrderNo);

    Map<String, Object> lifecycle(String typeCode, String subOrderNo);

    boolean enabled(String typeCode, String userNo, String storeNo);

    default boolean enabled(Availability context) {
        return enabled(context.typeCode(), context.userNo(), context.storeNo());
    }

    record Availability(
            String typeCode,
            String userNo,
            String storeNo,
            String merchantNo,
            String market,
            String scene,
            String client,
            Flow flow) {}
}
