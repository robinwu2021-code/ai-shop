package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.model.AccountSnapshot;
import ai.neargo.shop.pay.points.model.FundPool;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only shared snapshots keep applications away from ledger mutation. */
public interface PointsReadStore {
    Optional<PointType> type(String typeCode);
    Optional<PointType> activeType(String market);
    List<PointType> types();
    FundPool pool(String typeCode);
    AccountSnapshot account(String typeCode, HolderType holder, String holderNo);
    Map<String, Object> voucher(String bizRef);
    Map<String, Object> position(String table, String positionNo);
    Map<String, Object> reserve(String reserveNo);
    Map<String, Object> lifecycle(String typeCode, String subOrderNo);
}
