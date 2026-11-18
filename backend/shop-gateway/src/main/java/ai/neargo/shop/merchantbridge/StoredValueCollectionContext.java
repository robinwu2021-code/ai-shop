package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections;
import ai.neargo.shop.merchant.brand.StoredValueFinancialAuthorityService;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import org.springframework.stereotype.Component;
/** Resolve before a Pay financial command; actual MCH/USER subject kind is never inferred from a raw identifier. */
@Component
public class StoredValueCollectionContext {
 private final ManagementAccess access;private final StoredValueFinancialAuthorityService authority;private final StoredValueFinanceAppService pay;
 public StoredValueCollectionContext(ManagementAccess access,StoredValueFinancialAuthorityService authority,StoredValueFinanceAppService pay){this.access=access;this.authority=authority;this.pay=pay;}
 public StoredValueCollections.Proof proof(ExecutionScope scope){if(scope.brandNo()==null)return null;var requirement=pay.collectionRequirement(scope).requirement();return authority.collectionProof(access.actor(),scope,requirement);}
 public Query query(ExecutionScope scope,Customer customer){return new Query(scope,customer,proof(scope));}
 public StoreOptions options(ExecutionScope scope){return pay.collectionOptions(new Options(scope,proof(scope)));}
}
