package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.List;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.function.LongFunction;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Source-owned, permanent submission originals. No external IO is allowed in the freeze callback. */
@Component
public final class PayServiceApplicationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;

    public PayServiceApplicationStore(@Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        jdbc=new JdbcTemplate(source);
        writes=new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Original(long profileRevision,String sourceEventId,String requestJson) {
        @Override public String toString() { return "PayServiceApplicationOriginal[REDACTED]"; }
    }

    public Original freeze(String entity,String caller,String scope,String requestNo,String realm,String actor,
            String requestHash,LongFunction<String> encode) {
        if(requestNo==null || !requestNo.matches("[A-Za-z0-9_-]{1,64}")
                || caller==null || caller.isBlank() || scope==null || scope.isBlank()
                || !List.of("OPERATOR","MERCHANT").contains(realm) || actor==null || actor.isBlank()
                || requestHash==null || !requestHash.matches("[a-f0-9]{64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
        try { return writes.execute(tx->{
            var roots=jdbc.queryForList("SELECT ownership_change_no,archived_at FROM mch_entity"
                    +" WHERE tenant_no='MAIN' AND entity_no=? AND deleted=0 FOR UPDATE",entity);
            if(roots.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            if(roots.getFirst().get("ownership_change_no")!=null || roots.getFirst().get("archived_at")!=null)
                throw BizException.of(ErrorCode.CONFLICT);
            var children=jdbc.queryForList("SELECT ownership_change_no FROM mch_store WHERE tenant_no='MAIN'"
                    +" AND entity_no=? AND deleted=0 ORDER BY store_no FOR UPDATE",entity);
            if(children.stream().anyMatch(row->row.get("ownership_change_no")!=null)) throw BizException.of(ErrorCode.CONFLICT);
            var saved=jdbc.query("SELECT request_hash,profile_revision,source_event_id,request_json FROM mch_pay_service_application"
                    +" WHERE tenant_no='MAIN' AND source_caller=? AND source_scope=? AND request_no=?",
                    (row,index)->new Saved(row.getString(1),new Original(row.getLong(2),row.getString(3),row.getString(4))),
                    caller,scope,requestNo);
            if(!saved.isEmpty()) {
                if(!requestHash.equals(saved.getFirst().hash())) throw BizException.of(ErrorCode.CONFLICT);
                return saved.getFirst().original();
            }
            Long previous=jdbc.queryForObject("SELECT MAX(profile_revision) FROM mch_pay_service_application"
                    +" WHERE tenant_no='MAIN' AND entity_no=? AND source_caller=? AND source_scope=?",Long.class,entity,caller,scope);
            // Legacy source profiles used the entity snapshot revision. Keep that old namespace below the new counter.
            var legacy=jdbc.queryForObject("SELECT COALESCE(updated_at,created_at) FROM mch_entity WHERE tenant_no='MAIN' AND entity_no=?",
                    Timestamp.class,entity);
            long floor=legacy==null?1:legacy.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
            long revision=Math.addExact(Math.max(floor,previous==null?0:previous),1);
            String event="ais-pay-"+entity+"-"+revision;
            String bytes=encode.apply(revision);
            if(bytes==null || bytes.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
            jdbc.update("INSERT INTO mch_pay_service_application(tenant_no,entity_no,source_caller,source_scope,request_no,"
                    +"request_hash,profile_revision,source_event_id,actor_realm,actor_no,request_json,created_at,updated_at,created_by,updated_by,version,deleted)"
                    +" VALUES('MAIN',?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),?,?,0,0)",
                    entity,caller,scope,requestNo,requestHash,revision,event,realm,actor,bytes,actor,actor);
            return new Original(revision,event,bytes);
        }); } catch(DuplicateKeyException collision) { throw BizException.of(ErrorCode.CONFLICT); }
    }

    private record Saved(String hash,Original original) {}
}
