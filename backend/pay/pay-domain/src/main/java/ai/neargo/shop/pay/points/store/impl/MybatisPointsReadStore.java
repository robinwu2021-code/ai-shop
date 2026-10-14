package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.model.AccountSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundPool;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Trusted business services authorize subjects; SQL projection code stays in the store adapter. */
@Repository
public class MybatisPointsReadStore implements PointsReadStore {
    private final PointsConfigurationMapper mapper;
    public MybatisPointsReadStore(PointsConfigurationMapper mapper) { this.mapper = mapper; }
    @Override public Optional<PointType> type(String no) {
        return DataScopeContext.executeWithoutScope(() -> Optional.ofNullable(mapper.type(no)).map(this::type));
    }
    @Override public Optional<PointType> activeType(String market) {
        return DataScopeContext.executeWithoutScope(() -> Optional.ofNullable(mapper.activeType(market)).map(this::type));
    }
    @Override public List<PointType> types() {
        return DataScopeContext.executeWithoutScope(() -> mapper.types().stream().map(this::type).toList());
    }
    @Override public FundPool pool(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            Map<String, Object> row = mapper.pool(no);
            if (row == null) { throw new IllegalArgumentException("POINTS_TYPE_UNKNOWN"); }
            return new FundPool(no, (String) row.get("currency"), num(row,"u_minor"), num(row,"c_minor"),
                    num(row,"d_minor"), num(row,"b_minor"), num(row,"reserved_b_minor"),num(row,"locked_u_minor"),num(row,"locked_b_minor"));
        });
    }
    @Override public AccountSnapshot account(String type, HolderType holder, String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            Map<String, Object> row = mapper.account(type, holder.name(), no);
            Map<Bucket, Long> balances = new EnumMap<>(Bucket.class);
            if (row != null) { for (Bucket bucket : Bucket.values()) { balances.put(bucket, num(row, bucket.column())); } }
            return new AccountSnapshot(type, holder, no, balances, row != null && num(row,"frozen") == 1,
                    row == null || row.get("expire_at") == null ? null : num(row,"expire_at"),
                    row == null ? 0 : num(row,"expiry_revision"));
        });
    }
    @Override public Map<String, Object> voucher(String biz) {
        return DataScopeContext.executeWithoutScope(() -> mapper.voucher(biz));
    }
    @Override public Map<String,Object> position(String table,String no) {
        boolean allowed=java.util.Arrays.stream(PositionMove.Kind.values()).anyMatch(k -> k.table().equals(table));
        if (!allowed) { throw new IllegalArgumentException("POINTS_POSITION_KIND_INVALID"); }
        return DataScopeContext.executeWithoutScope(() -> mapper.position(table,no));
    }
    @Override public Map<String,Object> reserve(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.reserve(no));
    }
    @Override public List<Map<String,Object>> redemptionsForSub(String sub) {
        return DataScopeContext.executeWithoutScope(() -> mapper.redemptionsForSub(sub));
    }
    @Override public Map<String,Object> lifecycle(String type,String sub) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lifecycle(type,sub));
    }
    private PointType type(Map<String, Object> r) {
        return new PointType((String) r.get("type_code"), (String) r.get("market"), (String) r.get("currency"),
                (int) num(r,"scale"), new RedeemRate(num(r,"rate_points"), num(r,"rate_minor")),
                (int) num(r,"deduct_ratio_bp"), (int) num(r,"idle_days"),
                PointType.Status.valueOf((String) r.get("status")));
    }
    private long num(Map<String, Object> r, String key) { return new BigInteger(r.get(key).toString()).longValueExact(); }
}
