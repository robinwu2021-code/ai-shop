package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One durable index step per call. DDL autocommits; failure preserves the intent and all writer fences. */
@Service
public class StoredValueAccountKeyCutover {
    public record Result(String receiptNo,String approvalNo,String action,String state,String beforeSchemaHash,String afterSchemaHash){}
    private static final String TENANT="__LEDGER__",OP="SV_SCHEMA_KEY_STEP";
    private final IssuerRepository db;
    private final StoredValueSchemaAuthorization authorization;
    private final StoredValueAccountKeyInspector inspector;
    private final StoredValueSchemaDdl ddl;
    public StoredValueAccountKeyCutover(IssuerRepository db,StoredValueSchemaAuthorization authorization,StoredValueAccountKeyInspector inspector,StoredValueSchemaDdl ddl){
        this.db=db;this.authorization=authorization;this.inspector=inspector;this.ddl=ddl;
    }
    public Result step(String operator,String approvalNo,Proof proof){
        if(TransactionSynchronizationManager.isActualTransactionActive())conflict();
        var approval=authorization.authorize(operator,approvalNo,proof);
        return ddl.locked(execute->{
            reauthorize(operator,approvalNo,proof,approval.approvalHash());
            var plan=inspector.inspect();if(!plan.ready())conflict();
            var pending=db.rows("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND result_status='PENDING' AND deleted=0 LIMIT 2",TENANT,OP);
            if(pending.size()>1)conflict();
            Map<String,Object> row=pending.isEmpty()?null:pending.getFirst();Result intent;
            if(row!=null){
                if(!approval.approvalHash().equals(text(row,"payload_hash")))conflict();
                intent=db.decode(text(row,"response_snapshot"),Result.class);
                if(!approvalNo.equals(intent.approvalNo())||!text(row,"resource_no").equals(intent.receiptNo())||!"INTENT".equals(intent.state())
                        ||!Set.of("ADD_ISSUER_KEY","DROP_LEGACY_KEYS").contains(intent.action()))conflict();
                if(intent.afterSchemaHash().equals(plan.schemaHash())){
                    reauthorize(operator,approvalNo,proof,approval.approvalHash());
                    if(!same(inspector.inspect(),intent.afterSchemaHash()))conflict();
                    return finish(row,intent,"RECOVERED",operator);
                }
                if(!intent.beforeSchemaHash().equals(plan.schemaHash())||!intent.action().equals(plan.nextStep())||!intent.afterSchemaHash().equals(plan.targetSchemaHash()))conflict();
            }else{
                if("KEYS_READY".equals(plan.nextStep()))return new Result(null,approvalNo,"KEYS_READY","KEYS_READY",plan.schemaHash(),plan.schemaHash());
                String key=StoredValueSchemaAuthorization.hash(approvalNo+":"+plan.nextStep());
                if(db.one("SELECT request_key FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND request_key=? AND deleted=0",TENANT,OP,key)!=null)conflict();
                intent=new Result(BizKey.next(BizKey.STORED_VALUE_MIGRATION),approvalNo,plan.nextStep(),"INTENT",plan.schemaHash(),plan.targetSchemaHash());
                var saved=intent;
                db.write(()->{
                    var at=Timestamp.from(Instant.now());
                    db.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,response_snapshot,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,'PENDING',?,0,0,?,?,?,?)",
                        TENANT,OP,key,approval.approvalHash(),saved.receiptNo(),db.encode(saved),at,at,operator,operator);
                    return null;
                });
                row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND request_key=? AND deleted=0",TENANT,OP,key);
            }
            reauthorize(operator,approvalNo,proof,approval.approvalHash());
            var before=inspector.inspect();
            if(!same(before,intent.beforeSchemaHash())||!before.targetSchemaHash().equals(intent.afterSchemaHash())||before.statements().size()!=1)conflict();
            execute.accept(before.statements().getFirst()); // Never enclosed by db.write/Pay transaction.
            if(!same(inspector.inspect(),intent.afterSchemaHash()))conflict();
            reauthorize(operator,approvalNo,proof,approval.approvalHash());
            if(!same(inspector.inspect(),intent.afterSchemaHash()))conflict();
            return finish(row,intent,"APPLIED",operator);
        });
    }
    private void reauthorize(String actor,String no,Proof proof,String expected){
        if(!expected.equals(authorization.authorize(actor,no,proof).approvalHash()))conflict();
    }
    private Result finish(Map<String,Object> row,Result intent,String state,String actor){
        var result=new Result(intent.receiptNo(),intent.approvalNo(),intent.action(),state,intent.beforeSchemaHash(),intent.afterSchemaHash());
        return db.write(()->{
            int n=db.update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND operation_kind=? AND request_key=? AND payload_hash=? AND result_status='PENDING' AND deleted=0",
                db.encode(result),Timestamp.from(Instant.now()),actor,TENANT,OP,text(row,"request_key"),text(row,"payload_hash"));
            if(n!=1)conflict();return result;
        });
    }
    private static boolean same(StoredValueAccountKeyPlan.Plan plan,String hash){return plan.ready()&&hash.equals(plan.schemaHash());}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
