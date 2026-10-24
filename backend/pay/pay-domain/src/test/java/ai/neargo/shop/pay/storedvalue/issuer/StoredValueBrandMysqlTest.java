package ai.neargo.shop.pay.storedvalue.issuer;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueAccountDimensionGuard;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual new MySQL DDL/JSON/collations, in a fresh loopback-only database. No online ledger or external collection. */
@EnabledIfSystemProperty(named="p5.mysql.url",matches="jdbc:mysql://127.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoredValueBrandMysqlTest {
 DriverManagerDataSource ds;JdbcTemplate jdbc;IssuerRepository db;StoredValueBrandRuntimeReadiness readiness;
 StoredValueIssuePolicyService policies;StoredValueIssuePolicyEvaluator evaluator;StoredValueIssueQuotaService quotas;
 StoredValueReleaseService releases;String issuer;BrandScope brand;Query query;
 final ObjectMapper json=new ObjectMapper();final EntitlementContext ctx=new EntitlementContext("MAIN","OWNER","NATIVE","0".repeat(64));
 @BeforeAll void setup()throws Exception{
  String url=System.getProperty("p5.mysql.url");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p5_sv_[0-9_]+\\?.*"))throw new IllegalArgumentException("Fresh P5 loopback database required");
  ds=new DriverManagerDataSource(url,System.getProperty("p5.mysql.user"),Files.readString(Path.of(System.getProperty("p5.mysql.password-file"))).trim());
  jdbc=new JdbcTemplate(ds);if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)!=0)throw new IllegalStateException("Native fixture must start with an empty database");
  db=new IssuerRepository(new DataSourceTransactionManager(ds),json);
  for(String name:List.of("V498__stored_value_credit_ledger.sql","V502__stored_value_hold_allocation.sql","V532__stored_value_store_accounts.sql","V533__store_recharge_plans.sql","V534__direct_store_recharge_source.sql","V535__store_stored_value_statements.sql"))ddl(file(name));
  String identity=file("V539__stored_value_customer_identity.sql");ddl(identity.substring(0,identity.indexOf("ALTER TABLE mbr_identity_ticket")));
  ddl(identity.substring(identity.indexOf("CREATE TABLE sv_customer_identity"),identity.indexOf("-- Member selection")));
  for(String name:List.of("V548__stored_value_issuer_foundation.sql","V549__stored_value_issuer_ledger_dimensions.sql","V550__stored_value_legacy_activity_release.sql","V564__stored_value_issue_policy.sql","V565__stored_value_activity_governance.sql","V566__stored_value_governed_plan_projection.sql"))ddl(file(name));
  ddl("CREATE TABLE mch_brand(tenant_no VARCHAR(32),brand_no VARCHAR(64));");
  for(String name:List.of("V570__stored_value_clearing_entries.sql","V571__stored_value_brand_activity_scope.sql","V572__stored_value_brand_quota_scope.sql","V573__brand_financial_activation_fence.sql","V574__stored_value_brand_release_provenance.sql"))ddl(file(name));
  ddl("ALTER TABLE sv_account DROP INDEX uk_sv_owner,DROP INDEX uk_sv_customer_owner;ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_issuer_customer(tenant_no,issuer_no,customer_no,currency);");
  ddl((String)ReflectionTestUtils.getField(StoredValueAccountDimensionGuard.class,"SQL")+";");
  ddl("CREATE TABLE flyway_schema_history(version VARCHAR(64),success TINYINT NOT NULL);CREATE TABLE pay_ent_command_dedup(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32),operation_kind VARCHAR(64),request_key VARCHAR(64),result_status VARCHAR(24),response_snapshot JSON,deleted INT DEFAULT 0);");
  jdbc.update("INSERT INTO flyway_schema_history VALUES('570',1),('571',1),('572',1),('573',1),('574',1)");
  jdbc.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,result_status,response_snapshot) VALUES('__LEDGER__','SV_SCHEMA_RESUME','READY','COMPLETED',?)",json.writeValueAsString(Map.of("state","ISSUER_WRITERS_RESUMED")));
  readiness=new StoredValueBrandRuntimeReadiness(db,new StoredValueAccountDimensionGuard(db,null,null,null),true,true);
  evaluator=new StoredValueIssuePolicyEvaluator();policies=new StoredValueIssuePolicyService(db,evaluator);quotas=new StoredValueIssueQuotaService(db);
  releases=new StoredValueReleaseService(db,policies,evaluator,new StoredValueReleasePlanProjectionService(db));ReflectionTestUtils.setField(releases,"brandExecution",true);
  var route=new Route(null,"E1",null,"MERCHANT_OFFLINE","OFFLINE");
  var configured=new StoredValueIssuerService(db).configure(new AuthorizedScope("MAIN","OWNER","M1","BRAND","B1",0,null,Set.of("E1"),List.of(route)),new Config("Brand","E1","CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","CNY","Asia/Shanghai",List.of(route),0),"CONFIG");
  issuer=configured.issuerNo();jdbc.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER' WHERE issuer_no=?",issuer);
  jdbc.update("INSERT INTO sv_issuer_store_access(tenant_no,access_no,issuer_no,store_no,operating_entity_no,source_binding_no,source_revision,can_issue,can_redeem,status,effective_from,created_at,updated_at,created_by,updated_by) VALUES('MAIN','ACCESS',?,'S1','E1','BINDING',0,1,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OWNER','OWNER')",issuer);
  new StoredValuePolicyConfigurationService(db,evaluator).configure(new PolicyAuthority("MAIN","OWNER","M1","SPACE","M1",null,"CNY",0),new PolicyEdit(new Rules(true,false,3000,100000,30000,365,20000),0),"POLICY");
  brand=new BrandScope("MAIN","M1","B1",issuer,"CNY",0);query=new Query(new ExecutionScope("MAIN","OWNER","M1","E1","S1",0,"B1","BINDING",0,"CNY"),new Customer("PERSON",null));
 }
 @Test void readinessRequiresRealKeyGuardAndEachNewAppliedMigration(){
  assertThat(readiness.capability().ready()).isTrue();
  jdbc.update("UPDATE flyway_schema_history SET success=0 WHERE version='574'");
  try{assertThat(readiness.capability().blockers()).contains("P5_SCHEMA_NOT_READY");assertThatThrownBy(readiness::require).isInstanceOf(BizException.class);}
  finally{jdbc.update("UPDATE flyway_schema_history SET success=1 WHERE version='574'");}
 }
 @Test void actualBrandSourceCheckAndJsonDeductionPermitOffersAcrossMixedCollations(){
  var p=releases.prepare(new StoredValueReleases.Prepare(brand,"E1","OWNER","OWNER","Brand offer","A1","0","a".repeat(64),Instant.now().minusSeconds(5),Instant.now().plusSeconds(86400),"Asia/Shanghai",List.of(new StoredValueReleases.Tier("T1",10000,3000)),"PREPARE"));
  releases.activate(new StoredValueReleases.Activate(brand,"E1","OWNER",p.releaseNo(),"A1","0","a".repeat(64),p.contentHash(),"AV1","b".repeat(64),null,"ACTIVATE"));
  var options=new StoredValueBrandRechargeOptions(db,new StoredValueCursorCodec("native-p5-reference-key-longer-than-thirty-two-characters",json),readiness,policies,evaluator);
  var offers=options.options(query,null,20);assertThat(offers.records()).hasSize(1);assertThat(offers.records().getFirst().totalAmountMinor()).isEqualTo(13000);
  assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(policy_versions,'$.scope.brandNo')) FROM sv_release WHERE release_no=?",String.class,p.releaseNo())).isEqualTo("B1");
  assertThat(jdbc.queryForObject("SELECT source_kind FROM sv_release WHERE release_no=?",String.class,p.releaseNo())).isEqualTo("V2_BRAND_ACTIVITY");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isZero();
 }
 @Test void brandQuotaScopeRejectsDoubleDimensionAndFinancialFailureRollsBackPool(){
  assertThatThrownBy(()->db.write(()->{
   db.one("SELECT issuer_no FROM sv_issuer WHERE issuer_no=? FOR UPDATE",issuer);
   var pool=quotas.lockIssuerQuota(ctx,issuer,"CNY");
   db.update("UPDATE sv_issue_quota SET reserved_minor=13000 WHERE quota_no=?",text(pool,"quota_no"));
   throw BizException.of(ErrorCode.CONFLICT);
  })).isInstanceOf(BizException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_issue_quota WHERE issuer_no=?",Long.class,issuer)).isZero();
  db.write(()->{quotas.lockIssuerQuota(ctx,issuer,"CNY");return null;});
  assertThatThrownBy(()->jdbc.update("UPDATE sv_issue_quota SET store_no='S1' WHERE issuer_no=?",issuer)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class).satisfies(error->assertThat(((java.sql.SQLException)error.getCause()).getErrorCode()).isEqualTo(3819));
  assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE issuer_no=?",Long.class,issuer)).isZero();
 }
 String file(String name)throws Exception{return Files.readString(Path.of("../../shop-app/src/main/resources/db/migration",name));}
 void ddl(String source)throws Exception{try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8)));}}
}
