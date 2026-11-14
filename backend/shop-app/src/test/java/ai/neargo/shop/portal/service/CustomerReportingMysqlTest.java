package ai.neargo.shop.portal.service;

import ai.neargo.shop.portal.service.CustomerReportingService.*;
import ai.neargo.shop.portal.service.impl.CustomerReportingServiceImpl;
import ai.neargo.shop.common.BizException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*")
class CustomerReportingMysqlTest {
  JdbcTemplate admin,db;String schema;CustomerReportingService reporting;
  final Scope all=new Scope("MAIN",true,Set.of());
  final Scope one=new Scope("MAIN",false,Set.of("E1"));
  final Scope empty=new Scope("MAIN",false,Set.of());
  Filter filter(String store,String brand,long page,long size){return new Filter(null,store,brand,page,size);}
  @BeforeEach void setup(){
    var url=System.getenv("COMMERCE_MYSQL_URL");var user=System.getenv().getOrDefault("COMMERCE_MYSQL_USER","root");var password=System.getenv().getOrDefault("COMMERCE_MYSQL_PASSWORD","");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."));
    schema="customer_reporting_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,password);db=new JdbcTemplate(ds);CustomerReportingFixture.createAndSeed(db);reporting=new CustomerReportingServiceImpl(ds);
  }
  @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP DATABASE "+schema);}
  @Test void registrationsIncludeUnbuiltAccountsAndScopeBeforeCountsPagingAndTrend(){
    var r=reporting.registrations(all,filter(null,null,1,1));assertEquals(3,r.total());assertEquals(1,r.withoutStores());assertEquals(3,r.newLast30Days());assertEquals(30,r.trend().size());assertEquals("U0",r.page().records().getFirst().userNo());
    var scoped=reporting.registrations(one,filter(null,null,1,1));assertEquals(1,scoped.total());assertEquals(0,scoped.withoutStores());assertEquals("U1",scoped.page().records().getFirst().userNo());assertEquals(2,scoped.page().records().getFirst().storeCount());assertEquals(1,scoped.newLast30Days());
    assertEquals(0,reporting.registrations(empty,filter(null,null,1,20)).total());assertTrue(reporting.registrationStores(one,"U2").isEmpty());assertEquals(List.of("S1","S2"),reporting.registrationStores(one,"U1").stream().map(StoreLink::storeNo).toList());
  }
  @Test void unifiedPeoplePreserveOriginalSourceAndDoNotMergeSamePhoneTail(){
    var r=reporting.members(all,filter(null,null,1,20));assertEquals(3,r.total());assertEquals(3,r.records().stream().map(Person::personNo).distinct().count());
    var p=reporting.member(all,"PS1");assertEquals(2,p.person().membershipCount());assertEquals("S1",p.person().sourceStoreNo());assertEquals("SCAN",p.person().sourceType());assertEquals(100,p.person().joinedAt());assertEquals(3,p.sources().size());assertEquals(3,p.stores().size());
    var scoped=reporting.member(one,"PS1");assertEquals(1,scoped.person().membershipCount());assertEquals(List.of("M1"),scoped.memberships().stream().map(Membership::memberNo).toList());assertEquals(2,scoped.sources().size());assertEquals(2,scoped.stores().size());assertEquals("S1",scoped.person().sourceStoreNo());
    assertNull(reporting.member(one,"PS2").person().sourceStoreNo());assertEquals(2,reporting.members(one,new Filter("1234",null,null,1,20)).total());
  }
  @Test void memberRelationFiltersKeepOriginalSourceAndNeverFillPagesWithUnauthorizedPeople(){
    var store=reporting.members(one,filter("S2",null,1,1));assertEquals(1,store.total());assertEquals("PS1",store.records().getFirst().personNo());assertEquals("S1",store.records().getFirst().sourceStoreNo());
    assertEquals(1,reporting.members(one,filter(null,"B1",1,20)).total());assertEquals(0,reporting.members(one,filter("S3",null,1,20)).total());assertEquals(0,reporting.members(empty,filter(null,null,1,20)).total());assertThrows(BizException.class,()->reporting.member(one,"PS3"));
  }
  @Test void realBrandsUseEffectiveBindingsAndScopedStoreCounts(){
    var scoped=reporting.brands(one,filter(null,null,1,1));assertEquals(1,scoped.total());assertEquals("B1",scoped.records().getFirst().brandNo());assertEquals("MG1",scoped.records().getFirst().managementNo());assertEquals(List.of("S1"),scoped.records().getFirst().stores().stream().map(StoreLink::storeNo).toList());
    assertEquals(3,reporting.brands(all,filter(null,null,1,20)).total());assertEquals(0,reporting.brands(empty,filter(null,null,1,20)).total());
  }
  @Test void historicalStoreSourcesKeepIdentifiersWithoutLeakingCurrentOutOfScopeMetadata(){
    db.update("UPDATE mbr_member SET first_store_no='S3' WHERE member_no='M3'");
    db.update("INSERT INTO mbr_member_source(tenant_no,source_no,member_no,entity_no,source_type,store_no,occurred_at,is_first,created_at,updated_at) VALUES('MAIN','MOVED_SRC','M1','E1','ORDER','S3',600,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    // A mismatched denormalized entity anchor must not establish a store relation.
    db.update("INSERT INTO mbr_member_store(tenant_no,member_no,entity_no,store_no,created_at,updated_at) VALUES('MAIN','M3','E2','S2',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    db.update("INSERT INTO mbr_member_source(tenant_no,source_no,member_no,entity_no,source_type,store_no,occurred_at,is_first,created_at,updated_at) VALUES('MAIN','BAD_ANCHOR','M3','E2','ORDER','S2',700,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    assertEquals(0,reporting.members(one,filter("S3",null,1,20)).total());
    assertEquals(1,reporting.members(one,filter("S2",null,1,20)).total());
    var person=reporting.member(one,"PS1");assertEquals(List.of("S1","S2"),person.stores().stream().map(StoreLink::storeNo).toList());
    var moved=person.sources().stream().filter(src->src.sourceNo().equals("MOVED_SRC")).findFirst().orElseThrow();assertEquals("S3",moved.storeNo());assertNull(moved.storeName());
    var origin=reporting.member(one,"PS2");assertEquals("S3",origin.person().sourceStoreNo());assertNull(origin.person().sourceStoreName());assertNull(origin.memberships().getFirst().sourceStoreName());assertTrue(origin.sources().isEmpty());assertTrue(origin.stores().isEmpty());
  }
  @Test void operatingEntityBootstrapReturnsRealOwnerOnlyWithinTrustedScope(){
    var r=reporting.operatingEntity(one,"OE1");assertEquals("U1",r.ownerUserNo());assertEquals("TW",r.market());assertEquals(Set.of("PAYMENT_MERCHANT_CREATE"),r.allowedActions());assertEquals("U1",reporting.operatingEntity(one,"OE0").ownerUserNo());
    assertThrows(BizException.class,()->reporting.operatingEntity(one,"OE2"));assertThrows(BizException.class,()->reporting.operatingEntity(empty,"OE0"));assertThrows(BizException.class,()->reporting.operatingEntity(all,"OFF"));
    db.update("UPDATE usr_account SET status='BANNED' WHERE tenant_no='MAIN' AND user_no='U1'");assertThrows(BizException.class,()->reporting.operatingEntity(all,"OE1"));
  }
}
