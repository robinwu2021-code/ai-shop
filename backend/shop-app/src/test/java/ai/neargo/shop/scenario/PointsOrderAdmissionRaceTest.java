package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderBindingTest.Config.class)
class PointsOrderAdmissionRaceTest {
    @Test void different_scenes_share_the_store_admission_fence() throws Exception {
        var fixture=new PointsOrderBindingTest(); fixture.policies=policies; fixture.bindings=bindings;
        String store=fixture.activate();
        policies.change(new IntegrationPolicyChange("OFF:"+store,store,"OFFLINE",Mode.ACTIVE,true,true,-1,"QA"),"OPS");
        var base=fixture.admission(store,"S:"+store);
        var admission=new OrderPointsBindingService.Admission(base.requestId(),base.orderNo(),base.subOrderNo(),base.userNo(),base.merchantNo(),base.storeNo(),base.typeCode(),base.currency(),"OFFLINE",0,true,true,base.snapshot());
        var pool=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<Future<OrderPointsBindingService.Binding>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                mapper.lockPolicy(store,"ONLINE");
                pending.set(pool.submit(() -> bindings.admit(admission)));
                assertThatThrownBy(() -> pending.get().get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                policies.change(new IntegrationPolicyChange("DRAIN:"+store,store,"ONLINE",Mode.DRAINING,false,false,0,"QA"),"OPS");
            });
            assertThat(pending.get().get(10,TimeUnit.SECONDS).admission().scene()).isEqualTo("OFFLINE");
        } finally { pool.shutdownNow(); }
    }
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired OrderPointsBindingMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource data;

    @BeforeEach
    void schema() throws Exception {
        try (var c = data.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"))
                .execute(data);
    }

    @Test
    void closing_wins_before_new_admission() throws Exception {
        var fixture = new PointsOrderBindingTest();
        fixture.policies = policies;
        fixture.bindings = bindings;
        String store = fixture.activate();
        var admission = fixture.admission(store, "S:" + store);
        var executor = Executors.newSingleThreadExecutor();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<Boolean>>();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                mapper.lockPolicy(store, "ONLINE");
                                pending.set(
                                        executor.submit(
                                                () -> {
                                                    try {
                                                        bindings.admit(admission);
                                                        return true;
                                                    } catch (IllegalArgumentException closed) {
                                                        assertThat(closed)
                                                                .hasMessageContaining(
                                                                        "ADMISSION_CLOSED");
                                                        return false;
                                                    }
                                                }));
                                assertThatThrownBy(
                                                () -> pending.get().get(300, TimeUnit.MILLISECONDS))
                                        .isInstanceOf(TimeoutException.class);
                                policies.change(
                                        new IntegrationPolicyChange(
                                                "DRAIN:" + store,
                                                store,
                                                "ONLINE",
                                                Mode.DRAINING,
                                                false,
                                                false,
                                                0,
                                                "QA"),
                                        "OPS");
                            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(bindings.find(admission.subOrderNo())).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void committed_admission_survives_later_close() throws Exception {
        var fixture = new PointsOrderBindingTest();
        fixture.policies = policies;
        fixture.bindings = bindings;
        String store = fixture.activate();
        var admission = fixture.admission(store, "S:" + store);
        var executor = Executors.newSingleThreadExecutor();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<IntegrationPolicy>>();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                bindings.admit(admission);
                                pending.set(
                                        executor.submit(
                                                () ->
                                                        policies.change(
                                                                new IntegrationPolicyChange(
                                                                        "DRAIN:" + store,
                                                                        store,
                                                                        "ONLINE",
                                                                        Mode.DRAINING,
                                                                        false,
                                                                        false,
                                                                        0,
                                                                        "QA"),
                                                                "OPS")));
                                assertThatThrownBy(
                                                () -> pending.get().get(300, TimeUnit.MILLISECONDS))
                                        .isInstanceOf(TimeoutException.class);
                            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS).mode()).isEqualTo(Mode.DRAINING);
            assertThat(bindings.admit(admission).admission()).isEqualTo(admission);
        } finally {
            executor.shutdownNow();
        }
    }
}
