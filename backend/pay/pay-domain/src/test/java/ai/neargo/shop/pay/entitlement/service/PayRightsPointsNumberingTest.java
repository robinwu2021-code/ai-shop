package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.*;
import ai.neargo.shop.pay.points.posting.impl.PointsPostingServiceImpl;
import ai.neargo.shop.pay.points.store.PostingStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayRightsPointsNumberingTest {
    @Test void newVoucherBindsInsideOriginalTransactionAndReplayKeepsAlias() {
        var f=new Fixture();
        var first=f.proxy.post(f.voucher);
        assertTrue(first.documentNo().matches("PTV\\d{14}"));
        assertNotEquals(first.documentNo(),first.voucherNo());
        f.replay.set(true);
        var second=f.proxy.post(f.voucher);
        assertTrue(second.replayed());
        assertEquals(first.voucherNo(),second.voucherNo());
        assertEquals(first.documentNo(),second.documentNo());
    }
    @Test void failedPostingRollsBackAliasAndHistoricalReplayDoesNotBackfill() {
        var f=new Fixture();
        doThrow(new IllegalArgumentException("conditional write failed")).when(f.store).applyConditions(any(),anyString());
        assertThrows(IllegalArgumentException.class,()->f.proxy.post(f.voucher));
        assertTrue(f.coordinator.find(DocumentType.POINTS_VOUCHER,f.source()).isEmpty());
        f.replay.set(true);
        assertNull(f.proxy.post(f.voucher).documentNo());
        assertTrue(f.coordinator.find(DocumentType.POINTS_VOUCHER,f.source()).isEmpty());
    }
    static class Fixture {
        final PostingStore store=mock(PostingStore.class);
        final AtomicBoolean replay=new AtomicBoolean();
        final AtomicReference<String> internal=new AtomicReference<>();
        final DocumentNumberCoordinator coordinator;
        final PointsPostingService proxy;
        final Voucher voucher=new Voucher("BIZ","POINT","CNY",new RedeemRate(1,1),List.of(),List.of(),List.of(),0,List.of(),"{}");
        Fixture() {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:rights-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            var manager=new DataSourceTransactionManager(ds);
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE pay_document_number(document_no VARCHAR(32) PRIMARY KEY,tenant_no VARCHAR(64),document_type VARCHAR(40),source_system VARCHAR(32),source_resource_type VARCHAR(64),source_no VARCHAR(128),business_date DATE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
            var sequence=new AtomicInteger();
            DocumentNumberGenerator generator=mock(DocumentNumberGenerator.class);
            when(generator.reserve(any(DocumentType.class),any(Instant.class))).thenAnswer(a -> {
                DocumentType type=a.getArgument(0);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return new ReservedDocumentNumber(type,LocalDate.of(2026,10,20),type.prefix()+"20261020"+String.format("%06d",sequence.incrementAndGet()));
            });
            coordinator=new DocumentNumberCoordinator(new JdbcDocumentNumberRepository(DocumentOwner.PAY,manager),generator,manager);
            var service=new PointsPostingServiceImpl(store);
            ReflectionTestUtils.setField(service,"documentNumbers",new PayRightsDocumentNumbers(coordinator));
            when(store.claim(any(),anyString(),anyString())).thenAnswer(a->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                if(!replay.get()) internal.set(a.getArgument(2));
                return new PostingResult(internal.get(),replay.get(),"{}");
            });
            doAnswer(a->{assertTrue(coordinator.find(DocumentType.POINTS_VOUCHER,source()).isPresent());return null;})
                .when(store).applyConditions(any(),anyString());
            var factory=new ProxyFactory(service);
            var advisor=new PayRightsNumberReservations(coordinator);
            assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE,advisor.getOrder());
            factory.addAdvisor(advisor);
            var tx=new TransactionInterceptor(manager,new MatchAlwaysTransactionAttributeSource());
            factory.addAdvisor(new DefaultPointcutAdvisor(tx));
            proxy=(PointsPostingService)factory.getProxy();
        }
        DocumentSource source() {return new DocumentSource("MAIN","PAY","POINTS_VOUCHER",internal.get());}
    }
}
