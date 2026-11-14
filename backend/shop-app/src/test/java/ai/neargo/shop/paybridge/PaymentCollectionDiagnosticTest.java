package ai.neargo.shop.paybridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class PaymentCollectionDiagnosticTest {
    static PaymentCollectionDiagnosticProperties target() {
        return new PaymentCollectionDiagnosticProperties("USER", "ENTITY", "STORE", 10000L, 30000L);
    }
    @Test void schedulerUsesBoundedCadenceAndStopsWithoutFurtherMessages() {
        var stream=mock(PaymentCollectionStream.class);var clock=mock(Clock.class);
        var scheduler=mock(ScheduledExecutorService.class);
        var job=new PaymentCollectionDiagnosticJob(stream,target(),clock,scheduler);
        job.start();job.start();
        verify(scheduler,times(1)).scheduleAtFixedRate(any(Runnable.class),eq(10000L),eq(10000L),eq(TimeUnit.MILLISECONDS));
        for(long at:List.of(10000L,20000L,30000L)){when(clock.millis()).thenReturn(at);job.tick();}
        assertFalse(job.isRunning());verify(scheduler).shutdownNow();job.tick();
        verify(stream,times(3)).diagnostic(eq(target()),anyString(),anyLong(),anyLong());
    }
    @Test void clockRollbackCannotExtendTheNumberOfSamples() {
        var stream=mock(PaymentCollectionStream.class);var clock=mock(Clock.class);
        var job=new PaymentCollectionDiagnosticJob(stream,target(),clock,mock(ScheduledExecutorService.class));
        when(clock.millis()).thenReturn(100000L);job.start();
        when(clock.millis()).thenReturn(1L);for(int i=0;i<5;i++)job.tick();
        assertFalse(job.isRunning());verify(stream,times(3)).diagnostic(eq(target()),anyString(),anyLong(),eq(1L));
    }
    @Test void schedulerErrorIsObservedAndDoesNotTurnIntoAPaymentOrStopFutureTicks() {
        var stream=mock(PaymentCollectionStream.class);var clock=mock(Clock.class);
        when(stream.diagnostic(any(),anyString(),anyLong(),anyLong())).thenThrow(new IllegalStateException());
        var job=new PaymentCollectionDiagnosticJob(stream,target(),clock,mock(ScheduledExecutorService.class));
        job.start();job.tick();job.tick();verify(stream,times(2)).diagnostic(any(),anyString(),anyLong(),anyLong());job.stop();
    }
    @Test void disabledByDefaultNeedsNoTargetOrStreamBean() {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=api")
                .withUserConfiguration(PaymentCollectionDiagnosticConfiguration.class)
                .run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(PaymentCollectionDiagnosticJob.class).isEmpty());});
    }
    @Test void enabledApiBindsExactTargetsAndStartsLifecycleButOpsProfileDoesNot() {
        var p=new PaymentCollectionProperties();p.setEnabled(true);
        p.setSecret("unit-test-signing-secret-32-characters");
        p.setCursorSecret("unit-test-cursor-secret-32-characters");
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=api",
                "shop.payment-collections.diagnostic.enabled=true",
                "shop.payment-collections.diagnostic.user-no=USER",
                "shop.payment-collections.diagnostic.entity-no=ENTITY",
                "shop.payment-collections.diagnostic.store-no=STORE")
                .withBean(PaymentCollectionProperties.class,()->p)
                .withBean(PaymentCollectionStream.class,()->mock(PaymentCollectionStream.class))
                .withUserConfiguration(PaymentCollectionDiagnosticConfiguration.class)
                .run(c->{assertNull(c.getStartupFailure());
                    assertEquals("STORE",c.getBean(PaymentCollectionDiagnosticProperties.class).storeNo());
                    assertTrue(c.getBean(PaymentCollectionDiagnosticJob.class).isRunning());});
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=ops",
                "shop.payment-collections.diagnostic.enabled=true")
                .withUserConfiguration(PaymentCollectionDiagnosticConfiguration.class)
                .run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(PaymentCollectionDiagnosticJob.class).isEmpty());});
    }
    @Test void malformedTargetsAndUnboundedIntervalsAreRejected() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var v=factory.getValidator();assertTrue(v.validate(target()).isEmpty());
            for(var invalid:List.of(
                    new PaymentCollectionDiagnosticProperties(null,"E","S",10000L,30000L),
                    new PaymentCollectionDiagnosticProperties("U","*","S",10000L,30000L),
                    new PaymentCollectionDiagnosticProperties("U","E","S",1L,30000L),
                    new PaymentCollectionDiagnosticProperties("U","E","S",10000L,3600001L)))
                assertFalse(v.validate(invalid).isEmpty());
        }
    }
    @Test void productionAndDisabledCollectionCannotStartDiagnostics() {
        var p=new PaymentCollectionProperties();p.setEnabled(true);p.setEnvironment("PRODUCTION");
        var config=new PaymentCollectionDiagnosticConfiguration();
        assertThrows(IllegalStateException.class,()->config.paymentCollectionDiagnosticJob(mock(PaymentCollectionStream.class),p,target()));
        p.setEnvironment("SANDBOX");p.setEnabled(false);
        assertThrows(IllegalStateException.class,()->config.paymentCollectionDiagnosticJob(mock(PaymentCollectionStream.class),p,target()));
    }
    static final class Emitter extends SseEmitter {
        final List<String> frames=new CopyOnWriteArrayList<>();
        final CountDownLatch ready=new CountDownLatch(1),sample=new CountDownLatch(1);
        Emitter(){super(0L);}
        @Override public void send(SseEventBuilder frame) throws IOException {
            String text=frame.build().stream().map(x->String.valueOf(x.getData())).collect(java.util.stream.Collectors.joining());
            frames.add(text);if(text.contains("event:ready"))ready.countDown();if(text.contains("diagnostic.ping"))sample.countDown();
        }
        @Override public void complete() {}
        @Override public void completeWithError(Throwable error) {}
    }
    @Test void realWriterScopesSamplesAndNeverWritesBusinessRowsOrPaymentCursor() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:diag-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);db.execute("CREATE TABLE payment_collection_scope(scope_key VARCHAR(512),floor_seq BIGINT,seq BIGINT)");
        var p=new PaymentCollectionProperties();p.setEnabled(true);p.setPollMs(60000);p.setCursorSecret("test-cursor-secret-at-least-32-characters");
        var clock=Clock.systemUTC();var security=new PaymentCollectionSecurity(p,clock);
        var identities=mock(BizIdentityResolver.class);var projection=mock(PaymentCollectionProjection.class);
        var tokens=new MemoryTokenStore(Duration.ofHours(1));
        var user=LoginUser.merchantByUser("USER","test");var other=LoginUser.merchantByUser("OTHER","other");
        var ctx=new BizContext("ENTITY",Set.of(),Set.of(),Set.of("STORE"),"STORE",true);
        when(identities.resolveStrict(any(),eq("STORE"))).thenReturn(ctx);
        String token=tokens.issue(TokenStore.SessionData.of(user)),otherToken=tokens.issue(TokenStore.SessionData.of(other));
        var a=new Emitter();var b=new Emitter();var index=new AtomicInteger();
        try(var stream=new PaymentCollectionStream(ds,p,security,identities,tokens,projection,clock,()->index.getAndIncrement()==0?a:b)) {
            stream.open(user,ctx,token,null);stream.open(other,ctx,otherToken,null);
            assertTrue(a.ready.await(3,TimeUnit.SECONDS));assertTrue(b.ready.await(3,TimeUnit.SECONDS));
            assertEquals(1,stream.diagnostic(target(),"RUN-1",1,clock.millis()));assertTrue(a.sample.await(3,TimeUnit.SECONDS));
            assertTrue(b.frames.stream().noneMatch(x->x.contains("diagnostic.ping")));
            String frame=a.frames.stream().filter(x->x.contains("diagnostic.ping")).findFirst().orElseThrow();
            assertFalse(frame.contains("id:"));assertFalse(frame.contains("payment.succeeded"));assertFalse(frame.contains("payOrderNo"));assertFalse(frame.contains("amountMinor"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_collection_scope",Integer.class));verifyNoInteractions(projection);
            assertEquals(0,stream.diagnostic(new PaymentCollectionDiagnosticProperties("USER","WRONG","STORE",10000L,30000L),"RUN-1",2,clock.millis()));
            tokens.revoke(token);assertEquals(0,stream.diagnostic(target(),"RUN-1",3,clock.millis()));
        }
    }
}
