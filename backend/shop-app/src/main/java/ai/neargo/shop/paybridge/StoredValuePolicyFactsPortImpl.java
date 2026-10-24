package ai.neargo.shop.paybridge;
import ai.neargo.shop.merchant.brand.StoredValuePolicyAuthorityService;
import ai.neargo.shop.promotion.service.StoredValueActivityJournal;
import ai.neargo.shop.spi.storedvalue.*;
import org.springframework.stereotype.Component;
@Component
public class StoredValuePolicyFactsPortImpl implements StoredValuePolicyFactsPort {
 private final StoredValueActivityJournal journal;private final StoredValuePolicyAuthorityService authority;
 public StoredValuePolicyFactsPortImpl(StoredValueActivityJournal journal,StoredValuePolicyAuthorityService authority){this.journal=journal;this.authority=authority;}
 public StoredValuePolicies.StoreScope current(String tenant,String entity,String store,String version){var scope=journal.activePolicyScope(tenant,entity,store,version);return scope==null?null:authority.currentStoreScope(scope,entity);}
}
