package ai.neargo.inventory.domain.mapper;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

/** Inventory-owned projection persistence, intentionally unrelated to inv_stock_balance. */
@Repository
@ConditionalOnInventory
public class StockProjectionMapper {
    private final JdbcTemplate db;
    public StockProjectionMapper(@Qualifier("invDataSource") DataSource dataSource){db=new JdbcTemplate(dataSource);}
    public record Session(long generation,String status){}
    public record StoredReceipt(String hash,String json){}
    public record Projection(long generation,long version,String hash,Snapshot snapshot,LocalDateTime receivedAt){}
    public void ensureSession(Scope s) {
        // Callers serialize on the real inventory owner, so this insert is race-free for one entity.
        if(session(s)==null)db.update("INSERT INTO inv_food_stock_sync_session "
                +"(tenant_no,entity_no,store_no,created_at,created_by,updated_at,updated_by,source_system,generation,status) "
                +"VALUES ('MAIN',?,?,CURRENT_TIMESTAMP(3),'SYSTEM',CURRENT_TIMESTAMP(3),'SYSTEM','FOOD_LOCAL',0,'OFF')",s.entityNo(),s.storeNo());
    }
    public Session session(Scope s){var rows=db.query("SELECT generation,status FROM inv_food_stock_sync_session WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND deleted=0 FOR UPDATE",(rs,n)->new Session(rs.getLong(1),rs.getString(2)),s.entityNo(),s.storeNo());return rows.isEmpty()?null:rows.getFirst();}
    public void setSession(Scope s,long generation,String status){db.update("UPDATE inv_food_stock_sync_session SET generation=?,status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3),activated_at=CASE WHEN ?='ACTIVE' THEN CURRENT_TIMESTAMP(3) ELSE activated_at END,disabled_at=CASE WHEN ?='OFF' THEN CURRENT_TIMESTAMP(3) ELSE disabled_at END WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND deleted=0",generation,status,status,status,s.entityNo(),s.storeNo());}
    public StoredReceipt receipt(Scope s,String event){var rows=db.query("SELECT payload_hash,result_json FROM inv_food_stock_sync_receipt WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND event_no=? AND deleted=0",(rs,n)->new StoredReceipt(rs.getString(1),rs.getString(2)),s.entityNo(),s.storeNo(),event);return rows.isEmpty()?null:rows.getFirst();}
    public void receipt(Scope s,long generation,String event,String hash,String no,Long version,String json){db.update("INSERT INTO inv_food_stock_sync_receipt (tenant_no,entity_no,store_no,created_at,created_by,updated_at,updated_by,source_system,event_no,generation,payload_hash,receipt_no,result_json,applied_version,received_at) VALUES ('MAIN',?,?,CURRENT_TIMESTAMP(3),'SYSTEM',CURRENT_TIMESTAMP(3),'SYSTEM','FOOD_LOCAL',?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",s.entityNo(),s.storeNo(),event,generation,hash,no,json,version);}
    public Projection projection(Scope s,String item){var rows=db.query("SELECT * FROM inv_food_stock_projection WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND source_item_no=? AND deleted=0 FOR UPDATE",(rs,n)->new Projection(rs.getLong("generation"),rs.getLong("source_version"),rs.getString("payload_hash"),new Snapshot(rs.getString("source_item_no"),rs.getString("base_unit"),rs.getString("tracking_mode"),rs.getString("status"),rs.getBigDecimal("on_hand"),rs.getBigDecimal("reserved_qty"),rs.getLong("source_version"),rs.getTimestamp("source_updated_at").toLocalDateTime(),rs.getString("target_owner_id"),rs.getString("target_location_id"),rs.getString("target_item_id")),rs.getTimestamp("received_at").toLocalDateTime()),s.entityNo(),s.storeNo(),item);return rows.isEmpty()?null:rows.getFirst();}
    public long count(Scope s){Long count=db.queryForObject("SELECT COUNT(*) FROM inv_food_stock_projection WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND deleted=0",Long.class,s.entityNo(),s.storeNo());return count==null?0:count;}
    public List<String> page(Scope s,long offset,int size){return db.query("SELECT source_item_no FROM inv_food_stock_projection WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND deleted=0 ORDER BY id LIMIT ? OFFSET ?",(rs,n)->rs.getString(1),s.entityNo(),s.storeNo(),size,offset);}
    public void insert(Scope s,long generation,String hash,Snapshot p){db.update("INSERT INTO inv_food_stock_projection (tenant_no,entity_no,store_no,created_at,created_by,updated_at,updated_by,source_system,source_item_no,target_owner_id,target_location_id,target_item_id,base_unit,tracking_mode,status,on_hand,reserved_qty,source_version,generation,payload_hash,source_updated_at,received_at) VALUES ('MAIN',?,?,CURRENT_TIMESTAMP(3),'SYSTEM',CURRENT_TIMESTAMP(3),'SYSTEM','FOOD_LOCAL',?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",s.entityNo(),s.storeNo(),p.itemNo(),p.targetOwnerRef(),p.targetLocationRef(),p.targetItemRef(),p.baseUnit(),p.trackingMode(),p.status(),p.onHand(),p.reservedQty(),p.sourceVersion(),generation,hash,p.sourceUpdatedAt());}
    public void update(Scope s,long generation,String hash,Snapshot p){db.update("UPDATE inv_food_stock_projection SET target_owner_id=?,target_location_id=?,target_item_id=?,base_unit=?,tracking_mode=?,status=?,on_hand=?,reserved_qty=?,source_version=?,generation=?,payload_hash=?,source_updated_at=?,received_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND source_system='FOOD_LOCAL' AND source_item_no=? AND deleted=0",p.targetOwnerRef(),p.targetLocationRef(),p.targetItemRef(),p.baseUnit(),p.trackingMode(),p.status(),p.onHand(),p.reservedQty(),p.sourceVersion(),generation,hash,p.sourceUpdatedAt(),s.entityNo(),s.storeNo(),p.itemNo());}
}
