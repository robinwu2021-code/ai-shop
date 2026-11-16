package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformCollectionService.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Manual rail operations share stl_payment and its row lock with ordinary collection. No gateway IO. */
final class PlatformOfflineLedger {
    static final String UNMATCHED = "MALL_UNMATCHED";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final PlatformCollectionServiceImpl owner;

    PlatformOfflineLedger(JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json, PlatformCollectionServiceImpl owner) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.owner = owner;
    }
    private static void require(boolean b, String reason) { if (!b) throw new IllegalArgumentException(reason); }
    private static void text(String s, int max) { require(s != null && !s.isBlank() && s.length() <= max, "OFFLINE_REQUIRED_FIELD"); }
    static String hash(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    OfflineInstructions configure(OfflineProfile p) {
        require(p != null && p.expectedVersion() >= 0, "OFFLINE_PROFILE_INVALID");
        text(p.tenantNo(),32);text(p.profileNo(),64);text(p.merchantNo(),64);
        text(p.operatorNo(),64);text(p.reason(),1024);
        String method = p.collectionMethod() == null ? "BANK_TRANSFER" : p.collectionMethod();
        require(Set.of("BANK_TRANSFER", "SALES_COLLECTION").contains(method), "OFFLINE_METHOD_INVALID");
        text(p.paymentWindowVersion(),64);
        require(p.currency() != null && p.currency().matches("[A-Z]{3}")
                && p.paymentWindowMs() >= 1000 && p.paymentWindowMs() <= 90L*86400000, "OFFLINE_PROFILE_INVALID");
        String accountKey;
        Map<String,String> instructions;
        if ("SALES_COLLECTION".equals(method)) {
            text(p.contactInstructions(),1024);
            require(p.bankCode()==null && p.bankAccount()==null && p.accountName()==null, "OFFLINE_METHOD_MISMATCH");
            accountKey = hash("platform-sales-collection:v1",p.tenantNo(),p.merchantNo());
            instructions = Map.of("collectionMethod",method,"contactInstructions",p.contactInstructions());
        } else {
            text(p.bankCode(),32);text(p.bankAccount(),128);text(p.accountName(),128);
            String account = p.bankAccount().replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
            String bank = p.bankCode().trim().toUpperCase(Locale.ROOT);
            text(account,128);
            accountKey = hash("platform-bank-account:v1",p.tenantNo(),"BANK_TRANSFER",bank,account);
            instructions = Map.of("bankCode",p.bankCode(),"bankAccount",p.bankAccount(),"accountName",p.accountName());
        }
        var terms = new OfflineInstructions(p.profileNo(), Math.addExact(p.expectedVersion(),1), p.merchantNo(), p.currency(),
                accountKey,p.paymentWindowVersion(),p.paymentWindowMs(),instructions);
        return tx.execute(s -> {
            jdbc.update("INSERT INTO pay_platform_collection_head(tenant_no,profile_no,current_version,enabled) VALUES (?,?,0,0) ON DUPLICATE KEY UPDATE profile_no=profile_no",p.tenantNo(),p.profileNo());
            Long version = jdbc.queryForObject("SELECT current_version FROM pay_platform_collection_head WHERE tenant_no=? AND profile_no=? FOR UPDATE",Long.class,p.tenantNo(),p.profileNo());
            require(version != null && version == p.expectedVersion(),"OFFLINE_PROFILE_VERSION_CONFLICT");
            if (version > 0) {
                var prior = jdbc.queryForObject("SELECT instructions_json FROM pay_platform_collection_profile WHERE tenant_no=? AND profile_no=? AND profile_version=?",
                        (row,n)->json.readValue(row.getString(1),OfflineInstructions.class),p.tenantNo(),p.profileNo(),version);
                require(prior.collectionMethod().equals(method),"OFFLINE_METHOD_MISMATCH");
            }
            jdbc.update("INSERT INTO pay_platform_collection_profile(tenant_no,profile_no,profile_version,instructions_json,configured_by,reason,created_at) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                    p.tenantNo(),p.profileNo(),terms.version(),json.writeValueAsString(terms),p.operatorNo(),p.reason());
            jdbc.update("UPDATE pay_platform_collection_head SET current_version=?,enabled=? WHERE tenant_no=? AND profile_no=?",terms.version(),p.enabled()?1:0,p.tenantNo(),p.profileNo());
            return terms;
        });
    }
    Optional<OfflineInstructions> current(String tenant,String profile,String currency) {
        return jdbc.query("SELECT p.instructions_json FROM pay_platform_collection_profile p JOIN pay_platform_collection_head h ON h.tenant_no=p.tenant_no AND h.profile_no=p.profile_no AND h.current_version=p.profile_version WHERE h.tenant_no=? AND h.profile_no=? AND h.enabled=1",
                (r,n)->json.readValue(r.getString(1),OfflineInstructions.class),tenant,profile).stream().filter(p->p.currency().equals(currency)).findFirst();
    }
    Optional<OfflineProfileView> profile(String tenant, String profile, String currency) {
        return jdbc.query("SELECT p.instructions_json,h.enabled FROM pay_platform_collection_profile p JOIN pay_platform_collection_head h ON h.tenant_no=p.tenant_no AND h.profile_no=p.profile_no AND h.current_version=p.profile_version WHERE h.tenant_no=? AND h.profile_no=?",
                (r,n)->new OfflineProfileView(json.readValue(r.getString(1),OfflineInstructions.class),r.getBoolean(2)),tenant,profile)
                .stream().filter(p->p.instructions().currency().equals(currency)).findFirst();
    }
    /**
     * The order froze one immutable profile version at quote time. Issue exactly that version while
     * the profile stays enabled; a later reconfiguration only affects new quotes.
     */
    void verifyIssuance(Request r) {
        var terms = r.offline();
        require(terms != null && terms.merchantNo().equals(r.merchantNo()) && terms.currency().equals(r.currency())
                && terms.collectionMethod().equals(r.method()),"OFFLINE_PROFILE_MISMATCH");
        var frozen = jdbc.query("SELECT p.instructions_json FROM pay_platform_collection_profile p JOIN pay_platform_collection_head h ON h.tenant_no=p.tenant_no AND h.profile_no=p.profile_no WHERE p.tenant_no=? AND p.profile_no=? AND p.profile_version=? AND h.enabled=1 FOR SHARE",
                (row,n)->json.readValue(row.getString(1),OfflineInstructions.class),r.key().tenantNo(),terms.profileNo(),terms.version());
        require(frozen.size()==1 && terms.equals(frozen.getFirst()),"OFFLINE_PROFILE_UNAVAILABLE");
    }
    private String proof(Key key) {
        return jdbc.queryForObject("SELECT platform_manual_proof FROM stl_payment WHERE tenant_no=? AND payment_no=? FOR UPDATE",String.class,key.tenantNo(),PlatformCollectionServiceImpl.paymentNo(key));
    }
    ManualReceipt confirm(OfflineProof p) {
        require(p != null && p.key()!=null,"OFFLINE_PROOF_REQUIRED");
        text(p.operatorNo(),64);text(p.actualReceiver(),64);text(p.evidenceReference(),1024);
        require(p.currency()!=null && p.currency().matches("[A-Z]{3}") && p.actualMinor()>0
                && p.actualPaidAt()>=0 && p.actualPaidAt()%1000==0 && p.confirmedAt()>=p.actualPaidAt()
                && ("NORMAL".equals(p.mode()) || "HISTORICAL".equals(p.mode()) || "TEST".equals(p.mode())),"OFFLINE_PROOF_INVALID");
        try {
            Key resulting = tx.execute(s -> {
                Receipt original = owner.localReceipt(p.key());
                require(original!=null && original.request().offline()!=null,"OFFLINE_INTENT_REQUIRED");
                Request r = original.request();
                require(r.merchantNo().equals(p.actualReceiver()),"OFFLINE_RECEIVER_MISMATCH");
                Long issued = jdbc.queryForObject("SELECT platform_dispatch_at FROM stl_payment WHERE payment_no=?",Long.class,original.paymentNo());
                require(issued!=null,"OFFLINE_INSTRUCTIONS_NOT_ISSUED");
                require("HISTORICAL".equals(p.mode()) || Math.addExact(p.actualPaidAt(),999)>=r.preparedAt(),"OFFLINE_HISTORICAL_MODE_REQUIRED");
                boolean sales = "SALES_COLLECTION".equals(r.method());
                String referenceKey, reference;
                String referenceColumn = sales ? "platform_collection_ref_key" : "platform_bank_ref_key";
                require(!"TEST".equals(p.mode()) || sales && p.collectionReference()!=null && p.collectionReference().startsWith("TEST-"),"OFFLINE_PROOF_INVALID");
                if (sales) {
                    text(p.collectorNo(),64);text(p.collectionReference(),128);
                    require(p.bankReference()==null,"OFFLINE_METHOD_MISMATCH");
                    reference = p.collectionReference().trim();
                    referenceKey = PlatformCashAuthority.salesReferenceKey(r,p);
                } else {
                    text(p.bankReference(),128);
                    require(p.collectorNo()==null && p.collectionReference()==null,"OFFLINE_METHOD_MISMATCH");
                    reference=p.bankReference();
                    referenceKey=PlatformCashAuthority.bankReferenceKey(r,reference);
                }
                // A missing sales key must not acquire an InnoDB gap lock: competing orders
                // would deadlock while inserting the same key. The unique index arbitrates it.
                var prior = jdbc.queryForList("SELECT payment_no,platform_manual_proof FROM stl_payment WHERE "+referenceColumn+"=?"+(sales?"":" FOR UPDATE"),referenceKey);
                if (!prior.isEmpty()) {
                    require(p.equals(json.readValue((String)prior.getFirst().get("platform_manual_proof"),OfflineProof.class)),sales?"SALES_REFERENCE_CONFLICT":"OFFLINE_BANK_REFERENCE_CONFLICT");
                    return jdbc.query("SELECT payment_request_json FROM stl_payment WHERE payment_no=?",(rs,n)->json.readValue(rs.getString(1),PlatformCollectionServiceImpl.Frozen.class).request().key(),prior.getFirst().get("payment_no")).getFirst();
                }
                // A further real deposit against an already-paid intent is still money: keep it as an
                // unmatched row awaiting disposition instead of rejecting and losing the fact.
                boolean matches = !"SUCCESS".equals(original.state())
                        && r.currency().equals(p.currency()) && r.amountMinor()==p.actualMinor();
                if (matches) {
                    jdbc.update("UPDATE stl_payment SET status='SUCCESS',trade_no=?,succeeded_at=?,"+referenceColumn+"=?,platform_manual_proof=?,platform_handoff_state='PENDING',err_code=NULL,version=version+1 WHERE payment_no=?",
                            reference,p.actualPaidAt(),referenceKey,json.writeValueAsString(p),original.paymentNo());
                    return r.key();
                }
                Key unmatched = new Key(r.key().tenantNo(),r.key().orderNo(),"U"+referenceKey.substring(0,60));
                Request actual = new Request(unmatched,r.buyerNo(),r.merchantNo(),"OFFLINE",r.method(),p.actualMinor(),p.currency(),r.expiresAt(),r.preparedAt(),null,r.offline());
                jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,entity_no,pay_channel,pay_scene,pay_method,amount_minor,currency,status,out_trade_no,payment_request_json,tenant_no,created_at,updated_at,created_by,updated_by,trade_no,succeeded_at,"+referenceColumn+",platform_manual_proof,platform_dispatch_at) VALUES (?,'PAY',?,?,?,'OFFLINE',?,?,?,?,'SUCCESS',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,?,?,?, ?,?)",
                        PlatformCollectionServiceImpl.paymentNo(unmatched),r.key().orderNo(),r.buyerNo(),r.merchantNo(),UNMATCHED,r.method(),p.actualMinor(),p.currency(),"U"+referenceKey.substring(0,31),
                        json.writeValueAsString(new PlatformCollectionServiceImpl.Frozen(UNMATCHED,actual)),r.key().tenantNo(),p.operatorNo(),p.operatorNo(),reference,p.actualPaidAt(),referenceKey,json.writeValueAsString(p),issued);
                return unmatched;
            });
            Receipt receipt = owner.query(resulting);
            return new ManualReceipt(!resulting.equals(p.key())?"UNMATCHED":receipt.closureReference()!=null?"LATE":"MATCHED",receipt);
        } catch (DuplicateKeyException duplicate) { throw new IllegalArgumentException(p.collectorNo()!=null?"SALES_REFERENCE_CONFLICT":"OFFLINE_BANK_REFERENCE_CONFLICT",duplicate); }
    }
    List<CashReceipt> receipts(String tenant, String order) {
        if (tenant == null || tenant.isBlank() || order == null || order.isBlank()) return List.of();
        return jdbc.query("SELECT * FROM stl_payment WHERE tenant_no=? AND order_no=? AND direction='PAY' AND status='SUCCESS' AND pay_scene IN ('PLATFORM_MALL','MALL_UNMATCHED') AND deleted=0 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(platform_manual_proof,'$.mode')),'NORMAL')<>'TEST' ORDER BY payment_no FOR UPDATE",
                (rs,n) -> {
                    var r = PlatformCashAuthority.receiptMetadata(json, new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,n));
                    String no = rs.getString("payment_no");
                    var refunded = refundAuthority(r, no);
                    boolean closed = rs.getString("platform_close_fence") != null;
                    return new CashReceipt(no,r.key().attemptNo(),r.merchantNo(),r.amountMinor(),r.currency(),
                            refunded.minor(),refunded.pending(),UNMATCHED.equals(rs.getString("pay_scene")) || closed,closed);
                },tenant,order);
    }

    private record RefundAuthority(long minor, boolean pending) {}
    /** Only consumed, independently verified refund facts against this exact original are money. */
    private RefundAuthority refundAuthority(Request original, String no) {
        var facts = jdbc.queryForList("SELECT * FROM stl_payment WHERE tenant_no=? AND origin_payment_no=? AND direction='REFUND' AND deleted=0 FOR UPDATE",
                original.key().tenantNo(),no);
        long total = 0;
        boolean pending = false;
        for (var row : facts) {
            if ("RELEASED".equals(row.get("refund_capacity_state"))) continue;
            if (!"SUCCESS".equals(row.get("status"))) { pending = true; continue; }
            try {
                long amount = ((Number)row.get("amount_minor")).longValue();
                boolean valid = PlatformCashAuthority.refundVerified(json, original, no, row);
                if (valid) total = Math.addExact(total, amount); else pending = true;
            } catch (RuntimeException invalid) { pending = true; }
        }
        return new RefundAuthority(total,pending || total > original.amountMinor());
    }

    Receipt reconcile(OfflineVoid v) {
        require(v!=null && v.key()!=null && v.reconciledAt()>0,"OFFLINE_RECONCILIATION_REQUIRED");
        text(v.operatorNo(),64);text(v.reconciliationReference(),128);text(v.reason(),1024);text(v.evidenceReference(),1024);
        tx.executeWithoutResult(s -> {
            Receipt r=owner.localReceipt(v.key());
            require(r!=null && r.request().offline()!=null,"OFFLINE_INTENT_REQUIRED");
            String prior=jdbc.queryForObject("SELECT platform_reconciliation_proof FROM stl_payment WHERE payment_no=?",String.class,r.paymentNo());
            if (prior!=null) {require(v.equals(json.readValue(prior,OfflineVoid.class)),"OFFLINE_RECONCILIATION_CONFLICT");return;}
            var unmatched=jdbc.queryForList("SELECT payment_no FROM stl_payment WHERE tenant_no=? AND order_no=? AND pay_scene=? AND direction='PAY' FOR UPDATE",String.class,v.key().tenantNo(),v.key().orderNo(),UNMATCHED);
            require(v.unmatchedDispositions().keySet().equals(new HashSet<>(unmatched)),"OFFLINE_UNMATCHED_DISPOSITION_REQUIRED");
            v.unmatchedDispositions().values().forEach(x->text(x,1024));
            for (var cash : receipts(v.key().tenantNo(),v.key().orderNo())) {
                require(!cash.pendingRefund() && cash.refundedMinor() == cash.amountMinor(),
                        "OFFLINE_CASH_NOT_REFUNDED");
            }
            jdbc.update("UPDATE stl_payment SET status=CASE WHEN status='SUCCESS' THEN 'SUCCESS' ELSE 'CLOSED' END,platform_close_fence=?,platform_reconciliation_proof=?,closed_at=?,version=version+1 WHERE payment_no=?",
                    "OFFLINE_VOID:"+hash(v.reconciliationReference(),r.paymentNo()),json.writeValueAsString(v),v.reconciledAt(),r.paymentNo());
        });
        return owner.query(v.key());
    }
}
