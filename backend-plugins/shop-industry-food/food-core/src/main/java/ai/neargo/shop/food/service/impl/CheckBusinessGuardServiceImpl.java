package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.service.CheckBusinessGuardService;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Every writer locks the same check before touching its cart/batches. No payment state is inferred.
 */
@Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
public class CheckBusinessGuardServiceImpl implements CheckBusinessGuardService {
    private final FoodBusinessOperationsMapper mapper;

    public CheckBusinessGuardServiceImpl(FoodBusinessOperationsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void assertPartyMutable(String e, String store, String check) {
        assertMutable(e, store, check);
        if (mapper.activeServiceForms(e, store, check) > 0) conflict();
    }

    @Override
    public void lockBatchChecks(String e, String store, String batch) {
        var owners = mapper.checkBusinessGuardSelectOrderBatch(e, store, batch, e, store, batch);
        for (var owner : owners)
            if (owner.text("check_no") != null)
                assertMutable(e, store, (String) owner.text("check_no"));
    }

    @Override
    public void invalidateBatchShares(
            String e, String store, String batch, String actor, String reason) {
        for (var owner :
                mapper.checkBusinessGuardSelectOrderBatch(e, store, batch, e, store, batch)) {
            invalidateShares(e, store, owner.text("check_no"), actor, reason);
        }
    }

    @Override
    public void assertNoPendingChanges(String entity, String store, String check) {
        if (mapper.checkBusinessGuardPendingReturns(entity, store, check) > 0) conflict();
    }

    @Override
    public void assertMutable(String entity, String store, String check) {
        if (check == null) return;
        var rows = mapper.checkBusinessGuardSelectCheck(entity, store, check);
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!"OPEN".equals(rows.getFirst().text("status"))
                || !"OPEN".equals(rows.getFirst().get("service_state"))) conflict();
        if (mapper.checkBusinessGuardCountCheckFreeze(entity, store, check) > 0) conflict();
        if (mapper.checkBusinessGuardCountSettlementItem(entity, store, check, check) > 0)
            conflict();
    }

    @Override
    public void invalidateShares(
            String entity, String store, String check, String actor, String reason) {
        if (check == null) return;
        String explanation = "SOURCE_CHANGED:" + Objects.toString(reason, "");
        mapper.checkBusinessGuardUpdateBusinessSharePlan(
                explanation.substring(0, Math.min(explanation.length(), 255)),
                actor,
                entity,
                store,
                check);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
