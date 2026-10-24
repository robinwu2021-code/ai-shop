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
 "shop.pay.entitlement.use.enabled=true","shop.member-code.enabled=true","shop.core.entitlement.authorization.enabled=true",
 "shop.pay.entitlement.cursor-key=p5-integration-cursor-key-more-than-thirty-two-characters",
 "spring.datasource.url=jdbc:h2:mem:p5-brand-integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class BrandStoredValueRedemptionTest {
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @MockitoBean StoredValueBrandRuntimeReadiness readiness;
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
  // Spring loads the complete generated schema, including all P5 migration tables and constraints.
  // This fixture only simulates the separately approved P3 account-key cutover for brand wallets.
  jdbc.execute("ALTER TABLE sv_account DROP CONSTRAINT uk_sv_owner");jdbc.execute("ALTER TABLE sv_account DROP CONSTRAINT uk_sv_customer_owner");
  jdbc.execute("CREATE UNIQUE INDEX uk_sv_issuer_customer ON sv_account(tenant_no,issuer_no,customer_no,currency)");

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
}
