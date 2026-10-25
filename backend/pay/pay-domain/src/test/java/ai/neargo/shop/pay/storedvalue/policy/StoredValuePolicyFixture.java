package ai.neargo.shop.pay.storedvalue.policy;
import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueIssueQuotaServiceImpl;
import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueIssuerServiceImpl;


import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValueReleaseServiceImpl;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValueReleasePlanProjectionServiceImpl;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValuePolicyConfigurationServiceImpl;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValueIssuePolicyServiceImpl;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValueGovernedIssuanceServiceImpl;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.pay.storedvalue.entity.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
class StoredValuePolicyFixture {
 final DriverManagerDataSource ds=dataSource();
 final boolean nativeMysql=System.getProperty("p4.mysql.url")!=null;
 static DriverManagerDataSource dataSource(){
  String url=System.getProperty("p4.mysql.url");if(url==null)return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p4_sv_[0-9_]+\\?.*"))throw new IllegalArgumentException("Isolated P4 MySQL database required");
  try{return new DriverManagerDataSource(url,System.getProperty("p4.mysql.user"),Files.readString(Path.of(System.getProperty("p4.mysql.password-file"))).trim());}catch(java.io.IOException missing){throw new IllegalStateException("Missing isolated test credentials");}
 }
 final JdbcTemplate jdbc=new JdbcTemplate(ds);final IssuerRepository db=new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper());
 final StoredValueIssuePolicyEvaluator evaluator=new StoredValueIssuePolicyEvaluator();final StoredValueIssuePolicyService policies=new StoredValueIssuePolicyServiceImpl(db,evaluator);final StoredValuePolicyConfigurationService configs=new StoredValuePolicyConfigurationServiceImpl(db,evaluator);final StoredValueIssueQuotaService quotas=new StoredValueIssueQuotaServiceImpl(db);final StoredValueReleasePlanProjectionService projection=new StoredValueReleasePlanProjectionServiceImpl(db);final StoredValueReleaseService releases=new StoredValueReleaseServiceImpl(db,policies,evaluator,projection);final StoredValueGovernedIssuanceService issuance=new StoredValueGovernedIssuanceServiceImpl(db,policies,evaluator,quotas);
 final StoreScope scope=new StoreScope("MAIN","M1","S1",null,"CNY",0);final PolicyAuthority owner=new PolicyAuthority("MAIN","OWNER","M1","SPACE","M1",null,"CNY",1);
 StoredValuePolicyFixture()throws Exception{
  if(nativeMysql)for(String table:List.of("flyway_schema_history","pmt_sv_approval","pmt_sv_release_link","sv_recharge","sv_account","sv_issue_quota_reservation","sv_issue_quota","sv_customer_issuer_index","sv_release_tier","sv_release","sv_issuer_store_access","sv_issuer_version","sv_issuer","sv_recharge_plan_version","sv_recharge_plan","sv_issue_policy_version","sv_issue_policy"))jdbc.execute("DROP TABLE IF EXISTS "+table);
  execute(file("V533__store_recharge_plans.sql"));execute(file("V548__stored_value_issuer_foundation.sql"));
  String foundation=file("V549__stored_value_issuer_ledger_dimensions.sql");execute(foundation.substring(0,foundation.indexOf("ALTER TABLE sv_account")));
  execute("ALTER TABLE sv_recharge_plan ADD COLUMN issuer_no VARCHAR(64);ALTER TABLE sv_recharge_plan ADD COLUMN migrated_release_no VARCHAR(64);");
  execute("ALTER TABLE sv_release ADD COLUMN legacy_activity_version_no VARCHAR(64);ALTER TABLE sv_release_tier ADD COLUMN legacy_tier_key VARCHAR(128);");
  execute(file("V564__stored_value_issue_policy.sql"));
  // Core tables are covered in Core tests; only the additive Pay hash column is needed here.
  if(nativeMysql)execute(file("V565__stored_value_activity_governance.sql"));else execute("ALTER TABLE sv_release ADD COLUMN activity_version_hash CHAR(64);");execute(nativeMysql?file("V566__stored_value_governed_plan_projection.sql"):file("V566__stored_value_governed_plan_projection.sql").replace(" STORED",""));
  jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),account_no VARCHAR(64),store_no VARCHAR(64),currency VARCHAR(8),deleted INT)");
  jdbc.execute("CREATE TABLE sv_recharge(tenant_no VARCHAR(32),recharge_no VARCHAR(64) PRIMARY KEY,store_no VARCHAR(64),currency VARCHAR(8),principal_minor BIGINT,bonus_minor BIGINT,status VARCHAR(24),received_at TIMESTAMP,issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),release_no VARCHAR(64),tier_no VARCHAR(64),collection_entity_no VARCHAR(64),settlement_entity_no VARCHAR(64),quota_reservation_no VARCHAR(64),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,updated_at TIMESTAMP,updated_by VARCHAR(64))");
  var issuers=new StoredValueIssuerServiceImpl(db);var offline=new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Route(null,"E1","S1","MERCHANT_OFFLINE","OFFLINE");
  issuers.configure(new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope("MAIN","OWNER","M1","STORE","S1",1,"E1",Set.of("E1"),List.of(offline)),new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Config("Store","E1","CENTRALIZED","INTERNAL_RECON","PRO_RATA","CNY","Asia/Shanghai",List.of(offline),0),"ISSUER");
  jdbc.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER'");
  jdbc.update("INSERT INTO sv_issuer_store_access(tenant_no,access_no,issuer_no,store_no,operating_entity_no,source_binding_no,source_revision,can_issue,can_redeem,status,effective_from,created_at,updated_at,created_by,updated_by) SELECT 'MAIN','ACCESS',issuer_no,'S1','E1','S1',0,1,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OWNER','OWNER' FROM sv_issuer");
  jdbc.update("INSERT INTO sv_issue_quota(tenant_no,quota_no,store_no,currency,outstanding_minor,reserved_minor,version,deleted,created_at,updated_at,created_by,updated_by) VALUES('MAIN','Q1','S1','CNY',0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OWNER','OWNER')");
 }
 Rules rules(boolean approval,int bps,long cap){return new Rules(true,approval,bps,100000,100000,365,cap);}
 void root(Rules r){configs.configure(owner,new PolicyEdit(r,0),"ROOT");}
 Prepare command(long paid,long bonus){return new Prepare(scope,"E1","SUBMITTER","SUBMITTER","Offer","A1","0","a".repeat(64),Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400),"Asia/Shanghai",List.of(new Tier("T1",paid,bonus)),"PREPARE");}
 Activate activation(View v,Approval a){return new Activate(scope,"E1","APPROVER",v.releaseNo(),"A1","0","a".repeat(64),v.contentHash(),"AV1","b".repeat(64),a,"ACTIVATE");}
 View activate(long paid,long bonus){var prepared=releases.prepare(command(paid,bonus));return releases.activate(activation(prepared,prepared.approvalRequired()?new Approval("a".repeat(64),prepared.policyHash(),"SUBMITTER","APPROVER"):null));}
 SvRechargePlanVersion planVersion(){var row=db.one("SELECT * FROM sv_recharge_plan_version");var v=new SvRechargePlanVersion();v.setVersionNo(IssuerRepository.text(row,"version_no"));return v;}
 void recharge(String no,long paid,long bonus){recharge(no,paid,bonus,scope);}
 void recharge(String no,long paid,long bonus,StoreScope liveScope){var context=new EntitlementContext("MAIN","CLERK",no,"0".repeat(64));db.write(()->{
  var version=planVersion();issuance.beforePlanIssuer(context,"E1","S1",version.getVersionNo(),liveScope,no);
  db.one("SELECT issuer_no FROM sv_issuer WHERE tenant_no='MAIN' AND scope_no='S1' FOR UPDATE");
  jdbc.update("INSERT INTO sv_recharge(tenant_no,recharge_no,store_no,currency,principal_minor,bonus_minor,status) VALUES('MAIN',?,'S1','CNY',?,?,'WAIT_COLLECTION')",no,paid,bonus);
  var r=new SvRecharge();r.setRechargeNo(no);r.setStoreNo("S1");r.setCurrency("CNY");r.setCustomerNo("PS1");r.setUserNo(null);r.setPrincipalMinor(paid);r.setBonusMinor(bonus);issuance.freezePlan(context,"E1",version,r);return null;
 });}
 private String file(String name)throws Exception{return Files.readString(Path.of("../../shop-app/src/main/resources/db/migration",name));}
 private void execute(String ddl){if(nativeMysql){try(var connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(ddl.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.sql.SQLException e){throw new IllegalStateException(e);}return;}ddl=h2Checks(ddl).replaceAll("(?m)^--.*$","").replace(" JSON"," VARCHAR(20000)").replaceAll(" ENGINE=InnoDB[^;]*","");for(String part:ddl.split(";"))if(!part.isBlank()){
  var matcher=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);if(matcher.find())part=part.replaceAll("KEY ((?:uk|ix)_[0-9]+)\\b","KEY "+matcher.group(1)+"_$1");jdbc.execute(part);
 }}
 private static String h2Checks(String ddl){var matcher=java.util.regex.Pattern.compile("([a-z_]+) IN \\(([^()]*)\\)").matcher(ddl);var out=new StringBuffer();while(matcher.find()){var expression=new StringBuilder("(CASE ").append(matcher.group(1));for(String item:matcher.group(2).split(","))expression.append(" WHEN ").append(item.trim()).append(" THEN TRUE");expression.append(" ELSE FALSE END)");matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(expression.toString()));}matcher.appendTail(out);return out.toString();}
}
