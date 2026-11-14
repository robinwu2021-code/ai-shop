package ai.neargo.shop.merchant;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.PayMerchantMirror;
import ai.neargo.shop.merchant.service.impl.StoreSettingsServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class StoreSettingsMysqlTest {
    JdbcTemplate admin,jdbc;String schema;TransactionTemplate tx;StoreSettingsServiceImpl service;
    @BeforeEach void setup() throws Exception {
        var url=System.getenv("COMMERCE_MYSQL_URL");var user=System.getenv("COMMERCE_MYSQL_USER");var pw=System.getenv("COMMERCE_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,pw));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."));
        schema="store_settings_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,pw);jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE mch_store (id BIGINT PRIMARY KEY AUTO_INCREMENT,tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),name VARCHAR(64),address VARCHAR(255),address_detail VARCHAR(64),open_hours VARCHAR(512),deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO mch_store(tenant_no,entity_no,store_no,name,address,address_detail,open_hours) VALUES('MAIN','E1','S1','Original','Taipei','1F','09:00-18:00')");
        var configuration=new MybatisConfiguration();configuration.addMapper(MchStoreMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MchStoreMapper.class);
        var profiles=mock(MerchantStoreService.class);
        when(profiles.profile("E1","S1")).thenAnswer(call->jdbc.queryForObject("SELECT name,address,address_detail,open_hours FROM mch_store WHERE store_no='S1'",(rs,n)->{
            var p=mock(StoreProfileVO.class);when(p.name()).thenReturn(rs.getString(1));when(p.address()).thenReturn(rs.getString(2));
            when(p.addressDetail()).thenReturn(rs.getString(3));when(p.openHours()).thenReturn(rs.getString(4));return p;
        }));
        var ownership=mock(StoreOwnershipWritePort.class);
        doAnswer(call->{jdbc.queryForObject("SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no=? AND store_no=? FOR UPDATE",String.class,call.getArgument(0),call.getArgument(1),call.getArgument(2));return null;})
            .when(ownership).lockAndCheck(anyString(),anyString(),anyString());
        service=new StoreSettingsServiceImpl(profiles,mapper,ownership,mock(PayMerchantMirror.class));
    }
    @AfterEach void cleanup(){if(admin!=null && schema!=null)admin.execute("DROP DATABASE "+schema);}
    @Test void concurrentSameVersionHasOneWinnerAndPreservesOtherSections() throws Exception {
        String version=service.load("E1","S1").sectionVersions().get("BASIC");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->save(version,"A"));var b=pool.submit(()->save(version,"B"));
            assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));
        }
        assertEquals("09:00-18:00",jdbc.queryForObject("SELECT open_hours FROM mch_store",String.class));
        String finalName=jdbc.queryForObject("SELECT name FROM mch_store",String.class);
        assertTrue(finalName.equals("A") || finalName.equals("B"));
    }
    private int save(String version,String name) {
        try {tx.execute(s->service.basic("E1","S1",version,name,"Taipei","2F"));return 1;}
        catch(BizException e){assertEquals(ErrorCode.CONFLICT,e.errorCode());return 0;}
    }
    @Test void hoursAndBasicsCanBeSavedFromSameSnapshotWithoutOverwritingEachOther() {
        var snapshot=service.load("E1","S1");
        tx.execute(s->service.hours("E1","S1",snapshot.sectionVersions().get("HOURS"),"10:00-20:00"));
        tx.execute(s->service.basic("E1","S1",snapshot.sectionVersions().get("BASIC"),"New","Taipei","3F"));
        var actual=service.load("E1","S1");assertEquals("New",actual.profile().name());assertEquals("10:00-20:00",actual.profile().openHours());
    }
}
