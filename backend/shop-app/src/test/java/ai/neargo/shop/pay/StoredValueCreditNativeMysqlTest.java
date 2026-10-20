package ai.neargo.shop.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvEntryMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvLotMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvPostingLineMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvUserStoreIndexMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvVoucherMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueStoreProjector;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueCreditServiceImpl;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValuePostingServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeWriter;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueRechargeWriterImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import ai.neargo.shop.support.StoredValueCreditFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Actual production posting service and pay transaction owner, against a disposable native MySQL only. */
@EnabledIfEnvironmentVariable(named = "SV_CREDIT_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class StoredValueCreditNativeMysqlTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
    @Test void receivedPostingIsAtomicBalancedAndUniqueUnderNativeConcurrency() throws Exception {
        String url = System.getenv("SV_CREDIT_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/sv_credit_native_fixture\\?.*");
        var ds = new DriverManagerDataSource(url, "root", ""); var jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V478__entitlement_catalog.sql"),
                new ClassPathResource("db/migration/V482__pay_entitlement_event_outbox_inbox.sql"),
                new ClassPathResource("db/migration/V498__stored_value_credit_ledger.sql"),
                new ClassPathResource("db/migration/V499__stored_value_total_balance_bound.sql"),
                new ClassPathResource("db/migration/V501__stored_value_receipt_lifecycle.sql")).execute(ds);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor(); interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setPlugins(interceptor); SqlSessionFactory sessions = factory.getObject();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class, StoredValueCreditServiceImpl.class, StoredValuePostingServiceImpl.class,
                    StoredValueStoreProjector.class, PayEntitlementEventPublisher.class, StoredValueRechargeWriterImpl.class,
                    EntitlementCommandDeduplicator.class);
            context.registerBean("payTxManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(ds));
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            register(context, SvAccountMapper.class, sessions); register(context, SvRechargeMapper.class, sessions);
            register(context, SvLotMapper.class, sessions); register(context, SvVoucherMapper.class, sessions);
            register(context, SvEntryMapper.class, sessions); register(context, SvPostingLineMapper.class, sessions);
            register(context, SvUserStoreIndexMapper.class, sessions); register(context, PayEntOutboxMapper.class, sessions);
            register(context, PayEntCommandDedupMapper.class, sessions);
            context.refresh();
            var json = context.getBean(ObjectMapper.class); var credits = context.getBean(StoredValueCreditService.class);
            var actor = new EntitlementContext("MAIN", "NATIVE_WORKER", "NATIVE", "a".repeat(64));
            String user = UUID.randomUUID().toString();
            var command = StoredValueCreditFixture.received(jdbc, json, user, "M1", "S1", 10000, 2000);
            var jobs = new ArrayList<Callable<String>>();
            for (int n = 0; n < 20; n++) jobs.add(() -> credits.credit(actor, command).status().name());
            try (var pool = Executors.newFixedThreadPool(6)) {
                for (var result : pool.invokeAll(jobs)) assertThat(result.get()).isEqualTo("CREDITED");
            }
            assertThat(jdbc.queryForObject("SELECT account_seq FROM sv_account WHERE user_no=?", Long.class, user))
                    .isOne();
            assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE user_no=?",
                    Long.class, user)).isEqualTo(12000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_posting_line", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) "
                    + "FROM sv_posting_line", Long.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_posting_line SET amount_minor=0"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_account SET principal_minor=9223372036854775807,bonus_minor=1"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_account SET principal_minor=-1"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_recharge SET actual_amount_minor=NULL"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_recharge SET collection_method=NULL"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE sv_recharge SET permit_snapshot='invalid-json'"))
                    .isInstanceOf(RuntimeException.class);
            var failed = StoredValueCreditFixture.received(jdbc, json, "ROLLBACK", "M1", "S2", 5000, 0);
            jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_native_credit CHECK (aggregate_no<>'"
                    + failed.rechargeNo() + "')");
            try { assertThatThrownBy(() -> credits.credit(actor, failed)).isInstanceOf(RuntimeException.class); }
            finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_native_credit"); }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no='ROLLBACK'", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?", String.class,
                    failed.rechargeNo())).isEqualTo("RECEIVED");
            assertThat(credits.credit(actor, failed).status().name()).isEqualTo("CREDITED");
            manualReceiptFactsAreAtomicUniqueAndRaceSafe(context, jdbc, json);
        }
    }
    /** Core source freezing is proved by the real-core scenario; native tests exercise the actual pay writer. */
    private void manualReceiptFactsAreAtomicUniqueAndRaceSafe(AnnotationConfigApplicationContext context,
                                                              JdbcTemplate jdbc, ObjectMapper json) throws Exception {
        var writer = context.getBean(StoredValueRechargeWriter.class);
        var source = sourceFixture();
        var create = new StoredValuePort.CreateRecharge(source.source(), source.permit());
        var row = writer.create(source.context(), "NATIVE_MERCHANT", create, source);
        var submitted = writer.submit(commandContext(), "NATIVE_MERCHANT", new StoredValuePort.SubmitRecharge(
                row.rechargeNo(), row.revision(), 1000, StoredValuePort.CollectionMethod.TRANSFER,
                "native-private-receipt", "c".repeat(64)));
        var receive = new StoredValuePort.ReceiveRecharge(row.rechargeNo(), submitted.revision(), "c".repeat(64), null);
        var receiveContext = commandContext();
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_native_receive CHECK (aggregate_no<>'"
                + row.rechargeNo() + "')");
        try { assertThatThrownBy(() -> writer.receive(receiveContext, "NATIVE_MERCHANT", receive))
                .isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_native_receive"); }
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?", String.class,
                row.rechargeNo())).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT received_receipt_key FROM sv_recharge WHERE recharge_no=?", String.class,
                row.rechargeNo())).isNull();
        var received = writer.receive(receiveContext, "NATIVE_MERCHANT", receive);
        assertThat(received.status()).isEqualTo(StoredValuePort.RechargeState.RECEIVED);
        assertThat(writer.receive(receiveContext, "NATIVE_MERCHANT", receive)).isEqualTo(received);
        var decision = json.readValue(jdbc.queryForObject("SELECT received_decision_snapshot FROM sv_recharge "
                + "WHERE recharge_no=?", String.class, row.rechargeNo()), DecisionRef.class);
        assertThat(context.getBean(StoredValueCreditService.class).credit(source.context(),
                new StoredValuePort.CreditRecharge(row.rechargeNo(), new SourceRef("PAY", "RECHARGE", row.rechargeNo(),
                        null, null, null, 0), decision)).status()).isEqualTo(StoredValuePort.RechargeState.CREDITED);
        var second = sourceFixture();
        var secondRow = writer.create(second.context(), "NATIVE_MERCHANT",
                new StoredValuePort.CreateRecharge(second.source(), second.permit()), second);
        var duplicate = writer.submit(commandContext(), "NATIVE_MERCHANT", new StoredValuePort.SubmitRecharge(
                secondRow.rechargeNo(), secondRow.revision(), 1000, StoredValuePort.CollectionMethod.TRANSFER,
                "native-private-receipt", "c".repeat(64)));
        assertThatThrownBy(() -> writer.receive(commandContext(), "NATIVE_MERCHANT",
                new StoredValuePort.ReceiveRecharge(duplicate.rechargeNo(), duplicate.revision(), "c".repeat(64), null)))
                .isInstanceOf(RuntimeException.class);
        var race = sourceFixture();
        var initial = writer.create(race.context(), "NATIVE_MERCHANT",
                new StoredValuePort.CreateRecharge(race.source(), race.permit()), race);
        var ready = writer.submit(commandContext(), "NATIVE_MERCHANT", new StoredValuePort.SubmitRecharge(
                initial.rechargeNo(), initial.revision(), 1000, StoredValuePort.CollectionMethod.CASH,
                UUID.randomUUID().toString(), "c".repeat(64)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            var receiveFuture = pool.submit(() -> {
                latch.await(); return attempt(() -> writer.receive(commandContext(), "NATIVE_MERCHANT",
                        new StoredValuePort.ReceiveRecharge(ready.rechargeNo(), ready.revision(), "c".repeat(64), null)));
            });
            var cancelFuture = pool.submit(() -> {
                latch.await(); return attempt(() -> writer.cancel(commandContext(), "NATIVE_MERCHANT",
                        new StoredValuePort.CancelRecharge(ready.rechargeNo(), ready.revision(), "cancel"), null));
            });
            latch.countDown();
            assertThat(List.of(receiveFuture.get(), cancelFuture.get())).containsExactlyInAnyOrder(true, false);
        }
        var cancelled = sourceFixture();
        assertThat(writer.cancel(commandContext(), "NATIVE_MERCHANT", new StoredValuePort.CancelRecharge(
                cancelled.rechargeNo(), 0, "cancel before create"), cancelled).status())
                .isEqualTo(StoredValuePort.RechargeState.CANCELLED);
        assertThat(writer.create(cancelled.context(), "NATIVE_MERCHANT", new StoredValuePort.CreateRecharge(
                cancelled.source(), cancelled.permit()), cancelled).status())
                .isEqualTo(StoredValuePort.RechargeState.CANCELLED);
    }
    private static boolean attempt(Runnable work) {
        try { work.run(); return true; } catch (RuntimeException expectedConflict) { return false; }
    }
    private static EntitlementContext commandContext() {
        return new EntitlementContext("MAIN", "NATIVE_OPERATOR", UUID.randomUUID().toString(), "a".repeat(64));
    }
    private static SourceFact sourceFixture() {
        var ctx = commandContext();
        var ref = new SourceRef("CORE", "RECHARGE", "RI" + UUID.randomUUID(), null, null, null, 0);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        var binding = new ReserveActivity(ref, "A1", "AV1", "T1", UUID.randomUUID().toString(),
                "NATIVE_MERCHANT", "NATIVE_STORE", "CNY", 1);
        var permit = new ParticipationPermit("AR" + UUID.randomUUID(), ref, "A1", "AV1", "T1", binding.userNo(),
                binding.entityNo(), binding.storeNo(), "CNY", 1000L, 100L, List.of(),
                new RedemptionScope("STORES", List.of(binding.storeNo())), "b".repeat(64), now);
        return new SourceFact(ctx, ref, "RC" + ref.grantKey().substring(0, 60), binding, permit, "Native store", now,
                now.plusSeconds(900));
    }
    private static <T> void register(AnnotationConfigApplicationContext context, Class<T> type, SqlSessionFactory sessions) {
        context.registerBean(type.getSimpleName(), MapperFactoryBean.class, () -> {
            var mapper = new MapperFactoryBean<T>(type); mapper.setSqlSessionFactory(sessions); return mapper;
        });
    }
}
