package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
/** Pay-local SQL helpers; callers must authorize exact party action before mutation or projection. */
@Repository
public class StoredValueSettlementRepository {
 public final IssuerRepository db;
 public StoredValueSettlementRepository(IssuerRepository db){this.db=db;}
 public Map<String,Object> issuer(Authority a,String no,boolean lock){
  var grant=a.issuers().stream().filter(g->g.issuerNo().equals(no)).findFirst().orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
  var row=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0"+(lock?" FOR UPDATE":""),a.tenantNo(),no);
  if(row==null||!grant.scopeType().equals(text(row,"scope_type"))||!grant.scopeNo().equals(text(row,"scope_no")))missing();
  return row;
 }
 public Map<String,Object> bill(Authority a,String no,boolean lock){
  var row=db.one("SELECT * FROM sv_settlement_bill WHERE tenant_no=? AND bill_no=? AND deleted=0"+(lock?" FOR UPDATE":""),a.tenantNo(),no);
  if(row==null)missing();issuer(a,text(row,"issuer_no"),false);return row;
 }
 public Map<String,Object> payment(Authority a,String no,boolean lock){
  var row=db.one("SELECT * FROM sv_settlement_payment WHERE tenant_no=? AND payment_no=? AND deleted=0"+(lock?" FOR UPDATE":""),a.tenantNo(),no);
  if(row==null)missing();return row;
 }
 public Map<String,Object> base(Authority a){var m=new LinkedHashMap<String,Object>();m.put("tenant_no",a.tenantNo());m.put("version",0);m.put("deleted",0);var now=Timestamp.from(Instant.now());m.put("created_at",now);m.put("updated_at",now);m.put("created_by",a.actorNo());m.put("updated_by",a.actorNo());return m;}
 public void insert(String table,Map<String,Object> row){
  if(!Set.of("sv_settlement_bill","sv_settlement_bill_item","sv_settlement_payment","sv_settlement_event","sv_activity_daily_summary").contains(table))throw new IllegalArgumentException("Settlement table required");
  for(String key:row.keySet())if(!key.matches("[a-z_]+"))throw new IllegalArgumentException("Column name required");
  db.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
 }
 public static void requireParty(Authority a,Map<String,Object> bill,String entity,String permission){
  if(!Objects.equals(entity,text(bill,"payer_entity_no"))&&!Objects.equals(entity,text(bill,"payee_entity_no")))missing();
  if(!a.can(entity,permission))throw BizException.of(ErrorCode.FORBIDDEN);
 }
 public static void requireEither(Authority a,Map<String,Object> bill,String permission){if(!a.can(text(bill,"payer_entity_no"),permission)&&!a.can(text(bill,"payee_entity_no"),permission))throw BizException.of(ErrorCode.FORBIDDEN);}
 public static void revision(Map<String,Object> row,long expected){if(number(row,"version")!=expected)throw BizException.of(ErrorCode.CONFLICT);}
 public static Instant instant(Object value){if(value==null)return null;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(value instanceof Instant t)return t;throw BizException.of(ErrorCode.CONFLICT);}
 public static Bill view(Map<String,Object> r){return new Bill(text(r,"bill_no"),text(r,"issuer_no"),text(r,"payer_entity_no"),text(r,"payee_entity_no"),text(r,"currency"),instant(r.get("period_from")),instant(r.get("period_to")),text(r,"bill_kind"),text(r,"status"),number(r,"payable_minor"),number(r,"registered_payment_minor"),number(r,"confirmed_receipt_minor"),number(r,"principal_minor"),number(r,"bonus_minor"),number(r,"source_count"),number(r,"source_watermark"),text(r,"payer_reconciled_by"),text(r,"payee_reconciled_by"),text(r,"dispute_no"),number(r,"version"),instant(r.get("created_at")),instant(r.get("updated_at")));}
 public static Payment paymentView(Map<String,Object> r){return new Payment(text(r,"payment_no"),text(r,"bill_no"),text(r,"currency"),text(r,"payment_method"),number(r,"amount_minor"),instant(r.get("paid_at")),text(r,"status"),text(r,"registered_by"),text(r,"received_by"),instant(r.get("received_at")),number(r,"version"));}
 public static long exact(Object number){try{if(number instanceof java.math.BigDecimal n)return n.longValueExact();if(number instanceof java.math.BigInteger n)return n.longValueExact();if(number instanceof Long||number instanceof Integer||number instanceof Short||number instanceof Byte)return ((Number)number).longValue();throw new ArithmeticException();}catch(ArithmeticException e){throw BizException.of(ErrorCode.CONFLICT);}}
 public static void missing(){throw BizException.of(ErrorCode.NOT_FOUND);}
}
