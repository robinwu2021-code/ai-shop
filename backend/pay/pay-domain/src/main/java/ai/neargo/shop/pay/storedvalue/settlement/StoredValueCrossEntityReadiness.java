package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Capability;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
/** New cross-entity promises require actual schema, writer recovery, evidence and protocol readiness. */
@Service
public class StoredValueCrossEntityReadiness {
 private final IssuerRepository db;private final StoredValueBrandRuntimeReadiness foundation;private final StoredValueSettlementEvidenceCipher cipher;private final boolean settlement,cross;
 public StoredValueCrossEntityReadiness(IssuerRepository db,StoredValueBrandRuntimeReadiness foundation,StoredValueSettlementEvidenceCipher cipher,
  @Value("${shop.pay.stored-value.settlement-enabled:false}")boolean settlement,@Value("${shop.pay.stored-value.cross-entity-enabled:false}")boolean cross){this.db=db;this.foundation=foundation;this.cipher=cipher;this.settlement=settlement;this.cross=cross;}
 public Capability capability(){var reasons=new ArrayList<String>(foundation.capability().blockers());if(ai.neargo.shop.spi.storedvalue.StoredValueMembership.COORDINATION_IMPLEMENTATION_PROTOCOL<2)reasons.add("P6_FINANCIAL_COORDINATION_NOT_INSTALLED");if(!settlement)reasons.add("SETTLEMENT_DISABLED");if(!cross)reasons.add("CROSS_ENTITY_DISABLED");if(!schemaReady())reasons.add("P6_SCHEMA_NOT_READY");if(!cipher.configured())reasons.add("SETTLEMENT_EVIDENCE_KEY_UNCONFIGURED");return new Capability(2,reasons.isEmpty(),reasons);}
 public boolean settlementReady(){return settlement&&schemaReady()&&cipher.configured();}
 public boolean reportsReady(){var blocked=foundation.capability().blockers();if(blocked.stream().anyMatch(r->Set.of("P3_DIMENSIONS_NOT_ENFORCED","P3_ISSUER_KEY_NOT_READY","P3_LEGACY_KEYS_REMAIN","P3_RESUME_NOT_CONFIRMED","P5_SCHEMA_NOT_READY").contains(r)))return false;try{return number(db.one("SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sv_account','sv_lot','sv_voucher','sv_entry','sv_recharge','sv_statement','sv_clearing_entry')"),"n")==7;}catch(DataAccessException missing){return false;}}
 public boolean schemaReady(){try{
  var applied=db.one("SELECT COUNT(*) AS n FROM flyway_schema_history WHERE version IN ('575','576') AND success=1");if(number(applied,"n")!=2)return false;
  var tables=db.one("SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sv_settlement_bill','sv_settlement_bill_item','sv_settlement_payment','sv_settlement_event','sv_activity_daily_summary','sv_financial_membership_change')");if(number(tables,"n")!=6)return false;
  for(var expected:Map.of("sv_settlement_bill","tenant_no,issuer_no,payer_entity_no,payee_entity_no,currency,period_from,period_to","sv_settlement_bill_item","tenant_no,clearing_no","sv_settlement_payment","tenant_no,payer_entity_no,active_receipt_digest","sv_settlement_event","tenant_no,bill_no,bill_revision","sv_financial_membership_change","tenant_no,change_no,issuer_no").entrySet()){
   var indexes=db.rows("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND non_unique=0 GROUP BY index_name",expected.getKey());
   if(indexes.stream().noneMatch(i->expected.getValue().equals(text(i,"cols"))))return false;
  }
  var checks=db.one("SELECT COUNT(*) AS n FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name IN ('sv_settlement_bill','sv_settlement_payment','sv_financial_membership_change') AND constraint_type='CHECK' AND enforced='YES'");return number(checks,"n")>=12;
 }catch(DataAccessException invalid){return false;}}
 public void requireNewSettlement(){if(!settlement||!schemaReady())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
 public void requireCross(int coreProtocol){if(coreProtocol<2||!capability().ready())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
