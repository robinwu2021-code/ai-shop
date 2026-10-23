package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.MaintenanceResult;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;

/** Server-file global operator approval, not store-owner authority. No public HTTP entry or DDL here. */
@Service
public class StoredValueSchemaAuthorization {
    public record MappingReceipt(String tenantNo,String manifestNo,String pauseReceiptNo,String mappingReceiptNo){}
    public record Approval(String approvalNo,String operatorNo,String ledgerFingerprint,String inventoryHash,Instant expiresAt,boolean sealed,List<MappingReceipt> receipts){
        public Approval{receipts=List.copyOf(receipts);}
    }
    public record Authorized(Approval approval,String approvalHash,long issuerCount,long accountCount){}
    private final IssuerRepository db;
    private final StoredValueMigrationManifestService manifests;
    private final StoredValueMigrationExecutionService execution;
    private final boolean enabled;
    private final Supplier<Approval> approvals;
    private final Clock clock;
    @Autowired
    public StoredValueSchemaAuthorization(IssuerRepository db,StoredValueMigrationManifestService manifests,StoredValueMigrationExecutionService execution,
            @Value("${shop.pay.stored-value.migration.key-cutover-enabled:false}") boolean enabled,
            @Value("${shop.pay.stored-value.migration.key-cutover-approval-file:}") String file){
        this(db,manifests,execution,enabled,()->load(db,file),Clock.systemUTC());
    }
    StoredValueSchemaAuthorization(IssuerRepository db,StoredValueMigrationManifestService manifests,StoredValueMigrationExecutionService execution,boolean enabled,Supplier<Approval> approvals,Clock clock){
        this.db=db;this.manifests=manifests;this.execution=execution;this.enabled=enabled;this.approvals=approvals;this.clock=clock;
    }
    static Approval load(IssuerRepository db,String file){
        if(file==null||file.isBlank())return null;
        try{
            var path=Path.of(file);
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                var bytes=input.readNBytes(65537);
                if(bytes.length<=65536)return db.decode(new String(bytes,StandardCharsets.UTF_8),Approval.class);
            }
        }catch(java.io.IOException|RuntimeException invalid){ /* Never expose a server path or parser contents. */ }
        return null;
    }
    public Authorized authorizeApproval(String operator,String approvalNo,Proof proof){
        if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        ContractChecks.id(operator);ContractChecks.id(approvalNo);
        var approval=approvals.get();
        if(approval==null||!approval.sealed()||!operator.equals(approval.operatorNo())||!approvalNo.equals(approval.approvalNo())
                ||approval.expiresAt()==null||!approval.expiresAt().isAfter(clock.instant())||approval.receipts().isEmpty()||approval.receipts().size()>1000)conflict();
        String inventory=execution.guard(proof,true);
        if(!inventory.equals(approval.inventoryHash())||!proof.inventory().ledgerFingerprint().equals(approval.ledgerFingerprint()))conflict();
        return new Authorized(approval,hash(db.encode(List.of(approval.approvalNo(),approval.operatorNo(),approval.ledgerFingerprint(),approval.inventoryHash(),approval.receipts()))),0,0);
    }
    public Authorized authorize(String operator,String approvalNo,Proof proof){
        var allowed=authorizeApproval(operator,approvalNo,proof);
        return db.write(()->coverage(allowed.approval()));
    }
    private Authorized coverage(Approval approval){
        var issuers=new HashSet<List<String>>();var accounts=new HashMap<List<String>,StoredValueIssuerMigrationService.AccountMapping>();
        var stores=new HashMap<List<String>,StoredValueIssuerMigrationService.StoreMapping>();var receipts=new HashSet<List<String>>();
        for(var ref:approval.receipts()){
            ContractChecks.id(ref.tenantNo());ContractChecks.id(ref.manifestNo());ContractChecks.id(ref.pauseReceiptNo());ContractChecks.id(ref.mappingReceiptNo());
            if(!receipts.add(List.of(ref.tenantNo(),ref.mappingReceiptNo())))conflict();
            var manifestRow=completed(ref.tenantNo(),"SV_MIGRATION_MANIFEST",ref.manifestNo());
            var saved=db.decode(text(manifestRow,"response_snapshot"),StoredValueMigrationManifestService.SavedManifest.class);
            var evidence=saved.evidence().canonical();
            if(!ref.tenantNo().equals(evidence.batch().tenantNo()))conflict();
            var manifest=manifests.find(evidence,ref.manifestNo());evidence.verifyPayScope(db,false);
            manifests.verifyMappedFacts(evidence,saved.report().accounts());
            var pause=completed(ref.tenantNo(),"SV_MAINTENANCE_PAUSE",ref.pauseReceiptNo());
            if(!hash(db.encode(List.of(ref.manifestNo(),evidence,approval.inventoryHash()))).equals(text(pause,"payload_hash")))conflict();
            verifyResult(pause,ref.pauseReceiptNo(),ref.manifestNo(),approval.inventoryHash(),"WRITE_FENCED",evidence.batch().stores().size(),0);
            var mapped=completed(ref.tenantNo(),"SV_MAINTENANCE_APPLY",ref.mappingReceiptNo());
            if(!hash(db.encode(List.of(ref.manifestNo(),ref.pauseReceiptNo(),evidence,approval.inventoryHash()))).equals(text(mapped,"payload_hash")))conflict();
            verifyResult(mapped,ref.mappingReceiptNo(),ref.manifestNo(),approval.inventoryHash(),"METADATA_MAPPED",evidence.batch().stores().size(),manifest.accountCount());
            for(var store:evidence.batch().stores()){
                var key=List.of(ref.tenantNo(),store.issuerNo());
                if(!issuers.add(key))conflict();stores.put(key,store);
            }
            for(var account:saved.report().accounts()){
                if(!issuers.contains(List.of(ref.tenantNo(),account.issuerNo()))||accounts.putIfAbsent(List.of(ref.tenantNo(),account.accountNo()),account)!=null)conflict();
            }
            if(accounts.size()>10000||issuers.size()>1000)conflict();
        }
        var actualIssuers=db.rows("SELECT tenant_no,issuer_no,write_mode FROM sv_issuer WHERE deleted=0 AND scope_type='STORE' LIMIT 1001");
        if(actualIssuers.size()!=issuers.size())conflict();
        var foundIssuers=new HashSet<List<String>>();
        for(var row:actualIssuers)if(!"FENCED".equals(text(row,"write_mode"))||!foundIssuers.add(List.of(text(row,"tenant_no"),text(row,"issuer_no")))||!issuers.contains(List.of(text(row,"tenant_no"),text(row,"issuer_no"))))conflict();
        var drafts=db.rows("SELECT tenant_no,issuer_no,scope_type,status FROM sv_issuer WHERE deleted=0 AND scope_type<>'STORE' LIMIT 1001");
        if(drafts.size()>1000)conflict();
        for(var draft:drafts){
            if(!"BRAND".equals(text(draft,"scope_type"))||!"DRAFT".equals(text(draft,"status")))conflict();
            if(number(db.one("SELECT COUNT(*) AS n FROM sv_release WHERE tenant_no=? AND issuer_no=? AND deleted=0",text(draft,"tenant_no"),text(draft,"issuer_no")),"n")!=0)conflict();
            if(number(db.one("SELECT COUNT(*) AS n FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND deleted=0 AND (can_issue=1 OR can_redeem=1 OR status='ACTIVE')",text(draft,"tenant_no"),text(draft,"issuer_no")),"n")!=0)conflict();
        }
        var actualAccounts=db.rows("SELECT * FROM sv_account LIMIT 10001");
        if(actualAccounts.size()!=accounts.size())conflict();
        var foundAccounts=new HashSet<List<String>>();
        for(var row:actualAccounts){
            var key=List.of(text(row,"tenant_no"),text(row,"account_no"));var expected=accounts.get(key);
            if(expected==null||!foundAccounts.add(key)||number(row,"deleted")!=0)conflict();
            var store=stores.get(List.of(key.getFirst(),expected.issuerNo()));
            if(!expected.issuerNo().equals(text(row,"issuer_no"))||!expected.customerNo().equals(text(row,"customer_no"))
                    ||!expected.currency().equals(text(row,"currency"))||!store.storeNo().equals(text(row,"store_no"))||!store.entityNo().equals(text(row,"entity_no"))
                    ||expected.principalMinor()!=number(row,"principal_minor")||expected.bonusMinor()!=number(row,"bonus_minor")
                    ||expected.heldPrincipalMinor()!=number(row,"held_principal_minor")||expected.heldBonusMinor()!=number(row,"held_bonus_minor")
                    ||expected.accountSequence()!=number(row,"account_seq"))conflict();
        }
        return new Authorized(approval,hash(db.encode(List.of(approval.approvalNo(),approval.operatorNo(),approval.ledgerFingerprint(),approval.inventoryHash(),approval.receipts()))),issuers.size(),accounts.size());
    }
    private Map<String,Object> completed(String tenant,String kind,String resource){
        var row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND resource_no=? AND result_status='COMPLETED' AND deleted=0",tenant,kind,resource);
        if(row==null)conflict();return row;
    }
    private void verifyResult(Map<String,Object> row,String receipt,String manifest,String inventory,String state,long issuers,long accounts){
        var result=db.decode(text(row,"response_snapshot"),MaintenanceResult.class);
        if(!receipt.equals(result.receiptNo())||!manifest.equals(result.manifestNo())||!inventory.equals(result.inventoryHash())
                ||!state.equals(result.state())||issuers!=result.issuerCount()||accounts!=result.accountCount())conflict();
    }
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
