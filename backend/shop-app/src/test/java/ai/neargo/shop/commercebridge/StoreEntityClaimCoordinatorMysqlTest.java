package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.subscription.StoreRelinkService;
import ai.neargo.commerce.persistence.JdbcStoreRelinkRepository;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.paysync.StoreSyncPublisher;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.StoreEntityClaimService;
import ai.neargo.shop.merchant.service.impl.StoreOwnershipWritePortImpl;
import ai.neargo.shop.merchantbridge.ShopStoreRelinkAuthority;
import ai.neargo.shop.merchantbridge.StoreEntityClaimServiceImpl;
import ai.neargo.shop.merchantbridge.StoreRelinkLocalPolicy;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.user.StoreRelinkHostFactsPort;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual host/commerce DB stages; a controlled PAY protocol peer is not dual-JVM evidence. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class StoreEntityClaimCoordinatorMysqlTest {
    private static final StoreEntityClaimService.Command COMMAND = new StoreEntityClaimService.Command("request-1","T",7);

    private static <T> ObjectProvider<T> provider(Class<T> type,T value) {
        var beans=new StaticListableBeanFactory();
        beans.addBean(type.getSimpleName(),value);
        return beans.getBeanProvider(type);
    }

    /** Signals the actual exclusive-lock statement before the driver can block on the writer. */
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        final AtomicReference<CountDownLatch> exclusiveAttempt = new AtomicReference<>();
        ObservedDataSource(DataSource delegate) {this.delegate=delegate;}
        @Override public Connection getConnection() throws SQLException {return observed(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password) throws SQLException {return observed(delegate.getConnection(user,password));}
        private Connection observed(Connection connection) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(connection,args);
                    if(method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.contains("FROM mch_entity") && sql.contains("FOR UPDATE")) {
                        var statement=(java.sql.PreparedStatement)result;
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(p,m,a)->{
                            if(m.getName().startsWith("execute")) {
                                var signal=exclusiveAttempt.get();if(signal!=null)signal.countDown();
                            }
                            try{return m.invoke(statement,a);}catch(InvocationTargetException e){throw e.getCause();}
                        });
                    }
                    return result;
                } catch(InvocationTargetException e) {throw e.getCause();}
            });
        }
    }

    private static final class Peer {
        final PaySourceLinkApi api=mock(PaySourceLinkApi.class);
        PaySourceLinkApi.StoreClaimCommand command;
        String state;
        boolean prepareUnknown, commitUnknown, abortUnknown, rejectPrepare;
        int prepareCalls, commitMutations, abortMutations;
        Runnable prepared=()->{};
        Peer() {
            when(api.storeClaim(anyString(),anyString())).thenAnswer(inv->{
                outside();
                if(command==null)throw failure(404);
                assertEquals(command.requestNo(),inv.getArgument(0));
                assertEquals(command.sourceScope(),inv.getArgument(1));
                return view();
            });
            when(api.prepareStoreClaim(any())).thenAnswer(inv->{
                outside();prepareCalls++;
                var request=(PaySourceLinkApi.StoreClaimCommand)inv.getArgument(0);
                same(request);
                if(rejectPrepare)throw failure(409);
                if(state==null)state="RESERVED";
                prepared.run();
                if(prepareUnknown){prepareUnknown=false;throw failure(0);}
                return view();
            });
            when(api.commitStoreClaim(anyString(),any())).thenAnswer(inv->{
                outside();same(inv.getArgument(1));
                assertEquals(command.requestNo(),inv.getArgument(0));
                if(!"COMMITTED".equals(state)){assertEquals("RESERVED",state);state="COMMITTED";commitMutations++;}
                if(commitUnknown){commitUnknown=false;throw failure(0);}
                return view();
            });
            when(api.abortStoreClaim(anyString(),any())).thenAnswer(inv->{
                outside();same(inv.getArgument(1));
                assertEquals(command.requestNo(),inv.getArgument(0));
                assertNotEquals("COMMITTED",state,"A locally committed claim must never cancel PAY");
                if(!"ABORTED".equals(state)){state="ABORTED";abortMutations++;}
                if(abortUnknown){abortUnknown=false;throw failure(0);}
                return view();
            });
        }
        private void same(PaySourceLinkApi.StoreClaimCommand request) {
            if(command==null)command=request;
            else assertEquals(command,request,"Recovery must retain the full original wire command/hash");
        }
        private PaySourceLinkApi.StoreClaimView view() {
            return new PaySourceLinkApi.StoreClaimView(command.requestNo(),state,"remote-claim",command.sourceEntityRef(),command.targetEntityRef(),command.storeRef(),command.storeNo());
        }
        private static void outside() {assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"PAY I/O cannot execute inside the local DB transaction");}
        private static ServiceCallException failure(int status) {
            return new ServiceCallException(status==0?CallOutcome.TIMEOUT:CallOutcome.REMOTE_ERROR,"PAY",status,"controlled protocol result");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="store_claim_it_"+UUID.randomUUID().toString().replace("-","");
        final JdbcTemplate admin;
        final ObservedDataSource ds;
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final TransactionTemplate tx;
        final Peer peer=new Peer();
        final StoreRelinkHostFactsPort history=mock(StoreRelinkHostFactsPort.class);
        final MerchantPlanService plans=mock(MerchantPlanService.class);
        final MerchantEntityService entities=mock(MerchantEntityService.class);
        final OpsService ops=mock(OpsService.class);
        final StoreSyncPublisher publisher=mock(StoreSyncPublisher.class);
        final AtomicBoolean failAudit=new AtomicBoolean();
        final StoreOwnershipWritePortImpl ownership;
        final StoreRelinkFacade commerce;
        final StoreRelinkLocalPolicy policy;
        final StoreSyncProperties sync=new StoreSyncProperties(true,"http://127.0.0.1:1","AI_SHOP_UME","local-test","MAIN","CN",null,Duration.ofSeconds(60),Duration.ofSeconds(1));
        Fixture() throws Exception {
            String url=System.getenv("COMMERCE_MYSQL_URL"),user=System.getenv("COMMERCE_MYSQL_USER"),password=System.getenv("COMMERCE_MYSQL_PASSWORD");
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"));
            admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
            admin.execute("CREATE DATABASE "+schema);
            ds=new ObservedDataSource(new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,password));
            jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
            String ddl=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
            for(String table:List.of("mch_entity","mch_store","mch_account","mch_store_role","mch_role","mch_entity_plan","mch_service_area","mch_fulfillment_channel","mch_entity_community",
                    "sys_audit_log","ord_offline_record")) {
                var match=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+"\\s*\\(.*?\n\\);").matcher(ddl);
                assertTrue(match.find(),table);
                String sql=match.group().replaceAll("(?m)^.*ownership_change_no[^\n]*\n","");
                jdbc.execute(sql);
            }
            createFromMigration("V544__brand_management_organization.sql","mch_brand_store_binding","mch_brand_change");
            createFromMigration("V576__stored_value_financial_membership_saga.sql","mch_brand_financial_change");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V647__store_entity_claim.sql")).execute(ds);
            Flyway.configure().dataSource(ds).locations("classpath:db/commerce/migration").table("commerce_schema_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,created_at,updated_at) VALUES('M','placeholder','PENDING_LICENSE','CN',NOW(),NOW()),('T','target','ACTIVE','CN',NOW(),NOW()),('T2','other target','ACTIVE','CN',NOW(),NOW())");
            jdbc.update("INSERT INTO mch_account(mch_account_no,user_no,entity_no,is_owner,status,created_at,updated_at) VALUES('MA','U','M',1,'ACTIVE',NOW(),NOW()),('TA','U','T',1,'ACTIVE',NOW(),NOW()),('TA2','U','T2',1,'ACTIVE',NOW(),NOW()),('STAFF',NULL,'M',0,'ACTIVE',NOW(),NOW())");
            jdbc.update("INSERT INTO mch_store(entity_no,store_no,name,status,is_default,pay_merchant_no,commerce_model,business_type_code,version,created_at,updated_at) VALUES('M','S','store','ACTIVE',1,'old-ref','STORE_V1','RETAIL',7,NOW(),NOW())");
            jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES('STAFF','S','CLERK',NOW(),NOW()),('STAFF','S','MANAGER',NOW(),NOW())");
            jdbc.update("""
                    INSERT INTO mch_subscription_account(tenant_no,store_no,entity_no,business_type_code,entitlement_version,created_at,updated_at,version,deleted)
                    VALUES('MAIN','S','M','RETAIL',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
                    """);
            jdbc.update("""
                    INSERT INTO mch_subscription_period(tenant_no,period_no,store_no,entity_no,business_type_code,stream_key,source_type,source_ref_no,tier_code,delivered_at,delivery_seq,revoked_duration_ms,original_start_at,effective_start_at,eligible_basis_minor,cash_basis_minor,credit_basis_minor,currency,state,source_policy_snapshot,created_at,updated_at,version,deleted)
                    VALUES('MAIN','FREE-S','S','M','RETAIL','FREE','FREE','init-S','FREE',1,1,0,1,1,0,0,0,'CNY','NORMAL','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
                    """);
            when(history.inspect(anyString(),anyString(),anyString())).thenReturn(new StoreRelinkHostFactsPort.Facts(false,false));
            when(plans.current(anyString())).thenAnswer(inv->new MerchantPlanService.PlanVO(inv.getArgument(0),"FREE",10,10,false,"ACTIVE",null,"TEST",false,"PLAN"));
            doAnswer(inv->{((java.util.function.IntSupplier)inv.getArgument(1)).getAsInt();return null;}).when(plans).requireStoreQuota(anyString(),any());
            when(entities.myStores(any())).thenReturn(List.of());
            when(publisher.publishAll(anyList())).thenAnswer(inv->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),"Final sync enqueue shares the finish transaction");
                assertNull(marker());return 1;
            });
            doAnswer(inv->{
                jdbc.update("INSERT INTO sys_audit_log(staff_no,op_action,target,detail,at,created_at,critical,before_json,after_json) VALUES('U',?,?,?,0,NOW(),1,?,?)",
                        inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(4),inv.getArgument(5));
                if(failAudit.getAndSet(false))throw BizException.of(ErrorCode.CONFLICT);
                return null;
            }).when(ops).audit(anyString(),anyString(),anyString(),eq(true),anyString(),anyString());
            ownership=new StoreOwnershipWritePortImpl(jdbc);
            commerce=new StoreRelinkService(new JdbcStoreRelinkRepository(ds),new ShopStoreRelinkAuthority(jdbc));
            policy=new StoreRelinkLocalPolicy(jdbc,history,provider(StoreRelinkFacade.class,commerce),List.of(),plans);
        }
        private void createFromMigration(String migration,String... tables) throws java.io.IOException {
            String ddl=new ClassPathResource("db/migration/"+migration).getContentAsString(StandardCharsets.UTF_8);
            for(String table:tables) {
                var create=Pattern.compile("(?s)CREATE TABLE "+Pattern.quote(table)+"\\s*\\(.*?\n\\)\\s*ENGINE[^;]*;").matcher(ddl);
                assertTrue(create.find(),migration+"/"+table);
                jdbc.execute(create.group());
            }
        }
        ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl staff(
                ai.neargo.shop.spi.user.StoreOwnershipWritePort gate) throws Exception {
            var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
            var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setConfiguration(config);factory.setDataSource(ds);
            factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                    .setMetaObjectHandler(new ai.neargo.shop.common.AuditMetaObjectHandler()));
            var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
            for(var type:List.of(ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper.class,
                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper.class,
                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper.class,
                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper.class))config.addMapper(type);
            var service=new ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl(
                    session.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper.class),
                    mock(ai.neargo.shop.auth.TokenStores.class),
                    session.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper.class),
                    session.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper.class),
                    mock(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStaffLogMapper.class),
                    session.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper.class),
                    mock(ai.neargo.shop.merchant.service.impl.StaffAuditLogger.class),
                    mock(ai.neargo.shop.auth.TokenStore.class),mock(ai.neargo.shop.common.OtpStore.class));
            service.setOwnership(gate);return service;
        }
        StoreEntityClaimServiceImpl service() {return service(sync);}
        StoreEntityClaimServiceImpl service(StoreSyncProperties runtimeSync) {
            return new StoreEntityClaimServiceImpl(jdbc,manager,policy,provider(StoreRelinkFacade.class,commerce),
                    provider(PaySourceLinkApi.class,peer.api),new StoreSnapshotReader(ds),runtimeSync,provider(StoreSyncPublisher.class,publisher),entities,ops,plans,new ObjectMapper(),true);
        }
        StoreEntityClaimService.View create() {return service().create("MAIN","U","M","S",COMMAND);}
        String entity() {return jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no='S'",String.class);}
        String periodEntity() {return jdbc.queryForObject("SELECT entity_no FROM mch_subscription_period WHERE period_no='FREE-S'",String.class);}
        String marker() {return jdbc.queryForObject("SELECT ownership_change_no FROM mch_store WHERE store_no='S'",String.class);}
        int staffRoles(String store) {return jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_role WHERE store_no=?",Integer.class,store);}
        int audits() {return jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_log WHERE op_action='STORE_ENTITY_CLAIM'",Integer.class);}
        void cashRecord() {
            jdbc.update("""
                    INSERT INTO ord_offline_record(record_no,entity_no,store_no,amount_minor,currency,method,received_at,status,recorded_by,idem_key_hash,request_hash,created_at,updated_at)
                    VALUES('R','M','S',1,'CNY','CASH',NOW(),'VOID','U',?,?,NOW(),NOW())
                    ""","0".repeat(64),"1".repeat(64));
        }
        @Override public void close() {admin.execute("DROP DATABASE "+schema);}
    }

    @Test void merchantConfigurationHistoryBlocksEvenWhenDeletedOrDisabledAndTenantIsExplicit() throws Exception {
        try(var f=new Fixture()) {
            f.jdbc.update("INSERT INTO mch_service_area(tenant_no,entity_no,store_no,area_no,level,ref_code,status,deleted,created_at,updated_at) VALUES('OTHER','M','S','OTHER-AREA','COMMUNITY','C','REJECTED',1,NOW(),NOW())");
            f.jdbc.update("INSERT INTO mch_fulfillment_channel(tenant_no,entity_no,store_no,channel,enabled,deleted,created_at,updated_at) VALUES('OTHER','M','S','STORE_PICKUP',0,1,NOW(),NOW())");
            f.jdbc.update("INSERT INTO mch_entity_community(tenant_no,entity_no,community_no,deleted,created_at,updated_at) VALUES('OTHER','M','OTHER-C',1,NOW(),NOW())");
            assertFalse(f.policy.inspect("MAIN","U","M","S","T",null).blockers().contains("STORE_CONFIGURATION_HISTORY"));
            f.jdbc.update("INSERT INTO mch_service_area(tenant_no,entity_no,store_no,area_no,level,ref_code,status,deleted,created_at,updated_at) VALUES('MAIN','OLD_ENTITY','S','AREA','COMMUNITY','C','REJECTED',1,NOW(),NOW())");
            assertConfigurationBlocked(f);
            f.jdbc.update("DELETE FROM mch_service_area WHERE tenant_no='MAIN'");
            f.jdbc.update("INSERT INTO mch_fulfillment_channel(tenant_no,entity_no,store_no,channel,enabled,deleted,created_at,updated_at) VALUES('MAIN','OLD_ENTITY','S','STORE_PICKUP',0,1,NOW(),NOW())");
            assertConfigurationBlocked(f);
            f.jdbc.update("DELETE FROM mch_fulfillment_channel WHERE tenant_no='MAIN'");
            f.jdbc.update("INSERT INTO mch_entity_community(tenant_no,entity_no,community_no,deleted,created_at,updated_at) VALUES('MAIN','M','C',1,NOW(),NOW())");
            assertConfigurationBlocked(f);
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim",Integer.class));
            assertNull(f.marker());assertEquals("M",f.entity());
            verify(f.peer.api,never()).prepareStoreClaim(any());
        }
    }
    private static void assertConfigurationBlocked(Fixture f) {
        assertTrue(f.policy.inspect("MAIN","U","M","S","T",null).blockers().contains("STORE_CONFIGURATION_HISTORY"));
        assertThrows(BizException.class,f::create);
    }

    @Test void completedClaimIsDurableAndOriginalRequestReplayDoesNotRepeatAuditOrEmployeeRevocation() throws Exception {
        try(var f=new Fixture()) {
            var result=f.create();
            assertEquals("COMPLETED",result.state());assertEquals(1,result.revokedStaff());assertTrue(result.placeholderRetired());
            assertTrue(f.peer.command.retireSource());
            assertEquals("T",f.entity());assertEquals("T",f.periodEntity());assertEquals(0,f.staffRoles("S"));assertNull(f.marker());
            assertEquals(1,f.jdbc.queryForObject("SELECT deleted FROM mch_entity WHERE entity_no='M'",Integer.class));
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_account WHERE entity_no='M' AND status='DISABLED'",Integer.class));
            assertEquals(0,f.jdbc.queryForObject("SELECT is_default FROM mch_store WHERE store_no='S'",Integer.class));
            assertNull(f.jdbc.queryForObject("SELECT pay_merchant_no FROM mch_store WHERE store_no='S'",String.class));
            assertEquals(result,f.create());assertEquals(1,f.audits());assertEquals(1,f.peer.commitMutations);
            verify(f.publisher,times(1)).publishAll(List.of("S"));
            assertEquals(TokenHash.of("MAIN|U|M|S|T|7"),f.jdbc.queryForObject("SELECT request_hash FROM mch_store_entity_claim",String.class));
        }
    }

    @Test void unknownPrepareRetainsOldOwnershipAndEmployeesAndNewServiceRecoversOriginalHash() throws Exception {
        try(var f=new Fixture()) {
            f.peer.prepareUnknown=true;var result=f.create();
            assertEquals("FROZEN",result.state());assertEquals("M",f.entity());assertEquals("M",f.periodEntity());assertEquals(2,f.staffRoles("S"));assertNotNull(f.marker());assertEquals(0,f.audits());
            assertThrows(BizException.class,()->f.tx.execute(tx->{f.ownership.lockAndCheck("MAIN","M","S");return null;}));
            var changedScope=new StoreSyncProperties(true,"http://127.0.0.1:1","AI_SHOP_UME","local-test","OTHER","CN",null,Duration.ofSeconds(60),Duration.ofSeconds(1));
            var recovered=f.service(changedScope).resume("MAIN","U",result.claimNo());
            assertEquals("MAIN",f.peer.command.sourceScope());assertTrue(f.peer.command.retireSource());
            assertEquals("COMPLETED",recovered.state());assertEquals(1,f.peer.prepareCalls,"Already reserved prepare is queried, not recreated");assertEquals(1,f.audits());
        }
    }

    @Test void unknownCommitRetainsNewOwnershipFrozenAndRecoveryOnlyMovesForward() throws Exception {
        try(var f=new Fixture()) {
            f.peer.commitUnknown=true;var result=f.create();
            assertEquals("LOCAL_COMMITTED",result.state());assertEquals("T",f.entity());assertEquals("T",f.periodEntity());assertEquals(0,f.staffRoles("S"));assertNotNull(f.marker());assertEquals(1,f.audits());
            assertThrows(BizException.class,()->f.tx.execute(tx->{f.ownership.lockAndCheck("MAIN","T","S");return null;}));
            assertEquals("COMPLETED",f.service().resume("MAIN","U",result.claimNo()).state());
            assertEquals(1,f.peer.commitMutations);assertEquals(0,f.peer.abortMutations);assertEquals(1,f.audits());assertNull(f.marker());
            f.tx.execute(tx->{f.ownership.lockAndCheck("MAIN","T","S");return null;});
        }
    }

    @Test void targetChangedAfterPrepareIsAbortedBeforeAnyLocalMigration() throws Exception {
        try(var f=new Fixture()) {
            f.peer.prepared=()->f.jdbc.update("UPDATE mch_entity SET status='REJECTED' WHERE entity_no='T'");
            var result=f.create();assertEquals("ABORTED",result.state());
            assertEquals("M",f.entity());assertEquals("M",f.periodEntity());assertEquals(2,f.staffRoles("S"));assertNull(f.marker());assertEquals(0,f.audits());assertEquals(1,f.peer.abortMutations);
        }
    }

    @Test void localAuditFailureRollsBackCommerceStoreAndRolesThenAbortUnknownKeepsFreezeUntilConfirmed() throws Exception {
        try(var f=new Fixture()) {
            f.failAudit.set(true);f.peer.abortUnknown=true;
            var result=f.create();assertEquals("ABORTING",result.state());
            assertEquals("M",f.entity());assertEquals("M",f.periodEntity());assertEquals(2,f.staffRoles("S"));assertEquals(0,f.audits());assertNotNull(f.marker());
            assertEquals(0,f.jdbc.queryForObject("SELECT deleted FROM mch_entity WHERE entity_no='M'",Integer.class));
            assertEquals("ABORTED",f.service().resume("MAIN","U",result.claimNo()).state());
            assertNull(f.marker());assertEquals(1,f.peer.abortMutations);assertEquals(0,f.peer.commitMutations);
        }
    }

    @Test void originalKeyCannotChangeTargetOrOwnerAndStaleVersionNeverFreezes() throws Exception {
        try(var f=new Fixture()) {
            assertThrows(BizException.class,()->f.service().create("MAIN","U","M","S",new StoreEntityClaimService.Command("stale","T",6)));
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim",Integer.class));assertNull(f.marker());
            f.peer.prepareUnknown=true;var claim=f.create();
            assertThrows(BizException.class,()->f.service().create("MAIN","U","M","S",new StoreEntityClaimService.Command("request-1","T2",7)));
            assertThrows(BizException.class,()->f.service().create("MAIN","OTHER","M","S",COMMAND));
            assertThrows(BizException.class,()->f.service().resume("MAIN","OTHER",claim.claimNo()));
            assertEquals("FROZEN",f.service().byNo("MAIN","U",claim.claimNo()).state());assertEquals(1,f.peer.prepareCalls);
        }
    }

    @Test void payAbortTombstoneCannotBeRevivedByDelayedRecovery() throws Exception {
        try(var f=new Fixture()) {
            f.peer.prepareUnknown=true;var claim=f.create();
            f.peer.state="ABORTED"; // Controlled peer has durably accepted cancellation before the host learns its result.
            assertEquals("ABORTED",f.service().resume("MAIN","U",claim.claimNo()).state());
            assertEquals(1,f.peer.prepareCalls);assertEquals("M",f.entity());assertEquals(0,f.audits());assertNull(f.marker());
        }
    }

    @Test void priorRealSharedWriterCommitsHistoryBeforeClaimCanFreezeAndClaimThenRejects() throws Exception {
        try(var f=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            when(f.history.inspect(anyString(),anyString(),anyString())).thenAnswer(inv->new StoreRelinkHostFactsPort.Facts(
                    f.jdbc.queryForObject("SELECT COUNT(*) FROM ord_offline_record WHERE tenant_no='MAIN' AND store_no='S'",Integer.class)>0,false));
            var shared=new CountDownLatch(1);var release=new CountDownLatch(1);var exclusive=new CountDownLatch(1);
            f.ds.exclusiveAttempt.set(exclusive);
            var writer=pool.submit(()->f.tx.execute(tx->{
                f.ownership.lockAndCheck("MAIN","M","S");f.cashRecord();shared.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                return null;
            }));
            assertTrue(shared.await(10,TimeUnit.SECONDS));
            var claim=pool.submit(()->assertThrows(BizException.class,f::create));
            assertTrue(exclusive.await(10,TimeUnit.SECONDS));assertFalse(claim.isDone());
            release.countDown();writer.get(15,TimeUnit.SECONDS);claim.get(15,TimeUnit.SECONDS);
            assertEquals("M",f.entity());assertNull(f.marker());assertEquals(0,f.audits());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim",Integer.class));
            verify(f.peer.api,never()).prepareStoreClaim(any());
        }
    }

    @Test void inFlightActualStaffGrantHoldsSharedOwnershipUntilCommitThenClaimRevokesIt() throws Exception {
        try(var f=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            f.jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at) VALUES('M','CLERK','Clerk','',NOW(),NOW())");
            f.jdbc.update("UPDATE mch_store_role SET deleted=1 WHERE store_no='S' AND role='CLERK'");
            var shared=new CountDownLatch(1);var release=new CountDownLatch(1);var exclusive=new CountDownLatch(1);
            var staff=f.staff(new ai.neargo.shop.spi.user.StoreOwnershipWritePort() {
                @Override public void lockEntityAndCheck(String tenant,String entity) {f.ownership.lockEntityAndCheck(tenant,entity);}
                @Override public void lockAndCheck(String tenant,String entity,String store) {
                    f.ownership.lockAndCheck(tenant,entity,store);shared.countDown();
                    try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                }
            });
            var grant=pool.submit(()->f.tx.execute(tx->staff.grantStore("M","STAFF","S","CLERK",true)));
            assertTrue(shared.await(10,TimeUnit.SECONDS));f.ds.exclusiveAttempt.set(exclusive);
            var claim=pool.submit(f::create);
            assertTrue(exclusive.await(10,TimeUnit.SECONDS));assertFalse(claim.isDone());
            release.countDown();assertNotNull(grant.get(15,TimeUnit.SECONDS));
            assertEquals("COMPLETED",claim.get(15,TimeUnit.SECONDS).state());
            assertEquals("T",f.entity());assertEquals(0,f.staffRoles("S"));
            assertEquals("DISABLED",f.jdbc.queryForObject("SELECT status FROM mch_account WHERE mch_account_no='STAFF'",String.class));
            assertThrows(BizException.class,()->f.tx.execute(tx->staff.grantStore("M","STAFF","S","CLERK",true)));
            assertEquals(0,f.staffRoles("S"));
        }
    }

    @Test void claimingOneOfTwoPlaceholderStoresDoesNotRetireSiblingOrRevokeItsStaffGrant() throws Exception {
        try(var f=new Fixture()) {
            f.jdbc.update("INSERT INTO mch_store(entity_no,store_no,name,status,commerce_model,business_type_code,created_at,updated_at) VALUES('M','SIBLING','sibling','ACTIVE','STORE_V1','RETAIL',NOW(),NOW())");
            f.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES('STAFF','SIBLING','CLERK',NOW(),NOW())");
            var result=f.create();assertEquals("COMPLETED",result.state());assertFalse(result.placeholderRetired());assertFalse(f.peer.command.retireSource());
            assertEquals(0,f.jdbc.queryForObject("SELECT deleted FROM mch_entity WHERE entity_no='M'",Integer.class));
            assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM mch_account WHERE mch_account_no='STAFF'",String.class));
            assertEquals(1,f.staffRoles("SIBLING"));assertEquals(0,f.staffRoles("S"));assertNull(f.marker());
        }
    }
    @Test void inventoryEnabledDeploymentCannotClaimWithoutOwnedReservationProtocol() throws Exception {
        for(String flag:List.of("inventoryEnabled","inventoryBridgeEnabled")) {
            try(var f=new Fixture()) {
                org.springframework.test.util.ReflectionTestUtils.setField(f.policy,flag,true);
                assertThrows(BizException.class,f::create);
                assertEquals("M",f.entity());assertNull(f.marker());assertEquals(0,f.peer.prepareCalls);
                assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim",Integer.class));
            }
        }
    }

}
