package ai.neargo.shop.pay.storedvalue.policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;
@EnabledIfSystemProperty(named="p4.mysql.url",matches="jdbc:mysql://127.*")
class StoredValueGovernanceMysqlTest {
 @Test void actualJsonGeneratedLegacyKeyAndMixedCollationTierBindingWorkTogether()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,2000,100000));var release=f.activate(10000,2000);var version=f.planVersion();
  var binding=f.policies.planBinding("MAIN","E1","S1",version.getVersionNo());assertThat(binding.scope()).isEqualTo(f.scope);assertThat(binding.activityVersionNo()).isEqualTo("AV1");
  f.recharge("R_NATIVE",10000,2000);assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(12000);
  assertThat(f.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(policy_versions,'$.scope.storeNo')) FROM sv_release WHERE release_no=?",String.class,release.releaseNo())).isEqualTo("S1");
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sv_recharge_plan' AND column_name='legacy_name_key' AND extra LIKE '%STORED GENERATED%'",Long.class)).isEqualTo(1);
  assertThat(f.jdbc.queryForObject("SELECT legacy_name_key FROM sv_recharge_plan",String.class)).isNull();
 }
 @Test void actualStoreLimitRollsBackOverLimitOriginalAndReservation()throws Exception{
  var f=new StoredValuePolicyFixture();f.root(f.rules(false,0,5000));f.activate(3000,0);f.recharge("R1",3000,0);
  assertThatThrownBy(()->f.recharge("R2",3000,0)).isInstanceOf(ai.neargo.shop.common.BizException.class);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_recharge",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(3000);
 }
 @Test void readinessNeedsAppliedMigrationsNotOnlyReachableTables()throws Exception{
  var f=new StoredValuePolicyFixture();var readiness=new StoredValueGovernanceCapabilityService(f.db,true);assertThat(readiness.capability().enabled()).isFalse();
  f.jdbc.execute("CREATE TABLE flyway_schema_history(version VARCHAR(64),success TINYINT NOT NULL)");f.jdbc.update("INSERT INTO flyway_schema_history VALUES('564',1),('565',1),('566',1)");assertThat(readiness.capability().enabled()).isTrue();
  f.jdbc.update("UPDATE flyway_schema_history SET success=0 WHERE version='565'");assertThat(readiness.capability().enabled()).isFalse();
 }
}
