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

/** Global approved STORE-only cutover. Original accounts/funds remain; brand issuance cannot be activated here. */
@Service
public class StoredValueMigrationResumeService {
    public record Result(String receiptNo,String state,long issuerCount,long accountCount){}
    private final IssuerRepository db;
    private final StoredValueSchemaAuthorization authorization;
    private final StoredValueAccountKeyInspector inspector;
    private final StoredValueAccountDimensionGuard dimensions;
    private final StoredValueSchemaDdl ddl;
    public StoredValueMigrationResumeService(IssuerRepository db,StoredValueSchemaAuthorization authorization,StoredValueAccountKeyInspector inspector,StoredValueAccountDimensionGuard dimensions,StoredValueSchemaDdl ddl){
        this.db=db;this.authorization=authorization;this.inspector=inspector;this.dimensions=dimensions;this.ddl=ddl;
    }
    public Result resume(String operator,String approvalNo,Proof proof){return resume(operator,approvalNo,proof,null);}
    public Result resume(String operator,String approvalNo,Proof proof,String expectedApprovalHash){
        if(TransactionSynchronizationManager.isActualTransactionActive())conflict();
        var approved=authorization.authorizeApproval(operator,approvalNo,proof);
        if(expectedApprovalHash!=null&&!expectedApprovalHash.equals(approved.approvalHash()))conflict();
        String key=StoredValueSchemaAuthorization.hash(approvalNo+":RESUME");
        return ddl.locked(unused->{
            var basic=authorization.authorizeApproval(operator,approvalNo,proof);if(!approved.approvalHash().equals(basic.approvalHash()))conflict();
            var replay=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_RESUME' AND request_key=? AND deleted=0",key);
            if(replay!=null){if(!approved.approvalHash().equals(text(replay,"payload_hash"))||!"COMPLETED".equals(text(replay,"result_status")))conflict();return db.decode(text(replay,"response_snapshot"),Result.class);}
            var complete=authorization.authorize(operator,approvalNo,proof);
            if(!approved.approvalHash().equals(complete.approvalHash()))conflict();
            var plan=inspector.inspect();if(!plan.ready()||!"KEYS_READY".equals(plan.nextStep())||!dimensions.installed())conflict();
            if(!db.rows("SELECT request_key FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind IN ('SV_SCHEMA_KEY_STEP','SV_SCHEMA_DIMENSION') AND result_status='PENDING' AND deleted=0 LIMIT 1").isEmpty())conflict();
            return db.write(()->{
                var issuers=db.rows("SELECT * FROM sv_issuer WHERE deleted=0 AND scope_type='STORE' ORDER BY tenant_no,issuer_no FOR UPDATE");
                if(issuers.size()!=complete.issuerCount())conflict();
                var at=Timestamp.from(Instant.now());
                for(var issuer:issuers){
                    if(!"STORE".equals(text(issuer,"scope_type"))||!"FENCED".equals(text(issuer,"write_mode"))||!Set.of("DRAFT","ACTIVE","ISSUE_STOPPED").contains(text(issuer,"status")))conflict();
                    var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",text(issuer,"tenant_no"),text(issuer,"issuer_no"),text(issuer,"scope_no"));
                    if(access==null||!Set.of("PREPARING","ACTIVE").contains(text(access,"status")))conflict();
                    boolean stopped="ISSUE_STOPPED".equals(text(issuer,"status"));
                    if(db.update("UPDATE sv_issuer SET write_mode='ISSUER',status=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND write_mode='FENCED' AND deleted=0",stopped?"ISSUE_STOPPED":"ACTIVE",at,operator,text(issuer,"tenant_no"),text(issuer,"issuer_no"))!=1)conflict();
                    if(db.update("UPDATE sv_issuer_store_access SET status='ACTIVE',can_issue=?,can_redeem=1,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0",stopped?0:1,at,operator,text(issuer,"tenant_no"),text(issuer,"issuer_no"),text(issuer,"scope_no"))!=1)conflict();
                }
                if(!approved.approvalHash().equals(authorization.authorizeApproval(operator,approvalNo,proof).approvalHash()))conflict();
                var result=new Result(BizKey.next(BizKey.STORED_VALUE_MIGRATION),"ISSUER_WRITERS_RESUMED",issuers.size(),complete.accountCount());
                db.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,response_snapshot,version,deleted,created_at,updated_at,created_by,updated_by) VALUES('__LEDGER__','SV_SCHEMA_RESUME',?,?,?,'COMPLETED',?,0,0,?,?,?,?)",key,approved.approvalHash(),result.receiptNo(),db.encode(result),at,at,operator,operator);
                return result;
            });
        });
    }
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
