package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.*;
import org.springframework.stereotype.Service;

/** Organization facts only. Resource authorization stays with the calling BFF; no Core lookup inside a Pay lock. */
@Service
public class StoredValueExecutionScopeService implements StoredValueExecutionScopePort {
    private final ManagementRepository db;
    public StoredValueExecutionScopeService(ManagementRepository db){this.db=db;}
    public StoredValueWallets.ExecutionScope current(String tenant,String actor,String entity,String store,String currency){
        for(String value:java.util.List.of(tenant,actor,entity,store))ContractChecks.id(value);ContractChecks.currency(currency);
        var row=db.one("SELECT s.version FROM mch_store s JOIN mch_entity e ON CAST(e.tenant_no AS BINARY(256))=CAST(s.tenant_no AS BINARY(256)) AND CAST(e.entity_no AS BINARY(256))=CAST(s.entity_no AS BINARY(256)) WHERE s.tenant_no=? AND s.store_no=? AND s.entity_no=? AND s.status='ACTIVE' AND s.deleted=0 AND e.status='ACTIVE' AND e.deleted=0",tenant,store,entity);
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
        var binding=db.one("SELECT * FROM mch_brand_store_binding WHERE tenant_no=? AND store_no=? AND status='ACTIVE' AND deleted=0",tenant,store);
        if(binding==null)return new StoredValueWallets.ExecutionScope(tenant,actor,null,entity,store,num(row,"version"),null,null,-1,currency);
        var brand=db.one("SELECT b.management_no,b.version FROM mch_brand b JOIN mch_management_space m ON CAST(m.tenant_no AS BINARY(256))=CAST(b.tenant_no AS BINARY(256)) AND CAST(m.management_no AS BINARY(256))=CAST(b.management_no AS BINARY(256)) WHERE b.tenant_no=? AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0 AND m.status='ACTIVE' AND m.deleted=0",tenant,str(binding,"brand_no"));
        var now=java.time.Instant.now();var from=instant(binding.get("effective_from"));var to=instant(binding.get("effective_to"));
        if(brand==null||from==null||now.isBefore(from)||to!=null&&!now.isBefore(to))throw BizException.of(ErrorCode.CONFLICT);
        return new StoredValueWallets.ExecutionScope(tenant,actor,str(brand,"management_no"),entity,store,num(row,"version"),str(binding,"brand_no"),str(binding,"binding_no"),num(binding,"scope_revision"),currency,num(brand,"version"),2);
    }
}
