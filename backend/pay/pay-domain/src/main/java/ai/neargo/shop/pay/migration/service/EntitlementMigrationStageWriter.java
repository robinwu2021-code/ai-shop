package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One short pay transaction, without core calls or financial writers. */
@Component
public class EntitlementMigrationStageWriter {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private final EntMigrationStageMapper stages;
    public EntitlementMigrationStageWriter(EntMigrationStageMapper stages) { this.stages=stages; }
    @Transactional("payTxManager")
    public EntMigrationStage claim(EntMigrationStage candidate) {
        stages.claim(candidate);
        var stored=stages.lockRequest(candidate.getTenantNo(),candidate.getRequestKey());
        if(stored==null || !candidate.getCommandHash().equals(stored.getCommandHash())
                || !candidate.getEntityNo().equals(stored.getEntityNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        documents.bind(DocumentType.ENTITLEMENT_MIGRATION_STAGE, stored.getTenantNo(), stored.getStageNo());
        return stored;
    }
}
