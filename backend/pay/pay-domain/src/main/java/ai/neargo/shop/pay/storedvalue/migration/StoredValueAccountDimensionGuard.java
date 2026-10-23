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

/** Non-null/nonblank identity enforced without rewriting legacy column collations or balances. */
@Service
public class StoredValueAccountDimensionGuard {
    static final String NAME="ck_sv_issuer_dimensions";
    static final String EXPRESSION="issuer_no IS NOT NULL AND CHAR_LENGTH(TRIM(issuer_no))>0 AND issuer_version_no IS NOT NULL AND CHAR_LENGTH(TRIM(issuer_version_no))>0 AND customer_no IS NOT NULL AND CHAR_LENGTH(TRIM(customer_no))>0";
    static final String SQL="ALTER TABLE sv_account ADD CONSTRAINT "+NAME+" CHECK ("+EXPRESSION+") ENFORCED";
    private final IssuerRepository db;
    private final StoredValueSchemaAuthorization authorization;
    private final StoredValueAccountKeyInspector inspector;
    private final StoredValueSchemaDdl ddl;
    public StoredValueAccountDimensionGuard(IssuerRepository db,StoredValueSchemaAuthorization authorization,StoredValueAccountKeyInspector inspector,StoredValueSchemaDdl ddl){
        this.db=db;this.authorization=authorization;this.inspector=inspector;this.ddl=ddl;
    }
    public boolean installed(){
        var row=db.one("SELECT tc.ENFORCED AS enforced,cc.CHECK_CLAUSE AS clause FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='sv_account' AND tc.CONSTRAINT_NAME=? AND tc.CONSTRAINT_TYPE='CHECK'",NAME);
        if(row==null)return false;
        if(!"YES".equals(text(row,"enforced"))||!normalize(EXPRESSION).equals(normalize(text(row,"clause"))))conflict();
        return true;
    }
    public String install(String operator,String approvalNo,Proof proof){return install(operator,approvalNo,proof,null);}
    public String install(String operator,String approvalNo,Proof proof,String expectedApprovalHash){
        if(TransactionSynchronizationManager.isActualTransactionActive())conflict();
        var approved=authorization.authorize(operator,approvalNo,proof);
        if(expectedApprovalHash!=null&&!expectedApprovalHash.equals(approved.approvalHash()))conflict();
        return ddl.locked(execute->{
            var current=authorization.authorize(operator,approvalNo,proof);
            if(!approved.approvalHash().equals(current.approvalHash()))conflict();
            var plan=inspector.inspect();if(!plan.ready()||!"KEYS_READY".equals(plan.nextStep()))conflict();
            if(!db.rows("SELECT request_key FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_KEY_STEP' AND result_status='PENDING' AND deleted=0 LIMIT 1").isEmpty())conflict();
            String key=StoredValueSchemaAuthorization.hash(approvalNo+":DIMENSIONS");
            var pending=db.rows("SELECT request_key,payload_hash FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_DIMENSION' AND result_status='PENDING' AND deleted=0 LIMIT 2");
            if(pending.size()>1||!pending.isEmpty()&&(!key.equals(text(pending.getFirst(),"request_key"))||!approved.approvalHash().equals(text(pending.getFirst(),"payload_hash"))))conflict();
            var row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_DIMENSION' AND request_key=? AND deleted=0",key);
            if(row!=null&&!approved.approvalHash().equals(text(row,"payload_hash")))conflict();
            boolean guarded=installed();
            if(row!=null&&"COMPLETED".equals(text(row,"result_status"))){if(!guarded)conflict();return text(row,"resource_no");}
            if(row==null){
                String resource=BizKey.next(BizKey.STORED_VALUE_MIGRATION);var at=Timestamp.from(Instant.now());
                db.write(()->{db.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES('__LEDGER__','SV_SCHEMA_DIMENSION',?,?,?,'PENDING',0,0,?,?,?,?)",key,approved.approvalHash(),resource,at,at,operator,operator);return null;});
            }
            if(!guarded)execute.accept(SQL);
            if(!installed()||!approved.approvalHash().equals(authorization.authorize(operator,approvalNo,proof).approvalHash()))conflict();
            return db.write(()->{
                var saved=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_DIMENSION' AND request_key=? AND deleted=0 FOR UPDATE",key);
                int n=db.update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_DIMENSION' AND request_key=? AND result_status='PENDING' AND payload_hash=?",db.encode(Map.of("state","DIMENSIONS_GUARDED","receiptNo",text(saved,"resource_no"))),Timestamp.from(Instant.now()),operator,key,approved.approvalHash());
                if(n!=1)conflict();return text(saved,"resource_no");
            });
        });
    }
    private static String normalize(String value){return value==null?"":value.replaceAll("[\\s\\x60()]+","").toLowerCase(Locale.ROOT);}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
