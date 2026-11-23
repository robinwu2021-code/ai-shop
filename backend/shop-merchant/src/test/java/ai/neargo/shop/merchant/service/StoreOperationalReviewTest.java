package ai.neargo.shop.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.impl.StoreOperationalReviewServiceImpl;
import ai.neargo.shop.merchant.approval.StoreOpeningApprovalSubject;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.Map;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="PLAN_MYSQL_URL",matches=".+")
class StoreOperationalReviewTest {
    JdbcTemplate admin;
    String schema;
    JdbcTemplate db;
    TransactionTemplate tx;
    StoreOperationalReviewService svc;
    MchStoreMapper stores;
    AuditLogPort audit;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("PLAN_MYSQL_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3308/?"));
        String user=System.getenv("PLAN_MYSQL_USER"),pass=System.getenv("PLAN_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,pass));
        schema="store_review_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
        var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,pass);
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var root=Path.of("../shop-app/src/main/resources/db/migration");
        var sql=Files.readString(root.resolve("V1__baseline.sql"));
        var ddl=Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS mch_store\\s*\\(.*?\\)\\s*ENGINE[^;]*;").matcher(sql);
        assertTrue(ddl.find());
        db.execute(ddl.group().replace("utf8mb4_uca1400_ai_ci","utf8mb4_unicode_520_ci"));
        for(var statement:Files.readString(root.resolve("V672__store_operational_review.sql")).replaceAll("(?m)^--.*$", "").split(";"))
            if(!statement.isBlank())db.execute(statement);
        db.update("INSERT INTO mch_store(tenant_no,store_no,entity_no,status,created_at,updated_at) VALUES('MAIN','S1','E1','ACTIVE',NOW(),NOW()),('MAIN','S2','E1','ACTIVE',NOW(),NOW()),('OTHER','FS1','E1','ACTIVE',NOW(),NOW())");
        var registry=new DataScopeTableRegistry();registry.register("mch_store",Map.of("MERCHANT","entity_no"));
        var interceptor=new MybatisPlusInterceptor();interceptor.addInnerInterceptor(new DataPermissionInterceptor(new DataScopeHandler(registry)));
        var configuration=new MybatisConfiguration();configuration.addMapper(MchStoreMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);factory.setPlugins(interceptor);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        stores=new SqlSessionTemplate(factory.getObject()).getMapper(MchStoreMapper.class);
        DataScopeContext.set(DataScopeSpec.of("MERCHANT",Set.of("E1")));
        audit=mock(AuditLogPort.class);
        svc=new StoreOperationalReviewServiceImpl(db,stores,mock(StoreOwnershipWritePort.class),audit);
    }
    @Test void explicitSubmissionAndPaidActivationAreTargetedAndIdempotent() {
        tx.execute(s->svc.submit("S1"));
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
        assertEquals("PENDING",db.queryForObject("SELECT operational_review FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",String.class));
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","STORE","O1",Long.MAX_VALUE,100L);return null;});
        assertEquals("ACTIVE",status("MAIN","S1"));
        assertEquals("ACTIVE",status("MAIN","S2"));
        assertNull(db.queryForObject("SELECT operational_review FROM mch_store WHERE tenant_no='MAIN' AND store_no='S2'",String.class));
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","STORE","O2",Long.MAX_VALUE,200L);return null;});
        assertEquals("O1",db.queryForObject("SELECT operational_order_no FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",String.class));
        assertEquals(2,db.queryForObject("SELECT version FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT version FROM mch_store WHERE tenant_no='OTHER' AND store_no='FS1'",Integer.class));
    }
    @Test void manualRejectionAndSuspensionCannotBeBypassedByPaidReplay() {
        tx.execute(s->svc.submit("S1"));
        tx.execute(s->svc.decide("S1",false,"资料需补充"));
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","ENTITY","O1",Long.MAX_VALUE,100L);return null;});
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
        tx.execute(s->svc.submit("S1"));
        db.update("UPDATE mch_store SET status='SUSPENDED' WHERE tenant_no='MAIN' AND store_no='S1'");
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","STORE","O1",Long.MAX_VALUE,100L);return null;});
        assertEquals("SUSPENDED",status("MAIN","S1"));
    }
    @Test void scopedManualApprovalAndAtomicAuditRollback() {
        tx.execute(s->svc.submit("S1"));
        doThrow(new IllegalStateException("audit failed")).when(audit).record(eq("STORE_OPERATIONAL_APPROVED"),anyString(),anyString(),eq(true));
        assertThrows(IllegalStateException.class,()->tx.execute(s->svc.decide("S1",true,"人工核验")));
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
        DataScopeContext.set(DataScopeSpec.of("MERCHANT",Set.of("OUTSIDE")));
        assertThrows(BizException.class,()->tx.execute(s->svc.decide("S1",true,"人工核验")));
    }
    @Test void completedOldOrderCannotApproveLaterSubmission() {
        tx.execute(s->svc.submit("S1"));
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","STORE","OLD",0L,100L);return null;});
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
        assertNull(db.queryForObject("SELECT operational_order_no FROM mch_store WHERE store_no='S1'",String.class));
    }
    @Test void unsubmittedHistoryAndWrongOwnerRemainUntouched() {
        tx.execute(s->{svc.activatePaid("MAIN","E1","S1","STORE","O1",Long.MAX_VALUE,100L);return null;});
        assertNull(db.queryForObject("SELECT operational_review FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",String.class));
        tx.execute(s->svc.submit("S1"));
        tx.execute(s->{svc.activatePaid("MAIN","E2","S1","STORE","O1",Long.MAX_VALUE,100L);return null;});
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
    }
    /** TDD-OpsKit审批对接 §1.4：启用审批后「提交」带当前修订送审；执行节点生效、重投幂等、对象变了判 STALE；驳回回到可重新提交。 */
    @SuppressWarnings("unchecked")
    @Test void approvalPilotSubmitsCurrentRevisionAndAppliesOnlyUnchangedStores() {
        var port=mock(ApprovalPort.class);when(port.enabled("shop.store_opening")).thenReturn(true);
        ObjectProvider<ApprovalPort> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(port);
        svc=new StoreOperationalReviewServiceImpl(db,stores,mock(StoreOwnershipWritePort.class),audit,provider);
        var subject=new StoreOpeningApprovalSubject(db,svc);
        tx.execute(s->svc.submit("S1"));
        long revision=db.queryForObject("SELECT version FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",Long.class);
        verify(port).submit(eq("shop.store_opening"),eq("S1"),eq(revision),any(ApprovalPort.Submitter.class));
        assertEquals(Set.of("merchant:E1"),subject.snapshot("S1").scopes());
        assertEquals(ApprovalSubject.Level.IMPORTANT,subject.level());
        var change=subject.snapshot("S1").facts().stream().filter(f->f.before()!=null).findFirst().orElseThrow();
        assertEquals("待开通",change.before());assertEquals("营业中",change.value());
        var stale=assertThrows(ApprovalApplyException.class,()->subject.apply("S1",revision+1));
        assertEquals(ApprovalApplyException.STALE,stale.code());
        assertEquals("PENDING_OPEN",status("MAIN","S1"));
        DataScopeContext.clear();   // 回调线程没有数据域
        subject.apply("S1",revision);
        assertEquals("ACTIVE",status("MAIN","S1"));
        subject.apply("S1",revision);   // OpsKit 重投：幂等
        assertEquals("ACTIVE",status("MAIN","S1"));
    }
    @SuppressWarnings("unchecked")
    @Test void approvalPilotRejectionReturnsStoreToResubmittableButIgnoresChangedStores() {
        var port=mock(ApprovalPort.class);when(port.enabled("shop.store_opening")).thenReturn(true);
        ObjectProvider<ApprovalPort> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(port);
        svc=new StoreOperationalReviewServiceImpl(db,stores,mock(StoreOwnershipWritePort.class),audit,provider);
        var subject=new StoreOpeningApprovalSubject(db,svc);
        tx.execute(s->svc.submit("S1"));
        long revision=db.queryForObject("SELECT version FROM mch_store WHERE tenant_no='MAIN' AND store_no='S1'",Long.class);
        DataScopeContext.clear();
        subject.onClosed("S1",revision-1,ApprovalSubject.Outcome.REJECTED,"旧单");
        assertEquals("PENDING",db.queryForObject("SELECT operational_review FROM mch_store WHERE store_no='S1' AND tenant_no='MAIN'",String.class));
        subject.onClosed("S1",revision,ApprovalSubject.Outcome.REJECTED,null);
        assertEquals("REJECTED",db.queryForObject("SELECT operational_review FROM mch_store WHERE store_no='S1' AND tenant_no='MAIN'",String.class));
        DataScopeContext.set(DataScopeSpec.of("MERCHANT",Set.of("E1")));
        tx.execute(s->svc.submit("S1"));   // 驳回后可重新提交，新修订再送审
        verify(port,times(2)).submit(eq("shop.store_opening"),eq("S1"),anyLong(),any(ApprovalPort.Submitter.class));
    }
    @AfterEach void cleanup() {DataScopeContext.clear();if(admin!=null && schema!=null)admin.execute("DROP DATABASE "+schema);}
    String status(String tenant,String store) {return db.queryForObject("SELECT status FROM mch_store WHERE tenant_no=? AND store_no=?",String.class,tenant,store);}
}
