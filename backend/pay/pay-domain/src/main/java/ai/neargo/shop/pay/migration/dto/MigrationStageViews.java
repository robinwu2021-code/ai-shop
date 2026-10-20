package ai.neargo.shop.pay.migration.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import java.time.LocalDateTime;

public final class MigrationStageViews {
    private MigrationStageViews() { }
    public record StageCommand(Query query,String reason) {
        public StageCommand {
            ContractChecks.present(query);
            ContractChecks.require(reason!=null && !reason.isBlank() && reason.length()<=500);
        }
    }
    /** No payload, ciphertext, key, customer identity, or authority to import. */
    public record StageView(String stageNo,String sourceSystem,String sourceTemplateNo,String entityNo,
                            String migrationBatchNo,String status,int sourceSchemaVersion,long frozenRevision,
                            int payloadBytes,long instanceCount,long historyCount,String sourceStateAtCapture,
                            boolean importReady,LocalDateTime createdAt,long revision) {
        public StageView {
            ContractChecks.id(stageNo); ContractChecks.id(entityNo); ContractChecks.id(sourceTemplateNo);
            ContractChecks.id(migrationBatchNo);
            ContractChecks.require("STAGED".equals(status) && !importReady && "FROZEN".equals(sourceStateAtCapture)
                    && sourceSchemaVersion==1 && frozenRevision>=0 && payloadBytes>0
                    && instanceCount>=0 && historyCount>=0 && revision>=0);
        }
    }
    public record ManifestStageView(StageView evidence,int manifestVersion,boolean historyAvailable) {
        public ManifestStageView {
            ContractChecks.present(evidence);
            ContractChecks.require(manifestVersion==2 && !evidence.importReady()
                    && historyAvailable=="PMT".equals(evidence.sourceSystem()));
        }
    }
    public record Verification(String stageNo,String status,boolean importReady) {
        public Verification {
            ContractChecks.id(stageNo); ContractChecks.require("SOURCE_MATCHED".equals(status) && !importReady);
        }
    }
}
