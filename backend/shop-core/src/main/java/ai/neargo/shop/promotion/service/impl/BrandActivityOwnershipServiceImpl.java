package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.service.BrandActivityOwnershipService;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtActivity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The existing activity table's BRAND marker is never treated as a merchant-wide activity. */
@Service("brandActivityOwnershipService")
public class BrandActivityOwnershipServiceImpl implements BrandActivityOwnershipService {
    @Autowired(required=false) private BrandPromotionScopePort organization;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    private final JdbcTemplate db;private final Object datasource;
    public BrandActivityOwnershipServiceImpl(@Qualifier("transactionManager")PlatformTransactionManager manager){if(!(manager instanceof DataSourceTransactionManager data))throw new IllegalStateException("Core JDBC transaction required");datasource=Objects.requireNonNull(data.getDataSource());db=new JdbcTemplate(data.getDataSource());}
    public boolean visible(ActivityActor actor,PmtActivity row){
        if(!"BRAND".equals(row.getOwner()))return actor.brandNo()==null;
        if(actor.brandNo()==null)return false;
        return db.queryForObject("SELECT COUNT(*) FROM pmt_brand_activity_owner WHERE tenant_no=? AND activity_no=? AND management_no=? AND brand_no=? AND entity_no=?",Long.class,actor.tenantNo(),row.getActivityNo(),actor.managementNo(),actor.brandNo(),actor.entityNo())==1;
    }
    public void register(ActivityActor actor,String activity){
        if(actor.brandNo()==null)return;
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(datasource))throw new IllegalStateException("Activity ownership must join its Core transaction");
        db.update("INSERT INTO pmt_brand_activity_owner(tenant_no,activity_no,management_no,brand_no,entity_no,created_at,created_by) VALUES(?,?,?,?,?,?,?)",actor.tenantNo(),activity,actor.managementNo(),actor.brandNo(),actor.entityNo(),Timestamp.from(Instant.now()),actor.actorNo());
    }
    public boolean ordinaryScope(ActivityActor actor,UnifiedActivityDefinition definition){
        if(organization==null || actor.brandNo()==null || !"ORDER".equals(definition.scene()) || !"STORES".equals(definition.storeParticipationScope().mode()) || actor.allowedStores()==null || !actor.allowedStores().containsAll(definition.storeParticipationScope().refs()))return false;
        var scope=organization.find(actor.tenantNo(),actor.managementNo(),actor.brandNo(),actor.entityNo());
        return scope!=null && scope.stores().stream().map(BrandPromotionScopePort.Store::storeNo).toList().containsAll(definition.storeParticipationScope().refs());
    }
    public boolean participates(PmtActivity activity,String entity,String store){
        if(organization==null || !"BRAND".equals(activity.getOwner()) || store==null)return false;
        var owners=db.queryForList("SELECT management_no,brand_no,entity_no FROM pmt_brand_activity_owner WHERE tenant_no=? AND activity_no=? AND entity_no=?",activity.getTenantNo(),activity.getActivityNo(),activity.getEntityNo());
        if(owners.size()!=1)return false;
        var owner=owners.getFirst();var scope=organization.find(activity.getTenantNo(),(String)owner.get("management_no"),(String)owner.get("brand_no"),activity.getEntityNo());
        if(scope==null || scope.stores().stream().noneMatch(s->s.storeNo().equals(store)&&s.entityNo().equals(entity)))return false;
        var fixed=db.queryForList("SELECT stores_json FROM pmt_brand_cost_snapshot WHERE tenant_no=? AND resource_type='ACTIVITY' AND resource_no=? AND resource_version=?",String.class,activity.getTenantNo(),activity.getActivityNo(),activity.getCurrentVersionNo());
        if(fixed.size()!=1)return false;
        for(var member:json.readTree(fixed.getFirst()))if(store.equals(member.path("storeNo").asText())&&entity.equals(member.path("entityNo").asText()))return true;
        return false;
    }
    public List<String> ordinaryActivities(String tenant,String entity,String store){
        if(organization==null)return List.of();var brands=organization.activeBrands(tenant,store,entity);if(brands.isEmpty())return List.of();
        var args=new ArrayList<Object>();args.add(tenant);args.addAll(brands);
        return db.queryForList("SELECT o.activity_no FROM pmt_brand_activity_owner o JOIN pmt_activity a ON a.tenant_no=o.tenant_no AND a.activity_no=o.activity_no AND a.entity_no=o.entity_no AND a.owner='BRAND' AND a.scene='ORDER' AND a.status='RUNNING' AND a.archived_at IS NULL AND a.deleted=0 WHERE o.tenant_no=? AND o.brand_no IN ("+String.join(",",Collections.nCopies(brands.size(),"?"))+")",String.class,args.toArray());
    }

}
