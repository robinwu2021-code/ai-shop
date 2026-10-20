package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.pay.mapper.storedvalue.SvUserStoreIndexMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvUserStoreIndex;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Only a committed credit creates directory membership; live store status is enriched by the later query owner. */
@Component
public class StoredValueStoreProjector {
    private final SvUserStoreIndexMapper index;
    public StoredValueStoreProjector(SvUserStoreIndexMapper index) { this.index = index; }
    @Transactional(transactionManager = "payTxManager", propagation = Propagation.MANDATORY)
    public void credit(EntitlementContext ctx, SvRecharge recharge, LocalDateTime at) {
        var claim = new SvUserStoreIndex(); StoredValueFacts.audit(claim, ctx, at);
        claim.setUserNo(recharge.getUserNo());claim.setCustomerNo(recharge.customerIdentity()); claim.setEntityNo(recharge.getEntityNo());
        claim.setStoreNo(recharge.getStoreNo()); claim.setStoreName(recharge.getStoreName());
        claim.setLastRechargeAt(at); claim.setLastActivityAt(at); index.ensure(claim);
        var row = index.lock(ctx.tenantNo(), recharge.customerIdentity(), recharge.getStoreNo());
        if (row == null || !row.getEntityNo().equals(recharge.getEntityNo())) StoredValueFacts.conflict();
        boolean newest = !at.isBefore(row.getLastActivityAt());
        row.setHasRecharge(1);
        if (row.getLastRechargeAt() == null || at.isAfter(row.getLastRechargeAt())) row.setLastRechargeAt(at);
        boolean consumption = row.getLastConsumeAt() != null
                && !row.getLastConsumeAt().isBefore(row.getLastRechargeAt());
        row.setLastActivityAt(consumption ? row.getLastConsumeAt() : row.getLastRechargeAt());
        row.setLastActivityType(consumption ? "CONSUMPTION" : "RECHARGE");
        if (newest) row.setStoreName(recharge.getStoreName());
        row.setUpdatedAt(at); row.setUpdatedBy(ctx.actorNo());
        if (index.updateById(row) != 1) StoredValueFacts.conflict();
    }
    @Transactional(transactionManager = "payTxManager", propagation = Propagation.MANDATORY)
    public void consume(EntitlementContext ctx, String user, String entity, String store, String storeName,
                         LocalDateTime at) {
        var claim = new SvUserStoreIndex(); StoredValueFacts.audit(claim, ctx, at);
        claim.setUserNo(user); claim.setEntityNo(entity); claim.setStoreNo(store); claim.setStoreName(storeName);
        claim.setLastConsumeAt(at); claim.setLastActivityAt(at); index.ensureConsumption(claim);
        var row = index.lock(ctx.tenantNo(), user, store);
        if (row == null || !entity.equals(row.getEntityNo())) StoredValueFacts.conflict();
        boolean newest = !at.isBefore(row.getLastActivityAt());
        if (row.getLastConsumeAt() == null || at.isAfter(row.getLastConsumeAt())) row.setLastConsumeAt(at);
        boolean consumption = row.getLastRechargeAt() == null
                || !row.getLastConsumeAt().isBefore(row.getLastRechargeAt());
        row.setLastActivityAt(consumption ? row.getLastConsumeAt() : row.getLastRechargeAt());
        row.setLastActivityType(consumption ? "CONSUMPTION" : "RECHARGE");
        if (newest) row.setStoreName(storeName);
        row.setUpdatedAt(at); row.setUpdatedBy(ctx.actorNo());
        if (index.updateById(row) != 1) StoredValueFacts.conflict();
    }

}
