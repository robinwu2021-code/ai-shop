package ai.neargo.shop.pay.storedvalue.migration.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueIssuerMigrationService;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;



/** Audit and metadata-only mapping. Core resolves identity/store authority before Pay locks; mapping never activates writing. */
@Service("storedValueIssuerMigrationService")
public class StoredValueIssuerMigrationServiceImpl implements StoredValueIssuerMigrationService {
    private final IssuerRepository db;
    public StoredValueIssuerMigrationServiceImpl(IssuerRepository db) { this.db=db; }

    public Report audit(Batch batch) {
        validate(batch);
        return db.write(()->inspect(batch));
    }
    /** Add scope references only. This never activates issuance or removes legacy indexes. */
    public MappingResult map(Batch batch,String expectedFingerprint,String commandKey) {
        return map(batch,expectedFingerprint,commandKey,null);
    }
    /** Final mapping uses server-resolved evidence and checks FENCED under the same issuer locks. */
    public MappingResult mapFenced(StoredValueMigrationManifestService.Evidence evidence,String expectedFingerprint,String commandKey) {
        var canonical=evidence.canonical();
        return map(canonical.batch(),expectedFingerprint,commandKey,canonical);
    }
    private MappingResult map(Batch batch,String expectedFingerprint,String commandKey,StoredValueMigrationManifestService.Evidence evidence) {
        validate(batch);
        String operation=evidence==null?"SV_ISSUER_MAP":"SV_ISSUER_FENCED_MAP";
        if(expectedFingerprint==null||!expectedFingerprint.matches("[0-9a-f]{64}")||commandKey==null||commandKey.isBlank()||commandKey.length()>64)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String payloadHash=hash(db.encode(List.of(evidence==null?batch:evidence,expectedFingerprint)));
        return db.write(()->{
            var command=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND request_key=? AND deleted=0 FOR UPDATE",batch.tenantNo(),operation,commandKey);
            if(command!=null) {
                if(!payloadHash.equals(text(command,"payload_hash"))||!"COMPLETED".equals(text(command,"result_status"))) throw BizException.of(ErrorCode.CONFLICT);
                return db.decode(text(command,"response_snapshot"),MappingResult.class);
            }
            var now=Timestamp.from(Instant.now());
            db.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,'PENDING',0,0,?,?,?,?)",
                    batch.tenantNo(),operation,commandKey,payloadHash,BizKey.next(BizKey.STORED_VALUE_MIGRATION),now,now,batch.actorNo(),batch.actorNo());
            for(var store:batch.stores().stream().sorted(Comparator.comparing(StoreMapping::issuerNo)).toList()) {
                var issuer=db.one("SELECT issuer_no,write_mode FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",batch.tenantNo(),store.issuerNo(),store.storeNo());
                if(issuer==null||!(evidence==null?Set.of("LEGACY","LEGACY_MAPPED","FENCED"):Set.of("FENCED")).contains(text(issuer,"write_mode"))) throw BizException.of(ErrorCode.CONFLICT);
                db.rows("SELECT account_no FROM sv_account WHERE tenant_no=? AND (store_no=? OR (store_no IS NULL AND entity_no=?)) AND deleted=0 ORDER BY account_no FOR UPDATE",batch.tenantNo(),store.storeNo(),store.entityNo());
            }
            if(evidence!=null)evidence.verifyPayScope(db,true);
            var report=inspect(batch);
            if(!report.ready()||!expectedFingerprint.equals(report.fingerprint())) throw BizException.of(ErrorCode.CONFLICT);
            for(var store:batch.stores()) {
                var config=db.one("SELECT current_issuer_version_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=?",batch.tenantNo(),store.issuerNo());
                String version=text(config,"current_issuer_version_no");
                var accounts=report.accounts().stream().filter(a->store.issuerNo().equals(a.issuerNo())).toList();
                BigInteger outstanding=accounts.stream().map(a->BigInteger.valueOf(a.principalMinor()).add(BigInteger.valueOf(a.bonusMinor()))).reduce(BigInteger.ZERO,BigInteger::add);
                if(outstanding.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0) throw BizException.of(ErrorCode.CONFLICT);
                for(var account:accounts) {
                    db.update("UPDATE sv_account SET issuer_no=COALESCE(issuer_no,?),issuer_version_no=COALESCE(issuer_version_no,?),customer_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",store.issuerNo(),version,account.customerNo(),now,batch.actorNo(),batch.tenantNo(),account.accountNo());
                    db.update("UPDATE sv_lot SET issuer_no=COALESCE(issuer_no,?),issuer_version_no=COALESCE(issuer_version_no,?),collection_entity_no=COALESCE(collection_entity_no,?),settlement_entity_no=COALESCE(settlement_entity_no,?),updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",store.issuerNo(),version,store.entityNo(),store.entityNo(),now,batch.actorNo(),batch.tenantNo(),account.accountNo());
                    db.update("UPDATE sv_voucher SET issuer_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",store.issuerNo(),now,batch.actorNo(),batch.tenantNo(),account.accountNo());
                    for(var lot:db.rows("SELECT lot_no FROM sv_lot WHERE tenant_no=? AND account_no=? AND deleted=0",batch.tenantNo(),account.accountNo())) {
                        for(var hold:db.rows("SELECT operation_no FROM sv_hold_allocation WHERE tenant_no=? AND lot_no=? AND deleted=0",batch.tenantNo(),text(lot,"lot_no")))
                            db.update("UPDATE sv_hold_allocation SET issuer_no=?,allocation_no=COALESCE(allocation_no,?),updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND lot_no=? AND operation_no=?",store.issuerNo(),BizKey.next(BizKey.STORED_VALUE_HOLD_ALLOCATION),now,batch.actorNo(),batch.tenantNo(),text(lot,"lot_no"),text(hold,"operation_no"));
                    }
                    index(batch,store,account,now);
                }
                db.update("UPDATE sv_recharge SET issuer_no=COALESCE(issuer_no,?),issuer_version_no=COALESCE(issuer_version_no,?),collection_entity_no=COALESCE(collection_entity_no,entity_no),settlement_entity_no=COALESCE(settlement_entity_no,entity_no),updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND entity_no=? AND store_no=?",store.issuerNo(),version,now,batch.actorNo(),batch.tenantNo(),store.entityNo(),store.storeNo());
                db.update("UPDATE sv_statement SET issuer_no=?,scope_type='STORE',scope_no=?,operating_entity_no=entity_no,settlement_entity_no=entity_no,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND entity_no=? AND store_no=?",store.issuerNo(),store.storeNo(),now,batch.actorNo(),batch.tenantNo(),store.entityNo(),store.storeNo());
                db.update("UPDATE sv_recharge_plan SET issuer_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND entity_no=? AND store_no=?",store.issuerNo(),now,batch.actorNo(),batch.tenantNo(),store.entityNo(),store.storeNo());
                var quota=db.one("SELECT quota_no,reserved_minor FROM sv_issue_quota WHERE tenant_no=? AND store_no=? AND currency='CNY' FOR UPDATE",batch.tenantNo(),store.storeNo());
                if(quota==null) {
                    var row=base(batch,now);row.put("quota_no",BizKey.next(BizKey.STORED_VALUE_QUOTA));row.put("store_no",store.storeNo());row.put("currency","CNY");row.put("outstanding_minor",outstanding.longValueExact());row.put("reserved_minor",0);
                    db.insert("sv_issue_quota",row);
                } else {
                    if(number(quota,"reserved_minor")!=0) throw BizException.of(ErrorCode.CONFLICT);
                    db.update("UPDATE sv_issue_quota SET outstanding_minor=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND quota_no=?",outstanding.longValueExact(),now,batch.actorNo(),batch.tenantNo(),text(quota,"quota_no"));
                }
                db.update("UPDATE sv_issuer SET write_mode=CASE WHEN write_mode='FENCED' THEN 'FENCED' ELSE 'LEGACY_MAPPED' END,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=?",now,batch.actorNo(),batch.tenantNo(),store.issuerNo());
            }
            var result=new MappingResult("MAPPED",report.accounts().size(),expectedFingerprint);
            db.update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND operation_kind=? AND request_key=?",db.encode(result),now,batch.actorNo(),batch.tenantNo(),operation,commandKey);
            return result;
        });
    }
    private void index(Batch batch,StoreMapping store,AccountMapping account,Timestamp now) {
        var prior=db.one("SELECT issuer_no,customer_no FROM sv_customer_issuer_index WHERE tenant_no=? AND account_no=? FOR UPDATE",batch.tenantNo(),account.accountNo());
        if(prior!=null&&(!store.issuerNo().equals(text(prior,"issuer_no"))||!account.customerNo().equals(text(prior,"customer_no")))) throw BizException.of(ErrorCode.CONFLICT);
        var dates=db.one("SELECT COUNT(*) AS count,MAX(credited_at) AS last_recharge_at FROM sv_lot WHERE tenant_no=? AND account_no=? AND deleted=0",batch.tenantNo(),account.accountNo());
        var last=db.one("SELECT MAX(occurred_at) AS last_activity_at FROM sv_statement WHERE tenant_no=? AND account_no=? AND deleted=0",batch.tenantNo(),account.accountNo());
        Object rechargeAt=dates.get("last_recharge_at"),activityAt=last.get("last_activity_at");
        if(activityAt==null) activityAt=rechargeAt;
        int hasRecharge=number(dates,"count")>0?1:0;
        if(prior==null) {
            var row=base(batch,now);row.put("customer_no",account.customerNo());row.put("issuer_no",store.issuerNo());row.put("account_no",account.accountNo());row.put("currency",account.currency());
            row.put("has_recharge",hasRecharge);row.put("last_recharge_at",rechargeAt);row.put("last_activity_at",activityAt);row.put("last_occurrence_store_no",hasRecharge==1?store.storeNo():null);
            db.insert("sv_customer_issuer_index",row);
        } else db.update("UPDATE sv_customer_issuer_index SET has_recharge=?,last_recharge_at=?,last_activity_at=?,last_occurrence_store_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",hasRecharge,rechargeAt,activityAt,hasRecharge==1?store.storeNo():null,now,batch.actorNo(),batch.tenantNo(),account.accountNo());
    }
    private Map<String,Object> base(Batch batch,Timestamp now) {
        var row=new LinkedHashMap<String,Object>();row.put("tenant_no",batch.tenantNo());row.put("version",0);row.put("deleted",0);
        row.put("created_at",now);row.put("updated_at",now);row.put("created_by",batch.actorNo());row.put("updated_by",batch.actorNo());return row;
    }
    private Report inspect(Batch batch) {
        var mappings=new HashMap<String,StoreMapping>();
        var configs=new TreeMap<String,Map<String,Object>>();
        for(var store:batch.stores()) {
            if(mappings.put(store.storeNo(),store)!=null) throw BizException.of(ErrorCode.BAD_REQUEST);
            var config=db.one("SELECT i.issuer_no,v.currency,v.issuer_version_no FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_no=i.issuer_no AND v.issuer_version_no=i.current_issuer_version_no WHERE i.tenant_no=? AND i.issuer_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0 AND v.settlement_entity_no=?",
                    batch.tenantNo(),store.issuerNo(),store.storeNo(),store.entityNo());
            if(config==null) throw BizException.of(ErrorCode.CONFLICT);
            configs.put(store.storeNo(),config);
        }
        var issues=new ArrayList<Issue>();
        var output=new ArrayList<AccountMapping>();
        var snapshots=new ArrayList<Object>();
        snapshots.add(configs);
        var seenAccounts=new HashSet<String>();
        var walletKeys=new HashSet<String>();
        for(var store:batch.stores()) {
            var rows=db.rows("SELECT * FROM sv_account WHERE tenant_no=? AND (store_no=? OR (store_no IS NULL AND entity_no=?)) AND deleted=0 ORDER BY account_no",
                    batch.tenantNo(),store.storeNo(),store.entityNo());
            snapshots.add(rows);
            for(var row:rows) {
                String no=text(row,"account_no");
                if(!seenAccounts.add(no)) continue;
                String source=text(row,"store_no");
                if(source==null||source.isBlank()) { issues.add(new Issue("STORE_PROVENANCE_MISSING",no));continue; }
                if(!store.entityNo().equals(text(row,"entity_no"))||!store.storeNo().equals(source)) {
                    issues.add(new Issue("STORE_ENTITY_MISMATCH",no));continue;
                }
                String currency=text(row,"currency");
                if(!currency.equals(text(configs.get(source),"currency"))) issues.add(new Issue("CURRENCY_MISMATCH",no));
                String customer=canonical(batch,row);
                if(customer==null) { issues.add(new Issue("IDENTITY_UNRESOLVED",no));continue; }
                String walletKey=store.issuerNo()+"|"+customer+"|"+currency;
                if(!walletKeys.add(walletKey)) issues.add(new Issue("DUPLICATE_ISSUER_CUSTOMER",no));
                existingIssuer(batch.tenantNo(),store,row,no,issues);
                var lots=db.rows("SELECT * FROM sv_lot WHERE tenant_no=? AND account_no=? AND deleted=0 ORDER BY lot_no",batch.tenantNo(),no);
                var vouchers=db.rows("SELECT * FROM sv_voucher WHERE tenant_no=? AND account_no=? AND deleted=0 ORDER BY account_seq",batch.tenantNo(),no);
                for(var lot:lots) existingIssuer(batch.tenantNo(),store,lot,no,issues);
                snapshots.add(lots);snapshots.add(vouchers);
                verifyJournalAndHolds(batch.tenantNo(),store,no,lots,vouchers,issues,snapshots);
                String[] amounts={"principal_minor","bonus_minor","held_principal_minor","held_bonus_minor"};
                for(String amount:amounts) {
                    BigInteger total=lots.stream().map(l->BigInteger.valueOf(number(l,amount))).reduce(BigInteger.ZERO,BigInteger::add);
                    if(!total.equals(BigInteger.valueOf(number(row,amount)))) issues.add(new Issue("LOT_BALANCE_MISMATCH",no));
                }
                if(number(row,"principal_minor")<number(row,"held_principal_minor")||number(row,"bonus_minor")<number(row,"held_bonus_minor")
                        || Arrays.stream(amounts).anyMatch(a->number(row,a)<0)) issues.add(new Issue("INVALID_ACCOUNT_AMOUNT",no));
                for(var lot:lots) if(number(lot,"principal_minor")<number(lot,"held_principal_minor")||number(lot,"bonus_minor")<number(lot,"held_bonus_minor")
                        ||Arrays.stream(amounts).anyMatch(a->number(lot,a)<0)) issues.add(new Issue("INVALID_LOT_AMOUNT",no));
                long sequence=number(row,"account_seq");
                boolean sequenceValid=sequence==vouchers.size();
                for(int i=0;i<vouchers.size();i++) if(number(vouchers.get(i),"account_seq")!=i+1) sequenceValid=false;
                if(!sequenceValid) issues.add(new Issue("VOUCHER_SEQUENCE_MISMATCH",no));
                BigInteger runningPrincipal=BigInteger.ZERO,runningBonus=BigInteger.ZERO;
                for(var voucher:vouchers) {
                    runningPrincipal=runningPrincipal.add(BigInteger.valueOf(number(voucher,"principal_delta")));
                    runningBonus=runningBonus.add(BigInteger.valueOf(number(voucher,"bonus_delta")));
                    if(runningPrincipal.signum()<0||runningBonus.signum()<0
                            ||!runningPrincipal.equals(BigInteger.valueOf(number(voucher,"after_principal_minor")))
                            ||!runningBonus.equals(BigInteger.valueOf(number(voucher,"after_bonus_minor"))))
                        issues.add(new Issue("VOUCHER_RUNNING_BALANCE_MISMATCH",no));
                }
                if(!vouchers.isEmpty()&&lots.isEmpty()) issues.add(new Issue("HISTORICAL_LOT_MISSING",no));
                if(!vouchers.isEmpty()) {
                    var last=vouchers.getLast();
                    if(number(last,"after_principal_minor")!=number(row,"principal_minor")||number(last,"after_bonus_minor")!=number(row,"bonus_minor"))
                        issues.add(new Issue("VOUCHER_BALANCE_MISMATCH",no));
                } else if(number(row,"principal_minor")!=0||number(row,"bonus_minor")!=0) issues.add(new Issue("VOUCHER_BALANCE_MISMATCH",no));
                output.add(new AccountMapping(no,store.issuerNo(),customer,currency,number(row,"principal_minor"),number(row,"bonus_minor"),
                        number(row,"held_principal_minor"),number(row,"held_bonus_minor"),sequence));
            }
            var pending=db.rows("SELECT recharge_no,status,version FROM sv_recharge WHERE tenant_no=? AND entity_no=? AND store_no=? AND deleted=0 AND status IN ('WAIT_COLLECTION','SUBMITTED','RECEIVED') ORDER BY recharge_no",
                    batch.tenantNo(),store.entityNo(),store.storeNo());
            snapshots.add(pending);
            for(var original:pending) issues.add(new Issue("RECHARGE_IN_FLIGHT",null));
            var credited=db.rows("SELECT * FROM sv_recharge WHERE tenant_no=? AND entity_no=? AND store_no=? AND deleted=0 AND status='CREDITED' ORDER BY recharge_no",batch.tenantNo(),store.entityNo(),store.storeNo());
            snapshots.add(credited);
            for(var recharge:credited) {
                existingIssuer(batch.tenantNo(),store,recharge,null,issues);
                var owners=db.rows("SELECT l.lot_no,l.account_no FROM sv_lot l JOIN sv_account a ON a.tenant_no=l.tenant_no AND a.account_no=l.account_no WHERE l.tenant_no=? AND l.recharge_no=? AND l.deleted=0 AND a.deleted=0 AND a.entity_no=? AND a.store_no=?",batch.tenantNo(),text(recharge,"recharge_no"),store.entityNo(),store.storeNo());
                if(owners.size()!=1) issues.add(new Issue("ORPHAN_CREDITED_RECHARGE",null));
            }
        }
        return new Report(issues.isEmpty(),hash(db.encode(List.of(batch,snapshots))),output,issues);
    }
    private void existingIssuer(String tenant,StoreMapping store,Map<String,Object> row,String account,List<Issue> issues) {
        String issuer=text(row,"issuer_no"),version=text(row,"issuer_version_no");
        if(issuer!=null&&!store.issuerNo().equals(issuer)) issues.add(new Issue("ISSUER_SCOPE_CONFLICT",account));
        if(version!=null&&db.one("SELECT issuer_version_no FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND settlement_entity_no=? AND currency='CNY' AND deleted=0",tenant,store.issuerNo(),version,store.entityNo())==null)
            issues.add(new Issue("ISSUER_VERSION_CONFLICT",account));
        for(String key:List.of("collection_entity_no","settlement_entity_no"))
            if(text(row,key)!=null&&!store.entityNo().equals(text(row,key))) issues.add(new Issue("COLLECTION_SCOPE_CONFLICT",account));
    }
    private void verifyJournalAndHolds(String tenant,StoreMapping store,String account,List<Map<String,Object>> lots,
            List<Map<String,Object>> vouchers,List<Issue> issues,List<Object> snapshots) {
        var lotNos=lots.stream().map(l->text(l,"lot_no")).collect(Collectors.toSet());
        for(var voucher:vouchers) {
            String no=text(voucher,"voucher_no");
            var entries=db.rows("SELECT * FROM sv_entry WHERE tenant_no=? AND voucher_no=? AND deleted=0 ORDER BY lot_no",tenant,no);
            var lines=db.rows("SELECT * FROM sv_posting_line WHERE tenant_no=? AND voucher_no=? AND deleted=0 ORDER BY line_no",tenant,no);
            snapshots.add(entries);snapshots.add(lines);
            for(String amount:List.of("principal_delta","bonus_delta"))
                if(!sum(entries,amount).equals(BigInteger.valueOf(number(voucher,amount)))) issues.add(new Issue("VOUCHER_ENTRY_MISMATCH",account));
            if(entries.stream().anyMatch(e->!lotNos.contains(text(e,"lot_no")))) issues.add(new Issue("ENTRY_LOT_OWNERSHIP_MISMATCH",account));
            var debit=new HashMap<String,BigInteger>();var credit=new HashMap<String,BigInteger>();
            boolean invalid=lines.isEmpty();
            for(var line:lines) {
                String side=text(line,"side"),pool=text(line,"pool");long amount=number(line,"amount_minor");
                if(amount<=0||!Set.of("DEBIT","CREDIT").contains(side)||!Set.of("PRINCIPAL","BONUS").contains(pool)) { invalid=true;continue; }
                var totals="DEBIT".equals(side)?debit:credit;
                totals.merge(pool,BigInteger.valueOf(amount),BigInteger::add);
            }
            if(invalid||!debit.equals(credit)) issues.add(new Issue("JOURNAL_UNBALANCED",account));
            var actualBooks=new HashMap<String,BigInteger>();
            for(var line:lines) actualBooks.merge(text(line,"pool")+"|"+text(line,"book")+"|"+text(line,"side"),BigInteger.valueOf(number(line,"amount_minor")),BigInteger::add);
            var expectedBooks=new HashMap<String,BigInteger>();
            for(String pool:List.of("PRINCIPAL","BONUS")) {
                long delta=number(voucher,"PRINCIPAL".equals(pool)?"principal_delta":"bonus_delta");
                if(delta==0) continue;
                var amount=BigInteger.valueOf(delta).abs();
                expectedBooks.put(pool+"|CUSTOMER_CLAIM|"+(delta>0?"DEBIT":"CREDIT"),amount);
                expectedBooks.put(pool+"|MERCHANT_OBLIGATION|"+(delta>0?"CREDIT":"DEBIT"),amount);
            }
            if(!actualBooks.equals(expectedBooks)) issues.add(new Issue("JOURNAL_BOOK_MISMATCH",account));
            for(String pool:List.of("PRINCIPAL","BONUS")) {
                String amount="PRINCIPAL".equals(pool)?"principal_delta":"bonus_delta";
                if(!debit.getOrDefault(pool,BigInteger.ZERO).equals(BigInteger.valueOf(number(voucher,amount)).abs()))
                    issues.add(new Issue("JOURNAL_VOUCHER_MISMATCH",account));
            }
        }
        for(var lot:lots) {
            String lotNo=text(lot,"lot_no");
            var entries=db.rows("SELECT e.* FROM sv_entry e JOIN sv_voucher v ON v.tenant_no=e.tenant_no AND v.voucher_no=e.voucher_no WHERE e.tenant_no=? AND e.lot_no=? AND e.deleted=0 AND v.deleted=0 AND v.account_no=? ORDER BY v.account_seq,e.voucher_no",tenant,lotNo,account);
            snapshots.add(entries);
            if(!sum(entries,"principal_delta").equals(BigInteger.valueOf(number(lot,"principal_minor")))
                    ||!sum(entries,"bonus_delta").equals(BigInteger.valueOf(number(lot,"bonus_minor")))) issues.add(new Issue("LOT_ENTRY_MISMATCH",account));
            var recharge=db.one("SELECT recharge_no FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND entity_no=? AND store_no=? AND status='CREDITED' AND deleted=0",tenant,text(lot,"recharge_no"),store.entityNo(),store.storeNo());
            if(recharge==null) issues.add(new Issue("LOT_RECHARGE_MISMATCH",account));
            var holds=db.rows("SELECT h.*,i.status AS intent_status,i.entity_no AS intent_entity,i.store_no AS intent_store FROM sv_hold_allocation h LEFT JOIN ent_use_intent i ON i.tenant_no=h.tenant_no AND i.operation_no=h.operation_no AND i.deleted=0 WHERE h.tenant_no=? AND h.lot_no=? AND h.deleted=0 ORDER BY h.operation_no",tenant,lotNo);
            snapshots.add(holds);
            for(var hold:holds) if(!Set.of("HELD","COMMITTED","RELEASED").contains(Objects.toString(text(hold,"intent_status"),""))
                    ||!store.entityNo().equals(text(hold,"intent_entity"))||!store.storeNo().equals(text(hold,"intent_store"))) issues.add(new Issue("HOLD_OWNER_OR_STATE_MISMATCH",account));
            var active=holds.stream().filter(h->"HELD".equals(text(h,"intent_status"))).toList();
            if(!sum(active,"principal_minor").equals(BigInteger.valueOf(number(lot,"held_principal_minor")))
                    ||!sum(active,"bonus_minor").equals(BigInteger.valueOf(number(lot,"held_bonus_minor")))) issues.add(new Issue("HOLD_EVIDENCE_MISMATCH",account));
        }
    }
    private static BigInteger sum(List<Map<String,Object>> rows,String amount) {
        return rows.stream().map(row->BigInteger.valueOf(number(row,amount))).reduce(BigInteger.ZERO,BigInteger::add);
    }
    private String canonical(Batch batch,Map<String,Object> row) {
        String customer=text(row,"customer_no"),user=text(row,"user_no");
        var candidates=batch.identities().stream().filter(i->Objects.equals(customer,i.customerNo())
                ||customer==null&&user!=null&&Objects.equals(user,i.userNo())
                ||Objects.equals(customer,user)&&user!=null&&Objects.equals(user,i.userNo())).toList();
        if(candidates.size()!=1) return null;
        var evidence=candidates.getFirst();
        if(user!=null&&!Objects.equals(user,evidence.userNo())) return null;
        if(evidence.userNo()!=null) {
            var links=db.rows("SELECT customer_no,user_no FROM sv_customer_identity WHERE tenant_no=? AND (customer_no=? OR user_no=?)",batch.tenantNo(),evidence.customerNo(),evidence.userNo());
            if(links.size()!=1||!evidence.customerNo().equals(text(links.getFirst(),"customer_no"))||!evidence.userNo().equals(text(links.getFirst(),"user_no"))) return null;
        }
        return evidence.customerNo();
    }
    private void validate(Batch batch) {
        if(batch.tenantNo()==null||batch.tenantNo().isBlank()||batch.actorNo()==null||batch.actorNo().isBlank()
                ||batch.stores().isEmpty()||batch.stores().size()>100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var seen=new HashSet<String>();
        for(var identity:batch.identities()) if(identity.customerNo()==null||identity.customerNo().isBlank()||!seen.add(identity.customerNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
