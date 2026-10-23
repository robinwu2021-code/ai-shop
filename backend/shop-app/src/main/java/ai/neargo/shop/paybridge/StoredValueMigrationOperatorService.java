package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.*;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.ExecutionCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
/** Trusted operator transport only; node proof is always freshly generated here, never supplied by caller. */
@Service
public class StoredValueMigrationOperatorService {
 private final StoredValueMigrationReadinessService readiness;private final StoredValueMigrationAppService pay;private final boolean enabled;private final ai.neargo.shop.merchant.brand.IssuerAuthorityService authority;
 public StoredValueMigrationOperatorService(StoredValueMigrationReadinessService readiness,StoredValueMigrationAppService pay,ai.neargo.shop.merchant.brand.IssuerAuthorityService authority,@Value("${shop.pay.stored-value.migration.key-cutover-enabled:false}") boolean enabled){this.authority=authority;this.readiness=readiness;this.pay=pay;this.enabled=enabled;}
 public ExecutionCommand execute(String action,String operator,String approval){check(operator,approval);if(!java.util.Set.of("key-step","guard-dimensions","resume").contains(action))throw BizException.of(ErrorCode.BAD_REQUEST);var proof=proof();var evidence=pay.operatorSources(operator,approval,proof);authority.verifyMigrationSources(evidence.sources());return pay.execute(action,operator,approval,proof,evidence.approvalHash());}
 public ExecutionCommand command(String operator,String approval,String no){check(operator,approval);ContractChecks.id(no);return pay.command(operator,approval,no,proof());}
 private ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof proof(){var proof=readiness.proof();if(proof==null||proof.report()==null||!proof.report().ready()||proof.report().expiresAt()==null||!proof.report().expiresAt().isAfter(java.time.Instant.now()))throw BizException.of(ErrorCode.CONFLICT);return proof;}
 private void check(String operator,String approval){if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);ContractChecks.id(operator);ContractChecks.id(approval);}
}
