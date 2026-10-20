package ai.neargo.shop.pay.migration.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.LocalDateTime;

public final class MigrationAliasViews {
    private MigrationAliasViews() {}
    public record Pair(String pmtStageNo,String mktStageNo) {
        public Pair {
            ContractChecks.id(pmtStageNo); ContractChecks.id(mktStageNo);
            ContractChecks.require(!pmtStageNo.equals(mktStageNo));
        }
    }
    public record Recognize(Pair pair,long expectedPmtRevision,long expectedMktRevision,String reason) {
        public Recognize {
            ContractChecks.present(pair);
            ContractChecks.require(expectedPmtRevision>=0 && expectedMktRevision>=0 && reason!=null
                    && !reason.isBlank() && reason.length()<=500);
        }
    }
    public record AliasView(String aliasNo,String entityNo,String migrationBatchNo,String pmtStageNo,String mktStageNo,
                            String canonicalStageNo,String pmtContentHash,String mktContentHash,String comparisonHash,
                            long matchedInstanceCount,LocalDateTime comparedAt,String status,boolean importReady,
                            LocalDateTime createdAt,long revision) {
        public AliasView {
            ContractChecks.id(aliasNo); ContractChecks.id(entityNo); ContractChecks.id(migrationBatchNo);
            ContractChecks.id(pmtStageNo); ContractChecks.id(mktStageNo);
            ContractChecks.hash(pmtContentHash); ContractChecks.hash(mktContentHash); ContractChecks.hash(comparisonHash);
            ContractChecks.require(pmtStageNo.equals(canonicalStageNo) && !pmtStageNo.equals(mktStageNo)
                    && matchedInstanceCount>=0 && comparedAt!=null && createdAt!=null && revision>=0
                    && "RECOGNIZED".equals(status) && !importReady);
        }
    }
}
