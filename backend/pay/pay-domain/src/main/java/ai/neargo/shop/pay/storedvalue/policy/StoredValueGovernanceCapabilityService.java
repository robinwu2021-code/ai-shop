package ai.neargo.shop.pay.storedvalue.policy;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Capability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
@Service
public class StoredValueGovernanceCapabilityService {
 private final IssuerRepository db;private final boolean enabled;
 public StoredValueGovernanceCapabilityService(IssuerRepository db,@Value("${shop.pay.stored-value.governance-enabled:false}")boolean enabled){this.db=db;this.enabled=enabled;}
 public Capability capability(){if(!enabled)return new Capability(1,false);try{var row=db.one("SELECT COUNT(*) AS n FROM flyway_schema_history WHERE version IN ('564','565','566') AND success=1");db.rows("SELECT projected_plan_version_no FROM sv_release_tier WHERE 1=0");db.rows("SELECT activity_version_hash FROM sv_release WHERE 1=0");return new Capability(1,row!=null&&IssuerRepository.number(row,"n")==3);}catch(DataAccessException unavailable){return new Capability(1,false);}}
}
