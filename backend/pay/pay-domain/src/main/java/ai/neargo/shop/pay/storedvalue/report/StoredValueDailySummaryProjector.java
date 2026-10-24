package ai.neargo.shop.pay.storedvalue.report;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.exact;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Scope;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/** Bounded, repeatable rebuilding of the daily cache. Financial reports retain immutable ledgers as their authority. */
@Service
public class StoredValueDailySummaryProjector {
 public record Result(String issuerNo,LocalDate date,String afterRelease,String afterStore,boolean hasMore,int rows,Instant projectedAt){}
 private final IssuerRepository db;private final StoredValueSummaryQuery reports;
 public StoredValueDailySummaryProjector(IssuerRepository db,StoredValueSummaryQuery reports){this.db=db;this.reports=reports;}
 public Result rebuild(Scope scope,LocalDate date,String afterRelease,String afterStore,int size){
  if(scope.activityNo()!=null||scope.occurrenceStoreNo()!=null||date==null||size<1||size>500||(afterRelease==null)!=(afterStore==null))throw BizException.of(ErrorCode.BAD_REQUEST);
  return db.write(()->{
   // Same issuer lock as writers and membership changes. A later posting is picked up by an explicit rebuild.
   if(db.one("SELECT issuer_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),scope.issuerNo())==null)throw BizException.of(ErrorCode.NOT_FOUND);
   var issuer=reports.issuer(scope);var zone=ZoneId.of(text(issuer,"market_zone"));var period=new Period(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());var source=reports.source(scope,period);var args=source.args();String cursor="";
   if(afterRelease!=null){cursor=" AND (l.release_no>? OR (l.release_no=? AND s.store_no>?))";args.add(afterRelease);args.add(afterRelease);args.add(afterStore);}args.add(size+1);
   String sql="SELECT r.activity_no,r.source_kind,l.release_no,s.store_no,"+StoredValueSummaryQuery.TOTALS+",COALESCE(SUM(CASE WHEN "+StoredValueSummaryQuery.WINDOW+" AND v.biz_type='CONSUME' THEN -e.principal_delta ELSE 0 END),0) AS used_principal,COALESCE(SUM(CASE WHEN "+StoredValueSummaryQuery.WINDOW+" AND v.biz_type='CONSUME' THEN -e.bonus_delta ELSE 0 END),0) AS used_bonus"+source.source()+cursor+" GROUP BY r.activity_no,r.source_kind,l.release_no,s.store_no ORDER BY l.release_no,s.store_no LIMIT ?";
   var rows=db.rows(sql,args.toArray());boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));var now=Instant.now();
   for(var row:shown){if(exact(row.get("missing"))!=0)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    var clear=db.one("SELECT COALESCE(SUM(c.payable_minor),0) AS n FROM sv_clearing_entry c WHERE c.tenant_no=? AND c.issuer_no=? AND c.release_no=? AND c.redemption_store_no=? AND c.currency=? AND c.occurred_at>=? AND c.occurred_at<? AND c.deleted=0",scope.tenantNo(),scope.issuerNo(),text(row,"release_no"),text(row,"store_no"),scope.currency(),Timestamp.from(period.from()),Timestamp.from(period.to()));
    db.update("INSERT INTO sv_activity_daily_summary(tenant_no,issuer_no,activity_no,source_kind,release_no,occurrence_store_no,currency,business_date,credited_paid_minor,credited_bonus_minor,used_principal_minor,used_bonus_minor,reversed_minor,clearing_minor,recharge_count,usage_count,customer_count,source_watermark,projection_at,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,?,?,?) ON DUPLICATE KEY UPDATE activity_no=VALUES(activity_no),source_kind=VALUES(source_kind),credited_paid_minor=VALUES(credited_paid_minor),credited_bonus_minor=VALUES(credited_bonus_minor),used_principal_minor=VALUES(used_principal_minor),used_bonus_minor=VALUES(used_bonus_minor),reversed_minor=VALUES(reversed_minor),clearing_minor=VALUES(clearing_minor),recharge_count=VALUES(recharge_count),usage_count=VALUES(usage_count),customer_count=VALUES(customer_count),source_watermark=VALUES(source_watermark),projection_at=VALUES(projection_at),updated_at=VALUES(updated_at),updated_by=VALUES(updated_by),deleted=0,version=version+1",
     scope.tenantNo(),scope.issuerNo(),row.get("activity_no"),text(row,"source_kind"),text(row,"release_no"),text(row,"store_no"),scope.currency(),Date.valueOf(date),exact(row.get("paid")),exact(row.get("bonus")),exact(row.get("used_principal")),exact(row.get("used_bonus")),exact(row.get("reversed")),exact(clear.get("n")),exact(row.get("recharges")),exact(row.get("uses")),exact(row.get("customers")),exact(row.get("watermark")),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),scope.actorNo(),scope.actorNo());
   }
   var last=shown.isEmpty()?null:shown.getLast();return new Result(scope.issuerNo(),date,last==null?null:text(last,"release_no"),last==null?null:text(last,"store_no"),more,shown.size(),now);
  });
 }
}
