package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Capability;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueAccountDimensionGuard;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Financial activation never infers the P3 key cutover from a feature flag or the mere presence of columns. */
@Service
public class StoredValueBrandRuntimeReadiness {

    private final IssuerRepository db;private final StoredValueAccountDimensionGuard dimensions;private final boolean issuerMode;private final boolean brandMode;
    public StoredValueBrandRuntimeReadiness(IssuerRepository db,StoredValueAccountDimensionGuard dimensions,@Value("${shop.pay.stored-value.issuer-mode:false}")boolean issuerMode,@Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean brandMode){this.db=db;this.dimensions=dimensions;this.issuerMode=issuerMode;this.brandMode=brandMode;}
    public Capability capability(){
        var blockers=new ArrayList<String>();if(!issuerMode)blockers.add("ISSUER_WRITERS_DISABLED");if(!brandMode)blockers.add("BRAND_EXECUTION_DISABLED");
        try{
            if(!dimensions.installed())blockers.add("P3_DIMENSIONS_NOT_ENFORCED");
            var key=db.rows("SELECT column_name,non_unique,sub_part,index_type,expression FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sv_account' AND index_name='uk_sv_issuer_customer' ORDER BY seq_in_index");
            var expected=List.of("tenant_no","issuer_no","customer_no","currency");if(key.size()!=4)blockers.add("P3_ISSUER_KEY_NOT_READY");else for(int i=0;i<4;i++){var part=key.get(i);if(!expected.get(i).equals(text(part,"column_name"))||number(part,"non_unique")!=0||part.get("sub_part")!=null||part.get("expression")!=null||!"BTREE".equals(text(part,"index_type"))){blockers.add("P3_ISSUER_KEY_NOT_READY");break;}}
            if(number(db.one("SELECT COUNT(*) AS n FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sv_account' AND index_name IN ('uk_sv_owner','uk_sv_customer_owner')"),"n")!=0)blockers.add("P3_LEGACY_KEYS_REMAIN");
            var receipts=db.rows("SELECT response_snapshot FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind='SV_SCHEMA_RESUME' AND result_status='COMPLETED' AND deleted=0 ORDER BY id DESC LIMIT 1");
            if(receipts.isEmpty()||!"ISSUER_WRITERS_RESUMED".equals(db.decode(text(receipts.getFirst(),"response_snapshot"),Map.class).get("state")))blockers.add("P3_RESUME_NOT_CONFIRMED");
            if(number(db.one("SELECT COUNT(*) AS n FROM pay_ent_command_dedup WHERE tenant_no='__LEDGER__' AND operation_kind IN ('SV_SCHEMA_KEY_STEP','SV_SCHEMA_DIMENSION') AND result_status='PENDING' AND deleted=0"),"n")!=0)blockers.add("P3_SCHEMA_INTENT_PENDING");
            if(number(db.one("SELECT COUNT(*) AS n FROM sv_issuer WHERE scope_type='STORE' AND write_mode='FENCED' AND deleted=0"),"n")!=0)blockers.add("P3_STORE_WRITERS_FENCED");
            var migrations=db.one("SELECT COUNT(*) AS n FROM flyway_schema_history WHERE version IN ('570','571','572','573','574') AND success=1");if(number(migrations,"n")!=5)blockers.add("P5_SCHEMA_NOT_READY");
            db.rows("SELECT issuer_no FROM sv_issue_quota WHERE 1=0");db.rows("SELECT source_event_key FROM sv_clearing_entry WHERE 1=0");
        }catch(DataAccessException unavailable){blockers.add("P5_SCHEMA_NOT_READY");}
        return new Capability(1,blockers.isEmpty(),blockers.stream().distinct().toList());
    }
    public void require(){if(!capability().ready())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
