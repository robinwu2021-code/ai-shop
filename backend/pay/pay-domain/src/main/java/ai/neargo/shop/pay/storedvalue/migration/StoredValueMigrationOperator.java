package ai.neargo.shop.pay.storedvalue.migration;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import org.springframework.stereotype.Service;
@Service
public class StoredValueMigrationOperator {
 private final StoredValueAccountKeyCutover keys;private final StoredValueAccountDimensionGuard dimensions;private final StoredValueMigrationResumeService resume;private final StoredValueSchemaAuthorization authorization;private final IssuerRepository db;
 public StoredValueMigrationOperator(StoredValueAccountKeyCutover keys,StoredValueAccountDimensionGuard dimensions,StoredValueMigrationResumeService resume,StoredValueSchemaAuthorization authorization,IssuerRepository db){this.keys=keys;this.dimensions=dimensions;this.resume=resume;this.authorization=authorization;this.db=db;}
 public ExecutionCommand execute(String action,String operator,String approval,Proof proof,String expectedApprovalHash){
  if(expectedApprovalHash==null||!expectedApprovalHash.matches("[a-f0-9]{64}"))throw BizException.of(ErrorCode.BAD_REQUEST);
  return switch(action){
   case "key-step"->{var r=keys.step(operator,approval,proof,expectedApprovalHash);yield new ExecutionCommand(r.receiptNo(),approval,r.action(),r.state(),0,0);}
   case "guard-dimensions"->new ExecutionCommand(dimensions.install(operator,approval,proof,expectedApprovalHash),approval,"GUARD_DIMENSIONS","DIMENSIONS_GUARDED",0,0);
   case "resume"->{var r=resume.resume(operator,approval,proof,expectedApprovalHash);yield new ExecutionCommand(r.receiptNo(),approval,"RESUME",r.state(),r.issuerCount(),r.accountCount());}
   default->throw BizException.of(ErrorCode.BAD_REQUEST);
  };
 }

 public OperatorEvidence sources(String operator,String approval,Proof proof){
  var allowed=authorization.authorizeApproval(operator,approval,proof);
  var result=new java.util.ArrayList<ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority>();
  for(var ref:allowed.approval().receipts()){
   var row=db.one("SELECT response_snapshot FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind='SV_MIGRATION_MANIFEST' AND resource_no=? AND result_status='COMPLETED' AND deleted=0",ref.tenantNo(),ref.manifestNo());
   if(row==null)throw BizException.of(ErrorCode.CONFLICT);
   var saved=db.decode(text(row,"response_snapshot"),StoredValueMigrationManifestService.SavedManifest.class);
   if(!ref.manifestNo().equals(saved.manifest().manifestNo())||!ref.tenantNo().equals(saved.evidence().batch().tenantNo()))throw BizException.of(ErrorCode.CONFLICT);
   result.addAll(saved.evidence().authorities());if(result.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
  }
  return new OperatorEvidence(allowed.approvalHash(),result);
 }
 public ExecutionCommand command(String operator,String approval,String no,Proof proof){
  var allowed=authorization.authorizeApproval(operator,approval,proof);
  var row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND resource_no=? AND operation_kind IN ('SV_SCHEMA_KEY_STEP','SV_SCHEMA_DIMENSION','SV_SCHEMA_RESUME') AND deleted=0",no);
  if(row==null||!allowed.approvalHash().equals(text(row,"payload_hash")))throw BizException.of(ErrorCode.NOT_FOUND);
  return new ExecutionCommand(no,approval,text(row,"operation_kind"),text(row,"result_status"),0,0);
 }
}
