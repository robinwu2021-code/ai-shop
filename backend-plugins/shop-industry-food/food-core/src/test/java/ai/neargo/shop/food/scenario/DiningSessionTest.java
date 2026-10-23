package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.config.FoodDataScopeRegistration;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.DiningSessionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DiningSessionTest extends FoodWriteGuardFixture {
    static final Instant EXPIRY=Instant.now().plus(2,ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    void run(ContextConsumer<AssertableApplicationContext> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,TableServiceTest.Areas.class,
                Sessions.class).run(test);
    }
    Fixture fixture(AssertableApplicationContext c) {
        var f=new TableConfigurationTest().fixture(c);
        String common="id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                +"created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                +"version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_table_token ("+common+"token_no VARCHAR(32) UNIQUE,table_no VARCHAR(32),"
                +"token_hash VARBINARY(64) UNIQUE,expires_at DATETIME(3),revoked_at DATETIME(3),revoked_reason VARCHAR(255))");
        f.sql().execute("CREATE TABLE fnb_guest_session ("+common+"session_no VARCHAR(32) UNIQUE,check_no VARCHAR(32),"
                +"buyer_no VARCHAR(32),device_no VARCHAR(32),token_hash VARBINARY(64) UNIQUE,expires_at DATETIME(3),"
                +"scope JSON,store_no VARCHAR(32),table_no VARCHAR(32),revoked_at DATETIME(3))");
        return f;
    }
    TableVO table(AssertableApplicationContext c) {
        return c.getBean(TableService.class).createTable("E","A","actor",
                new CreateTableReq("AREA1","T1","四人桌",4,0,null),"table").table();
    }
    @Test void rotatesHashOnlyAndRefusesSecretReplay() {
        run(c -> {
            var f=fixture(c);var t=table(c);var s=c.getBean(DiningSessionService.class);
            var r=new RotateTableTokenReq(EXPIRY,0L);
            var token=s.rotateTableToken("E","A",t.tableNo(),"actor",r,"rotate");
            assertThat(token.token()).isNotBlank();assertThat(token.expiresAt()).isEqualTo(EXPIRY);
            assertThat(s.resolveTableToken(token.token()).storeNo()).isEqualTo("A");
            code(() -> s.rotateTableToken("E","A",t.tableNo(),"actor",r,"rotate"),ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_token",Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_table",Long.class)).isEqualTo(1);
            for(String sql:List.of("SELECT result_json FROM sys_idempotent", "SELECT payload FROM sys_outbox")) {
                for(String value:f.sql().queryForList(sql,String.class)) assertThat(value).doesNotContain(token.token());
            }
            assertThat(token.toString()).doesNotContain(token.token());
        });
    }
    @Test void bootstrapsLimitedSessionAndEndsOnlyTheBearer() {
        run(c -> {
            var f=fixture(c);var t=table(c);var s=c.getBean(DiningSessionService.class);
            var token=s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"rotate");
            var req=new StartDiningSessionReq(token.token(),null);
            var session=s.startSession("E","A","buyer",req,"start");
            assertThat(session.checkNo()).isNull();assertThat(session.expiresAt()).isBefore(EXPIRY);
            assertThat(f.sql().queryForObject("SELECT scope FROM fnb_guest_session",String.class)).contains("TABLE_MENU");
            code(() -> s.startSession("E","B","buyer",req,"wrong"),ErrorCode.NOT_FOUND);
            code(() -> s.startSession("E","A","buyer",req,"start"),ErrorCode.CONFLICT);
            code(() -> s.endSession(session.sessionNo(),"invalid","buyer","end"),ErrorCode.NOT_FOUND);
            code(() -> s.endSession(session.sessionNo(),session.accessToken(),"other","end"),ErrorCode.NOT_FOUND);
            var ended=s.endSession(session.sessionNo(),session.accessToken(),"buyer","end");
            assertThat(ended.operation().status()).isEqualTo("SUCCEEDED");
            assertThat(s.endSession(session.sessionNo(),session.accessToken(),"buyer","end")).isEqualTo(ended);
            assertThat(f.sql().queryForObject("SELECT revoked_at FROM fnb_guest_session",Object.class)).isNotNull();
            for(String value:f.sql().queryForList("SELECT result_json FROM sys_idempotent",String.class))
                assertThat(value).doesNotContain(session.accessToken()).doesNotContain(token.token());
        });
    }
    @Test void rotationRevokesOldCodesAndSessionsAtomically() {
        run(c -> {
            var f=fixture(c);var t=table(c);var s=c.getBean(DiningSessionService.class);
            var old=s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"one");
            s.startSession("E","A",null,new StartDiningSessionReq(old.token(),null),"session");
            var next=s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,1L),"two");
            code(() -> s.resolveTableToken(old.token()),ErrorCode.NOT_FOUND);
            assertThat(s.resolveTableToken(next.token()).tableNo()).isEqualTo(t.tableNo());
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_guest_session WHERE revoked_at IS NULL",Integer.class)).isZero();
            var result=s.revokeTableToken("E","A",t.tableNo(),"actor",new RevokeTableTokenReq(2L,"撤销"),"revoke");
            assertThat(s.revokeTableToken("E","A",t.tableNo(),"actor",new RevokeTableTokenReq(2L,"撤销"),"revoke")).isEqualTo(result);
            code(() -> s.resolveTableToken(next.token()),ErrorCode.NOT_FOUND);
        });
    }
    @Test void rejectsExpiredDisabledForeignAndUnverifiedDevice() {
        run(c -> {
            var f=fixture(c);var t=table(c);var s=c.getBean(DiningSessionService.class);
            code(() -> s.rotateTableToken("E","B",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"x"),ErrorCode.NOT_FOUND);
            code(() -> s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(Instant.EPOCH,0L),"x"),ErrorCode.BAD_REQUEST);
            code(() -> s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(Instant.MAX,0L),"x"),ErrorCode.BAD_REQUEST);
            var token=s.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"rotate");
            code(() -> s.startSession("E","A",null,new StartDiningSessionReq(token.token(),"claimed-device"),"x"),ErrorCode.FORBIDDEN);
            f.sql().update("UPDATE fnb_table SET status='DISABLED'");
            code(() -> s.resolveTableToken(token.token()),ErrorCode.NOT_FOUND);
            f.sql().update("UPDATE fnb_table SET status='ACTIVE'");
            f.sql().update("UPDATE fnb_table_token SET expires_at='2000-01-01 00:00:00'");
            code(() -> s.resolveTableToken(token.token()),ErrorCode.NOT_FOUND);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_guest_session",Integer.class)).isZero();
        });
    }
    @Test void failuresAfterOutboxFlushRollBackEveryCredentialMutation() {
        for(String action:List.of("rotate","start","revoke","end")) {
            run(c -> {
                var f=fixture(c);var t=table(c);var service=c.getBean(DiningSessionService.class);
                TableTokenVO token=null;DiningSessionVO session=null;
                if(!action.equals("rotate")) token=service.rotateTableToken("E","A",t.tableNo(),"actor",
                        new RotateTableTokenReq(EXPIRY,0L),"initial");
                if(action.equals("end")) session=service.startSession("E","A",null,
                        new StartDiningSessionReq(token.token(),null),"initial-session");
                int events=f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class);
                long version=f.sql().queryForObject("SELECT version FROM fnb_table",Long.class);
                var finalToken=token;var finalSession=session;
                c.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
                assertThatThrownBy(() -> {
                    switch(action) {
                        case "rotate" -> service.rotateTableToken("E","A",t.tableNo(),"actor",
                                new RotateTableTokenReq(EXPIRY,0L),"fail");
                        case "start" -> service.startSession("E","A",null,new StartDiningSessionReq(finalToken.token(),null),"fail");
                        case "revoke" -> service.revokeTableToken("E","A",t.tableNo(),"actor",new RevokeTableTokenReq(1L,"撤销"),"fail");
                        default -> service.endSession(finalSession.sessionNo(),finalSession.accessToken(),null,"fail");
                    }
                }).isInstanceOf(IllegalStateException.class).hasMessage("after-session-event-flush");
                assertThat(f.sql().queryForObject("SELECT version FROM fnb_table",Long.class)).isEqualTo(version);
                for(String table:List.of("sys_outbox","sys_idempotent","fnb_action_log"))
                    assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(events);
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_token WHERE revoked_at IS NOT NULL",Integer.class)).isZero();
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_guest_session WHERE revoked_at IS NOT NULL",Integer.class)).isZero();
                if(action.equals("start")) assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_guest_session",Integer.class)).isZero();
            });
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void sameVersionRotationHasOneWinner() {
        run(c -> {
            var f=fixture(c);var t=table(c);var service=c.getBean(DiningSessionService.class);
            var result=race(() -> service.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"left"),
                    () -> service.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"right"));
            assertThat(result).filteredOn(TableTokenVO.class::isInstance).hasSize(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_token WHERE revoked_at IS NULL",Integer.class)).isOne();
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void revocationRacingBootstrapNeverLeavesUsableOldSession() {
        run(c -> {
            var f=fixture(c);var t=table(c);var service=c.getBean(DiningSessionService.class);
            var token=service.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"initial");
            race(() -> service.startSession("E","A",null,new StartDiningSessionReq(token.token(),null),"start"),
                    () -> service.revokeTableToken("E","A",t.tableNo(),"actor",new RevokeTableTokenReq(1L,"撤销"),"revoke"));
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_guest_session WHERE revoked_at IS NULL",Integer.class)).isZero();
            code(() -> service.resolveTableToken(token.token()),ErrorCode.NOT_FOUND);
        });
    }
    List<Object> race(Callable<?> a,Callable<?> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(() -> attempt(a,barrier));var y=pool.submit(() -> attempt(b,barrier));
            return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
        }
    }
    Object attempt(Callable<?> call,CyclicBarrier barrier) throws Exception {
        barrier.await(10,TimeUnit.SECONDS);
        try { return call.call(); } catch(BizException e) {
            assertThat(e.errorCode()).isIn(ErrorCode.CONFLICT,ErrorCode.NOT_FOUND);return e.errorCode();
        }
    }
    @Test void authenticatedCustomerScopeStillUsesCredentialAuthorityAndRestoresSqlContext() {
        run(c -> {
            fixture(c);var t=table(c);var service=c.getBean(DiningSessionService.class);
            var token=service.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"rotate");
            var scope=DataScopeSpec.of(ScopeDim.SELF,Set.of("buyer"));
            DataScopeContext.set(scope);
            try {
                assertThat(service.resolveTableToken(token.token()).storeNo()).isEqualTo("A");
                var session=service.startSession("E","A","buyer",new StartDiningSessionReq(token.token(),null),"start");
                code(() -> service.endSession(session.sessionNo(),session.accessToken(),"other","bad"),ErrorCode.NOT_FOUND);
                service.endSession(session.sessionNo(),session.accessToken(),"buyer","end");
                assertThat(DataScopeContext.current()).isSameAs(scope);
                assertThat(DataScopeContext.isSkipped()).isFalse();
            } finally { DataScopeContext.clear(); }
        });
    }
    @Test void expiredOrRevokedSessionCannotExecuteNewCommands() {
        run(c -> {
            var f=fixture(c);var t=table(c);var service=c.getBean(DiningSessionService.class);
            var token=service.rotateTableToken("E","A",t.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"rotate");
            var session=service.startSession("E","A",null,new StartDiningSessionReq(token.token(),null),"start");
            f.sql().update("UPDATE fnb_guest_session SET expires_at='2000-01-01 00:00:00'");
            code(() -> service.endSession(session.sessionNo(),session.accessToken(),null,"expired"),ErrorCode.NOT_FOUND);
            f.sql().update("UPDATE fnb_guest_session SET expires_at='2099-01-01 00:00:00',revoked_at=CURRENT_TIMESTAMP");
            code(() -> service.endSession(session.sessionNo(),session.accessToken(),null,"revoked"),ErrorCode.NOT_FOUND);
        });
    }
    @Test void anonymousBearerCanEndItsOwnSessionAfterPlatformLoginWithoutClaimingOwnership() {
        run(c -> {
            var f=fixture(c);var table=table(c);var service=c.getBean(DiningSessionService.class);
            var token=service.rotateTableToken("E","A",table.tableNo(),"actor",new RotateTableTokenReq(EXPIRY,0L),"rotate");
            var session=service.startSession("E","A",null,new StartDiningSessionReq(token.token(),null),"anonymous");
            assertThat(service.endSession(session.sessionNo(),session.accessToken(),"logged-buyer","end").operation().status())
                    .isEqualTo("SUCCEEDED");
            assertThat(f.sql().queryForObject("SELECT buyer_no FROM fnb_guest_session",String.class)).isNull();
        });
    }
    static void code(Runnable r,ErrorCode expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    @Configuration(proxyBeanMethods=false)
    static class Sessions {
        @Bean
        FoodDataScopeRegistration foodScopes() {
            return new FoodDataScopeRegistration();
        }
        @Bean
        MapperFactoryBean<TableTokenMapper> tokens(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(TableTokenMapper.class);
            m.setSqlSessionTemplate(t); return m;
        }
        @Bean
        MapperFactoryBean<GuestSessionMapper> guests(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m=new MapperFactoryBean<>(GuestSessionMapper.class);
            m.setSqlSessionTemplate(t); return m;
        }
        @Bean
        DiningSessionService sessions(TableAreaMapper areas,
                TableMapper tables,
                TableTokenMapper tokens,
                GuestSessionMapper guests,
                ActionLogMapper actions,
                IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher pub,
                PlatformTransactionManager manager,
                StorePolicyPublicationTest.Signals signals, DataSource source) {
            OutboxPublisher checked=(event,options) -> {
                int before=signals.afterFlush.get() ? new JdbcTemplate(source)
                        .queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class):0;
                var result=pub.publish(event,options);
                if(signals.afterFlush.get()) TransactionSynchronizationManager
                        .registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                assertThat(new JdbcTemplate(source)
                                        .queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(before+1);
                                throw new IllegalStateException("after-session-event-flush");
                            }
                        });
                return result;
            };
            return new DiningSessionServiceImpl(areas,tables,tokens,guests,actions,idem,checked,manager);
        }
    }
}
