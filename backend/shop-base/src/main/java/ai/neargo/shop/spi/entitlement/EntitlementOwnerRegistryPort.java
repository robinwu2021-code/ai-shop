package ai.neargo.shop.spi.entitlement;

/** Exact routing metadata only: a registry read is not a financial mutation permit or a cutover lease. */
public interface EntitlementOwnerRegistryPort {
    OwnerRef resolve(EntitlementContext context,SourceIdentity source);
    enum SourceSystem { MKT_LEGACY, PMT, PAY }
    enum SourceKind { TEMPLATE, INSTANCE, USAGE }
    enum OwnerSystem { CORE_LEGACY, PAY }
    enum MigrationState { PREPARING, VERIFIED, CUTOVER, ARCHIVED }
    record SourceIdentity(SourceSystem sourceSystem,SourceKind sourceKind,String sourceNo) {
        public SourceIdentity {
            ContractChecks.present(sourceSystem); ContractChecks.present(sourceKind); ContractChecks.id(sourceNo);
        }
    }
    record OwnerRef(SourceIdentity source,String targetNo,OwnerSystem ownerSystem,MigrationState status,
                    String migrationBatchNo,String cutoverWatermark,long revision) {
        public OwnerRef {
            ContractChecks.present(source); ContractChecks.present(ownerSystem); ContractChecks.present(status);
            ContractChecks.optionalId(targetNo); ContractChecks.id(migrationBatchNo); ContractChecks.require(revision>=0);
            ContractChecks.require(cutoverWatermark==null || (!cutoverWatermark.isBlank() && cutoverWatermark.length()<=128));
            if(ownerSystem==OwnerSystem.PAY) {
                ContractChecks.require(targetNo!=null && cutoverWatermark!=null
                        && (status==MigrationState.CUTOVER || status==MigrationState.ARCHIVED));
            } else ContractChecks.require(status==MigrationState.PREPARING || status==MigrationState.VERIFIED);
            if(status==MigrationState.VERIFIED) ContractChecks.require(targetNo!=null && cutoverWatermark!=null);
            if(source.sourceSystem()==SourceSystem.PAY) ContractChecks.require(ownerSystem==OwnerSystem.PAY);
        }
    }
}
