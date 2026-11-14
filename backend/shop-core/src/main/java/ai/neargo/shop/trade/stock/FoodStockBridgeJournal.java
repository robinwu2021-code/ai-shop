package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Keeps transport retries independent of a changing local reservation revision or expiry. */
@Repository
public class FoodStockBridgeJournal {
    public record Wire(FoodLocalStockPort.Command command,String payloadJson){}
    private record Stored(String hash,String command,String payload){}
    private final JdbcTemplate db;private final TransactionTemplate write;private final ObjectMapper json;
    public FoodStockBridgeJournal(@Qualifier("dataSource")DataSource source,
            @Qualifier("transactionManager")PlatformTransactionManager manager,ObjectMapper json){
        this.db=new JdbcTemplate(source);this.json=json;write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Wire lookup(FoodLocalStockPort.Scope scope,String planNo,String action,String key,String intentHash) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock lookup must happen outside owner locks");
        return write.execute(tx->{var row=find(scope,planNo,action,key);return row==null?null:decode(row,intentHash);});
    }
    public Wire acquire(FoodLocalStockPort.Scope scope,String planNo,String action,String key,
            String stableIntentHash,Supplier<Wire> original) {
        if(scope==null||!valid(scope.entityNo(),32)||!valid(scope.storeNo(),32)||!valid(planNo,32)
                ||!valid(action,32)||!valid(key,128)||stableIntentHash==null||!stableIntentHash.matches("[0-9a-f]{64}"))throw bad();
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock bridge journal must commit before remote execution");
        try{return write.execute(tx->{var old=find(scope,planNo,action,key);if(old!=null)return decode(old,stableIntentHash);
            var wire=original.get();if(wire==null||wire.command()==null||wire.payloadJson()==null)throw bad();
            db.update("INSERT INTO ord_food_stock_bridge_command "
                    +"(tenant_no,entity_no,store_no,plan_no,action,operation_key,intent_hash,command_json,payload_json,created_at,created_by) "
                    +"VALUES ('MAIN',?,?,?,?,?,?,?,?,?,'SYSTEM')",scope.entityNo(),scope.storeNo(),planNo,action,key,
                    stableIntentHash,json.writeValueAsString(wire.command()),wire.payloadJson(),LocalDateTime.now());
            return wire;});
        }catch(DuplicateKeyException concurrent){return write.execute(tx->{var committed=find(scope,planNo,action,key);if(committed==null)throw conflict();return decode(committed,stableIntentHash);});}
    }
    private Stored find(FoodLocalStockPort.Scope s,String plan,String action,String key){var rows=db.query("SELECT intent_hash,command_json,payload_json FROM ord_food_stock_bridge_command WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND plan_no=? AND action=? AND operation_key=?",(rs,n)->new Stored(rs.getString(1),rs.getString(2),rs.getString(3)),s.entityNo(),s.storeNo(),plan,action,key);return rows.isEmpty()?null:rows.getFirst();}
    private Wire decode(Stored row,String hash){if(!hash.equals(row.hash()))throw conflict();return new Wire(json.readValue(row.command(),FoodLocalStockPort.Command.class),row.payload());}
    private static boolean valid(String v,int max){return v!=null&&!v.isBlank()&&v.length()<=max&&v.equals(v.trim());}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
