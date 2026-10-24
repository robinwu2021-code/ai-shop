package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Record;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Account-owned consumer history. Issuer wallets never aggregate different customers or issuers. */
@Service
public class StoredValueConsumerIssuerService {
 private final IssuerRepository db;private final StoredValueCursorCodec refs;private final PayRightsDocumentNumbers numbers;
 public StoredValueConsumerIssuerService(IssuerRepository db,StoredValueCursorCodec refs,PayRightsDocumentNumbers numbers){this.db=db;this.refs=refs;this.numbers=numbers;}
 private static final String OWNED="a.tenant_no=? AND a.deleted=0 AND (a.user_no=? OR EXISTS (SELECT 1 FROM sv_customer_identity ci WHERE CAST(ci.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(ci.customer_no AS BINARY(256))=CAST(a.customer_no AS BINARY(256)) AND ci.user_no=?))";
 private static final String FUNDED="EXISTS (SELECT 1 FROM sv_statement c WHERE CAST(c.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(c.account_no AS BINARY(256))=CAST(a.account_no AS BINARY(256)) AND c.type='RECHARGE' AND c.signed_amount_minor>0 AND c.deleted=0)";
 private static final String JOIN=" FROM sv_account a JOIN sv_statement s ON CAST(s.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(s.account_no AS BINARY(256))=CAST(a.account_no AS BINARY(256)) AND s.deleted=0 AND s.type IN ('RECHARGE','REDEMPTION') LEFT JOIN sv_issuer i ON CAST(i.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(i.issuer_no AS BINARY(256))=CAST(a.issuer_no AS BINARY(256)) AND i.deleted=0 LEFT JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) AND v.deleted=0 ";
 private static final String LAST=" AND NOT EXISTS (SELECT 1 FROM sv_statement later WHERE CAST(later.tenant_no AS BINARY(256))=CAST(s.tenant_no AS BINARY(256)) AND CAST(later.account_no AS BINARY(256))=CAST(s.account_no AS BINARY(256)) AND later.type IN ('RECHARGE','REDEMPTION') AND later.deleted=0 AND (later.occurred_at>s.occurred_at OR (later.occurred_at=s.occurred_at AND later.record_no>s.record_no)))";
 private static final String FIELDS="SELECT a.account_no,a.entity_no,a.store_no,a.currency,i.name,i.scope_type,v.market_zone,s.store_name,s.occurred_at,s.type,s.record_no";
 public CursorPage<Issuer> directory(EntitlementContext c,String cursor,int size){
  check(c);ContractChecks.require(size>0&&size<=50);String filter=hash(List.of("DIRECTORY"));var after=refs.decode(cursor,"SV_C_ISSUERS",c.tenantNo(),c.actorNo(),filter);
  var args=new ArrayList<Object>(List.of(c.tenantNo(),c.actorNo(),c.actorNo()));String keyset="";
  if(after!=null){keyset=" AND (s.occurred_at<? OR (s.occurred_at=? AND a.account_no<?))";var at=Timestamp.from(Instant.ofEpochMilli(after.occurredAtMillis()));args.add(at);args.add(at);args.add(after.sortKey());}args.add(size+1);
  var rows=db.rows(FIELDS+JOIN+"WHERE "+OWNED+" AND "+FUNDED+LAST+keyset+" ORDER BY s.occurred_at DESC,a.account_no DESC LIMIT ?",args.toArray());
  boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));String next=null;
  if(more){var last=shown.getLast();next=refs.encode("SV_C_ISSUERS",c.tenantNo(),c.actorNo(),filter,instant(last.get("occurred_at")).toEpochMilli(),text(last,"account_no"));}
  return new CursorPage<>(shown.stream().map(r->issuer(c,r)).toList(),next);
 }
 public Issuer issuer(EntitlementContext c,String issuerRef){return issuer(c,owned(c,issuerRef));}
 public CursorPage<Record> records(EntitlementContext c,String issuerRef,String type,Instant from,Instant to,String focus,String cursor,int size){
  var account=owned(c,issuerRef);String accountNo=text(account,"account_no");
  ContractChecks.require(List.of("ALL","RECHARGE","REDEMPTION").contains(Objects.requireNonNullElse(type,""))&&size>0&&size<=50&&(focus==null||cursor==null)&&(from==null||to==null||from.isBefore(to)));
  String filters=hash(Arrays.asList(accountNo,type,from,to));var after=refs.decode(cursor,"SV_C_ISSUER_RECORDS",c.tenantNo(),c.actorNo(),filters);
  Instant at=after==null?null:Instant.ofEpochMilli(after.occurredAtMillis());String key=after==null?null:after.sortKey();
  if(focus!=null){var row=focus(c,focus);if(!accountNo.equals(text(row,"account_no"))||!"ALL".equals(type)&&!type.equals(text(row,"type"))||from!=null&&instant(row.get("occurred_at")).isBefore(from)||to!=null&&!instant(row.get("occurred_at")).isBefore(to))notFound();at=instant(row.get("occurred_at"));key=text(row,"record_no");}
  var args=new ArrayList<Object>(List.of(c.tenantNo(),accountNo));String where="s.tenant_no=? AND s.account_no=? AND s.deleted=0 AND s.type IN ('RECHARGE','REDEMPTION')";
  if(!"ALL".equals(type)){where+=" AND s.type=?";args.add(type);}if(from!=null){where+=" AND s.occurred_at>=?";args.add(Timestamp.from(from));}if(to!=null){where+=" AND s.occurred_at<?";args.add(Timestamp.from(to));}
  if(at!=null){where+=" AND (s.occurred_at<? OR (s.occurred_at=? AND s.record_no"+(focus!=null?"<=":"<")+"?))";args.add(Timestamp.from(at));args.add(Timestamp.from(at));args.add(key);}args.add(size+1);
  var rows=db.rows("SELECT s.* FROM sv_statement s WHERE "+where+" ORDER BY s.occurred_at DESC,s.record_no DESC LIMIT ?",args.toArray());
  boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));String next=null;
  if(more){var last=shown.getLast();next=refs.encode("SV_C_ISSUER_RECORDS",c.tenantNo(),c.actorNo(),filters,instant(last.get("occurred_at")).toEpochMilli(),text(last,"record_no"));}
  return new CursorPage<>(numbered(c,shown),next);
 }
 /** Durable document links resolve a new short-lived owner-bound issuer reference at open time. */
 public Issuer locate(EntitlementContext c,String documentNo){
  check(c);var row=focus(c,documentNo);
  var rows=db.rows(FIELDS+JOIN+"WHERE "+OWNED+" AND a.account_no=? AND "+FUNDED+LAST,c.tenantNo(),c.actorNo(),c.actorNo(),text(row,"account_no"));
  if(rows.size()!=1)notFound();return issuer(c,rows.getFirst());
 }
 private Map<String,Object> owned(EntitlementContext c,String reference){
  check(c);var token=refs.decode(reference,"SV_C_ISSUER",c.tenantNo(),c.actorNo(),hash(List.of("OWNED_ACCOUNT")));if(token==null)notFound();
  var rows=db.rows(FIELDS+JOIN+"WHERE "+OWNED+" AND a.account_no=? AND "+FUNDED+LAST,c.tenantNo(),c.actorNo(),c.actorNo(),token.sortKey());if(rows.size()!=1)notFound();return rows.getFirst();
 }
 private Map<String,Object> focus(EntitlementContext c,String document){
  if(document==null||!document.matches("(PR|EU)[0-9]{14,}")){notFound();}
  boolean recharge=document.startsWith("PR");String original=numbers.resolve(recharge?DocumentType.SV_RECHARGE:DocumentType.ENTITLEMENT_USAGE,c.tenantNo(),document);if(document.equals(original))notFound();
  if(!recharge){var usage=db.one("SELECT operation_no FROM ent_usage WHERE tenant_no=? AND usage_no=? AND asset_kind='STORED_VALUE' AND status='COMMITTED' AND deleted=0",c.tenantNo(),original);if(usage==null)notFound();original=text(usage,"operation_no");}
  var rows=db.rows("SELECT s.*"+JOIN+"WHERE "+OWNED+" AND "+FUNDED+" AND s.type=? AND s.business_no=?",c.tenantNo(),c.actorNo(),c.actorNo(),recharge?"RECHARGE":"REDEMPTION",original);if(rows.size()!=1)notFound();return rows.getFirst();
 }
 private Issuer issuer(EntitlementContext c,Map<String,Object> r){
  String ref=refs.encode("SV_C_ISSUER",c.tenantNo(),c.actorNo(),hash(List.of("OWNED_ACCOUNT")),Instant.now().toEpochMilli(),text(r,"account_no"));
  return new Issuer(ref,Objects.requireNonNullElse(text(r,"scope_type"),"STORE"),Objects.requireNonNullElse(text(r,"name"),Objects.requireNonNullElse(text(r,"store_name"),"")),text(r,"entity_no"),text(r,"store_no"),text(r,"currency"),text(r,"market_zone"),text(r,"type"),instant(r.get("occurred_at")));
 }
 private List<Record> numbered(EntitlementContext c,List<Map<String,Object>> rows){
  var usages=new HashMap<String,String>();var ops=rows.stream().filter(r->"REDEMPTION".equals(text(r,"type"))).map(r->text(r,"business_no")).distinct().toList();
  if(!ops.isEmpty()){var args=new ArrayList<Object>();args.add(c.tenantNo());args.addAll(ops);for(var u:db.rows("SELECT operation_no,usage_no FROM ent_usage WHERE tenant_no=? AND operation_no IN ("+String.join(",",Collections.nCopies(ops.size(),"?"))+") AND asset_kind='STORED_VALUE' AND status='COMMITTED' AND deleted=0",args.toArray()))if(usages.put(text(u,"operation_no"),text(u,"usage_no"))!=null)throw BizException.of(ErrorCode.CONFLICT);}
  var rechargeNumbers=numbers.findAll(DocumentType.SV_RECHARGE,c.tenantNo(),rows.stream().filter(r->"RECHARGE".equals(text(r,"type"))).map(r->text(r,"business_no")).toList());
  var usageNumbers=numbers.findAll(DocumentType.ENTITLEMENT_USAGE,c.tenantNo(),usages.values());
  return rows.stream().map(r->new Record(text(r,"type"),text(r,"title"),text(r,"currency"),number(r,"signed_amount_minor"),instant(r.get("occurred_at")),"RECHARGE".equals(text(r,"type"))?rechargeNumbers.get(text(r,"business_no")):usageNumbers.get(usages.get(text(r,"business_no"))),text(r,"store_no"),text(r,"store_name"))).toList();
 }
 private String hash(Object o){return ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest(db.encode(o));}
 private static Instant instant(Object value){if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(value instanceof Instant t)return t;throw BizException.of(ErrorCode.CONFLICT);}
 private static void check(EntitlementContext c){ContractChecks.id(c.tenantNo());ContractChecks.id(c.actorNo());}
 private static void notFound(){throw BizException.of(ErrorCode.NOT_FOUND);}
}
