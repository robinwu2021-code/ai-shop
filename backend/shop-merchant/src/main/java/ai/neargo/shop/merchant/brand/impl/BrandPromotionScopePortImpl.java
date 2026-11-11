package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads real organization facts. No membership in this result grants financial permission. */
@Service
public class BrandPromotionScopePortImpl implements BrandPromotionScopePort {
    private final ManagementRepository db;
    public BrandPromotionScopePortImpl(ManagementRepository db){this.db=db;}
    public Brand find(String tenant,String management,String brand,String entity){
        var row=db.one("SELECT b.*,m.auth_revision FROM mch_brand b JOIN mch_management_space m ON m.tenant_no=b.tenant_no AND m.management_no=b.management_no AND m.status='ACTIVE' AND m.deleted=0 JOIN mch_entity e ON e.tenant_no=b.tenant_no AND e.entity_no=b.legal_entity_no AND e.status='ACTIVE' AND e.deleted=0 AND e.ownership_change_no IS NULL WHERE b.tenant_no=? AND b.management_no=? AND b.brand_no=? AND b.legal_entity_no=? AND b.status='ACTIVE' AND b.deleted=0",tenant,management,brand,entity);
        if(row==null)return null;
        var stores=db.rows("SELECT s.store_no,s.entity_no,s.version AS store_revision,e.market,b.binding_no,b.version AS binding_revision FROM mch_brand_store_binding b JOIN mch_store s ON s.tenant_no=b.tenant_no AND s.store_no=b.store_no AND s.status='ACTIVE' AND s.deleted=0 AND s.ownership_change_no IS NULL JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.status='ACTIVE' AND e.deleted=0 AND e.ownership_change_no IS NULL WHERE b.tenant_no=? AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0 ORDER BY s.store_no",tenant,brand)
                .stream().map(s->new Store(str(s,"store_no"),str(s,"entity_no"),num(s,"store_revision"),str(s,"binding_no"),num(s,"binding_revision"),str(s,"market"))).toList();
        return new Brand(management,brand,entity,num(row,"version"),num(row,"auth_revision"),stores);
    }
    public boolean storeBelongsTo(String tenant,String store,String entity){
        return db.one("SELECT s.store_no FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.status='ACTIVE' AND e.deleted=0 AND e.ownership_change_no IS NULL WHERE s.tenant_no=? AND s.store_no=? AND s.entity_no=? AND s.status='ACTIVE' AND s.deleted=0 AND s.ownership_change_no IS NULL",tenant,store,entity)!=null;
    }
    public List<String> activeBrands(String tenant,String store,String entity){
        return db.rows("SELECT b.brand_no FROM mch_brand_store_binding r JOIN mch_brand b ON b.tenant_no=r.tenant_no AND b.brand_no=r.brand_no AND b.status='ACTIVE' AND b.deleted=0 JOIN mch_management_space m ON m.tenant_no=b.tenant_no AND m.management_no=b.management_no AND m.status='ACTIVE' AND m.deleted=0 JOIN mch_store s ON s.tenant_no=r.tenant_no AND s.store_no=r.store_no AND s.entity_no=? AND s.status='ACTIVE' AND s.deleted=0 AND s.ownership_change_no IS NULL JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.status='ACTIVE' AND e.deleted=0 AND e.ownership_change_no IS NULL JOIN mch_entity h ON h.tenant_no=b.tenant_no AND h.entity_no=b.legal_entity_no AND h.status='ACTIVE' AND h.deleted=0 AND h.ownership_change_no IS NULL WHERE r.tenant_no=? AND r.store_no=? AND r.status='ACTIVE' AND r.deleted=0 AND b.legal_entity_no IS NOT NULL",entity,tenant,store)
                .stream().map(b->str(b,"brand_no")).toList();
    }
}
