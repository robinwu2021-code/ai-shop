package ai.neargo.shop.merchant;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.plan.mapper.PlanMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.plan.mapper.PlanMappers.PlanDefMapper;
import ai.neargo.shop.merchant.port.MerchantPortImpl;
import ai.neargo.shop.merchant.service.EntityCreationService;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.impl.EntityCreationServiceImpl;
import ai.neargo.shop.spi.platform.MasterDataPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class EntityCreationMysqlTest {
    JdbcTemplate admin,jdbc;
    String schema;
    TransactionTemplate tx;
    EntityCreationService service;
    MerchantEntityService entities;
    LoginUser user=LoginUser.merchantByUser("ENTITY_OWNER","owner");

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("COMMERCE_MYSQL_URL"),dbUser=System.getenv("COMMERCE_MYSQL_USER");
        String password=System.getenv("COMMERCE_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,dbUser,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."));
        schema="entity_creation_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),dbUser,password);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String fixture=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("usr_account","mch_entity","mch_account","mch_store","mch_entity_plan","sys_merchant_plan_def")) {
            var match=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\n\\);").matcher(fixture);
            assertTrue(match.find(),table);
            String ddl = match.group();
            if ("mch_store".equals(table)) {
                ddl = ddl.replaceAll("(?m)^ +contact_(?:name|phone) [^\n]+,\n", "");
            }
            jdbc.execute(ddl);
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V674__store_contact_and_entity_creation.sql"),new ClassPathResource("db/migration/V675__entity_profile_initialization_receipt.sql")).execute(ds);
        jdbc.update("INSERT INTO usr_account(user_no,market,created_at,updated_at) VALUES('ENTITY_OWNER','TW',NOW(),NOW())");
        var config=new MybatisConfiguration();
        var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
        factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler())
                .setDbConfig(new GlobalConfig.DbConfig()));
        var sessions=new SqlSessionTemplate(factory.getObject());
        for(var mapper:List.of(MchEntityMapper.class,MchAccountMapper.class,EntityPlanMapper.class,PlanDefMapper.class))
            config.addMapper(mapper);
        var merchants=mock(MerchantPortImpl.class,CALLS_REAL_METHODS);
        merchants.setDefaultStoreReadDataSource(ds);
        ReflectionTestUtils.setField(merchants,"merchantMapper",sessions.getMapper(MchEntityMapper.class));
        ReflectionTestUtils.setField(merchants,"staffMapper",sessions.getMapper(MchAccountMapper.class));
        ReflectionTestUtils.setField(merchants,"entityPlanMapper",sessions.getMapper(EntityPlanMapper.class));
        ReflectionTestUtils.setField(merchants,"planDefMapper",sessions.getMapper(PlanDefMapper.class));
        entities=mock(MerchantEntityService.class);
        when(entities.detail(anyString(),anyString())).thenAnswer(call->{
            String owner=call.getArgument(0),no=call.getArgument(1);
            Integer owns=jdbc.queryForObject("SELECT COUNT(*) FROM mch_account WHERE user_no=? AND entity_no=? "
                    +"AND is_owner=1 AND status='ACTIVE' AND deleted=0",Integer.class,owner,no);
            if(owns!=1)throw BizException.of(ErrorCode.FORBIDDEN);
            return jdbc.queryForObject("SELECT entity_no,name,status,verified,legal_form FROM mch_entity WHERE entity_no=?",
                    (rs,n)->new EntityStoresVO(new EntityVO(rs.getString(1),rs.getString(2),rs.getString(3),
                            rs.getBoolean(4),rs.getString(5),0,true,true),List.of()),no);
        });
        var master=mock(MasterDataPort.class);when(master.canonicalSubject("ENTERPRISE")).thenReturn("ENTERPRISE");
        service=new EntityCreationServiceImpl(jdbc,new ObjectMapper(),merchants,entities,master);
    }
    @AfterEach void cleanup(){if(admin!=null && schema!=null)admin.execute("DROP DATABASE "+schema);}
    EntityCreationService.Result create(String key,String name){return tx.execute(s->service.create(user,key,name,"ENTERPRISE"));}

    @Test void createsIndependentPendingEntityAndRecoversReceipt() {
        var first=create("CREATE-1"," Independent ");var entity=first.entity();
        assertFalse(first.replayed());assertEquals("Independent",entity.name());
        assertEquals("PENDING_LICENSE",entity.status());assertEquals("NONE",entity.review());
        assertFalse(entity.verified());assertTrue(entity.canManage());assertEquals(0,entity.storeCount());
        assertEquals("TW",jdbc.queryForObject("SELECT market FROM mch_entity",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_store",Integer.class));
        assertEquals("FREE",jdbc.queryForObject("SELECT plan_code FROM mch_entity_plan",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_account WHERE is_owner=1 AND status='ACTIVE'",Integer.class));
        assertEquals(entity,create("CREATE-1"," Independent ").entity());
        assertEquals(entity,service.find(user,"CREATE-1").entity());
        assertTrue(service.find(user,"CREATE-1").replayed());
    }
    @Test void sameKeyConflictAndCurrentOwnerRevocation() {
        create("CREATE-2","One");
        assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->create("CREATE-2","Two")).errorCode());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BizException.class,
                ()->service.find(LoginUser.merchantByUser("OTHER","other"),"CREATE-2")).errorCode());
        jdbc.update("UPDATE mch_account SET status='DISABLED'");
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BizException.class,()->service.find(user,"CREATE-2")).errorCode());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BizException.class,()->create("CREATE-2","One")).errorCode());
    }
    @Test void concurrentRequestCreatesOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->create("CREATE-3","One"));var b=pool.submit(()->create("CREATE-3","One"));
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertEquals(first.entity(),second.entity());assertNotEquals(first.replayed(),second.replayed());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity",Integer.class));
    }
    @Test void failureRollsBackReceipt() {
        doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(entities).detail(anyString(),anyString());
        assertThrows(BizException.class,()->create("CREATE-4","One"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_creation_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_account",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity_plan",Integer.class));
    }
    @Test void concurrentDifferentRequestsRetainExistingEntityQuota() throws Exception {
        for(int i=0;i<4;i++)create("SEED-"+i,"Seed");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->quotaAttempt("LAST-A"));var b=pool.submit(()->quotaAttempt("LAST-B"));
            assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity",Integer.class));
    }
    private int quotaAttempt(String key) {
        try{create(key,"Last");return 1;}
        catch(BizException e){assertEquals(ErrorCode.ENTITY_QUOTA_EXCEEDED,e.errorCode());return 0;}
    }
}
