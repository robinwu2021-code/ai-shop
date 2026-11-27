package ai.neargo.shop.pay.service.impl;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.fulfillment.CommerceFulfillmentService;
import ai.neargo.commerce.core.mall.DefaultMallFacade;
import ai.neargo.commerce.core.offline.DefaultOfflinePurchaseFacade;
import ai.neargo.commerce.core.spi.Period;
import ai.neargo.commerce.core.subscription.SubscriptionService;
import ai.neargo.commerce.persistence.*;
import ai.neargo.commerce.starter.http.MallHttpContextPort;
import ai.neargo.shop.commercebridge.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches=".+")
class EntityPlanUnifiedCheckoutMysqlTest {
  @ParameterizedTest @ValueSource(booleans={false,true})
  void paidStoreOrderActivatesFrozenRightsAndEntityQuota(boolean assisted) throws Exception {
    try (var ledger=new PlatformOfflineCollectionMysqlTest.Fixture(); var mall=new PlatformCollectionMysqlTest.Fixture()) {
      var host=ledger.base;
      EntityPlanOfflineLedgerMysqlTest.seedMerchant(host.jdbc);
      host.jdbc.execute("ALTER TABLE mch_store ADD COLUMN id BIGINT AUTO_INCREMENT UNIQUE");
      ledger.profile(1,"TWD");
      for (String slice : List.of("catalog","order","fulfillment","subscription","offline")) {
        String name="offline".equals(slice)?"offline_payment":"mall_"+slice;
        new ResourceDatabasePopulator(new ClassPathResource("db/mall/"+slice+"/V1__"+name+".sql")).execute(mall.ds);
      }
      seedCatalog(mall.jdbc,host.jdbc);
      var clock=Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ZoneOffset.UTC);
      var store=new StoreSnapshot(new StoreRef("MAIN","S1"),"E1","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1);
      CommerceIdGenerator ids=namespace -> UUID.randomUUID().toString().replace("-","");
      var events=mock(CommerceEventPersistencePort.class);
      when(events.recordInbound(any())).thenReturn(true);
      var catalog=new JdbcCatalogRepository(mall.ds,host.ds);
      var uow=new JdbcCommerceUnitOfWork(mall.ds,new DataSourceTransactionManager(mall.ds));
      var subscriptions=new JdbcSubscriptionRepository(mall.ds,ids,new JdbcEndActivation(mall.ds,host.ds,ids));
      uow.required(()->{
        subscriptions.ensureAccount(store,"buyer");
        subscriptions.insert(subscriptions.lock(store.store()),new Period("free","initial","FREE","FREE","FREE-V1","FREE",
            1000,1000,null,Map.of(),0,0,"TWD","policy",false),1,"buyer");
        return null;
      });
      var accounts=new JdbcSubscriptionAccountRepository(mall.ds);
      var orders=new JdbcMallRepository(mall.ds,events,ids);
      StoreDirectoryPort stores=ref->Optional.of(store);
      ActorAuthorizationPort permissions=(a,s,cap)->new AuthorizationDecision(true,"OWNER",Optional.empty());
      var mallApi=new DefaultMallFacade(orders,catalog,accounts,uow,stores,permissions,clock,ids);
      var collection=new ShopMallOfflineCollectionAdapter(host.service,ledger.refunds());
      var pay=new ShopMallPlatformPaymentAdapter(host.service,ledger.refunds(),ShopMallPayerBindings.UNAVAILABLE);
      var operations=new JdbcFulfillmentRepository(mall.ds,true);
      var delivery=new SubscriptionService(subscriptions,catalog,uow,new EndScheduleService(subscriptions,uow,events,ids,clock),events,ids,clock);
      var payment=new CommerceFulfillmentService(orders,operations,accounts,uow,delivery,pay,mock(HardwareInventoryPort.class),
          events,clock,ids,"platform-mch",Set.of("OFFLINE:BANK_TRANSFER"),permissions,(s,e,o,b,t)->{});
      var media=mock(PrivateEvidencePort.class,CALLS_REAL_METHODS);
      when(media.stored(any(),anyString())).thenReturn(true);
      var offline=new DefaultOfflinePurchaseFacade(orders,operations,new JdbcMallOfflineRepository(mall.ds),accounts,uow,
          stores,permissions,mallApi,payment,null,collection,media,clock,ids,"profile");
      var ctx=mock(MallHttpContextPort.class);
      var owner=new Actor("U1","E1","S1",false);
      var ops=new Actor("ops","E1",null,true);
      when(ctx.operator(anyString())).thenReturn(new ActorContext("MAIN",SubjectKind.OPERATOR,"ops","session",new RawPrincipalIdentity("OPERATOR","OPS","ops")));
      when(ctx.buyer(eq("S1"),anyString())).thenReturn(new MallHttpContextPort.Buyer(new ActorContext("MAIN",SubjectKind.ACCOUNT,"U1","session",
          new RawPrincipalIdentity("MERCHANT","USR","U1")),store.store()));
      var json=new ObjectMapper();
      var checkout=new ShopEntityPlanCheckout(mall.jdbc,json,ctx,mallApi,offline,payment);
      var service=new EntityPlanPurchaseServiceImpl(host.jdbc,new DataSourceTransactionManager(host.ds),json,pay,collection,media,
          (t,c)->Optional.of(new MoneyPolicy("twd","TWD",2,100,100)),mock(StoreOwnershipWritePort.class),
          e->true,clock,"profile",mock(StoreOperationalReviewService.class),mall.jdbc,checkout);
      var amount=new Money("TWD",19900);
      // Existing sale rows predate the catalog binding.
      var prior=new Offer("P30",1,"PRO","月包",30,amount,3,10,false,true);
      host.jdbc.update("INSERT INTO mch_plan_offer VALUES('MAIN','P30',1,JSON_REMOVE(CAST(? AS JSON),'$.catalog'))",json.writeValueAsString(prior));
      assertTrue(service.offers(owner).isEmpty());
      assertThrows(ai.neargo.shop.common.BizException.class,()->service.saveOffer(ops,
          new OfferEdit("P30",1,"PRO","月包",31,amount,true,"SV1")));
      var offer=service.saveOffer(ops,new OfferEdit("P30",1,"PRO","月包",30,amount,true,"SV1"));
      assertEquals(List.of("POS_USE"),offer.catalog().entitlementCodes());
      var request=new Buy("REQ","P30",offer.version());
      var assistance=new AssistedBuy("REQ","P30",offer.version(),"S1","MA1");
      var order=assisted?service.assistedBuy(ops,assistance):service.buy(owner,request);
      var no=order.orderNo();
      assertEquals(no,(assisted?service.assistedBuy(ops,assistance):service.buy(owner,request)).orderNo());
      assertEquals(assisted?"PLATFORM":"MERCHANT",mall.jdbc.queryForObject("SELECT buyer_kind FROM mall_order WHERE order_no=? AND store_no='S1' AND stage='ORDERED'",String.class,no));
      assertEquals("SV1",mall.jdbc.queryForObject("SELECT sku_version_no FROM mall_order_item WHERE order_no=?",String.class,no));
      assertEquals(0,host.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant",Integer.class));
      var at=clock.instant();
      if (!assisted) service.submit(owner,no,new Claim("C",amount,at,"BANK",service.reserveEvidence(owner,no)));
      if (assisted) {
        var operator=ctx.operator("commerce:mall:offline:confirm");
        var target=new ai.neargo.commerce.api.mall.OrderQuery(store.store(),no);
        var evidence=offline.reserveEvidence(operator,target,"PLATFORM_RECEIPT");
        var review=new ai.neargo.commerce.api.offline.OfflinePurchaseFacade.ReviewCommand(target,"CONF",null,
            amount,at,"platform-mch","BANK",evidence.assetKey(),false,"verified");
        var receipt=offline.confirm(operator,review);
        assertEquals("APPLIED",receipt.status());
        assertEquals(0,host.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant",Integer.class));
        assertEquals(Optional.of(true),service.recoverPayment("MAIN",no,receipt.paymentNo()));
        assertEquals(Optional.of(true),service.recoverPayment("MAIN",no,receipt.paymentNo()));
      } else {
        var confirm=new Confirm("CONF","C",amount,at,"BANK",service.reserveEvidence(ops,no),"verified");
        host.jdbc.execute("CREATE TRIGGER unavailable BEFORE INSERT ON mch_store_end_grant FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='activation unavailable'");
        assertEquals("PAID_PENDING",service.confirm(ops,no,confirm).status());
        assertEquals(0,host.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant",Integer.class));
        host.jdbc.execute("DROP TRIGGER unavailable");
        mall.jdbc.update("UPDATE mall_fulfillment_task SET next_retry_at=0 WHERE task_type='END_ACTIVATION'");
        new JdbcEndActivation(mall.ds,host.ds,ids).retryPendingAll(10);
        assertEquals("APPLIED",service.confirm(ops,no,confirm).status());
      }
      assertEquals("COMPLETED",mall.jdbc.queryForObject("SELECT fulfillment_status FROM mall_order WHERE order_no=? AND pay_status='PAID' AND cash_paid_minor=19900",String.class,no));
      assertEquals(1,host.jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS'",Integer.class));
      assertEquals(1,host.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant WHERE store_no='S1' AND entitlement_code='POS_USE' AND state IN ('ACTIVE','SCHEDULED')",Integer.class));
      assertEquals("PRO",host.jdbc.queryForObject("SELECT plan_code FROM mch_entity_plan WHERE entity_no='E1'",String.class));
    }
  }

  private static void seedCatalog(JdbcTemplate db,JdbcTemplate host) throws Exception {
    String grants=Files.readString(Path.of("src/test/resources/db/n_platform/V1__platform_design.sql"));
    int start=grants.indexOf("CREATE TABLE `mch_store_end_grant`");
    host.execute(grants.substring(start,grants.indexOf(';',start)));
    try (var files=Files.list(Path.of("src/main/resources/db/n_platform"))) {
      host.execute(Files.readString(files.filter(p->p.getFileName().toString().startsWith("V3__")).findFirst().orElseThrow()));
    }
    host.execute("CREATE TABLE sys_dict_item(tenant_no varchar(32),dict_type varchar(32),item_level varchar(16),item_code varchar(64),name varchar(64),sort_no int,status varchar(16),version bigint,attrs json,deleted tinyint)");
    host.update("INSERT INTO sys_dict_item VALUES('MAIN','BUSINESS_TYPE','TYPE','RETAIL','Retail',1,'ENABLED',1,JSON_OBJECT('plan_catalog_version',1),0)");
    String base="tenant_no,created_at,updated_at,version,deleted,";
    String values=" VALUES('MAIN',NOW(),NOW(),1,0,";
    db.update("INSERT INTO mall_product("+base+"product_no,category_code,product_type,name,allowed_business_types,sale_status,sort)"+values+"'P','SUB','BUNDLE','plan','[\"RETAIL\"]','ON_SALE',1)");
    db.update("INSERT INTO mall_sku("+base+"sku_no,product_no,sku_name,current_version_no,sale_status,max_per_order)"+values+"'SKU','P','plan','SV1','ON_SALE',1)");
    db.update("INSERT INTO mall_sku_version("+base+"sku_version_no,sku_no,max_per_order_snapshot,sku_name_snapshot,revision,status,currency,list_price_minor,sale_price_minor,allowed_business_types,refund_policy_snapshot)"+values+"'SV1','SKU',1,'plan',1,'PUBLISHED','TWD',19900,19900,'[\"RETAIL\"]','{}')");
    db.update("INSERT INTO mall_sku_component("+base+"component_no,sku_version_no,component_seq,component_type,plan_version_no,period_days,unit_count,allocation_weight,is_gift,activation_policy)"+values+"'C1','SV1',1,'PLAN','PV1',30,1,100,0,'IMMEDIATE')");
    db.update("INSERT INTO sys_industry_plan("+base+"plan_no,version_no,business_type_code,tier_code,`rank`,sort,sale_status,effective_from,revision,display_name,status,policy_version)"+values+"'PRO','PV1','RETAIL','PRO',1,1,'ON_SALE',1000,1,'Pro','ACTIVE','p1')");
    db.update("INSERT INTO sys_plan_version_end("+base+"plan_version_no,end_code,entitlement_code,sort)"+values+"'PV1','POS','POS_USE',1)");
    db.update("INSERT INTO mall_end_entitlement("+base+"end_code,entitlement_code,name,scope,allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id,route_policy_ref,acceptance_ref)"+values+"'POS','POS_USE','POS','STORE','[\"RETAIL\"]','GA',1,0,1,'pos-client','route','receipt')");
  }
}
