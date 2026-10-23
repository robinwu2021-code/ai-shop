package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only audit. Identity and store authority must be resolved by Core before entering this Pay snapshot. */
@Service
public class StoredValueIssuerMigrationService {
    public record StoreMapping(String storeNo,String entityNo,String issuerNo) { }
    public record IdentityEvidence(String customerNo,String userNo) { }
    public record Batch(String tenantNo,String actorNo,List<StoreMapping> stores,List<IdentityEvidence> identities) {
        public Batch { stores=List.copyOf(stores); identities=List.copyOf(identities); }
    }
    public record Issue(String code,String accountNo) { }
    public record AccountMapping(String accountNo,String issuerNo,String customerNo,String currency,
            long principalMinor,long bonusMinor,long heldPrincipalMinor,long heldBonusMinor,long accountSequence) { }
    public record Report(boolean ready,String fingerprint,List<AccountMapping> accounts,List<Issue> issues) {
        public Report { accounts=List.copyOf(accounts); issues=List.copyOf(issues); }
    }
    private final IssuerRepository db;
    public StoredValueIssuerMigrationService(IssuerRepository db) { this.db=db; }

    public Report audit(Batch batch) {
        validate(batch);
        return db.write(()->inspect(batch));
    }
    private Report inspect(Batch batch) {
        var mappings=new HashMap<String,StoreMapping>();
        var configs=new HashMap<String,Map<String,Object>>();
        for(var store:batch.stores()) {
            if(mappings.put(store.storeNo(),store)!=null) throw BizException.of(ErrorCode.BAD_REQUEST);
            var config=db.one("SELECT i.issuer_no,v.currency FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_no=i.issuer_no AND v.issuer_version_no=i.current_issuer_version_no WHERE i.tenant_no=? AND i.issuer_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0 AND v.settlement_entity_no=?",
                    batch.tenantNo(),store.issuerNo(),store.storeNo(),store.entityNo());
            if(config==null) throw BizException.of(ErrorCode.CONFLICT);
            configs.put(store.storeNo(),config);
        }
        var issues=new ArrayList<Issue>();
        var output=new ArrayList<AccountMapping>();
        var snapshots=new ArrayList<Object>();
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
                var lots=db.rows("SELECT * FROM sv_lot WHERE tenant_no=? AND account_no=? AND deleted=0 ORDER BY lot_no",batch.tenantNo(),no);
                var vouchers=db.rows("SELECT * FROM sv_voucher WHERE tenant_no=? AND account_no=? AND deleted=0 ORDER BY account_seq",batch.tenantNo(),no);
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
            var credited=db.rows("SELECT recharge_no,version FROM sv_recharge WHERE tenant_no=? AND entity_no=? AND store_no=? AND deleted=0 AND status='CREDITED' ORDER BY recharge_no",batch.tenantNo(),store.entityNo(),store.storeNo());
            snapshots.add(credited);
            for(var recharge:credited) {
                var owners=db.rows("SELECT l.lot_no,l.account_no FROM sv_lot l JOIN sv_account a ON a.tenant_no=l.tenant_no AND a.account_no=l.account_no WHERE l.tenant_no=? AND l.recharge_no=? AND l.deleted=0 AND a.deleted=0 AND a.entity_no=? AND a.store_no=?",batch.tenantNo(),text(recharge,"recharge_no"),store.entityNo(),store.storeNo());
                if(owners.size()!=1) issues.add(new Issue("ORPHAN_CREDITED_RECHARGE",null));
            }
        }
        return new Report(issues.isEmpty(),hash(db.encode(List.of(batch,snapshots))),output,issues);
    }
    private void verifyJournalAndHolds(String tenant,StoreMapping store,String account,List<Map<String,Object>> lots,
            List<Map<String,Object>> vouchers,List<Issue> issues,List<Object> snapshots) {
        var lotNos=lots.stream().map(l->text(l,"lot_no")).collect(java.util.stream.Collectors.toSet());
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
