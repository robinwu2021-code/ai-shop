package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.Set;

/** Trusted application scope; never deserialize this value from a merchant request. */
public record ActivityActor(String tenantNo, String entityNo, String actorNo, Set<String> allowedStores,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String managementNo,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String brandNo) {
    public ActivityActor(String tenantNo,String entityNo,String actorNo,Set<String> allowedStores){this(tenantNo,entityNo,actorNo,allowedStores,null,null);}
    public ActivityActor {
        ContractChecks.require(brandNo==null?managementNo==null:managementNo!=null);ContractChecks.optionalId(managementNo);ContractChecks.optionalId(brandNo);
        ContractChecks.id(tenantNo);
        ContractChecks.require(tenantNo.length() <= 32);
        ContractChecks.id(entityNo);
        ContractChecks.id(actorNo);
        allowedStores = allowedStores == null ? null : Set.copyOf(allowedStores);
    }
}
