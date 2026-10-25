package ai.neargo.shop.scenario;
import ai.neargo.shop.common.*;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.pay.storedvalue.service.*;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import ai.neargo.shop.trade.dto.*;
import ai.neargo.shop.trade.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"shop.pay.stored-value.enabled=true","shop.pay.stored-value.issuer-mode=true","shop.pay.stored-value.brand-execution-enabled=true",
 "shop.pay.stored-value.cross-entity-enabled=true","shop.pay.stored-value.settlement-evidence-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","shop.pay.stored-value.settlement-evidence-key-id=p6-integration","shop.pay.entitlement.use.enabled=true","shop.member-code.enabled=true","shop.core.entitlement.authorization.enabled=true",
 "shop.pay.entitlement.cursor-key=p5-integration-cursor-key-more-than-thirty-two-characters",
 "spring.datasource.url=jdbc:h2:mem:p5-brand-integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class BrandStoredValueRedemptionTest {
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @MockitoBean StoredValueBrandRuntimeReadiness readiness;
 @MockitoBean ai.neargo.shop.pay.storedvalue.settlement.StoredValueCrossEntityReadiness financialReadiness;
 @Autowired StoredValueCollectionGuard collectionGuard;
 @Autowired ai.neargo.shop.merchant.brand.ManagementScopeService managementScopes;
 @Autowired ai.neargo.shop.merchant.brand.BrandFinancialChangeService financialJournal;
 @Autowired StoredValueFinancialMembershipService membership;
 @Autowired ai.neargo.shop.merchant.brand.StoredValueExecutionScopeService executionScopes;
 @Autowired ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementService settlementBills;
 @Autowired ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementDecisionService settlementDecisions;
 @Autowired ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementPaymentService settlementPayments;
 @Autowired StoredValueIssuerService issuers;@Autowired StoredValueBrandRechargeService recharges;@Autowired StoredValueBrandRechargeWriter writer;
 @Autowired StoredValuePolicyConfigurationService policies;@Autowired StoredValueReleaseService releases;@Autowired IssuerRepository db;
 @Autowired StoredValueIssuerQueryService wallets;@Autowired StoredValuePlanService plans;@Autowired StoredValuePlanRechargeService local;
 @Autowired StoredValueRechargeService receipts;@Autowired StoredValueUseQuotePort quotes;@Autowired EntitlementServiceOrderService orders;
 @Autowired EntitlementAuthorizationService consent;@Autowired EntitlementServiceUseCoordinator uses;@Autowired MemberCodeResolveService codes;@Autowired CustomerIdentityPort customers;
 @Autowired ai.neargo.shop.payclient.StoredValueConsumerQueryAppService consumer;
 static boolean installed;
 static String id(){return UUID.randomUUID().toString().replace("-","");}
 @BeforeEach void schema()throws Exception{
  if(installed)return;
  if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='sv_issuer'",Long.class)==0){
  ddl(file("V548__stored_value_issuer_foundation.sql"));
  String dimensions=file("V549__stored_value_issuer_ledger_dimensions.sql").replace("source_kind='V2_ACTIVITY'","source_kind IN ('V2_ACTIVITY','V2_BRAND_ACTIVITY')");
  ddl(dimensions);
  ddl("ALTER TABLE sv_release ADD COLUMN legacy_activity_version_no VARCHAR(64);ALTER TABLE sv_release_tier ADD COLUMN legacy_tier_key VARCHAR(128);");
  ddl(file("V564__stored_value_issue_policy.sql"));ddl(file("V565__stored_value_activity_governance.sql"));ddl(file("V566__stored_value_governed_plan_projection.sql").replace(" STORED",""));
  }
  ddl(file("V570__stored_value_clearing_entries.sql"));ddl(file("V572__stored_value_brand_quota_scope.sql"));
  jdbc.execute("ALTER TABLE sv_account DROP CONSTRAINT uk_sv_owner");jdbc.execute("ALTER TABLE sv_account DROP CONSTRAINT uk_sv_customer_owner");
  jdbc.execute("CREATE UNIQUE INDEX uk_sv_issuer_customer ON sv_account(tenant_no,issuer_no,customer_no,currency)");

  String provenance=file("V574__stored_value_brand_release_provenance.sql");ddl(provenance);
  ddl(file("V575__stored_value_settlement_and_reports.sql").replace(" STORED",""));
  ddl(file("V576__stored_value_financial_membership_saga.sql").replace(" STORED",""));
  installed=true;
 }
 record F(String entity,String a,String b,String brand,String issuer,String user,String actor,String person,String refA,String refB,Query qa,Query qb,StoredValueIssuerRecharges.Create create,EntitlementContext ctx){}
 F fixture(long cap){return fixture(cap,id());}
 F fixture(long cap,String actor){
  String entity=id(),a=id(),b=id(),brand=id(),user=id();
  for(String no:List.of(user,actor))jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no);
  jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) VALUES(?,?,'Beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity,actor);
  for(String store:List.of(a,b))jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) VALUES(?,?,'Store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,entity);
  String management=id();var route=new Route(null,entity,null,"MERCHANT_OFFLINE","OFFLINE");
  var issuer=issuers.configure(new AuthorizedScope("MAIN",actor,management,"BRAND",brand,0,null,Set.of(entity),List.of(route)),new Config("Brand",entity,"CENTRALIZED","PRINCIPAL_COMPONENT","PRO_RATA","CNY","Asia/Shanghai",List.of(route),0),id());
  jdbc.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER' WHERE issuer_no=?",issuer.issuerNo());
  for(String store:List.of(a,b))jdbc.update("INSERT INTO sv_issuer_store_access(tenant_no,access_no,issuer_no,store_no,operating_entity_no,source_binding_no,source_revision,can_issue,can_redeem,status,effective_from,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,?,?,0,1,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",id(),issuer.issuerNo(),store,entity,"B"+store,actor,actor);
  policies.configure(new PolicyAuthority("MAIN",actor,management,"SPACE",management,null,"CNY",0),new PolicyEdit(new Rules(true,false,3000,100000,30000,365,cap),0),id());
  var scope=new BrandScope("MAIN",management,brand,issuer.issuerNo(),"CNY",0);
  var prepared=releases.prepare(new StoredValueReleases.Prepare(scope,entity,actor,actor,"100 + 30",id(),"0","a".repeat(64),Instant.now().minusSeconds(5),Instant.now().plusSeconds(86400),"Asia/Shanghai",List.of(new StoredValueReleases.Tier("T1",10000,3000)),id()));
  releases.activate(new StoredValueReleases.Activate(scope,entity,actor,prepared.releaseNo(),db.one("SELECT activity_no FROM sv_release WHERE release_no=?",prepared.releaseNo()).get("activity_no").toString(),"0","a".repeat(64),prepared.contentHash(),id(),"b".repeat(64),null,id()));
  String refA=codes.resolve(new MemberCodeScope("MAIN",entity,a,actor),codes.issue("MAIN",user).memberCode()).customerRef();
  String refB=codes.resolve(new MemberCodeScope("MAIN",entity,b,actor),codes.issue("MAIN",user).memberCode()).customerRef();
  var ctx=new EntitlementContext("MAIN",actor,id(),"0".repeat(64));
  var person=customers.resolveReference(ctx,new CustomerIdentityPort.ReferenceQuery(entity,a,refA));
  var qa=new Query(new ExecutionScope("MAIN",actor,management,entity,a,0,brand,"B"+a,0,"CNY"),new Customer(person.customerNo(),user));
  var qb=new Query(new ExecutionScope("MAIN",actor,management,entity,b,0,brand,"B"+b,0,"CNY"),qa.customer());
  var offer=recharges.options(qa,null,20).records().getFirst();
  var create=new StoredValueIssuerRecharges.Create(qa,offer.optionRef(),"c".repeat(64),"Store A");
  return new F(entity,a,b,brand,issuer.issuerNo(),user,actor,person.customerNo(),refA,refB,qa,qb,create,ctx);
 }
 void local(F f,String store,long paid){
  var route=new Route(null,f.entity(),store,"MERCHANT_OFFLINE","OFFLINE");
  var i=issuers.configure(new AuthorizedScope("MAIN",f.actor(),f.qa().scope().managementNo(),"STORE",store,0,f.entity(),Set.of(f.entity()),List.of(route)),new Config("Local",f.entity(),"STORE_LOCAL","INTERNAL_RECON","LEGACY_SNAPSHOT","CNY","Asia/Shanghai",List.of(route),0),id());
  jdbc.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER' WHERE issuer_no=?",i.issuerNo());
  var p=plans.create(new CatalogScope("MAIN",f.entity(),f.actor(),false),new StoredValuePlans.Create(store,"Local","CNY",paid,0),id());
  local.confirmOffline(new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64)),f.entity(),new StoredValuePlanRecharges.Create(f.user(),store,"Store",p.planNo(),p.versionNo(),"d".repeat(64),f.person()));
 }
 long balance(String issuer){return jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE issuer_no=?",Long.class,issuer);}
 @Test void chooseOneWalletOnlyAndSwitchStorePreservesBrandWallet(){
  var f=fixture(1000000);local(f,f.a(),6000);local(f,f.b(),2000);var recharge=recharges.confirm(f.ctx(),f.entity(),f.create());assertThat(recharge.status().name()).isEqualTo("CREDITED");
  assertThat(wallets.accounts(f.qa()).stream().map(w->w.account().balanceMinor())).containsExactly(6000L,13000L);
  var atB=wallets.accounts(f.qb());assertThat(atB.stream().map(w->w.account().balanceMinor())).containsExactly(2000L,13000L);
  var selected=atB.get(1);String goods=id(),sku=id();
  jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,tenant_no,created_at,updated_at) VALUES(?,?,?,'Care','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",goods,f.entity(),f.b());
  jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,created_at,updated_at) VALUES(?,?,?,'CN',5000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sku,goods,f.entity());
  var ctx=new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64));
  String freshRef=codes.resolve(new MemberCodeScope("MAIN",f.entity(),f.b(),f.actor()),codes.issue("MAIN",f.user()).memberCode()).customerRef();
  var order=orders.createMonetarySelected(ctx,f.entity(),new ServiceOrderCreate(freshRef,f.b(),List.of(new ServiceOrderCreate.Line(sku,1))),id(),"e".repeat(64));
  var quote=quotes.quote(ctx,order.fact().source(),new Choice(f.qb(),selected.issuerRef(),selected.accountRef()));assertThat(quote.redeemable()).isTrue();
  order=orders.bindMoneyPreview(ctx,f.entity(),f.b(),order.serviceOrderNo(),quote);
  consent.confirm("MAIN",f.user(),order.serviceOrderNo(),new AuthorizationCommand(order.fact().source().sourceRevision(),quote.quoteRef(),quote.snapshotHash(),id()));
  var used=uses.submit(ctx,f.entity(),f.b(),new ServiceUseSubmit(order.serviceOrderNo(),quote.quoteRef(),null,quote.assetRevision()));
  assertThat(used.status().name()).isEqualTo("COMMITTED");assertThat(balance(f.issuer())).isEqualTo(8000);
  assertThat(wallets.accounts(f.qa()).getFirst().account().balanceMinor()).isEqualTo(6000);
  assertThat(wallets.accounts(f.qb()).getFirst().account().balanceMinor()).isEqualTo(2000);
  uses.submit(ctx,f.entity(),f.b(),new ServiceUseSubmit(order.serviceOrderNo(),quote.quoteRef(),null,quote.assetRevision()));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_clearing_entry WHERE usage_no=?",Long.class,used.usageNo())).isEqualTo(1);
  var c=new EntitlementContext("MAIN",f.user(),id(),"0".repeat(64));
  var directory=consumer.issuers(c,null,20).records();assertThat(directory.stream().filter(i->"BRAND".equals(i.scopeType()))).hasSize(1);
  var brand=directory.stream().filter(i->"BRAND".equals(i.scopeType())).findFirst().orElseThrow();
  var records=consumer.issuerRecords(c,brand.issuerRef(),"ALL",null,null,null,null,20).records();
  assertThat(records.getFirst().storeNo()).isEqualTo(f.b());assertThat(records.getFirst().signedAmountMinor()).isEqualTo(-5000);
 }
 @Test void receivedUnknownResumesFrozenOriginalAfterPolicyAndStoreAccessChange(){
  var f=fixture(1000000);var created=db.write(()->writer.create(f.ctx(),f.entity(),f.create()));
  var submitted=receipts.submit(new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64)),f.entity(),new StoredValuePort.SubmitRecharge(created.rechargeNo(),created.revision(),10000,StoredValuePort.CollectionMethod.MERCHANT_OFFLINE,id(),"f".repeat(64)));
  receipts.receive(new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64)),f.entity(),new StoredValuePort.ReceiveRecharge(created.rechargeNo(),submitted.revision(),"f".repeat(64),"Test receipt"));
  jdbc.update("UPDATE sv_issuer SET status='ISSUE_STOPPED' WHERE issuer_no=?",f.issuer());jdbc.update("UPDATE sv_issuer_store_access SET can_issue=0,can_redeem=0 WHERE issuer_no=?",f.issuer());
  assertThat(recharges.recover(f.ctx(),f.entity(),created.rechargeNo())).isTrue();recharges.recover(f.ctx(),f.entity(),created.rechargeNo());
  assertThat(balance(f.issuer())).isEqualTo(13000);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?",Long.class,created.rechargeNo())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT reserved_minor FROM sv_issue_quota WHERE issuer_no=?",Long.class,f.issuer())).isZero();
 }
 @Test void limitIncludesBonusAndCancellingNeverCreatesFunds(){
  var f=fixture(20000);recharges.confirm(f.ctx(),f.entity(),f.create());
  assertThatThrownBy(()->recharges.confirm(new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64)),f.entity(),f.create())).isInstanceOf(BizException.class);
  assertThat(balance(f.issuer())).isEqualTo(13000);assertThat(jdbc.queryForObject("SELECT outstanding_minor FROM sv_issue_quota WHERE issuer_no=?",Long.class,f.issuer())).isEqualTo(13000);
  var key=new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64));var replay=new StoredValueIssuerRecharges.Replay(f.a(),StoredValueFacts.digest(f.create().optionRef(),json),f.create().customerReferenceHash());
  assertThat(recharges.cancelCreation(key,f.entity(),replay).status().name()).isEqualTo("CANCELLED");
  assertThat(recharges.confirm(key,f.entity(),f.create()).status().name()).isEqualTo("CANCELLED");assertThat(balance(f.issuer())).isEqualTo(13000);
 }
 @Test void nullableConsumerIdentityOpensOnlyTheCanonicalBrandWallet(){
  var f=fixture(1000000);var q=new Query(f.qa().scope(),new Customer(id(),null));
  var offer=recharges.options(q,null,20).records().getFirst();
  var c=new StoredValueIssuerRecharges.Create(q,offer.optionRef(),"a".repeat(64),"Store");
  recharges.confirm(new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64)),f.entity(),c);
  var rows=jdbc.queryForList("SELECT customer_no,user_no,store_no FROM sv_account WHERE issuer_no=?",f.issuer());
  assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("customer_no")).isEqualTo(q.customer().customerNo());assertThat(rows.getFirst().get("user_no")).isNull();assertThat(rows.getFirst().get("store_no")).isNull();
 }
 String file(String name)throws Exception{return Files.readString(Path.of("src/main/resources/db/migration",name));}
 void ddl(String ddl){
  ddl=ddl.replace(" DROP CHECK "," DROP CONSTRAINT ").replaceAll("(?m)^--.*$","").replace(" JSON"," VARCHAR(30000)").replaceAll(" ENGINE=InnoDB[^;]*","");
  var in=java.util.regex.Pattern.compile("([a-z_]+) IN \\(([^()]*)\\)").matcher(ddl);var out=new StringBuffer();
  while(in.find()){String expression="CASE "+in.group(1);for(String item:in.group(2).split(","))expression+=" WHEN "+item.trim()+" THEN TRUE";expression+=" ELSE FALSE END";in.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(expression));}in.appendTail(out);
  for(String part:out.toString().split(";"))if(!part.isBlank()){var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);if(m.find())part=part.replaceAll("KEY ((?:uk|ix)_[0-9]+)\\b","KEY "+m.group(1)+"_$1");jdbc.execute(part);}
 }
 @Test void actualCorePayMembershipRecoversLostCommitAckUsingTheSameFrozenHash(){
  org.mockito.Mockito.when(financialReadiness.schemaReady()).thenReturn(true);var f=fixture(1000000);String account=id(),store=id();
  jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,status,tenant_no,created_at,updated_at) VALUES(?,?,?,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",account,f.entity(),f.actor());
  var actor=new ai.neargo.shop.merchant.brand.ManagementModels.Actor("MAIN","MCH:"+account);String management=managementScopes.create(actor,"Integration",account,id()).get("managementNo").toString();jdbc.update("INSERT INTO mch_brand(tenant_no,brand_no,management_no,name,name_key,status,scope_revision,create_key,create_hash,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,'Integration brand','Integration brand','ACTIVE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",f.brand(),management,id(),"0".repeat(64),actor.actorKey(),actor.actorKey());jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) VALUES(?,?,'New store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,f.entity());
  for(String permission:List.of("store.read","store.brand.change"))managementScopes.grant(actor,management,new ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand(actor.actorKey(),"STORE",store,permission,null,null,false,null,false));
  local(f,store,500);String localIssuer=jdbc.queryForObject("SELECT issuer_no FROM sv_issuer WHERE scope_type='STORE' AND scope_no=?",String.class,store);jdbc.update("INSERT INTO sv_issuer_store_access(tenant_no,access_no,issuer_no,store_no,operating_entity_no,source_binding_no,source_revision,can_issue,can_redeem,status,effective_from,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,?,?,?,?,0,1,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",id(),localIssuer,store,f.entity(),store,f.actor(),f.actor());
  var boundary=org.mockito.Mockito.mock(ai.neargo.shop.payclient.StoredValueFinanceAppService.class);var remoteJson=new ObjectMapper();var lost=new java.util.concurrent.atomic.AtomicBoolean(true);
  org.mockito.Mockito.when(boundary.membershipTargets(org.mockito.ArgumentMatchers.any())).thenAnswer(i->membership.describe(remoteJson.readValue(json.writeValueAsString(i.getArgument(0)),StoredValueMembership.Describe.class)));
  org.mockito.Mockito.when(boundary.prepareMembership(org.mockito.ArgumentMatchers.any())).thenAnswer(i->json.readValue(remoteJson.writeValueAsString(membership.prepare(remoteJson.readValue(json.writeValueAsString(i.getArgument(0)),StoredValueMembership.Prepare.class))),StoredValueMembership.Ack.class));
  org.mockito.Mockito.when(boundary.commitMembership(org.mockito.ArgumentMatchers.any())).thenAnswer(i->{var ack=membership.commit(remoteJson.readValue(json.writeValueAsString(i.getArgument(0)),StoredValueMembership.Commit.class));if(lost.getAndSet(false))throw new IllegalStateException("lost commit acknowledgement");return json.readValue(remoteJson.writeValueAsString(ack),StoredValueMembership.Ack.class);});
  var coordinator=new ai.neargo.shop.merchantbridge.BrandFinancialChangeCoordinator(financialJournal,boundary);String key=id();assertThatThrownBy(()->coordinator.start(actor,management,store,f.brand(),0,"join",key)).isInstanceOf(IllegalStateException.class);
  String change=jdbc.queryForObject("SELECT change_no FROM mch_brand_financial_change WHERE store_no=?",String.class,store);assertThat(jdbc.queryForObject("SELECT status FROM sv_issuer_store_access WHERE issuer_no=? AND store_no=?",String.class,f.issuer(),store)).isEqualTo("ACTIVE");assertThat(jdbc.queryForObject("SELECT status FROM mch_brand_store_binding WHERE store_no=?",String.class,store)).isEqualTo("PREPARING");assertThatThrownBy(()->executionScopes.current("MAIN",actor.actorKey(),f.entity(),store,"CNY")).isInstanceOf(BizException.class);
  org.mockito.Mockito.when(financialReadiness.schemaReady()).thenReturn(false);assertThat(coordinator.resume(actor,management,change).state()).isEqualTo("COMPLETED");assertThat(coordinator.start(actor,management,store,f.brand(),0,"join",key).state()).isEqualTo("COMPLETED");assertThat(executionScopes.current("MAIN",actor.actorKey(),f.entity(),store,"CNY").brandNo()).isEqualTo(f.brand());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_financial_membership_change WHERE change_no=?",Long.class,change)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE issuer_no=?",Long.class,f.issuer())).isZero();var localWallet=wallets.accounts(new Query(executionScopes.current("MAIN",f.actor(),f.entity(),store,"CNY"),f.qa().customer())).stream().filter(w->"STORE".equals(w.scopeType())).findFirst().orElseThrow();assertThat(localWallet.account().balanceMinor()).isEqualTo(500);assertThat(localWallet.canRedeem()).isTrue();assertThat(localWallet.unavailableReason()).isNull();
 }
 @Test void foreignStoreRechargeUsesCentralAuthorityAndKeepsWalletClearingAndOccurrenceSeparate(){
  org.mockito.Mockito.when(financialReadiness.capability()).thenReturn(new StoredValueSettlements.Capability(2,true,List.of()));var f=fixture(1000000);String operating=id();jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) VALUES(?,?,'Operating','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",operating,f.actor());jdbc.update("UPDATE mch_store SET entity_no=? WHERE store_no=?",operating,f.b());jdbc.update("UPDATE sv_issuer_store_access SET operating_entity_no=? WHERE issuer_no=? AND store_no=?",operating,f.issuer(),f.b());
  var original=f.qb().scope();var scope=new ExecutionScope("MAIN",f.actor(),original.managementNo(),operating,f.b(),0,f.brand(),"B"+f.b(),0,"CNY",0,2);var requirement=collectionGuard.requirement(scope);var proof=new StoredValueCollections.Proof(requirement,"MCH:"+f.actor(),0,Instant.now().plusSeconds(60));var query=new Query(scope,f.qa().customer(),proof);
  assertThat(recharges.options(new Query(scope,query.customer()),null,20).records()).isEmpty();var offer=recharges.options(query,null,20).records().getFirst();var command=new StoredValueIssuerRecharges.Create(query,offer.optionRef(),"c".repeat(64),"Foreign store");var ctx=new EntitlementContext("MAIN",f.actor(),id(),"0".repeat(64));var credited=recharges.confirm(ctx,operating,command);assertThat(credited.status().name()).isEqualTo("CREDITED");
  assertThat(jdbc.queryForObject("SELECT entity_no FROM sv_account WHERE issuer_no=?",String.class,f.issuer())).isEqualTo(f.entity());assertThat(jdbc.queryForObject("SELECT entity_no FROM sv_recharge WHERE recharge_no=?",String.class,credited.rechargeNo())).isEqualTo(operating);assertThat(jdbc.queryForObject("SELECT collection_entity_no FROM sv_recharge WHERE recharge_no=?",String.class,credited.rechargeNo())).isEqualTo(f.entity());assertThat(jdbc.queryForObject("SELECT recharge_store_no FROM sv_lot WHERE recharge_no=?",String.class,credited.rechargeNo())).isEqualTo(f.b());
  String goods=id(),sku=id();jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,tenant_no,created_at,updated_at) VALUES(?,?,?,'Care','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",goods,operating,f.b());jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,created_at,updated_at) VALUES(?,?,?,'CN',5000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sku,goods,operating);
  String customer=codes.resolve(new MemberCodeScope("MAIN",operating,f.b(),f.actor()),codes.issue("MAIN",f.user()).memberCode()).customerRef();var order=orders.createMonetarySelected(ctx,operating,new ServiceOrderCreate(customer,f.b(),List.of(new ServiceOrderCreate.Line(sku,1))),id(),"e".repeat(64));var selected=wallets.accounts(query).stream().filter(w->"BRAND".equals(w.scopeType())).findFirst().orElseThrow();var quote=quotes.quote(ctx,order.fact().source(),new Choice(query,selected.issuerRef(),selected.accountRef()));assertThat(quote.redeemable()).isTrue();order=orders.bindMoneyPreview(ctx,operating,f.b(),order.serviceOrderNo(),quote);consent.confirm("MAIN",f.user(),order.serviceOrderNo(),new AuthorizationCommand(order.fact().source().sourceRevision(),quote.quoteRef(),quote.snapshotHash(),id()));var used=uses.submit(ctx,operating,f.b(),new ServiceUseSubmit(order.serviceOrderNo(),quote.quoteRef(),null,quote.assetRevision()));assertThat(used.status().name()).isEqualTo("COMMITTED");assertThat(balance(f.issuer())).isEqualTo(8000);
  assertThat(jdbc.queryForObject("SELECT payer_entity_no FROM sv_clearing_entry WHERE usage_no=?",String.class,used.usageNo())).isEqualTo(f.entity());assertThat(jdbc.queryForObject("SELECT payee_entity_no FROM sv_clearing_entry WHERE usage_no=?",String.class,used.usageNo())).isEqualTo(operating);assertThat(jdbc.queryForObject("SELECT payable_minor FROM sv_clearing_entry WHERE usage_no=?",Long.class,used.usageNo())).isEqualTo(3847);
  // Existing allocation floors the bonus share; the principal share and payable therefore equal 3847, not 3846.
  var permissions=Set.of("sv.settlement.generate","sv.settlement.read","sv.settlement.reconcile","sv.settlement.payment.register","sv.settlement.receipt.confirm");var grant=List.of(new StoredValueSettlements.IssuerGrant(f.issuer(),"BRAND",f.brand()));var payer=new StoredValueSettlements.Authority("MAIN","MCH:FINANCE-PAYER","USER:FINANCE-PAYER",scope.managementNo(),grant,Map.of(f.entity(),permissions),Set.of());var payee=new StoredValueSettlements.Authority("MAIN","MCH:FINANCE-PAYEE","USER:FINANCE-PAYEE",scope.managementNo(),grant,Map.of(operating,permissions),Set.of());
  var bill=settlementBills.generate(new StoredValueSettlements.Generation(payer,f.issuer(),new StoredValueReports.Period(Instant.now().minusSeconds(3600),Instant.now()),"CNY",id())).bills().getFirst();assertThat(bill.payableMinor()).isEqualTo(3847);bill=settlementDecisions.reconcile(new StoredValueSettlements.Decision(payer,bill.billNo(),bill.revision(),"PAYER",null,id()));bill=settlementDecisions.reconcile(new StoredValueSettlements.Decision(payee,bill.billNo(),bill.revision(),"PAYEE",null,id()));assertThat(bill.status()).isEqualTo("RECONCILED");
  var payment=settlementPayments.register(new StoredValueSettlements.Registration(payer,bill.billNo(),bill.revision(),bill.payableMinor(),Instant.now(),"TRANSFER","ISOLATED-P6-"+id(),List.of(),id()));assertThat(payment.status()).isEqualTo("REGISTERED");assertThat(jdbc.queryForObject("SELECT confirmed_receipt_minor FROM sv_settlement_bill WHERE bill_no=?",Long.class,bill.billNo())).isZero();settlementPayments.confirm(new StoredValueSettlements.Receipt(payee,payment.paymentNo(),payment.revision(),Instant.now(),id()));assertThat(jdbc.queryForObject("SELECT status FROM sv_settlement_bill WHERE bill_no=?",String.class,bill.billNo())).isEqualTo("SETTLED");assertThat(balance(f.issuer())).isEqualTo(8000);

 }
}
