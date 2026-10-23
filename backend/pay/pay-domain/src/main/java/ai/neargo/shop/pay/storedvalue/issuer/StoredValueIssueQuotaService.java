package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Pay-local counters, never a payment channel or proof that an external collection succeeded. */
@Service
public class StoredValueIssueQuotaService {
    public record Reservation(String reservationNo,String status,long amountMinor) { }
    private final IssuerRepository db;
    public StoredValueIssueQuotaService(IssuerRepository db) { this.db=db; }

    public Reservation reserve(EntitlementContext actor,String rechargeNo) {
        return db.write(()->{
            var known=original(actor,rechargeNo,false);var quota=lockQuota(actor,known);
            var r=original(actor,rechargeNo,true);var prior=reservation(actor,rechargeNo);
            long amount=add(number(r,"principal_minor"),number(r,"bonus_minor"));
            if(amount<=0||number(r,"principal_minor")<=0||number(r,"bonus_minor")<0) throw BizException.of(ErrorCode.BAD_REQUEST);
            if(prior!=null) {
                if(amount!=number(prior,"amount_minor")||!text(quota,"quota_no").equals(text(prior,"quota_no"))
                        ||!Objects.equals(text(r,"quota_reservation_no"),text(prior,"reservation_no"))) throw BizException.of(ErrorCode.CONFLICT);
                return view(prior);
            }
            if(!Set.of("WAIT_COLLECTION","SUBMITTED","RECEIVED").contains(text(r,"status"))) throw BizException.of(ErrorCode.CONFLICT);
            long reserved=add(number(quota,"reserved_minor"),amount);add(number(quota,"outstanding_minor"),reserved);
            String no=BizKey.next(BizKey.STORED_VALUE_QUOTA_RESERVATION);var at=Timestamp.from(Instant.now());
            var row=base(actor,at);row.put("reservation_no",no);row.put("quota_no",text(quota,"quota_no"));row.put("recharge_no",rechargeNo);
            row.put("amount_minor",amount);row.put("status","RESERVED");row.put("policy_versions","[]");
            row.put("expires_at",received(r)?null:Timestamp.from(Instant.now().plusSeconds(1800)));
            db.insert("sv_issue_quota_reservation",row);
            updateQuota(actor,quota,number(quota,"outstanding_minor"),reserved,at);
            db.update("UPDATE sv_recharge SET quota_reservation_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND recharge_no=?",no,at,actor.actorNo(),actor.tenantNo(),rechargeNo);
            return new Reservation(no,"RESERVED",amount);
        });
    }
    public void commit(EntitlementContext actor,String rechargeNo) {
        db.write(()->{
            var quota=lockQuota(actor,original(actor,rechargeNo,false));var r=original(actor,rechargeNo,true);var row=reservation(actor,rechargeNo);
            if(row==null||!"CREDITED".equals(text(r,"status"))) throw BizException.of(ErrorCode.CONFLICT);
            requireBinding(r,quota,row);
            if("COMMITTED".equals(text(row,"status"))) return null;
            if(!"RESERVED".equals(text(row,"status"))) throw BizException.of(ErrorCode.CONFLICT);
            long amount=number(row,"amount_minor");long reserved=subtract(number(quota,"reserved_minor"),amount);
            long outstanding=add(number(quota,"outstanding_minor"),amount);add(outstanding,reserved);
            var at=Timestamp.from(Instant.now());updateQuota(actor,quota,outstanding,reserved,at);
            db.update("UPDATE sv_issue_quota_reservation SET status='COMMITTED',expires_at=NULL,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND reservation_no=?",at,actor.actorNo(),actor.tenantNo(),text(row,"reservation_no"));
            return null;
        });
    }
    public void release(EntitlementContext actor,String rechargeNo) {
        db.write(()->{
            var quota=lockQuota(actor,original(actor,rechargeNo,false));var r=original(actor,rechargeNo,true);var row=reservation(actor,rechargeNo);
            if(row==null||received(r)||!Set.of("CANCELLED","REJECTED").contains(text(r,"status"))) throw BizException.of(ErrorCode.CONFLICT);
            requireBinding(r,quota,row);
            if("RELEASED".equals(text(row,"status"))) return null;
            if(!"RESERVED".equals(text(row,"status"))) throw BizException.of(ErrorCode.CONFLICT);
            var at=Timestamp.from(Instant.now());
            updateQuota(actor,quota,number(quota,"outstanding_minor"),subtract(number(quota,"reserved_minor"),number(row,"amount_minor")),at);
            db.update("UPDATE sv_issue_quota_reservation SET status='RELEASED',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND reservation_no=?",at,actor.actorNo(),actor.tenantNo(),text(row,"reservation_no"));
            return null;
        });
    }
    /** Only a durable original cancellation can release quota; elapsed time alone never does. */
    public boolean expire(EntitlementContext actor,String rechargeNo,Instant now) {
        return db.write(()->{
            lockQuota(actor,original(actor,rechargeNo,false));var r=original(actor,rechargeNo,true);var row=reservation(actor,rechargeNo);
            if(row==null||received(r)||!"RESERVED".equals(text(row,"status"))||row.get("expires_at")==null
                    ||!Set.of("CANCELLED","REJECTED").contains(text(r,"status"))) return false;
            Object expires=row.get("expires_at");Instant deadline=expires instanceof Timestamp t?t.toInstant():((LocalDateTime)expires).toInstant(ZoneOffset.UTC);
            if(now.isBefore(deadline)) return false;
            release(actor,rechargeNo);return true;
        });
    }
    /** Called before any original receipt/account locks when the issuer write path is enabled. */
    public void lockForOriginal(EntitlementContext actor,String rechargeNo) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Pay transaction required");
        lockQuota(actor,original(actor,rechargeNo,false));
    }
    public void lockForAccount(EntitlementContext actor,String accountNo) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Pay transaction required");
        var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",actor.tenantNo(),accountNo);
        if(account==null||text(account,"issuer_no")==null)throw BizException.of(ErrorCode.CONFLICT);
        lockQuota(actor,account);
    }
    private Map<String,Object> lockQuota(EntitlementContext actor,Map<String,Object> original) {
        var quota=db.one("SELECT * FROM sv_issue_quota WHERE tenant_no=? AND store_no=? AND currency=? AND deleted=0 FOR UPDATE",actor.tenantNo(),text(original,"store_no"),text(original,"currency"));
        if(quota==null) {
            if(!db.rows("SELECT account_no FROM sv_account WHERE tenant_no=? AND store_no=? AND currency=? AND deleted=0 LIMIT 1",actor.tenantNo(),text(original,"store_no"),text(original,"currency")).isEmpty())
                throw BizException.of(ErrorCode.CONFLICT);
            var at=Timestamp.from(Instant.now());var row=base(actor,at);row.put("quota_no",BizKey.next(BizKey.STORED_VALUE_QUOTA));row.put("store_no",text(original,"store_no"));row.put("currency",text(original,"currency"));row.put("outstanding_minor",0);row.put("reserved_minor",0);db.insert("sv_issue_quota",row);
            quota=db.one("SELECT * FROM sv_issue_quota WHERE tenant_no=? AND store_no=? AND currency=? FOR UPDATE",actor.tenantNo(),text(original,"store_no"),text(original,"currency"));
        }
        return quota;
    }
    private Map<String,Object> original(EntitlementContext actor,String no,boolean lock) {
        var row=db.one("SELECT * FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND deleted=0"+(lock?" FOR UPDATE":""),actor.tenantNo(),no);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        if(text(row,"issuer_no")==null||text(row,"store_no")==null||text(row,"currency")==null) throw BizException.of(ErrorCode.CONFLICT);
        return row;
    }
    private Map<String,Object> reservation(EntitlementContext actor,String no) {
        return db.one("SELECT * FROM sv_issue_quota_reservation WHERE tenant_no=? AND recharge_no=? AND deleted=0 FOR UPDATE",actor.tenantNo(),no);
    }
    private void requireBinding(Map<String,Object> original,Map<String,Object> quota,Map<String,Object> reservation) {
        if(!Objects.equals(text(original,"quota_reservation_no"),text(reservation,"reservation_no"))
                ||!Objects.equals(text(quota,"quota_no"),text(reservation,"quota_no"))
                ||number(reservation,"amount_minor")!=add(number(original,"principal_minor"),number(original,"bonus_minor"))) throw BizException.of(ErrorCode.CONFLICT);
    }
    private void updateQuota(EntitlementContext actor,Map<String,Object> quota,long outstanding,long reserved,Timestamp at) {
        db.update("UPDATE sv_issue_quota SET outstanding_minor=?,reserved_minor=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND quota_no=?",outstanding,reserved,at,actor.actorNo(),actor.tenantNo(),text(quota,"quota_no"));
    }
    private Map<String,Object> base(EntitlementContext actor,Timestamp at) {
        var row=new LinkedHashMap<String,Object>();row.put("tenant_no",actor.tenantNo());row.put("version",0);row.put("deleted",0);
        row.put("created_at",at);row.put("updated_at",at);row.put("created_by",actor.actorNo());row.put("updated_by",actor.actorNo());return row;
    }
    private static boolean received(Map<String,Object> original) {
        return original.get("received_at")!=null||Set.of("RECEIVED","CREDITED").contains(text(original,"status"));
    }
    private static Reservation view(Map<String,Object> row) { return new Reservation(text(row,"reservation_no"),text(row,"status"),number(row,"amount_minor")); }
    private static long add(long a,long b) { try { return Math.addExact(a,b); } catch(ArithmeticException overflow) { throw BizException.of(ErrorCode.BAD_REQUEST); } }
    private static long subtract(long a,long b) { long result;try { result=Math.subtractExact(a,b); } catch(ArithmeticException invalid) { throw BizException.of(ErrorCode.CONFLICT); }if(result<0) throw BizException.of(ErrorCode.CONFLICT);return result; }
}
