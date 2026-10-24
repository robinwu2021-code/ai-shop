package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.service.BrandActivityOwnershipService;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtActivity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The existing activity table's BRAND marker is never treated as a merchant-wide activity. */
@Service("brandActivityOwnershipService")
public class BrandActivityOwnershipServiceImpl implements BrandActivityOwnershipService {
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
}
