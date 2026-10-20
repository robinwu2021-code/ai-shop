package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Metadata methods join the application's short pay transaction; they do not change money. */
@Repository
public class MybatisPointsConfigurationStore implements PointsConfigurationStore {
    private final PointsConfigurationMapper mapper;

    private final AcceptanceAvailabilityProjection availability;
    public MybatisPointsConfigurationStore(PointsConfigurationMapper mapper, AcceptanceAvailabilityProjection availability) {
        this.availability = availability;
        this.mapper = mapper;
    }

    @Override
    public String rules(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.rules(type));
    }

    @Override
    public Map<String, Object> lockRules(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockRules(type));
    }

    @Override
    public void saveRules(String type, long version, String body, String actor) {
        if (DataScopeContext.executeWithoutScope(() -> mapper.saveRules(type, version, body, actor))
                != 1) {
            throw new IllegalArgumentException("POINTS_RULE_REVISION_CONFLICT");
        }
    }

    @Override
    public Map<String, Object> lockRedeemPosition(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockStatementSource(no));
    }

    @Override
    public void createType(PointType t, String actor) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    mapper.createType(t, actor);
                    mapper.createPool(t.typeCode(), t.currency(), actor);
                    return null;
                });
    }

    @Override
    public void lockTypeAndPool(String type) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    if (mapper.lockType(type) == null || mapper.lockPool(type) == null) {
                        throw new IllegalArgumentException("POINTS_TYPE_UNKNOWN");
                    }
                    return null;
                });
    }

    @Override
    public Map<String, Object> lockRefundPreparation(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockRefundPreparation(no));
    }

    @Override
    public Map<String, Object> lockPayoutTicket(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockPayoutTicket(no));
    }

    @Override
    public Map<String, Object> lockStatement(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockStatement(no));
    }

    @Override
    public Map<String, Object> lockReceivable(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockReceivable(no));
    }

    @Override
    public Map<String, Object> lockLifecycle(String type, String sub) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockLifecycle(type, sub));
    }

    @Override
    public void changeTypeStatus(
            String type,
            PointType.Status expected,
            PointType.Status next,
            String market,
            String actor) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    try {
                        if (mapper.status(type, expected.name(), next.name(), market, actor) != 1) {
                            throw new IllegalStateException("POINTS_TYPE_STATE_CONFLICT");
                        }
                    } catch (DuplicateKeyException e) {
                        throw new IllegalArgumentException("POINTS_ACTIVE_EXISTS", e);
                    }
                    return null;
                });
    }

    @Override
    @org.springframework.transaction.annotation.Transactional("payTxManager")
    public void putAgreement(
            String no,
            String type,
            String store,
            String key,
            String state,
            String payload,
            String actor) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    mapper.putAgreement(no, type, store, key, state, payload, actor);
                    if ("ACTIVE".equals(state)) availability.changed(type);
                    return null;
                });
    }

    @Override
    public Map<String, Object> agreement(String key) {
        return DataScopeContext.executeWithoutScope(() -> mapper.agreement(key));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional("payTxManager")
    public void terminateAgreement(String key, String actor) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var prior = mapper.agreement(key);
                    mapper.terminateAgreement(key, actor);
                    if (prior != null && "ACTIVE".equals(prior.get("state")))
                        availability.changed((String)prior.get("type_code"));
                    return null;
                });
    }

    @Override
    public void submit(PoolRequest r, String json) {
        submit(r, json, null);
    }

    @Override
    public void submit(PoolRequest r, String json, String reason) {
        lockTypeAndPool(r.typeCode());
        DataScopeContext.executeWithoutScope(
                () -> {
                    if ("RETIRED".equals(mapper.typeStatusForUpdate(r.typeCode()))) {
                        if (mapper.lockRequest(r.requestNo()) != null) {
                            throw new DuplicateKeyException("Existing request");
                        }
                        throw new IllegalArgumentException("POINTS_TYPE_RETIRED");
                    }
                    mapper.submit(r, json, reason);
                    return null;
                });
    }

    @Override
    public Map<String, Object> request(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.request(no));
    }

    @Override
    public Map<String, Object> lockRequest(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockRequest(no));
    }

    @Override
    public void beginApproval(String no, String actor) {
        beginApproval(no, actor, null);
    }

    @Override
    public void beginApproval(String no, String actor, String reason) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    if (mapper.beginApproval(no, actor, reason) != 1) {
                        throw new IllegalStateException("POINTS_REQUEST_STATE");
                    }
                    return null;
                });
    }

    @Override
    public void approve(String no, String actor, String json) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    if (mapper.approve(no, json) != 1) {
                        throw new IllegalStateException("POINTS_REQUEST_STATE");
                    }
                    return null;
                });
    }

    @Override
    public void rejectRequest(String no, long version, String actor) {
        rejectRequest(no, version, actor, null);
    }

    @Override
    public void rejectRequest(String no, long version, String actor, String reason) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    if (mapper.rejectRequest(no, version, actor, reason) != 1)
                        throw new IllegalArgumentException("POINTS_REQUEST_STATE_CONFLICT");
                    return null;
                });
    }
}
