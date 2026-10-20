package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.pay.entitlement.service.EntitlementPostingService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every quota mutation is accompanied by an immutable entry within the caller's pay transaction. */
@Component
public class EntitlementPostingServiceImpl implements EntitlementPostingService {
    private final EntLedgerMapper ledger;
    public EntitlementPostingServiceImpl(EntLedgerMapper ledger) { this.ledger = ledger; }

    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void post(EntitlementContext context, EntInstance asset, String type, String operation,
                     int unitsDelta, int reservedDelta, String bucketKey, String originEntryNo) {
        var row = new EntLedger();
        EntitlementRecords.audit(row, context);
        row.setEntryNo("EL" + UUID.randomUUID().toString().replace("-", ""));
        row.setInstanceNo(asset.getInstanceNo());
        row.setAssetSeq(asset.getAssetSeq());
        row.setBizType(type);
        row.setBizKey(type + ":" + operation);
        row.setUnitsDelta(unitsDelta);
        row.setReservedDelta(reservedDelta);
        row.setAfterUnits(asset.getRemainingUnits());
        row.setAfterReserved(asset.getReservedUnits());
        row.setBucketKey(bucketKey);
        row.setOriginEntryNo(originEntryNo);
        row.setOccurredAt(row.getCreatedAt());
        EntitlementRecords.changed(ledger.insert(row));
    }
}
