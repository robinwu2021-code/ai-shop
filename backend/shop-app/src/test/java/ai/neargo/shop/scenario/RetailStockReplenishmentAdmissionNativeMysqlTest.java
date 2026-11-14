package ai.neargo.shop.scenario;

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

/** Actual V659, product mappers and transactions. FOOD history/availability are explicit remote doubles. */
@EnabledIfEnvironmentVariable(named="R01_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailStockReplenishmentAdmissionNativeMysqlTest extends RetailStockReplenishmentNativeFixture {    @Test void registrationIsPermanentAndDoesNotPretendThatSourceGoodsHaveBeenReceived(){
        enable();var original=admissions.registerRetail(S,"A","register",source("OUT",20),mapping());
        assertThat(original.status()).isEqualTo("PENDING");assertThat(original.receivedQuantity()).isZero();assertThat(stock()).isEqualTo(17);
        assertThat(admissions.registerRetail(S,"A","register",source("OUT",20),mapping())).isEqualTo(original);
        assertThat(admissions.registerRetail(S,"A","different-key",source("OUT",20),mapping()).originalNo()).isEqualTo(original.originalNo());
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);
        assertThatThrownBy(()->admissions.registerRetail(S,"A","register",source("OTHER",21),mapping())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->admissions.recoverRegistration(S,"OTHER_ACTOR","register","OUT",1,mapping())).isInstanceOf(BizException.class);
        beans.destroySingleton("food");
        assertThat(admissions.recoverRegistration(S,"A","register","OUT",1,mapping())).isEqualTo(original);
        assertThat(admissions.registerRetail(S,"A","offline-new-key",source("OUT",20),mapping()).originalNo()).isEqualTo(original.originalNo());
    }

    @Test void offAndReopenKeepSeventeenAndDoNotReadmitOldUnknownSourceRows(){
        var active=enable();var original=admissions.registerRetail(S,"A","register",source("OUT",20),mapping());
        var off=admissions.configureRetail(S,"A","off",new Change(false,active.revision(),"stop"),null);
        assertThat(off.enabled()).isFalse();assertThat(stock()).isEqualTo(17);
        assertThat(enable()).isEqualTo(active);assertThat(admissions.connection(S).enabled()).isFalse();
        assertThatThrownBy(()->admissions.configureRetail(S,"A","backward",new Change(true,off.revision(),"bad"),
                new Checkpoint("INV","OWNER",5))).isInstanceOf(BizException.class);
        assertThat(admissions.registerRetail(S,"A","known-off",source("OUT",20),mapping()).originalNo()).isEqualTo(original.originalNo());
        assertThatThrownBy(()->admissions.registerRetail(S,"A","unknown-off",source("OTHER",25),mapping())).isInstanceOf(BizException.class);
        var reopened=admissions.configureRetail(S,"A","reopen",new Change(true,off.revision(),"restart"),new Checkpoint("INV","OWNER",30));
        assertThat(reopened.generation()).isEqualTo(2);assertThat(stock()).isEqualTo(17);
        assertThatThrownBy(()->admissions.registerRetail(S,"A","too-old",source("OTHER",25),mapping())).isInstanceOf(BizException.class);
        assertThat(admissions.registerRetail(S,"A","new",source("NEW",40),mapping()).status()).isEqualTo("PENDING");
        assertThatThrownBy(()->admissions.configureRetail(S,"A","reopen",new Change(false,off.revision(),"restart"),null)).isInstanceOf(BizException.class);
    }

    @Test void changedTargetOrFractionalMappingRollsBackBothOriginalAndRequestReceipt(){
        enable();
        assertThatThrownBy(()->admissions.registerRetail(S,"A","fraction",source("OUT",20),new Mapping("SKU","EA",new BigDecimal("0.025")))).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isZero();assertThat(count("prd_stock_replenishment_command")).isEqualTo(1);
        db.update("UPDATE prd_sku SET sale_unit='BOX'");
        assertThatThrownBy(()->admissions.registerRetail(S,"A","bad-unit",source("OUT",20),mapping())).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isZero();assertThat(stock()).isEqualTo(17);
    }

    @Test void persistedOriginalNeverRebindsWhenTheLiveUnitOrPoolChanges(){
        enable();var original=admissions.registerRetail(S,"A","register",source("OUT",20),mapping());
        db.update("UPDATE prd_sku SET sale_unit='BOX'");
        var tx=new TransactionTemplate(manager);
        assertThat(tx.execute(status->target.requireCurrent(S,new RetailReplenishmentTargetPort.Mapping("SKU","EA",BigDecimal.TEN,
                BigDecimal.ONE),original.target())).ready()).isFalse();
        db.update("UPDATE prd_sku SET sale_unit='EA'");
        db.update("INSERT INTO prd_store_stock(store_no,sku_no,entity_no,stock,locked_stock,created_at,updated_at) VALUES('S','SKU','E',7,0,NOW(),NOW())");
        assertThat(tx.execute(status->target.requireCurrent(S,new RetailReplenishmentTargetPort.Mapping("SKU","EA",BigDecimal.TEN,
                BigDecimal.ONE),original.target())).issues()).containsExactly(RetailReplenishmentTargetPort.Issue.TARGET_CHANGED);
        assertThat(admissions.getRetail(S,original.originalNo()).target()).isEqualTo(original.target());
        assertThat(stock()).isEqualTo(17);
    }

    @Test void activeLegacyWriterBlocksTakeoverAndBothDirectQuotaAndAutomaticWriteStayFencedAfterOff(){
        sync.markAligned("E","S","COUNT","A");sync.setEnabled("E","S",true,"A");
        assertThatThrownBy(this::enable).isInstanceOf(BizException.class);
        assertThat(db.queryForObject("SELECT mode FROM prd_stock_writer",String.class)).isEqualTo("LEGACY");
        sync.setEnabled("E","S",false,"A");var active=enable();
        for(var result:List.of(sync.applyQuota("E","S","SKU",1,1,"Q","A"),sync.apply("E","S","SKU",2,"AUTO","A"))){
            assertThat(result.applied()).isFalse();assertThat(result.skipped()).isEqualTo("LOCAL_STOCK_MANAGED");assertThat(result.after()).isEqualTo(17);
        }
        admissions.configureRetail(S,"A","off",new Change(false,active.revision(),"stop"),null);
        assertThatThrownBy(()->sync.setEnabled("E","S",true,"A")).isInstanceOf(BizException.class);
        assertThat(sync.applyQuota("E","S","SKU",1,1,"AFTER_OFF","A").skipped()).isEqualTo("LOCAL_STOCK_MANAGED");
        assertThat(stock()).isEqualTo(17);assertThat(count("prd_stock_sync_log")).isZero();
    }

    @Test void simultaneousLegacyWriteAndTakeoverHaveOnlyOneSerializedOrdering()throws Exception{
        var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var quota=pool.submit(()->{go.await();return sync.applyQuota("E","S","SKU",9,9,"Q","A");});
            var opening=pool.submit(()->{go.await();return enable();});go.countDown();
            var result=quota.get(10,TimeUnit.SECONDS);assertThat(opening.get(10,TimeUnit.SECONDS).enabled()).isTrue();
            assertThat(stock()).isEqualTo(result.applied()?9:17);
            assertThat(count("prd_stock_sync_log")).isEqualTo(result.applied()?1:0);
            assertThat(sync.applyQuota("E","S","SKU",1,1,"LATE","A").skipped()).isEqualTo("LOCAL_STOCK_MANAGED");
        }
    }

    @Test void legacyModeStillUsesItsOriginalAtomicCountersAndPreservesReservations(){
        db.update("UPDATE prd_sku SET locked_stock=3");
        var result=sync.applyQuota("E","S","SKU",9,9,"LEGACY","A");
        assertThat(result.applied()).isTrue();assertThat(result.before()).isEqualTo(14);assertThat(result.after()).isEqualTo(9);
        assertThat(stock()).isEqualTo(12);assertThat(db.queryForObject("SELECT locked_stock FROM prd_sku",Integer.class)).isEqualTo(3);
        assertThat(sync.applyQuota("E","S","SKU",9,9,"LEGACY","A").skipped()).isEqualTo("DONE_BEFORE");
        assertThat(count("prd_stock_sync_log")).isEqualTo(1);
    }

    @Test void explicitOffFencesALateEnableEvenWithoutAnEarlierActiveConnection(){
        var off=admissions.configureRetail(S,"A","off-first",new Change(false,0,"stop pending"),null);
        assertThat(off.revision()).isEqualTo(1);
        assertThatThrownBy(this::enable).isInstanceOf(BizException.class);
        assertThat(admissions.connection(S).enabled()).isFalse();assertThat(stock()).isEqualTo(17);
        assertThat(db.queryForObject("SELECT mode FROM prd_stock_writer",String.class)).isEqualTo("LEGACY");
    }

    @Test void openingRejectsANonPlatformExecutorBeforeChangingTheWriterAuthority(){
        beans.destroySingleton("platform");beans.registerSingleton("otherStock",mock(StockPort.class));
        assertThatThrownBy(this::enable).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_writer")).isZero();assertThat(count("prd_stock_replenishment_connection")).isZero();
        assertThat(count("prd_stock_replenishment_command")).isZero();assertThat(stock()).isEqualTo(17);
    }

    @Test void readOnlyTargetObservationDoesNotInvertTheAdmissionSyncAndGoodsLocks(){
        sync.markAligned("E","S","COUNT","A");
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newSingleThreadExecutor()){
            tx.executeWithoutResult(status->{
                db.queryForObject("SELECT id FROM prd_store_stock_sync WHERE store_no='S' FOR UPDATE",Long.class);
                var future=pool.submit(()->target.inspect(S,new RetailReplenishmentTargetPort.Mapping("SKU","EA",BigDecimal.ONE,BigDecimal.ONE)));
                try{assertThat(future.get(5,TimeUnit.SECONDS).ready()).isTrue();}
                catch(Exception failure){throw new AssertionError(failure);}
            });
        }
    }

    @Test void rejectedFoodConnectedAdmissionDoesNotPoisonTheSharedSourceIdentity(){
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("FOOD"));
        when(food.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(false,1,2,"INV","OWNER",10));
        assertThatThrownBy(()->admissions.claimFood(S,"A",source("OUT",20),mapping())).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isZero();
        when(food.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(true,1,3,"INV","OWNER",30));
        assertThatThrownBy(()->admissions.claimFood(S,"A",source("OUT",20),mapping())).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isZero();
        when(food.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(true,1,4,"INV","OWNER",10));
        var tiny=new Outbound("INV","OWNER","TINY",1,"SOURCE","EA",new BigDecimal("0.000001"),"S",20);
        assertThatThrownBy(()->admissions.claimFood(S,"A",tiny,new Mapping("SKU","EA",new BigDecimal("0.000001"))))
                .isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isZero();
        when(food.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(false,1,5,"INV","OWNER",30));
        assertThat(admissions.claimFood(S,"A",source("OUT",20),mapping(),true).targetOwner()).isEqualTo("FOOD_LOCAL");
        assertThat(stock()).isEqualTo(17);
    }

    @Test void parallelSameKeyAndDifferentKeysCreateOnlyOneSourceOriginal()throws Exception{
        enable();var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)){
            var futures=new ArrayList<Future<Original>>();
            for(int i=0;i<6;i++){String key=i<3?"same":"other-"+i;futures.add(pool.submit(()->{go.await();return admissions.registerRetail(S,"A",key,source("OUT",20),mapping());}));}
            go.countDown();String no=null;for(var future:futures){var value=future.get(15,TimeUnit.SECONDS);if(no==null)no=value.originalNo();assertThat(value.originalNo()).isEqualTo(no);}
        }
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);assertThat(stock()).isEqualTo(17);
    }

    @Test void foodIntentAndOldFoodOriginalBothPreventRetailReclaim(){
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("FOOD"));
        var proof=admissions.claimFood(S,"A",source("OUT",20),mapping());
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("RETAIL"));enable();
        assertThatThrownBy(()->admissions.registerRetail(S,"A","retail",source("OUT",20),mapping())).isInstanceOf(BizException.class);
        beans.destroySingleton("food");beans.destroySingleton("foodStock");
        assertThat(admissions.claimFood(S,"A",source("OUT",20),mapping())).isEqualTo(proof);
        assertThat(stock()).isEqualTo(17);assertThat(count("prd_stock_replenishment")).isEqualTo(1);
    }

    @Test void remoteFoodFailureKeepsTheCommittedSourceIntentForOriginalRecovery(){
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("FOOD"));
        var sourcePort=mock(StockReplenishmentSourcePort.class);beans.registerSingleton("source",sourcePort);
        when(sourcePort.outbound(S,"A","OUT",1)).thenReturn(source("OUT",20));
        when(food.registerClaimed(any(),any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("remote outcome unknown"));
        var host=new StockReplenishmentServiceImpl(beans.getBeanProvider(FoodStockReplenishmentPort.class),
                beans.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        var foodMapping=new FoodStockReplenishmentPort.Mapping("SKU","EA",BigDecimal.ONE);
        assertThatThrownBy(()->host.register(S,"A","original-key","OUT",1,foodMapping)).isInstanceOf(IllegalStateException.class);
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM prd_stock_replenishment",String.class)).isEqualTo("EXTERNAL");
        var result=mock(FoodStockReplenishmentPort.View.class);
        doReturn(result).when(food).registerClaimed(any(),any(),any(),any(),any(),any());
        assertThat(host.register(S,"A","original-key","OUT",1,foodMapping)).isSameAs(result);
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);assertThat(stock()).isEqualTo(17);
        verify(food,times(1)).findSource(any(),eq("INV"),eq("OWNER"),eq("OUT"),eq(1));
    }

    @Test void legacyFoodLookupCannotBeReplacedByAbsenceOrCurrentRetailBusinessType(){
        enable();var old=new FoodStockReplenishmentPort.View("FRP_OLD","INV","OWNER","OLD",1,"SOURCE","EA",new BigDecimal("20"),
                "SKU","EA",BigDecimal.ONE,new BigDecimal("20"),new BigDecimal("18"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"PARTIAL");
        when(food.findSource(any(),eq("INV"),eq("OWNER"),eq("OLD"),eq(1))).thenReturn(old);
        assertThatThrownBy(()->admissions.registerRetail(S,"A","retail-old",source("OLD",20),mapping())).isInstanceOf(BizException.class);
        var imported=admissions.claimFood(S,"A",source("OLD",20),mapping());assertThat(imported.targetOwner()).isEqualTo("FOOD_LOCAL");
        verifyNoInteractions(foodStock);
        beans.destroySingleton("food");
        assertThatThrownBy(()->admissions.registerRetail(S,"A","no-history-reader",source("NEW",30),mapping())).isInstanceOf(BizException.class);
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);assertThat(stock()).isEqualTo(17);
    }

    @Test void sourceUniquenessAndSnapshotQuantityChecksAreDatabaseConstraints(){
        enable();admissions.registerRetail(S,"A","register",source("OUT",20),mapping());
        checkViolation("UPDATE prd_stock_replenishment SET expected_quantity=0.5");
        checkViolation("UPDATE prd_stock_replenishment SET source_snapshot='invalid'");
        checkViolation("UPDATE prd_stock_replenishment SET deleted=1");
        var columns=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='prd_stock_replenishment' AND column_name<>'id' ORDER BY ordinal_position",String.class,schema);
        String names=String.join(",",columns);String copy=columns.stream().map(c->c.equals("original_no")?"'OTHER_ORIGINAL'":c.equals("target_owner")?"'FOOD_LOCAL'":c).collect(Collectors.joining(","));
        assertThatThrownBy(()->db.update("INSERT INTO prd_stock_replenishment("+names+") SELECT "+copy+" FROM prd_stock_replenishment"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count("prd_stock_replenishment")).isEqualTo(1);
    }

    @Test void distinctRawRequestKeysAreNotFoldedByMysqlPaddingRules(){
        enable();
        var first=admissions.registerRetail(S,"A","request",source("OUT",20),mapping());
        var second=admissions.registerRetail(S,"A","request ",source("SECOND",21),mapping());
        assertThat(second.originalNo()).isNotEqualTo(first.originalNo());
        assertThat(count("prd_stock_replenishment_command")).isEqualTo(3);
        assertThat(count("prd_stock_replenishment")).isEqualTo(2);assertThat(stock()).isEqualTo(17);
        assertThat(db.queryForObject("SELECT PAD_ATTRIBUTE FROM information_schema.collations WHERE COLLATION_NAME='utf8mb4_0900_bin'",String.class)).isEqualTo("NO PAD");
    }

    @Test void legacyEnableAndLocalTakeoverAreMutuallyExclusiveAtTheEntityFence()throws Exception{
        sync.markAligned("E","S","COUNT","A");var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var legacy=pool.submit(()->{go.await();try{sync.setEnabled("E","S",true,"A");return true;}catch(BizException rejected){return false;}});
            var local=pool.submit(()->{go.await();try{enable();return true;}catch(BizException rejected){return false;}});
            go.countDown();boolean legacyWon=legacy.get(10,TimeUnit.SECONDS),localWon=local.get(10,TimeUnit.SECONDS);
            assertThat(legacyWon).isNotEqualTo(localWon);
            assertThat(db.queryForObject("SELECT mode FROM prd_stock_writer",String.class)).isEqualTo(localWon?"REPLENISH":"LEGACY");
            assertThat(db.queryForObject("SELECT enabled FROM prd_store_stock_sync",Integer.class)).isEqualTo(legacyWon?1:0);
            assertThat(stock()).isEqualTo(17);
        }
    }
    private void checkViolation(String sql) {
        var failure=catchThrowable(()->db.update(sql));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        var cause=((DataAccessException)failure).getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException)cause).getErrorCode()).isEqualTo(3819);
    }

}
