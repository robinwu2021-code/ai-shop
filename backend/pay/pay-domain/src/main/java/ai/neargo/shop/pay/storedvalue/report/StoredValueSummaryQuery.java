package ai.neargo.shop.pay.storedvalue.report;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Scope;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Internal immutable-entry query fragments shared by the report reader and daily projector. */
@Component
public class StoredValueSummaryQuery {
 private final IssuerRepository db;
 public StoredValueSummaryQuery(IssuerRepository db){this.db=db;}
 public static final String JOIN=" FROM sv_entry e JOIN sv_voucher v ON CAST(v.tenant_no AS BINARY(256))=CAST(e.tenant_no AS BINARY(256)) AND CAST(v.voucher_no AS BINARY(256))=CAST(e.voucher_no AS BINARY(256)) JOIN sv_lot l ON CAST(l.tenant_no AS BINARY(256))=CAST(e.tenant_no AS BINARY(256)) AND CAST(l.lot_no AS BINARY(256))=CAST(e.lot_no AS BINARY(256)) JOIN sv_account a ON CAST(a.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(a.account_no AS BINARY(256))=CAST(l.account_no AS BINARY(256)) JOIN sv_recharge r ON CAST(r.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(r.recharge_no AS BINARY(256))=CAST(l.recharge_no AS BINARY(256)) LEFT JOIN sv_statement s ON CAST(s.tenant_no AS BINARY(256))=CAST(v.tenant_no AS BINARY(256)) AND CAST(s.voucher_no AS BINARY(256))=CAST(v.voucher_no AS BINARY(256)) AND s.deleted=0";
 public static final String BOUNDS=" CROSS JOIN(SELECT CAST(? AS DATETIME(3)) AS start_at,CAST(? AS DATETIME(3)) AS end_at) period_scope";
 public static final String WINDOW="v.occurred_at>=period_scope.start_at AND v.occurred_at<period_scope.end_at";
 private static final String DELTA="(e.principal_delta+e.bonus_delta)";
 public static final String TOTALS="COALESCE(SUM(CASE WHEN v.occurred_at<period_scope.start_at THEN "+DELTA+" ELSE 0 END),0) AS opening,COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type='RECHARGE' THEN e.principal_delta ELSE 0 END),0) AS paid,COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type='RECHARGE' THEN e.bonus_delta ELSE 0 END),0) AS bonus,COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type='CONSUME' THEN -"+DELTA+" ELSE 0 END),0) AS consumed,COALESCE(SUM(CASE WHEN "+WINDOW+" AND v.biz_type NOT IN ('RECHARGE','CONSUME') THEN "+DELTA+" ELSE 0 END),0) AS reversed,COALESCE(SUM(CASE WHEN v.occurred_at<period_scope.end_at THEN "+DELTA+" ELSE 0 END),0) AS closing,COUNT(DISTINCT CASE WHEN "+WINDOW+" AND v.biz_type='RECHARGE' THEN v.voucher_no END) AS recharges,COUNT(DISTINCT CASE WHEN "+WINDOW+" AND v.biz_type='CONSUME' THEN v.voucher_no END) AS uses,COUNT(DISTINCT CASE WHEN "+WINDOW+" THEN a.customer_no END) AS customers,COALESCE(MAX(e.id),0) AS watermark,COALESCE(SUM(CASE WHEN s.voucher_no IS NULL OR s.store_no IS NULL OR l.issuer_no IS NULL OR r.issuer_no IS NULL OR l.release_no IS NULL OR CAST(l.issuer_no AS BINARY(256))<>CAST(a.issuer_no AS BINARY(256)) OR CAST(r.issuer_no AS BINARY(256))<>CAST(a.issuer_no AS BINARY(256)) THEN 1 ELSE 0 END),0) AS missing";
 public record Sql(String source,ArrayList<Object> args){}
 public Map<String,Object> issuer(Scope scope){var row=db.one("SELECT i.*,v.currency,v.market_zone FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.issuer_no=? AND i.deleted=0 AND v.deleted=0",scope.tenantNo(),scope.issuerNo());if(row==null||!scope.scopeType().equals(text(row,"scope_type"))||!scope.scopeNo().equals(text(row,"scope_no"))||!scope.currency().equals(text(row,"currency")))throw BizException.of(ErrorCode.NOT_FOUND);if(!Set.of("ISSUER","FENCED").contains(Objects.toString(text(row,"write_mode"),"")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);return row;}
 public Sql source(Scope scope,Period period){var args=new ArrayList<Object>(Arrays.asList(Timestamp.from(period.from()),Timestamp.from(period.to()),scope.tenantNo(),scope.issuerNo(),scope.currency()));String where=" WHERE a.tenant_no=? AND a.issuer_no=? AND a.currency=? AND a.deleted=0 AND e.deleted=0 AND v.deleted=0 AND l.deleted=0 AND r.deleted=0 AND r.status='CREDITED'";
  if(scope.activityNo()!=null){where+=" AND r.activity_no=?";args.add(scope.activityNo());}if(scope.occurrenceStoreNo()!=null){where+=" AND s.store_no=?";args.add(scope.occurrenceStoreNo());}return new Sql(JOIN+BOUNDS+where,args);
 }
}
