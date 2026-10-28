package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.impl.BundleServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real primary Mappers verify composition and atomicity without inventing product or price facts. */
class BundleServiceTest extends FoodWriteGuardFixture {
    Fixture fixture(AssertableApplicationContext c) {
        var f = new OptionGroupServiceTest().fixture(c);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_bundle (" + common + "sku_no VARCHAR(32) UNIQUE,"
                + "config_version BIGINT,status VARCHAR(16),published_revision_no VARCHAR(32))");
        f.sql().execute("CREATE TABLE fnb_bundle_group (" + common + "group_no VARCHAR(32) UNIQUE,"
                + "bundle_sku_no VARCHAR(32),name VARCHAR(64),min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),"
                + "base_sku_no VARCHAR(32),sort_no INT)");
        f.sql().execute("CREATE TABLE fnb_bundle_choice (" + common + "choice_no VARCHAR(32) UNIQUE,"
                + "group_no VARCHAR(32),sku_no VARCHAR(32),units_per_pick DECIMAL(12,3),"
                + "min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),default_qty DECIMAL(18,6),"
                + "price_mode VARCHAR(24),sort_no INT,UNIQUE(group_no,sku_no))");
        c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_BUNDLE_CHANGED"));
        return f;
    }
    private void run(BiConsumer<AssertableApplicationContext,Fixture> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class, Bundles.class).run(c -> test.accept(c, fixture(c)));
    }
    static BigDecimal n(String n) { return new BigDecimal(n); }
    static BundleGroupInput group(String id, String min, String max, String base) {
        return new BundleGroupInput(id, " 配餐 ", n(min), n(max), base, 0);
    }
    static BundleChoiceInput choice(String id, String group, String sku, String mode,
            String min, String max, String def) {
        return new BundleChoiceInput(id, group, sku, n("1.500"), mode, n(def), n(min), n(max), 0);
    }
    static ReplaceBundleDefinitionReq fixed(long v) {
        return new ReplaceBundleDefinitionReq(v, List.of(group("G1", "1", "1", null)),
                List.of(choice("C1", "G1", "SIDE", "INCLUDED", "1", "1", "1")));
    }
    @Test void foreignBundleValidatesRootBaseAndChoicesBeforeAtomicDefinitionWrite() {
        run((c, f) -> {
            var source = new CatalogCurrencyBusinessTest.Sources();
            var service = new BundleServiceImpl(c.getBean(BundleMapper.class), c.getBean(BundleGroupMapper.class),
                    c.getBean(BundleChoiceMapper.class), c.getBean(ActionLogMapper.class),
                    c.getBean(IdempotencyService.class), c.getBean("foodOutboxPublisher", OutboxPublisher.class),
                    c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class), source.goods,
                    source.currencies);
            service.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "foreign-create");
            var result = service.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "foreign-definition");
            assertThat(result.version()).isEqualTo(1);
            assertThat(service.getBundle("E", "A", "MEAL").config().data().choices()).hasSize(1);
            source.corrupt = true;
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", "MEAL", "actor", fixed(1), "corrupt"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_bundle", Long.class)).isEqualTo(1);
        });
    }

    @Test void fixedAndSelectableDefinitionsReadRealChildrenAndReplayWithoutDuplicates() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            var created = s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "create");
            assertThat(created.configNo()).isEqualTo("MEAL");
            assertThat(created.version()).isZero();
            assertThat(s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "create")).isEqualTo(created);
            var changed = s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "define");
            assertThat(changed.version()).isEqualTo(1);
            assertThat(s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "define")).isEqualTo(changed);
            var view = s.getBundle("E", "B", "MEAL").config();
            assertThat(view.data().groups().getFirst().name()).isEqualTo("配餐");
            assertThat(view.data().choices().getFirst().unitsPerPick()).isEqualByComparingTo("1.5");
            assertThat(view.data().configVersion()).isEqualTo(1);
            var req = new ReplaceBundleDefinitionReq(1L, List.of(group("G1", "1", "2", "BASE")),
                    List.of(choice("C1", "G1", "SIDE", "DIFF_BASE", "0", "1", "0"),
                            choice("C2", "G1", "DRINK", "ADD_SKU", "0", "1", "0")));
            s.replaceDefinition("E", "A", "MEAL", "actor", req, "selectable");
            assertThat(s.getBundle("E", "A", "MEAL").config().data().choices()).hasSize(2);
            for (String t : List.of("sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)).as(t).isEqualTo(3);
            }
        });
    }
    @Test void nestedMembersSelfReferenceAndLaterBundleRegistrationAreRejected() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "a");
            s.createBundle("E", "A", "actor", new CreateBundleReq("OTHER"), "b");
            for (String sku : List.of("MEAL", "OTHER")) {
                var req = new ReplaceBundleDefinitionReq(0L, List.of(group("G1", "0", "1", null)),
                        List.of(choice("C1", "G1", sku, "INCLUDED", "0", "1", "0")));
                code(() -> s.replaceDefinition("E", "A", "MEAL", "actor", req, "nested"), ErrorCode.BAD_REQUEST);
                var base = new ReplaceBundleDefinitionReq(0L, List.of(group("G1", "0", "1", sku)),
                        List.of(choice("C1", "G1", "SIDE", "DIFF_BASE", "0", "1", "0")));
                code(() -> s.replaceDefinition("E", "A", "MEAL", "actor", base, "base"), ErrorCode.BAD_REQUEST);
            }
            s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "ok");
            code(() -> s.createBundle("E", "A", "actor", new CreateBundleReq("SIDE"), "later"),
                    ErrorCode.BAD_REQUEST);
            var withBase = new ReplaceBundleDefinitionReq(1L, List.of(group("G1", "0", "1", "BASE")),
                    List.of(choice("C1", "G1", "SIDE", "DIFF_BASE", "0", "1", "0")));
            s.replaceDefinition("E", "A", "MEAL", "actor", withBase, "base-ok");
            code(() -> s.createBundle("E", "A", "actor", new CreateBundleReq("BASE"), "later-base"),
                    ErrorCode.BAD_REQUEST);
        });
    }
    @Test void casIdentityAndChildOwnershipArePreservedAcrossReplacementAndClear() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "a");
            s.createBundle("E", "A", "actor", new CreateBundleReq("OTHER"), "b");
            s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "ok");
            code(() -> s.replaceDefinition("E", "A", "OTHER", "actor", fixed(0), "steal"), ErrorCode.CONFLICT);
            code(() -> s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "stale"), ErrorCode.CONFLICT);
            code(() -> s.getBundle("OTHER", "A", "MEAL"), ErrorCode.NOT_FOUND);
            var changedSku = new ReplaceBundleDefinitionReq(1L, List.of(group("G1", "0", "1", null)),
                    List.of(choice("C1", "G1", "DRINK", "INCLUDED", "0", "1", "0")));
            code(() -> s.replaceDefinition("E", "A", "MEAL", "actor", changedSku, "rebind"), ErrorCode.CONFLICT);
            f.sql().update("UPDATE fnb_bundle SET status='ACTIVE',published_revision_no='REV' WHERE sku_no='MEAL'");
            s.replaceDefinition("E", "A", "MEAL", "actor",
                    new ReplaceBundleDefinitionReq(1L, List.of(), List.of()), "clear");
            var v = s.getBundle("E", "A", "MEAL").config();
            assertThat(v.status()).isEqualTo("DRAFT");
            assertThat(v.data().publishedRevisionNo()).isEqualTo("REV");
            assertThat(v.data().groups()).isEmpty();
            s.replaceDefinition("E", "A", "MEAL", "actor", fixed(2), "readd");
            assertThat(s.getBundle("E", "A", "MEAL").config().data().choices()).hasSize(1);
        });
    }
    @Test void upstreamFactsAreOutsideTransactionsAndCompletedReceiptsSurviveOutages() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class); var state=c.getBean(State.class);
            for (String sku : List.of("FOREIGN", "MISSING", "WRONG_STORE", "WRONG_SKU")) {
                assertThatThrownBy(() -> s.createBundle("E", "A", "actor", new CreateBundleReq(sku), "bad"))
                        .isInstanceOf(RuntimeException.class);
            }
            var created=s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "a");
            var changed=s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "b");
            state.outage.set(true);
            assertThat(s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "a")).isEqualTo(created);
            assertThat(s.replaceDefinition("E", "A", "MEAL", "actor", fixed(0), "b")).isEqualTo(changed);
            code(() -> s.createBundle("E", "A", "other", new CreateBundleReq("MEAL"), "a"), ErrorCode.NOT_FOUND);
            code(() -> s.createBundle("E", "A", "actor", new CreateBundleReq("CHANGED"), "a"), ErrorCode.CONFLICT);
            assertThatThrownBy(() -> s.createBundle("E", "A", "actor", new CreateBundleReq("NEW"), "new"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void actualPagesFilterEntityBeforeCountingAndSortStably() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            for (String sku : List.of("C","A","B")) s.createBundle("E","A","actor",new CreateBundleReq(sku),sku);
            s.createBundle("FOREIGN","A","actor",new CreateBundleReq("HIDDEN"),"foreign");
            var page=s.listBundles("E","B",new ListBundlesQuery(2,2));
            assertThat(page.total()).isEqualTo(3); assertThat(page.records()).hasSize(1);
            assertThat(page.records().getFirst().configNo()).isEqualTo("C");
            code(() -> s.listBundles("E","A",new ListBundlesQuery(0,101)), ErrorCode.BAD_REQUEST);
        });
    }
    @Test void outboxFailureRollsBackAllWorkingRowsAndCounters() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            s.createBundle("E","A","actor",new CreateBundleReq("MEAL"),"a");
            s.replaceDefinition("E","A","MEAL","actor",fixed(0),"b");
            c.getBean(State.class).fail.set(true);
            assertThatThrownBy(() -> s.replaceDefinition("E","A","MEAL","actor",
                    new ReplaceBundleDefinitionReq(1L,List.of(),List.of()),"fail"))
                    .isInstanceOf(IllegalStateException.class);
            var v=s.getBundle("E","A","MEAL").config();
            assertThat(v.version()).isEqualTo(1); assertThat(v.data().configVersion()).isEqualTo(1);
            assertThat(v.data().choices()).hasSize(1);
            for (String t:List.of("sys_outbox","sys_idempotent","fnb_action_log"))
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM "+t,Integer.class)).as(t).isEqualTo(2);
            assertThatThrownBy(() -> f.tx().execute(tx -> s.createBundle("E","A","actor",new CreateBundleReq("X"),"x")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void simultaneousMemberUseAndBundleRegistrationCannotBothCommitOnMySql() {
        run((c,f) -> {
            var s = c.getBean(BundleService.class);
            s.createBundle("E", "A", "actor", new CreateBundleReq("MEAL"), "a");
            c.getBean(State.class).start = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var registration = executor.submit(() -> attempted(() -> s.createBundle("E", "A", "actor",
                        new CreateBundleReq("SIDE"), "race-create")));
                var definition = executor.submit(() -> attempted(() -> s.replaceDefinition("E", "A", "MEAL",
                        "actor", fixed(0), "race-define")));
                try {
                    boolean created = registration.get(15, TimeUnit.SECONDS);
                    boolean replaced = definition.get(15, TimeUnit.SECONDS);
                    assertThat(created || replaced).isTrue();
                    assertThat(created && replaced).isFalse();
                } catch (Exception failure) { throw new AssertionError("Concurrent command failed", failure); }
            } finally { c.getBean(State.class).start = null; }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_bundle b JOIN fnb_bundle_choice c "
                    + "ON b.entity_no=c.entity_no AND b.sku_no=c.sku_no", Integer.class)).isZero();
        });
    }
    private static boolean attempted(Runnable call) {
        try { call.run(); return true; }
        catch (BizException failure) {
            assertThat(failure.errorCode()).isIn(ErrorCode.BAD_REQUEST, ErrorCode.CONFLICT); return false;
        }
    }
    private static void code(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
    static class State {
        final AtomicBoolean fail=new AtomicBoolean(); final AtomicBoolean outage=new AtomicBoolean();
        volatile CyclicBarrier start;
    }
    @Configuration(proxyBeanMethods=false)
    static class Bundles {
        @Bean State bundleState() { return new State(); }
        @Bean MapperFactoryBean<BundleMapper> bundleHeads(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b=new MapperFactoryBean<>(BundleMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<BundleGroupMapper> bundleGroups(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b=new MapperFactoryBean<>(BundleGroupMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<BundleChoiceMapper> bundleChoices(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b=new MapperFactoryBean<>(BundleChoiceMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean BundleService bundleService(BundleMapper heads,BundleGroupMapper groups,BundleChoiceMapper choices,
                ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
                ObjectMapper json,PlatformTransactionManager manager,State state) {
            var catalog=mock(GoodsCatalogQueryPort.class);
            when(catalog.getSku(anyString(),anyString(),anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if(state.outage.get()) throw new IllegalStateException("authority unavailable");
                String sku=i.getArgument(2);
                if("MISSING".equals(sku)) throw BizException.of(ErrorCode.NOT_FOUND);
                return new GoodsCatalogQueryPort.SkuIdentityFact("WRONG_SKU".equals(sku)?"X":sku,"G",
                        "FOREIGN".equals(sku)?"OTHER":i.getArgument(0),
                        "WRONG_STORE".equals(sku)?"OTHER":i.getArgument(1),0);
            });
            OutboxPublisher checked=(event,options) -> {
                var receipt=outbox.publish(event,options);
                if(state.fail.get()) throw new IllegalStateException("after-bundle-outbox");
                return receipt;
            };
            PlatformTransactionManager synchronizedStart = new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                    if (state.start != null
                            && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_SERIALIZABLE) {
                        try { state.start.await(5, TimeUnit.SECONDS); }
                        catch (Exception failure) { throw new AssertionError("Transaction start barrier", failure); }
                    }
                    return manager.getTransaction(definition);
                }
                @Override public void commit(TransactionStatus status) { manager.commit(status); }
                @Override public void rollback(TransactionStatus status) { manager.rollback(status); }
            };
            return new BundleServiceImpl(heads,groups,choices,actions,idem,checked,json,synchronizedStart,catalog);
        }
    }
}
