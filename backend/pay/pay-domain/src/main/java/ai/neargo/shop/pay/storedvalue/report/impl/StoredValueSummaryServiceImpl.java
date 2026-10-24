package ai.neargo.shop.pay.storedvalue.report.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.exact;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryQuery;
import ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryQuery.Sql;
import static ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryQuery.TOTALS;
import static ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryQuery.WINDOW;
import ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.ActivityAttribution;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Scope;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.StoreAttribution;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Exact immutable-entry attribution; aggregate participants/uses by source identity, never by summing detail counts. */
@Service("storedValueSummaryService")
public class StoredValueSummaryServiceImpl implements StoredValueSummaryService {
 private final StoredValueSummaryQuery queries;
 private final IssuerRepository db;private final StoredValueCursorCodec cursors;
 public StoredValueSummaryServiceImpl(IssuerRepository db,StoredValueCursorCodec cursors){this.queries=new StoredValueSummaryQuery(db);this.db=db;this.cursors=cursors;}

 private Map<String,Object> issuer(Scope scope){return queries.issuer(scope);}
 private Sql source(Scope scope,Period period){return queries.source(scope,period);}

 public Summary summarize(Scope scope,Period period){return db.write(()->{
  var owner=issuer(scope);var q=source(scope,period);var totals=db.one("SELECT "+TOTALS+q.source(),q.args().toArray());complete(totals);
  var heldArgs=new ArrayList<Object>(List.of(scope.tenantNo(),scope.issuerNo(),scope.currency()));String condition=" WHERE a.tenant_no=? AND a.issuer_no=? AND a.currency=? AND l.deleted=0 AND a.deleted=0 AND r.deleted=0 AND r.status='CREDITED'";
  if(scope.activityNo()!=null){condition+=" AND r.activity_no=?";heldArgs.add(scope.activityNo());}
  if(scope.occurrenceStoreNo()!=null){condition+=" AND l.recharge_store_no=?";heldArgs.add(scope.occurrenceStoreNo());}
  var held=db.one("SELECT COALESCE(SUM(l.held_principal_minor+l.held_bonus_minor),0) AS n FROM sv_lot l JOIN sv_account a ON CAST(a.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(a.account_no AS BINARY(256))=CAST(l.account_no AS BINARY(256)) JOIN sv_recharge r ON CAST(r.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(r.recharge_no AS BINARY(256))=CAST(l.recharge_no AS BINARY(256))"+condition,heldArgs.toArray());
  long payable=payable(scope,period);return new Summary(scope.issuerNo(),scope.scopeType(),text(owner,"name"),scope.currency(),period,Instant.now(),value(totals,"watermark"),value(totals,"opening"),value(totals,"paid"),value(totals,"bonus"),value(totals,"consumed"),value(totals,"reversed"),value(totals,"closing"),exact(held.get("n")),value(totals,"recharges"),value(totals,"uses"),value(totals,"customers"),payable);
 });}
 public CursorPage<StoreAttribution> stores(Query c){return db.write(()->{
  issuer(c.scope());var q=source(c.scope(),c.period());String filters=hash(Arrays.asList(c.scope(),c.period()));var after=cursors.decode(c.cursor(),"SV_REPORT_STORES",c.scope().tenantNo(),c.scope().actorNo(),filters);String extra="";if(after!=null){extra=" AND s.store_no>?";q.args().add(after.sortKey());}q.args().add(c.size()+1);
  var rows=db.rows("SELECT s.store_no,MAX(s.store_name) AS store_name,"+TOTALS+q.source()+extra+" GROUP BY s.store_no ORDER BY s.store_no LIMIT ?",q.args().toArray());rows.forEach(StoredValueSummaryServiceImpl::complete);boolean more=rows.size()>c.size();var shown=rows.subList(0,Math.min(c.size(),rows.size()));String next=more?cursors.encode("SV_REPORT_STORES",c.scope().tenantNo(),c.scope().actorNo(),filters,Instant.now().toEpochMilli(),text(shown.getLast(),"store_no")):null;
  return new CursorPage<>(shown.stream().map(r->{var scoped=new Scope(c.scope().tenantNo(),c.scope().actorNo(),c.scope().managementNo(),c.scope().issuerNo(),c.scope().scopeType(),c.scope().scopeNo(),c.scope().currency(),c.scope().activityNo(),text(r,"store_no"));return new StoreAttribution(text(r,"store_no"),text(r,"store_name"),value(r,"paid"),value(r,"bonus"),value(r,"consumed"),payable(scoped,c.period()),value(r,"recharges"),value(r,"uses"),value(r,"customers"));}).toList(),next,more);
 });}
 public CursorPage<ActivityAttribution> activities(Query c){if(c.scope().occurrenceStoreNo()!=null&&"BRAND".equals(c.scope().scopeType()))throw BizException.of(ErrorCode.BAD_REQUEST);return db.write(()->{
  issuer(c.scope());var q=source(c.scope(),c.period());String filters=hash(Arrays.asList(c.scope(),c.period()));var after=cursors.decode(c.cursor(),"SV_REPORT_ACTIVITIES",c.scope().tenantNo(),c.scope().actorNo(),filters);String extra="";if(after!=null){extra=" AND l.release_no>?";q.args().add(after.sortKey());}q.args().add(c.size()+1);
  var rows=db.rows("SELECT r.activity_no,l.release_no,r.source_kind,MAX(r.activity_version_no) AS activity_version_no,"+TOTALS+",COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type='CONSUME' THEN -e.principal_delta ELSE 0 END),0) AS consumed_principal,COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type='CONSUME' THEN -e.bonus_delta ELSE 0 END),0) AS consumed_bonus"+q.source()+extra+" GROUP BY r.activity_no,l.release_no,r.source_kind ORDER BY l.release_no LIMIT ?",q.args().toArray());rows.forEach(StoredValueSummaryServiceImpl::complete);boolean more=rows.size()>c.size();var shown=rows.subList(0,Math.min(c.size(),rows.size()));String next=more?cursors.encode("SV_REPORT_ACTIVITIES",c.scope().tenantNo(),c.scope().actorNo(),filters,Instant.now().toEpochMilli(),text(shown.getLast(),"release_no")):null;
  return new CursorPage<>(shown.stream().map(r->{String release=text(r,"release_no");var remaining=db.one("SELECT COALESCE(SUM(principal_minor+bonus_minor),0) AS n FROM sv_lot WHERE tenant_no=? AND issuer_no=? AND release_no=? AND deleted=0",c.scope().tenantNo(),c.scope().issuerNo(),release);var name=db.one("SELECT name FROM sv_release WHERE tenant_no=? AND issuer_no=? AND release_no=? AND deleted=0",c.scope().tenantNo(),c.scope().issuerNo(),release);return new ActivityAttribution(text(r,"activity_no"),release,text(r,"source_kind"),text(name,"name"),value(r,"paid"),value(r,"bonus"),value(r,"consumed_principal"),value(r,"consumed_bonus"),exact(remaining.get("n")),value(r,"recharges"),value(r,"uses"),value(r,"customers"));}).toList(),next,more);
 });}
 private long payable(Scope scope,Period period){var args=new ArrayList<Object>(List.of(scope.tenantNo(),scope.issuerNo(),scope.currency(),Timestamp.from(period.from()),Timestamp.from(period.to())));String condition=" WHERE c.tenant_no=? AND c.issuer_no=? AND c.currency=? AND c.occurred_at>=? AND c.occurred_at<? AND c.deleted=0";if(scope.activityNo()!=null){condition+=" AND r.activity_no=?";args.add(scope.activityNo());}if(scope.occurrenceStoreNo()!=null){condition+=" AND c.redemption_store_no=?";args.add(scope.occurrenceStoreNo());}var r=db.one("SELECT COALESCE(SUM(c.payable_minor),0) AS n FROM sv_clearing_entry c JOIN sv_lot l ON CAST(l.tenant_no AS BINARY(256))=CAST(c.tenant_no AS BINARY(256)) AND CAST(l.lot_no AS BINARY(256))=CAST(c.lot_no AS BINARY(256)) JOIN sv_recharge r ON CAST(r.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(r.recharge_no AS BINARY(256))=CAST(l.recharge_no AS BINARY(256))"+condition,args.toArray());return exact(r.get("n"));}
 private String hash(Object value){return EntitlementRecords.digest(db.encode(value));}
 private static long value(Map<String,Object> row,String key){return exact(row.get(key));}
 private static void complete(Map<String,Object> row){if(value(row,"missing")!=0)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
