package ai.neargo.shop.spi.entitlement;

/** The work-order owner resolves the holder and service line under the current merchant scope. */
public interface ServiceWorkOrderOriginPort {
    Origin resolve(String entityNo, String storeNo, String actorNo, String workOrderNo, String taskNo);

    record Origin(String workOrderNo, String taskNo, long scopeRevision, long taskRevision,
                  String userNo, String skuNo, int quantity) {
        public Origin {
            ContractChecks.id(workOrderNo);
            ContractChecks.id(taskNo);
            ContractChecks.id(userNo);
            ContractChecks.id(skuNo);
            ContractChecks.require(scopeRevision > 0 && taskRevision >= 0 && quantity >= 1 && quantity <= 100);
        }
    }
}
