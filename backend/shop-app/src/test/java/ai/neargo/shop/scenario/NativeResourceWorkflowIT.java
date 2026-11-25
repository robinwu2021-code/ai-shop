package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchant.service.MerchantRoleService;
import ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl;
import ai.neargo.shop.merchant.service.impl.MerchantRoleServiceImpl;
import ai.neargo.shop.merchant.service.impl.StaffAuditLogger;
import ai.neargo.shop.merchant.port.StoreOwnershipWritePortImpl;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CancellationOutcome;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.merchant.entity.MchStore;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.infra.NativeResourceProofFixture;
import ai.neargo.shop.infra.ResourceOccupationFixtureConfiguration;
import ai.neargo.shop.infra.ResourceOccupationNativeFixture;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourceStartAccess;
import ai.neargo.shop.merchant.port.ResourceExecutionEvidencePortImpl;
import ai.neargo.shop.merchant.port.ResourceOccupationLifecyclePortImpl;
import ai.neargo.shop.merchant.port.ResourceOccupationPortImpl;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.aop.support.AopUtils;
import ai.neargo.shop.merchant.service.ResourceOccupationLifecycleService;
import ai.neargo.shop.merchant.service.ResourceOccupationService;
import ai.neargo.shop.merchant.service.impl.ResourceAdmissionServiceImpl;
import ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl;
import ai.neargo.shop.merchant.service.impl.ResourceOccupationLifecycleServiceImpl;
import ai.neargo.shop.merchant.service.impl.ResourceOccupationServiceImpl;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.Outcome;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Action;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Replacement;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.CleaningRule;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real MySQL schema, real resource services/mappers/permission roots. Only independent source/Core/stock evidence is registered. */
class NativeResourceWorkflowIT {
    private static final Instant T=Instant.parse("2026-11-10T09:00:00Z");
    private final MutableClock clock=new MutableClock();
    private NativeResourceProofFixture proof;
    private JdbcTemplate sql;
    private JdbcTemplate admin;
    private String database;
    private String url;
    private final Map<String,WorkflowAuthority> authorities=new ConcurrentHashMap<>();
    private volatile String racedOperation;
    private volatile CountDownLatch applyAuthorized,cancelAuthorized,dispatchRace;

    @Configuration(proxyBeanMethods=false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    @EnableTransactionManagement
    static class Mappers {
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean static MethodValidationPostProcessor methodValidationPostProcessor() {
            var validation=new MethodValidationPostProcessor();validation.setProxyTargetClass(true);return validation;
        }
    }

    @BeforeEach void database() throws Exception {
        var adminUrl=System.getProperty("resource-booking.test.admin-url",System.getenv("RESOURCE_BOOKING_TEST_ADMIN_URL"));
        assertThat(adminUrl).isNotBlank();
        database="native_workflow_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(adminUrl,"root",""));
        admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url=adminUrl.replace("/mysql?","/"+database+"?");
        var source=new DriverManagerDataSource(url,"root","");sql=new JdbcTemplate(source);
        sql.execute("CREATE TABLE mch_entity(tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),archived_at DATETIME(3),ownership_change_no VARCHAR(32),deleted INT,PRIMARY KEY(tenant_no,entity_no))");
        sql.execute("CREATE TABLE mch_store(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),status VARCHAR(24),ownership_change_no VARCHAR(32),deleted INT,PRIMARY KEY(tenant_no,entity_no,store_no))");
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',NULL,0)");
        String base="id BIGINT PRIMARY KEY AUTO_INCREMENT,tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),updated_at DATETIME(3),created_by VARCHAR(64),updated_by VARCHAR(64),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        sql.execute("CREATE TABLE mch_account("+base+"mch_account_no VARCHAR(32),user_no VARCHAR(32),login_phone VARCHAR(32),display_name VARCHAR(64),is_owner INT,is_primary INT,status VARCHAR(24))");
        sql.execute("CREATE TABLE mch_store_role("+base+"mch_account_no VARCHAR(32),store_no VARCHAR(32),role VARCHAR(32))");
        sql.execute("CREATE TABLE mch_role("+base+"role_code VARCHAR(32),name VARCHAR(64),perms JSON,builtin INT)");
        sql.execute("CREATE TABLE mch_staff_log("+base+"log_no VARCHAR(32),actor_account_no VARCHAR(64),target_account_no VARCHAR(32),action VARCHAR(32),store_no VARCHAR(32),role VARCHAR(32),detail TEXT)");
        sql.execute("INSERT INTO mch_account(entity_no,mch_account_no,is_owner,is_primary,status) VALUES('E','ACCOUNT',1,1,'ACTIVE')");
        try(var c=source.getConnection()) {
            for(var migration:List.of("V592__shared_personnel_foundation.sql","V593__shared_resource_identity.sql",
                    "V594__shared_schedule_rules.sql","V595__shared_resource_booking_foundation.sql"))
                ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/"+migration));
        }
        ResourceOccupationNativeFixture.install(source);
        proof=NativeResourceProofFixture.create(T);
        ResourceOccupationNativeFixture.insert(sql,"mch_personnel",Map.of("personnel_no","PERSON","name","Person","status","ACTIVE"));
        ResourceOccupationNativeFixture.insert(sql,"mch_personnel_account",Map.of("link_no","LINK","personnel_no","PERSON","mch_account_no","ACCOUNT"));
        var p=proof.source().currentPersonnel();
        ResourceOccupationNativeFixture.insert(sql,"mch_personnel_store",Map.of("assignment_no","EMPLOYMENT","personnel_no","PERSON","store_no","S",
                "valid_from",LocalDateTime.ofInstant(p.validFrom(),ZoneOffset.UTC),"valid_to",LocalDateTime.ofInstant(p.validTo(),ZoneOffset.UTC),"status","ACTIVE","public_visible",1,"sort_order",0));
        for(var no:List.of("STAFF1","ROOM1","ROOM2")) {
            if(no.equals("STAFF1")) ResourceOccupationNativeFixture.insert(sql,"mch_resource",Map.of("resource_no",no,"resource_type","STAFF","personnel_no","PERSON","name",no,"capacity",1,"status","ACTIVE"));
            else ResourceOccupationNativeFixture.insert(sql,"mch_resource",Map.of("resource_no",no,"resource_type","ROOM","store_no","S","name",no,"capacity",1,"status","ACTIVE"));
            sql.update("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) VALUES('E',NOW(),NOW(),?,?,'S','UTC',127,0,1440,0,'2020-01-01','ACTIVE')","RULE_"+no,no);
        }
    }
    @AfterEach void cleanup() { if(admin!=null && database!=null) admin.execute("DROP DATABASE "+database); }

    private ApplicationContextRunner runner() {
        var stock=mock(ExecutionStockRetentionPort.class);
        when(stock.queryOriginalV2(any())).thenReturn(proof.retention());
        var source=new ServiceTaskStartAuthorityPort() {
            @Override public StartAuthority authorizeCurrent(StartAuthorityLookup lookup) { assertThat(lookup).isEqualTo(proof.request().source());return proof.source(); }
            @Override public StartAuthorityOriginal queryOriginal(StartAuthorityLookup lookup) { throw new AssertionError("no cancellation requested"); }
        };
        var core=new ServiceExecutionEvidencePort() {
            @Override public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup lookup) { assertThat(lookup.reference()).isEqualTo(proof.request().reference());return proof.core(); }
            @Override public CurrentActor authorizeCurrent(ExecutionEvidenceLookup lookup) { throw new AssertionError("Native may not call creator-only Core HUMAN gate"); }
        };
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,Mappers.class,
                ResourceOccupationFixtureConfiguration.class,ResourceCommandServiceImpl.class,ResourceAdmissionServiceImpl.class,
                ResourceStartAccess.class,ResourceExecutionEvidencePortImpl.class,ResourceOccupationServiceImpl.class,
                ResourceOccupationPortImpl.class,ResourceOccupationLifecyclePortImpl.class,
                ResourceOccupationLifecycleServiceImpl.class,MerchantStaffServiceImpl.class,MerchantRoleServiceImpl.class,
                StaffAuditLogger.class,StoreOwnershipWritePortImpl.class)
                .withBean(AuditMetaObjectHandler.class).withBean(IdentifierGenerator.class,()->new DefaultIdentifierGenerator(1,1))
                .withBean("resourceOccupationClock",Clock.class,()->clock)
                .withBean(ServiceTaskStartAuthorityPort.class,()->source).withBean(ServiceExecutionEvidencePort.class,()->core)
                .withBean(ExecutionStockRetentionPort.class,()->stock)
                .withBean(PersonnelOperatorFactsPort.class,()->mock(PersonnelOperatorFactsPort.class))
                .withBean(TokenStore.class,()->mock(TokenStore.class))
                .withBean(TokenStores.class,()->TokenStores.single(mock(TokenStore.class)))
                .withBean(OtpStore.class,()->mock(OtpStore.class))
                .withBean(ServiceTaskWorkflowAuthorityPort.class,()->lookup -> {
                    var a=authorities.get(lookup.submissionNo()); assertThat(a).isNotNull();
                    if(lookup.submissionNo().equals(racedOperation)) {
                        (a.action()==ServiceTaskWorkflowAuthorityPort.Action.CANCEL?cancelAuthorized:applyAuthorized).countDown();
                        try {assertThat(dispatchRace.await(10,TimeUnit.SECONDS)).isTrue();}
                        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                    }
                    return a;
                }).withPropertyValues("spring.datasource.url="+url,"spring.datasource.username=root","spring.datasource.password=");
    }
    private OriginalResult start(ApplicationContext ctx) {
        var original=human(() -> ctx.getBean(ResourceOccupationPort.class).start(proof.request()).original());
        assertThat(original.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(human(() -> ctx.getBean(ResourceOccupationPort.class).start(proof.request()).original())).isEqualTo(original);
        assertThat(count("mch_resource_occupation")).isOne(); return original;
    }
    private Request request(OriginalResult start,long revision,Action action,List<Replacement> replacements,List<String> cleaning,String plan) {
        String no="WF"+UUID.randomUUID().toString().replace("-","").substring(0,29);
        var lookup=new WorkflowAuthorityLookup(start.original().reference(),no,NativeResourceProofFixture.H);
        var original=new OriginalLookup(start.original().reference(),start.original().source(),start.original().requestKey(),start.original().requestHash(),start.actual());
        var request=new Request(lookup,original,start.applied().occupationNo(),revision,action,no,ExecutionEvidenceContract.hash("NATIVE_WORKFLOW_TEST_KEY_V1",no),"",replacements,cleaning).signed();
        var rules=sql.queryForList("SELECT DISTINCT logical_demand_no,personnel_no FROM mch_resource_occupation_segment").stream()
                .map(r -> new CleaningRule((String)r.get("logical_demand_no"),r.get("personnel_no")==null?"MANUAL":"NONE",0)).toList();
        var authority=new WorkflowAuthority("SERVICE_TASK_WORKFLOW_AUTHORITY_V1",lookup,ServiceTaskWorkflowAuthorityPort.Action.valueOf(action.name()),
                no,request.payloadHash(),revision,NativeResourceProofFixture.ACTOR,"ACCOUNT",proof.source().currentPersonnel(),proof.source().acceptedRoster(),
                plan,plan==null?null:ResourcePlanContract.hash(plan),rules,clock.instant(),clock.instant().plusSeconds(15),null).signed();
        authorities.put(no,authority);return request;
    }
    private Result execute(ApplicationContext ctx,Request request) { return human(() -> ctx.getBean(ResourceOccupationLifecyclePort.class).execute(request)); }
    private static <T> T human(Supplier<T> call) { return DelegatedIdentityContext.call(NativeResourceProofFixture.ACTOR,call); }
    private long count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private long used(ApplicationContext ctx,String resource) {
        return ctx.getBean(ResourceOccupationGuard.class).currentUsed(new ResourceCommandScopeRow("MAIN","E","S"),resource,
                LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
    }

    @Test void actualStartPauseResumeHandoverEndAndManualCleaningKeepPermanentOriginals() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(ctx.getBean(ResourceOccupationLifecyclePort.class))).isTrue();
            var original=start(ctx);var workflow=ctx.getBean(ResourceOccupationLifecycleService.class);
            var pause=request(original,1,Action.PAUSE,List.of(),List.of(),null);
            var paused=execute(ctx,pause);assertThat(paused.state()).isEqualTo("PAUSED");assertThat(used(ctx,"ROOM1")).isOne();
            assertThat(execute(ctx,pause)).isEqualTo(paused);
            var resumed=execute(ctx,request(original,2,Action.RESUME,List.of(),List.of(),null));assertThat(resumed.state()).isEqualTo("OPEN");
            var oldRoom=resumed.segments().stream().filter(s -> s.resourceNo().equals("ROOM1")&&s.endedAt()==null).findFirst().orElseThrow();
            var transfer=request(original,3,Action.HANDOVER,List.of(new Replacement(oldRoom.segmentNo(),"ROOM2")),List.of(),
                    NativeResourceProofFixture.resourcePlan(T).replace("ROOM1","ROOM2"));
            var transferred=execute(ctx,transfer);assertThat(transferred.revision()).isEqualTo(4);
            assertThat(transferred.segments().stream().filter(s -> s.resourceNo().equals("ROOM1"))).allMatch(s -> s.endedAt()!=null&&s.cleaningRequired());
            assertThat(used(ctx,"ROOM1")).isOne();assertThat(used(ctx,"ROOM2")).isOne();
            var end=request(original,4,Action.END,List.of(),List.of(),null);var ended=execute(ctx,end);
            assertThat(ended.state()).isEqualTo("CLEANING");assertThat(ended.segments()).allMatch(s -> s.endedAt()!=null);
            assertThat(workflow.queryOriginal(end)).contains(ended);assertThat(execute(ctx,end)).isEqualTo(ended);
            assertThat(count("mch_resource_occupation_unit")).isEqualTo(2);
            var dirty=ended.segments().stream().filter(s -> s.cleaningRequired()&&s.cleaningCompletedAt()==null).map(s -> s.segmentNo()).toList();
            var clean=request(original,5,Action.CLEAN,List.of(),dirty,null);var cleaned=execute(ctx,clean);
            assertThat(cleaned.state()).isEqualTo("ENDED");assertThat(used(ctx,"ROOM1")).isZero();assertThat(used(ctx,"ROOM2")).isZero();
            assertThat(execute(ctx,clean)).isEqualTo(cleaned);assertThat(workflow.queryOriginal(end)).contains(ended);
            assertThat(sql.queryForObject("SELECT SUM(occupied_qty) FROM mch_resource_bucket",Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_occupation_bucket WHERE retired_at IS NULL",Long.class)).isZero();
        });
    }

    @Test void overrunAndZeroMinuteManualCleaningNeverAutomaticallyFreeCapacity() {
        runner().run(ctx -> {
            var original=start(ctx);clock.at.set(T.plusSeconds(7200));assertThat(used(ctx,"ROOM1")).isOne();
            var ended=execute(ctx,request(original,1,Action.END,List.of(),List.of(),null));assertThat(ended.state()).isEqualTo("CLEANING");
            clock.at.set(T.plusSeconds(172800));assertThat(used(ctx,"ROOM1")).isOne();
            var dirty=ended.segments().stream().filter(s -> s.cleaningRequired()).map(s -> s.segmentNo()).toList();
            execute(ctx,request(original,2,Action.CLEAN,List.of(),dirty,null));assertThat(used(ctx,"ROOM1")).isZero();
        });
    }

    @Test void sameRevisionConcurrentPauseAndEndApplyOnlyOneOriginalEffect() throws Exception {
        runner().run(ctx -> {
            var original=start(ctx);var pause=request(original,1,Action.PAUSE,List.of(),List.of(),null);
            var end=request(original,1,Action.END,List.of(),List.of(),null);var ready=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var attempts=List.of(pause,end).stream().map(r -> pool.submit(() -> {
                    ready.await();try { execute(ctx,r);return true; } catch(IllegalStateException conflict) {return false;}
                })).toList();ready.countDown();
                int applied=0;for(var attempt:attempts) if(attempt.get(20,TimeUnit.SECONDS)) applied++;
                assertThat(applied).isOne();
                assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isEqualTo(2);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_command WHERE action<>'START'",Long.class)).isOne();
            } catch(Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void deniedHandoverAndWrongRevisionLeaveOldOccupiedResourceUntouched() {
        runner().run(ctx -> {
            var original=start(ctx);
            assertThatThrownBy(() -> execute(ctx,request(original,2,Action.PAUSE,List.of(),List.of(),null))).isInstanceOf(IllegalStateException.class);
            var oldRoom=sql.queryForObject("SELECT segment_no FROM mch_resource_occupation_segment WHERE resource_no='ROOM1'",String.class);
            sql.update("UPDATE mch_resource SET status='SUSPENDED' WHERE resource_no='ROOM2'");
            assertThatThrownBy(() -> execute(ctx,request(original,1,Action.HANDOVER,List.of(new Replacement(oldRoom,"ROOM2")),List.of(),
                    NativeResourceProofFixture.resourcePlan(T).replace("ROOM1","ROOM2")))).isInstanceOf(IllegalStateException.class);
            assertThat(used(ctx,"ROOM1")).isOne();assertThat(count("mch_resource_occupation_segment")).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isOne();
        });
    }
    private WorkflowAuthority cancelAuthority(Request request) {
        var a=authorities.get(request.actionNo());
        var cancel=new WorkflowAuthority(a.schema(),a.lookup(),ServiceTaskWorkflowAuthorityPort.Action.CANCEL,a.actionNo(),a.actionHash(),
                a.expectedOccupationRevision(),a.actor(),a.canonicalAccountNo(),a.currentPersonnel(),a.acceptedRoster(),a.replacementResourcePlanJson(),
                a.replacementResourcePlanHash(),a.cleaningRules(),clock.instant(),clock.instant().plusSeconds(15),null).signed();
        authorities.put(request.actionNo(),cancel);return cancel;
    }
    @Test void failedOriginalRequiresRealNoEffectSealBeforeNewKeyAndRejectsLateExecute() {
        runner().run(ctx -> {
            var original=start(ctx);var service=ctx.getBean(ResourceOccupationLifecycleService.class);
            var room=sql.queryForObject("SELECT segment_no FROM mch_resource_occupation_segment WHERE resource_no='ROOM1'",String.class);
            sql.update("UPDATE mch_resource SET status='SUSPENDED' WHERE resource_no='ROOM2'");
            var failed=request(original,1,Action.HANDOVER,List.of(new Replacement(room,"ROOM2")),List.of(),
                    NativeResourceProofFixture.resourcePlan(T).replace("ROOM1","ROOM2"));
            var originalAuthority=authorities.get(failed.actionNo());
            assertThatThrownBy(() -> execute(ctx,failed)).isInstanceOf(IllegalStateException.class);
            assertThat(service.queryOriginal(failed)).isEmpty();assertThat(service.queryCancellationOriginal(failed)).isEmpty();
            cancelAuthority(failed);var seal=human(() -> service.cancelBeforeEffect(failed));seal.validate();
            assertThat(seal.outcome()).isEqualTo(CancellationOutcome.NO_EFFECT_SEALED);
            assertThat(service.queryCancellationOriginal(failed)).contains(seal);
            authorities.put(failed.actionNo(),originalAuthority);
            assertThatThrownBy(() -> execute(ctx,failed)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SEALED");
            assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isOne();
            assertThat(used(ctx,"ROOM1")).isOne();
            assertThat(execute(ctx,request(original,1,Action.PAUSE,List.of(),List.of(),null)).state()).isEqualTo("PAUSED");
        });
    }
    @Test void cancellingAppliedOriginalReturnsItsActualResultWithoutReleasingAnything() {
        runner().run(ctx -> {
            var original=start(ctx);var service=ctx.getBean(ResourceOccupationLifecycleService.class);
            var pause=request(original,1,Action.PAUSE,List.of(),List.of(),null);var applied=execute(ctx,pause);
            cancelAuthority(pause);var cancellation=human(() -> service.cancelBeforeEffect(pause));
            assertThat(cancellation.outcome()).isEqualTo(CancellationOutcome.APPLIED);assertThat(cancellation.applied()).isEqualTo(applied);
            assertThat(used(ctx,"ROOM1")).isOne();assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isEqualTo(2);
        });
    }
    @Test void acceptedHumanApplyAndDurableCancelRaceToOnePermanentNativeOutcome() {
        runner().run(ctx -> {
            var original=start(ctx);var service=ctx.getBean(ResourceOccupationLifecycleService.class);
            var pause=request(original,1,Action.PAUSE,List.of(),List.of(),null);
            racedOperation=pause.actionNo();applyAuthorized=new CountDownLatch(1);cancelAuthorized=new CountDownLatch(1);dispatchRace=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var apply=pool.submit(() -> {
                    try {return java.util.Optional.of(execute(ctx,pause));}
                    catch(IllegalStateException sealed) {assertThat(sealed).hasMessageContaining("SEALED");return java.util.Optional.<Result>empty();}
                });
                assertThat(applyAuthorized.await(10,TimeUnit.SECONDS)).isTrue();
                cancelAuthority(pause);
                var cancel=pool.submit(() -> human(() -> service.cancelBeforeEffect(pause)));
                assertThat(cancelAuthorized.await(10,TimeUnit.SECONDS)).isTrue();dispatchRace.countDown();
                var applied=apply.get(20,TimeUnit.SECONDS);var cancellation=cancel.get(20,TimeUnit.SECONDS);cancellation.validate();
                if(applied.isPresent()) {
                    assertThat(cancellation.outcome()).isEqualTo(CancellationOutcome.APPLIED);
                    assertThat(cancellation.applied()).isEqualTo(applied.get());
                    assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isEqualTo(2);
                } else {
                    assertThat(cancellation.outcome()).isEqualTo(CancellationOutcome.NO_EFFECT_SEALED);
                    assertThat(sql.queryForObject("SELECT revision FROM mch_resource_occupation",Long.class)).isOne();
                }
                assertThat(service.queryCancellationOriginal(pause)).contains(cancellation);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_command WHERE command_no=?",Long.class,pause.actionNo())).isOne();
                assertThat(used(ctx,"ROOM1")).isOne();
            } catch(Exception failure) {throw new AssertionError(failure);}
            finally {racedOperation=null;if(dispatchRace!=null) dispatchRace.countDown();}
        });
    }
    @Test void currentParticipationProofHasImmutableJournalAndRevokedEmploymentDoesNotFreeCapacity() {
        runner().run(ctx -> {
            var original=start(ctx);var service=ctx.getBean(ResourceOccupationLifecycleService.class);
            var current=human(() -> service.validateCurrentStart(proof.request().source(),proof.request().lookup()));current.validate();
            assertThat(current.occupationRevision()).isOne();
            assertThat(sql.queryForObject("SELECT status FROM mch_resource_command WHERE command_no=?",String.class,current.validationCommandNo())).isEqualTo("SUCCEEDED");
            var pause=request(original,1,Action.PAUSE,List.of(),List.of(),null);execute(ctx,pause);
            var resume=request(original,2,Action.RESUME,List.of(),List.of(),null);execute(ctx,resume);
            var resumed=human(() -> service.validateCurrentWorkflow(resume));resumed.validate();
            assertThat(resumed.workflow()).isEqualTo(resume.authority());assertThat(resumed.occupationRevision()).isEqualTo(3);
            sql.update("UPDATE mch_personnel_store SET status='INACTIVE',version=version+1 WHERE assignment_no='EMPLOYMENT'");
            assertThatThrownBy(() -> human(() -> service.validateCurrentWorkflow(resume))).isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_command WHERE action='VALIDATE_WORKFLOW'",Long.class)).isEqualTo(2);
            assertThat(used(ctx,"ROOM1")).isOne();
        });
    }
    private void staffedRole() {
        sql.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no='ACCOUNT'");
        sql.update("INSERT INTO mch_role(entity_no,role_code,name,perms,builtin) VALUES('E','ROLE','Staff',?,0)",
                "[\"biz:service:task:self\",\"biz:service:task:coordinate\"]");
        sql.update("INSERT INTO mch_store_role(entity_no,mch_account_no,store_no,role) VALUES('E','ACCOUNT','S','ROLE')");
    }
    @Test void currentRoleRevocationRacesActualStartWithoutDeadlockOrFalseRelease() {
        staffedRole();runner().run(ctx -> {
            var ready=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var start=pool.submit(() -> {ready.await();return human(() -> ctx.getBean(ResourceOccupationPort.class).start(proof.request()).original());});
                var revoke=pool.submit(() -> {ready.await();return human(() -> ctx.getBean(MerchantRoleService.class)
                        .update("E","role","Staff",List.of("biz:service:task:coordinate")));});
                ready.countDown();var result=start.get(20,TimeUnit.SECONDS);revoke.get(20,TimeUnit.SECONDS);
                if(result.outcome()==Outcome.APPLIED) {
                    assertThat(sql.queryForObject("SELECT stop_obligation FROM mch_resource_occupation",Integer.class)).isOne();
                    assertThat(used(ctx,"ROOM1")).isOne();
                } else {
                    assertThat(result.outcome()).isEqualTo(Outcome.SEALED_NO_EFFECT);assertThat(count("mch_resource_occupation")).isZero();
                    assertThat(used(ctx,"ROOM1")).isZero();
                }
            } catch(Exception e) {throw new AssertionError(e);}
        });
    }
    @Test void disablingActualStaffWriterRecordsStopObligationAndKeepsOriginalCapacity() {
        staffedRole();runner().run(ctx -> {
            var original=start(ctx);
            // This fixture only needs a store name projection for the unchanged staff response assembler.
            var table=TableInfoHelper.getTableInfo(MchStore.class);
            var columns=new java.util.HashSet<>(sql.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mch_store'",String.class));
            var mapped=new java.util.ArrayList<String>();mapped.add(table.getKeyColumn());table.getFieldList().forEach(f -> mapped.add(f.getColumn()));
            for(var column:mapped) if(columns.add(column)) sql.execute("ALTER TABLE mch_store ADD COLUMN "+column+" TEXT NULL");
            human(() -> ctx.getBean(MerchantStaffService.class).setStatus("E","ACCOUNT",false));
            assertThat(sql.queryForObject("SELECT stop_obligation FROM mch_resource_occupation",Integer.class)).isOne();
            assertThat(used(ctx,"ROOM1")).isOne();
            assertThatThrownBy(() -> execute(ctx,request(original,2,Action.PAUSE,List.of(),List.of(),null))).isInstanceOf(RuntimeException.class);
        });
    }
    @Test void successorStaffNeedOnlyCoverActualHandoverRemainder() {
        runner().run(ctx -> {
            var original=start(ctx);clock.at.set(T.plusSeconds(2700));
            var from=T.plusSeconds(1800);var until=T.plusSeconds(86400);
            ResourceOccupationNativeFixture.insert(sql,"mch_personnel",Map.of("personnel_no","PERSON2","name","Second","status","ACTIVE"));
            ResourceOccupationNativeFixture.insert(sql,"mch_personnel_account",Map.of("link_no","LINK2","personnel_no","PERSON2","mch_account_no","ACCOUNT2"));
            ResourceOccupationNativeFixture.insert(sql,"mch_personnel_store",Map.of("assignment_no","EMPLOYMENT2","personnel_no","PERSON2","store_no","S","valid_from",LocalDateTime.ofInstant(from,ZoneOffset.UTC),"valid_to",LocalDateTime.ofInstant(until,ZoneOffset.UTC),"status","ACTIVE","public_visible",1,"sort_order",0));
            ResourceOccupationNativeFixture.insert(sql,"mch_resource",Map.of("resource_no","STAFF2","resource_type","STAFF","personnel_no","PERSON2","name","Second","capacity",1,"status","ACTIVE"));
            sql.update("INSERT INTO mch_account(entity_no,mch_account_no,is_owner,is_primary,status) VALUES('E','ACCOUNT2',1,0,'ACTIVE')");
            sql.update("INSERT INTO mch_schedule_rule(entity_no,created_at,updated_at,rule_no,resource_no,store_no,timezone,weekday_mask,start_minute,end_minute,end_day_offset,valid_from,status) VALUES('E',NOW(),NOW(),'RULE_STAFF2','STAFF2','S','UTC',127,570,1440,0,'2020-01-01','ACTIVE')");
            var old=sql.queryForObject("SELECT segment_no FROM mch_resource_occupation_segment WHERE resource_no='STAFF1'",String.class);
            var transfer=request(original,1,Action.HANDOVER,List.of(new Replacement(old,"STAFF2")),List.of(),
                    NativeResourceProofFixture.resourcePlan(T).replace("STAFF1","STAFF2"));
            var a=authorities.get(transfer.actionNo());var roster=new java.util.ArrayList<>(a.acceptedRoster());
            roster.add(new AcceptedStaffRef("ASSIGNMENT2",1,"STAFF_REQ",1,"THERAPIST","STAFF2","PERSON2","ACCEPT2",NativeResourceProofFixture.H,NativeResourceProofFixture.H,
                    new DelegatedIdentity("MAIN","MERCHANT","MCH","ACCOUNT2"),"ACCOUNT2",new OperatorPersonnelFact("PERSON2","EMPLOYMENT2",0,0,0,from,until)));
            authorities.put(transfer.actionNo(),new WorkflowAuthority(a.schema(),a.lookup(),a.action(),a.actionNo(),a.actionHash(),a.expectedOccupationRevision(),
                    a.actor(),a.canonicalAccountNo(),a.currentPersonnel(),roster,a.replacementResourcePlanJson(),a.replacementResourcePlanHash(),a.cleaningRules(),a.issuedAt(),a.validUntil(),null).signed());
            var result=execute(ctx,transfer);assertThat(result.segments()).anyMatch(s -> s.resourceNo().equals("STAFF2")&&s.startedAt().equals(clock.instant())&&s.endedAt()==null);
            assertThat(count("mch_resource_occupation_unit")).isEqualTo(2);assertThat(used(ctx,"STAFF1")).isZero();assertThat(used(ctx,"STAFF2")).isOne();
        });
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> at=new AtomicReference<>(T);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at.get(); }
    }
}
