package ai.neargo.shop.pay.storedvalue.settlement.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.instant;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.missing;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.paymentView;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.requireEither;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.view;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementQueryService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Event;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.IssuerGrant;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Item;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Location;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Financial reads are limited to authorized issuers AND an actual payer/payee entity grant. */
@Service("storedValueSettlementQueryService")
public class StoredValueSettlementQueryServiceImpl implements StoredValueSettlementQueryService {
 private final StoredValueSettlementRepository repo;private final StoredValueCursorCodec cursors;
 public StoredValueSettlementQueryServiceImpl(StoredValueSettlementRepository repo,StoredValueCursorCodec cursors){this.repo=repo;this.cursors=cursors;}
 public Location locate(Resource c){
  var row="PAYMENT".equals(c.kind())?repo.database().one("SELECT b.* FROM sv_settlement_payment p JOIN sv_settlement_bill b ON CAST(b.tenant_no AS BINARY(256))=CAST(p.tenant_no AS BINARY(256)) AND CAST(b.bill_no AS BINARY(256))=CAST(p.bill_no AS BINARY(256)) WHERE p.tenant_no=? AND p.payment_no=? AND p.deleted=0 AND b.deleted=0",c.tenantNo(),c.resourceNo()):repo.database().one("SELECT * FROM sv_settlement_bill WHERE tenant_no=? AND bill_no=? AND deleted=0",c.tenantNo(),c.resourceNo());
  if(row==null)missing();return new Location(text(row,"bill_no"),text(row,"issuer_no"),text(row,"payer_entity_no"),text(row,"payee_entity_no"),text(row,"currency"));
 }
 public Bill detail(Authority a,String no){var row=repo.bill(a,no,false);requireEither(a,row,"sv.settlement.read");return view(row);}
 public CursorPage<Bill> bills(Query c){
  var a=c.authority();for(var grant:a.issuers())repo.issuer(a,grant.issuerNo(),false);
  var entities=a.entityPermissions().entrySet().stream().filter(e->e.getValue().contains("sv.settlement.read")).map(Map.Entry::getKey).sorted().toList();if(entities.isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);
  var issuers=a.issuers().stream().map(IssuerGrant::issuerNo).sorted().distinct().toList();String filters=hash(Arrays.asList(a.managementNo(),a.principalNo(),issuers,entities,c.status(),c.period(),c.currency()));var after=cursors.decode(c.cursor(),"SV_SETTLEMENT_BILLS",a.tenantNo(),a.actorNo(),filters);
  String issuerH=String.join(",",Collections.nCopies(issuers.size(),"?")),entityH=String.join(",",Collections.nCopies(entities.size(),"?"));var args=new ArrayList<Object>();args.add(a.tenantNo());args.addAll(issuers);args.addAll(entities);args.addAll(entities);
  String sql="SELECT * FROM sv_settlement_bill WHERE tenant_no=? AND issuer_no IN ("+issuerH+") AND (payer_entity_no IN ("+entityH+") OR payee_entity_no IN ("+entityH+")) AND deleted=0";
  if(c.status()!=null&&!c.status().isBlank()){if(!Set.of("DRAFT","RECONCILED","DISPUTED","PART_PAID","WAIT_RECEIPT","SETTLED","INTERNAL_RECONCILED").contains(c.status()))throw BizException.of(ErrorCode.BAD_REQUEST);sql+=" AND status=?";args.add(c.status());}
  if(c.currency()!=null){sql+=" AND currency=?";args.add(c.currency());}
  if(c.period()!=null){sql+=" AND period_from<? AND period_to>?";args.add(Timestamp.from(c.period().to()));args.add(Timestamp.from(c.period().from()));}
  if(after!=null){sql+=" AND (created_at<? OR (created_at=? AND bill_no<?))";var at=Timestamp.from(Instant.ofEpochMilli(after.occurredAtMillis()));args.add(at);args.add(at);args.add(after.sortKey());}args.add(c.size()+1);
  var rows=repo.database().rows(sql+" ORDER BY created_at DESC,bill_no DESC LIMIT ?",args.toArray());boolean more=rows.size()>c.size();var shown=rows.subList(0,Math.min(c.size(),rows.size()));String next=null;if(more){var r=shown.getLast();next=cursors.encode("SV_SETTLEMENT_BILLS",a.tenantNo(),a.actorNo(),filters,instant(r.get("created_at")).toEpochMilli(),text(r,"bill_no"));}return new CursorPage<>(shown.stream().map(StoredValueSettlementRepository::view).toList(),next,more);
 }
 public CursorPage<Item> items(Authority a,String no,String cursor,int size){var bill=repo.bill(a,no,false);requireEither(a,bill,"sv.settlement.read");String filters=hash(List.of(a.managementNo(),a.principalNo(),no));long after=id(cursor,"SV_SETTLEMENT_ITEMS",a,filters);range(size);
  var rows=repo.database().rows("SELECT i.*,c.recharge_store_no,c.redemption_store_no,c.occurred_at,c.created_at AS posting_at,c.entry_type,c.original_clearing_no FROM sv_settlement_bill_item i JOIN sv_clearing_entry c ON CAST(c.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(c.clearing_no AS BINARY(256))=CAST(i.clearing_no AS BINARY(256)) WHERE i.tenant_no=? AND i.bill_no=? AND i.deleted=0 AND c.deleted=0 AND i.id>? ORDER BY i.id LIMIT ?",a.tenantNo(),no,after,size+1);boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));String next=next(shown,more,"SV_SETTLEMENT_ITEMS",a,filters);
  return new CursorPage<>(shown.stream().map(r->new Item(text(r,"item_no"),text(r,"clearing_no"),number(r,"amount_minor"),number(r,"principal_minor"),number(r,"bonus_minor"),text(r,"recharge_store_no"),text(r,"redemption_store_no"),instant(r.get("occurred_at")),instant(r.get("posting_at")),instant(r.get("posting_at")).isBefore(instant(bill.get("period_from"))),text(r,"entry_type"),text(r,"original_clearing_no"))).toList(),next,more);
 }
 public CursorPage<Event> events(Authority a,String no,String cursor,int size){var bill=repo.bill(a,no,false);requireEither(a,bill,"sv.settlement.audit");String filters=hash(List.of(a.managementNo(),a.principalNo(),no));long after=id(cursor,"SV_SETTLEMENT_EVENTS",a,filters);range(size);var rows=repo.database().rows("SELECT * FROM sv_settlement_event WHERE tenant_no=? AND bill_no=? AND deleted=0 AND id>? ORDER BY id LIMIT ?",a.tenantNo(),no,after,size+1);boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));return new CursorPage<>(shown.stream().map(r->new Event(text(r,"event_no"),text(r,"event_type"),number(r,"bill_revision"),text(r,"created_by"),text(r,"reason"),instant(r.get("created_at")))).toList(),next(shown,more,"SV_SETTLEMENT_EVENTS",a,filters),more);}
 public CursorPage<Payment> payments(Authority a,String no,String cursor,int size){var bill=repo.bill(a,no,false);requireEither(a,bill,"sv.settlement.read");String filters=hash(List.of(a.managementNo(),a.principalNo(),no));long after=id(cursor,"SV_SETTLEMENT_PAYMENTS",a,filters);range(size);var rows=repo.database().rows("SELECT * FROM sv_settlement_payment WHERE tenant_no=? AND bill_no=? AND deleted=0 AND id>? ORDER BY id LIMIT ?",a.tenantNo(),no,after,size+1);boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));return new CursorPage<>(shown.stream().map(StoredValueSettlementRepository::paymentView).toList(),next(shown,more,"SV_SETTLEMENT_PAYMENTS",a,filters),more);}
 private String hash(Object value){return EntitlementRecords.digest(repo.database().encode(value));}
 private long id(String cursor,String kind,Authority a,String filters){var decoded=cursors.decode(cursor,kind,a.tenantNo(),a.actorNo(),filters);if(decoded==null)return 0;try{long id=Long.parseLong(decoded.sortKey());if(id<0)throw new IllegalArgumentException();return id;}catch(IllegalArgumentException invalid){throw BizException.of(ErrorCode.BAD_REQUEST);}}
 private String next(List<Map<String,Object>> rows,boolean more,String kind,Authority a,String filters){return more?cursors.encode(kind,a.tenantNo(),a.actorNo(),filters,Instant.now().toEpochMilli(),Long.toString(number(rows.getLast(),"id"))):null;}
 private static void range(int size){if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);}
}
