package ai.neargo.shop.merchant;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.merchant.service.impl.StoreOpeningServiceImpl;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class StoreOpeningRecoveryMysqlTest {
    JdbcTemplate admin,jdbc;
    String schema;
    TransactionTemplate tx;
    MerchantAdminPort merchants;
    StoreAdminService stores;
    StoreCreationAccessService access;
    StoreOpeningService service;
    LoginUser user=LoginUser.merchantByUser("OPEN_OWNER","owner");
    MerchantAdminPort.QuickStartCommand command(String name) {
        return new MerchantAdminPort.QuickStartCommand("OPEN_OWNER",name,"Taipei","BAPP_TW_RETAIL","TW",null,null,"1F",null,null,null,"RETAIL","GENERAL_RETAIL","Operating entity");
    }
    @BeforeEach void setup() {
        String url=System.getenv("COMMERCE_MYSQL_URL"),dbUser=System.getenv("COMMERCE_MYSQL_USER"),pw=System.getenv("COMMERCE_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,dbUser,pw));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."));
        schema="store_open_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),dbUser,pw);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V667__store_opening_receipts.sql")).execute(ds);
        merchants=mock(MerchantAdminPort.class);stores=mock(StoreAdminService.class);access=mock(StoreCreationAccessService.class);
        when(access.access(user)).thenReturn(new StoreCreationAccessService.Access(true,false,List.of()));
        when(merchants.quickStart(any())).thenReturn(new MerchantAdminPort.QuickStartResult("E1","S1"));
        service=new StoreOpeningServiceImpl(jdbc,new ObjectMapper(),merchants,stores,access);
    }
    @AfterEach void cleanup() { if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema); }
    StoreOpeningService.Receipt open(String request,String name) {
        return tx.execute(s->service.open(user,request,null,null,command(name)));
    }
    @Test void committedReceiptRecoversLostResponseAndRejectsChangedIntent() {
        var result=open("OPEN-1","Shop");
        assertEquals(result,service.find(user,"OPEN-1"));
        assertEquals(result,open("OPEN-1","Shop"));
        assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->open("OPEN-1","Changed")).errorCode());
        verify(merchants,times(1)).quickStart(any());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BizException.class,()->service.find(LoginUser.merchantByUser("OTHER","other"),"OPEN-1")).errorCode());
    }
    @Test void failureRollsBackReceiptAndRetryUsesSameRequest() {
        when(merchants.quickStart(any())).thenThrow(BizException.of(ErrorCode.STORE_QUOTA_EXCEEDED));
        assertThrows(BizException.class,()->open("OPEN-2","Shop"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_opening_request",Integer.class));
        doReturn(new MerchantAdminPort.QuickStartResult("E1","S1")).when(merchants).quickStart(any());
        assertEquals("S1",open("OPEN-2","Shop").storeNo());
    }
    @Test void concurrentSameRequestRunsCreationOnce() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->open("OPEN-3","Shop"));
            var b=executor.submit(()->open("OPEN-3","Shop"));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        verify(merchants,times(1)).quickStart(any());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_opening_request",Integer.class));
    }
    @Test void branchReceiptKeepsItsChosenIndustryAndAddressFields() {
        jdbc.execute("CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),address_detail VARCHAR(64),industry_code VARCHAR(32),sub_industry_code VARCHAR(32),industry_text VARCHAR(32),sub_industry_text VARCHAR(32),lat_e6 INT,lng_e6 INT,coord_sys VARCHAR(16),deleted INT NOT NULL DEFAULT 0)");
        when(access.access(user)).thenReturn(new StoreCreationAccessService.Access(false,false,List.of(
            new StoreCreationAccessService.Source("E1","Entity","ANCHOR","TW",2,1,true))));
        var branch=mock(ai.neargo.shop.merchant.dto.StoreVO.class);when(branch.storeNo()).thenReturn("BRANCH");
        when(stores.create(eq("E1"),any(StoreAdminService.CreateCommand.class))).thenAnswer(call->{
            jdbc.update("INSERT INTO mch_store(tenant_no,entity_no,store_no) VALUES('MAIN','E1','BRANCH')");return branch;
        });
        var receipt=tx.execute(status->service.open(user,"BRANCH-REQ","E1","ANCHOR",command("Branch")));
        assertEquals("BRANCH",receipt.storeNo());
        assertEquals("GENERAL_RETAIL",jdbc.queryForObject("SELECT sub_industry_code FROM mch_store",String.class));
        assertEquals("1F",jdbc.queryForObject("SELECT address_detail FROM mch_store",String.class));
        verifyNoInteractions(merchants);
    }
    @Test void branchRequiresOwnedSourceAndCapacityWithoutCreatingEntity() {
        when(access.access(user)).thenReturn(new StoreCreationAccessService.Access(false,false,List.of(
            new StoreCreationAccessService.Source("E1","Entity","ANCHOR","TW",1,1,false))));
        var denied=assertThrows(BizException.class,()->tx.execute(s->service.open(user,"OPEN-4","E1","ANCHOR",command("Branch"))));
        assertEquals(ErrorCode.STORE_QUOTA_EXCEEDED,denied.errorCode());
        var foreign=assertThrows(BizException.class,()->tx.execute(s->service.open(user,"OPEN-4","FOREIGN","ANCHOR",command("Branch"))));
        assertEquals(ErrorCode.FORBIDDEN,foreign.errorCode());
        verifyNoInteractions(merchants,stores);
    }
}
