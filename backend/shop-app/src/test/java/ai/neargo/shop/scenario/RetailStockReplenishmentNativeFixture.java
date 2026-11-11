package ai.neargo.shop.scenario;

import ai.neargo.shop.product.policy.ExecutionStockRetentionGuard;

import ai.neargo.shop.product.service.support.RetailStockReplenishmentLedger;

import ai.neargo.shop.product.service.support.StockReplenishmentCommands;

import ai.neargo.shop.product.service.impl.RetailStockReplenishmentServiceImpl;

import ai.neargo.shop.product.service.impl.OriginalStockRestoreServiceImpl;

import ai.neargo.shop.product.service.OriginalStockRestoreService;

import ai.neargo.shop.product.service.RetailStockReplenishmentService;

import ai.neargo.shop.product.mapper.StockRestoreMapper;

import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentReturnMapper;

import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentManualMapper;

import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMovementMapper;

import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;

import org.springframework.dao.DuplicateKeyException;

import org.springframework.dao.DataAccessException;

import java.util.stream.Collectors;

import java.util.concurrent.Future;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SellRuleMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentCommandMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentConnectionMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockSyncLogMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockWriterMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockSyncMapper;
import ai.neargo.shop.product.policy.StockReplenishmentWriterGuard;
import ai.neargo.shop.product.port.RetailReplenishmentTargetPortImpl;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Change;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Connection;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Mapping;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Original;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.product.service.impl.StockReplenishmentAdmissionServiceImpl;
import ai.neargo.shop.product.service.impl.StockReplenishmentServiceImpl;
import ai.neargo.shop.product.service.impl.StockSyncServiceImpl;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/** Shared actual PLATFORM fixtures and V659/V660; remote FOOD facts remain explicit doubles. */
abstract class RetailStockReplenishmentNativeFixture {
    protected static final Scope S=new Scope("MAIN","E","S");
    protected final String schema="i04d2_"+UUID.randomUUID().toString().replace("-","");
    protected JdbcTemplate admin,db;
    protected DataSourceTransactionManager manager;
    protected SqlSessionTemplate sessions;
    protected StockReplenishmentAdmissionService admissions;
    protected StockReplenishmentCommands commands;
    protected StockReplenishmentWriterGuard guard;
    protected RetailStockReplenishmentService effects;
    protected StockPort stockPort;
    protected RetailReplenishmentTargetPort target;
    protected StockSyncService sync;
    protected MerchantQueryPort merchants;
    protected FoodStockReplenishmentPort food;
    protected FoodLocalStockPort foodStock;
    protected DefaultListableBeanFactory beans;
    protected static Mapping mapping(){return new Mapping("SKU","EA",BigDecimal.ONE);}
    protected static Outbound source(String document,long sequence){
        return new Outbound("INV","OWNER",document,1,"SOURCE","EA",new BigDecimal("20"),"S",sequence);
    }
    protected Connection enable(){return admissions.configureRetail(S,"A","on",new Change(true,0,"start"),new Checkpoint("INV","OWNER",10));}
    protected int stock(){return db.queryForObject("SELECT stock FROM prd_sku",Integer.class);}
    protected int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @BeforeAll void database()throws Exception{
        String url=System.getenv("R01_MYSQL_URL"),user=System.getenv("R01_MYSQL_USER"),pass=System.getenv("R01_MYSQL_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/information_schema");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,pass));
        assertThat(admin.queryForObject("SELECT VERSION()",String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source=new DriverManagerDataSource(url.replace("information_schema",schema),user,pass);db=new JdbcTemplate(source);
        String ddl=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("prd_goods","prd_sku","prd_store_stock","prd_stock_lock","prd_store_stock_sync",
                "prd_sell_rule","prd_stock_sync_log","prd_stock_restore")){
            var found=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\n\\);").matcher(ddl);
            assertThat(found.find()).as(table).isTrue();db.execute(found.group());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V659__retail_stock_replenishment_admission.sql"),
                new ClassPathResource("db/migration/V660__retail_stock_replenishment_effects.sql")).execute(source);
        manager=new DataSourceTransactionManager(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
        var plugins=new MybatisPlusInterceptor();plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());factory.setPlugins(plugins);
        var sessionFactory=factory.getObject();
        for(var type:List.of(SkuMapper.class,StoreStockMapper.class,GoodsMapper.class,StockLockMapper.class,
                StoreStockSyncMapper.class,SellRuleMapper.class,StockSyncLogMapper.class,StockWriterMapper.class,
                StockReplenishmentMapper.class,StockReplenishmentConnectionMapper.class,StockReplenishmentCommandMapper.class,StockReplenishmentMovementMapper.class,StockReplenishmentManualMapper.class,
                StockReplenishmentReturnMapper.class,StockRestoreMapper.class))
            sessionFactory.getConfiguration().addMapper(type);
        sessions=new SqlSessionTemplate(sessionFactory);
    }

    @BeforeEach void fixture(){
        for(String table:List.of("prd_stock_replenishment_manual","prd_stock_replenishment_return","prd_stock_replenishment_movement",
                "prd_stock_restore","prd_stock_replenishment_command","prd_stock_replenishment_connection","prd_stock_replenishment",
                "prd_stock_writer","prd_stock_sync_log","prd_sell_rule","prd_stock_lock","prd_store_stock","prd_store_stock_sync","prd_sku","prd_goods"))db.execute("DELETE FROM "+table);
        db.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,created_at,updated_at) VALUES('G','E','item','NORMAL',NOW(),NOW())");
        db.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,price,stock,locked_stock,sale_unit,created_at,updated_at) VALUES('SKU','G','E',100,17,0,'EA',NOW(),NOW())");
        merchants=mock(MerchantQueryPort.class);food=mock(FoodStockReplenishmentPort.class);foodStock=mock(FoodLocalStockPort.class);
        when(merchants.storeProfileOf(anyString())).thenAnswer(call->Optional.of(new MerchantQueryPort.StoreProfile(call.getArgument(0),"E","store","address","CN")));
        when(merchants.storeBusinessTypeOf(anyString())).thenReturn(Optional.of("RETAIL"));when(merchants.storeNos("E")).thenReturn(List.of("S"));
        when(foodStock.findAvailability(any(),any())).thenReturn(List.of(new FoodLocalStockPort.Availability("SKU","EA","TRACKED",BigDecimal.TEN)));
        when(food.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(true,1,1,"INV","OWNER",10));
        beans=new DefaultListableBeanFactory();beans.registerSingleton("food",food);beans.registerSingleton("foodStock",foodStock);
        var reader=new StoreStockReader(sessions.getMapper(SkuMapper.class),sessions.getMapper(StoreStockMapper.class));
        var platform=new StockPortImpl(sessions.getMapper(SkuMapper.class),sessions.getMapper(StockLockMapper.class),sessions.getMapper(StoreStockMapper.class),reader);
        platform.setRetentionGuard(mock(ExecutionStockRetentionGuard.class));
        var restore=new OriginalStockRestoreServiceImpl(sessions.getMapper(StockLockMapper.class),sessions.getMapper(StockRestoreMapper.class),
                sessions.getMapper(SkuMapper.class),sessions.getMapper(StoreStockMapper.class));
        restore.setRetentionGuard(mock(ExecutionStockRetentionGuard.class));
        platform.setOriginalRestore(proxy(restore,OriginalStockRestoreService.class));
        stockPort=proxy(platform,StockPort.class);
        beans.registerSingleton("platform",stockPort);
        target=proxy(new RetailReplenishmentTargetPortImpl(reader,sessions.getMapper(GoodsMapper.class),
                sessions.getMapper(StoreStockSyncMapper.class),merchants,beans.getBeanProvider(StockPort.class)),RetailReplenishmentTargetPort.class);
        guard=proxy(new StockReplenishmentWriterGuard(sessions.getMapper(StockWriterMapper.class),sessions.getMapper(StoreStockSyncMapper.class)),StockReplenishmentWriterGuard.class);
        var json=JsonMapper.builder().build();
        commands=new StockReplenishmentCommands(sessions.getMapper(StockReplenishmentCommandMapper.class),guard,json,manager);
        var ledger=proxy(new RetailStockReplenishmentLedger(target,sessions.getMapper(SkuMapper.class),sessions.getMapper(StoreStockMapper.class),
                sessions.getMapper(StockReplenishmentMovementMapper.class),json),RetailStockReplenishmentLedger.class);
        effects=new RetailStockReplenishmentServiceImpl(sessions.getMapper(StockReplenishmentMapper.class),sessions.getMapper(StockReplenishmentMovementMapper.class),
                sessions.getMapper(StockReplenishmentManualMapper.class),sessions.getMapper(StockReplenishmentReturnMapper.class),commands,ledger,target,json,manager);
        admissions=new StockReplenishmentAdmissionServiceImpl(sessions.getMapper(StockReplenishmentMapper.class),
                sessions.getMapper(StockReplenishmentConnectionMapper.class),commands,
                guard,target,beans.getBeanProvider(StockPort.class),merchants,beans.getBeanProvider(FoodStockReplenishmentPort.class),beans.getBeanProvider(FoodLocalStockPort.class),JsonMapper.builder().build(),manager);
        sync=proxy(new StockSyncServiceImpl(sessions.getMapper(SellRuleMapper.class),sessions.getMapper(StoreStockSyncMapper.class),
                sessions.getMapper(StockSyncLogMapper.class),sessions.getMapper(StoreStockMapper.class),sessions.getMapper(SkuMapper.class),
                merchants,sessions.getMapper(GoodsMapper.class),guard,reader),StockSyncService.class);
    }
    @SuppressWarnings("deprecation") protected <T>T proxy(Object object,Class<T> type){
        var p=new ProxyFactory(object);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return type.cast(p.getProxy());
    }
    @AfterAll void cleanup(){if(admin!=null)admin.execute("DROP DATABASE IF EXISTS "+schema);}

}
