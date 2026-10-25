package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.config.FoodSchemaValidator;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Run only against the disposable container created by verify-food-primary-mysql.py. */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodPrimaryDatabaseTest {
    private String database;
    private DataSource source;
    private JdbcTemplate sql;
    private final FoodSchemaValidator validator = new FoodSchemaValidator();

    @BeforeEach void createDatabase() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        database = "food_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin()).execute("CREATE DATABASE " + database);
        source = new DriverManagerDataSource(url.replace("/mysql", "/" + database), "root", "");
        sql = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("food-primary-fixture.sql")).execute(source);
    }
    private DataSource admin() { return new DriverManagerDataSource(System.getenv("FOOD_TEST_JDBC_URL"), "root", ""); }
    @AfterEach void dropDatabase() {
        DataScopeContext.clear();
        if (database != null) new JdbcTemplate(admin()).execute("DROP DATABASE " + database);
    }
    /** Independent fixture applies the real immutable V1; does not use the validator to prepare validation tests. */
    private void appliedFood() {
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class, ai.neargo.shop.food.config.DiningDeliveryAutoConfiguration.class))
                .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withBean(ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler.class,
                        () -> new ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler(
                                new ai.neargo.shop.food.mapper.FoodKdsMapper(new JdbcTemplate(source)),new ObjectMapper()))
                .withBean(ai.neargo.shop.food.service.CheckBusinessGuardService.class,
                        () -> new ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl(
                                new ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper(source)))
                .withBean(AuditMetaObjectHandler.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("shop.food.enabled=true", "spring.datasource.url=" + ((DriverManagerDataSource)source).getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=");
    }
    @Test void nativeKitchenUsesOriginalFrozenRoutesAndDurableTransactionalServiceEvents() {
        appliedFood();
        runner().withUserConfiguration(RealFoodProducer.class).run(c -> {
            assertThat(c).hasNotFailed();
            var json=c.getBean(ObjectMapper.class);
            String station="""
                    {"contentSchema":5,"configKind":"STATION","configNo":"STATION","sourceVersion":0,
                     "entityNo":"E","storeNo":"S","station":{"configNo":"STATION","storeNo":"S",
                     "name":"Kitchen","stationType":"KITCHEN","enabled":true,"status":"PUBLISHED","version":0},
                     "routes":[{"skuNo":"SKU","taskRole":"MAKE","routeVersion":1}],
                     "skuFacts":[{"skuNo":"SKU","goodsNo":"GOODS","entityNo":"E","storeNo":"S","version":0}],
                     "approvalRef":"APPROVAL"}
                    """;
            // Validate the exact publication DTO before inserting an immutable original publication fixture.
            ai.neargo.shop.food.policy.StationPublicationDocuments.validate(json,"STATION",station);
            String hash=ai.neargo.shop.common.pricing.PricingSnapshotHash.of(json.readTree(station));
            sql.update("INSERT INTO fnb_config_revision(entity_no,created_at,created_by,updated_at,updated_by,"
                    +"revision_no,config_kind,config_no,revision,content_schema,definition_snapshot,content_hash,"
                    +"effective_at,status,scope_store_nos) VALUES('E',NOW(),'A',NOW(),'A','REV','STATION','STATION',1,5,?,?,NOW(),'PUBLISHED','[\"S\"]')",
                    station,hash);
            String frozen=json.writeValueAsString(java.util.Map.of("kitchenRoutes",
                    new ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes(List.of(
                            new ai.neargo.shop.food.dto.KitchenContracts.FrozenRoute("SKU","STATION","MAKE",1,"REV",hash)))));
            sql.update("INSERT INTO fnb_order_batch(entity_no,created_at,created_by,updated_at,updated_by,"
                    +"batch_no,store_no,request_key,request_hash,source,buyer_no,operator_no,status,frozen_request,"
                    +"accepted_at,cart_no,cart_version,quote_ref,quote_hash,business_date,policy_no,fulfillment,pay_mode,"
                    +"actor_realm,quota_claim_refs) VALUES('E',NOW(),'A',NOW(),'A','B','S',? ,?,'H5','BUYER','A',"
                    +"'ACCEPTED',?,NOW(),'CART',0,'QUOTE',?,'2026-10-24','POLICY','PICKUP','POSTPAY','MP','[]')",
                    "1".repeat(64),"2".repeat(64),frozen,"3".repeat(64));
            sql.update("INSERT INTO fnb_order_line(entity_no,created_at,created_by,updated_at,updated_by,"
                    +"food_line_no,batch_no,goods_no,sku_no,qty,selection_version,display_snapshot,source_line_ref,"
                    +"config_revision_nos,unit) VALUES('E',NOW(),'A',NOW(),'A','L','B','GOODS','SKU',2,1,'{}','SRC','[]','EA')");
            sql.update("INSERT INTO fnb_station(entity_no,created_at,created_by,updated_at,updated_by,"
                    + "station_no,store_no,name,station_type,enabled) VALUES('E',NOW(),'A',NOW(),'A','STATION','S','Kitchen','HOT',true)");
            var kitchen=c.getBean(ai.neargo.shop.food.service.KitchenTaskService.class);
            var ticket=kitchen.accepted("E","S","B","OUTBOX_ORIGINAL");
            assertThat(kitchen.accepted("E","S","B","REDELIVERY").ticketNo()).isEqualTo(ticket.ticketNo());
            assertThat(ticket.tasks()).hasSize(1);
            var task=ticket.tasks().getFirst();
            var start=new ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq(task.version(),"START",java.math.BigDecimal.ONE,null);
            var event=kitchen.act("E","S","A",task.taskNo(),start,"START_1");
            assertThat(kitchen.act("E","S","A",task.taskNo(),start,"START_1").eventNo()).isEqualTo(event.eventNo());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_service_event",Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> kitchen.act("E","S","A",task.taskNo(),start,"STALE_NEW_KEY"))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_service_event",Integer.class)).isOne();
            var current=kitchen.queue("E","S",null,"B").getFirst();
            kitchen.act("E","S","A",task.taskNo(),new ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq(
                    current.version(),"READY",java.math.BigDecimal.ONE,null),"READY_1");
            var ready=kitchen.queue("E","S",null,"B").getFirst();
            assertThat(ready.readyQty()).isEqualByComparingTo("1");
            assertThat(ready.makingQty()).isEqualByComparingTo("1");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Integer.class)).isEqualTo(4);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class RealFoodProducer {
        @org.springframework.context.annotation.Bean
        ai.neargo.shop.food.service.FoodStationRuntimeService stationRuntime(DataSource source,
                PlatformTransactionManager manager,ObjectMapper json,
                @org.springframework.beans.factory.annotation.Qualifier("foodOutboxPublisher") ai.neargo.shop.event.outbox.OutboxPublisher outbox) {
            return new ai.neargo.shop.food.service.impl.FoodStationRuntimeServiceImpl(
                    new ai.neargo.shop.food.mapper.FoodStationRuntimeMapper(new JdbcTemplate(source)),
                    new ai.neargo.shop.food.mapper.FoodKdsMapper(new JdbcTemplate(source)),null,null,
                    outbox,manager,java.time.Clock.systemUTC(),json);
        }

        @org.springframework.context.annotation.Bean
        ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade foodProducer(DataSource source,
                PlatformTransactionManager transactionManager,ObjectMapper json) {
            var facade=new ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade(json);
            facade.setArchiveOnly(ai.neargo.shop.spi.food.FoodOutboxRouting.ARCHIVE_ONLY);
            facade.registerProducer(new ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore(
                    ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore.Source.PLATFORM,source,transactionManager,json),
                    ai.neargo.shop.spi.food.FoodOutboxRouting.DELIVERY_TARGETS);
            return facade;
        }
    }

    @Test void specIdentityMigrationWidensWithoutRewritingExistingRows() {
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").target("2").load().migrate();
        String checksumQuery = "SELECT checksum FROM fnb_flyway_history WHERE version IN ('1','2') ORDER BY version";
        var checksums = sql.queryForList(checksumQuery);
        sql.update("INSERT INTO fnb_option_group_item(entity_no,created_at,created_by,updated_at,updated_by,"
                + "option_item_no,group_no,source_kind,spec_value_no,min_qty,max_qty,default_qty,sort_no) "
                + "VALUES('E',NOW(),'SYSTEM',NOW(),'SYSTEM','OLD','OG','SPEC_VALUE','OLD_VALUE',0,1,0,0)");
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.MIGRATE).version()).isEqualTo("25");
        assertThat(sql.queryForObject("SELECT spec_value_no FROM fnb_option_group_item", String.class))
                .isEqualTo("OLD_VALUE");
        sql.update("UPDATE fnb_option_group_item SET spec_value_no=?", "V".repeat(64));
        assertThat(sql.queryForObject("SELECT spec_value_no FROM fnb_option_group_item", String.class))
                .isEqualTo("V".repeat(64));
        assertThat(sql.queryForList(checksumQuery))
                .isEqualTo(checksums);
    }

    @Test void validateRejectsNarrowSpecIdentityEvenWithCurrentHistory() {
        appliedFood();
        sql.execute("ALTER TABLE fnb_option_group_item MODIFY COLUMN spec_value_no VARCHAR(32) NULL");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("64 characters");
        assertThat(sql.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='fnb_option_group_item' AND column_name='spec_value_no'",
                Long.class)).isEqualTo(32);
    }

    @Test void sharingMigrationBackfillsExistingOccupancyWithoutChangingV1() {
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").target("1").load().migrate();
        sql.update("INSERT INTO fnb_table_occupancy(entity_no,created_at,created_by,updated_at,updated_by,"
                +"occupancy_no,table_no,check_no,seats,started_at) VALUES('E',NOW(),'SYSTEM',NOW(),'SYSTEM',"
                +"'OLD_OCC','OLD_TABLE','OLD_CHECK',4,NOW())");
        var result=validator.initialize(source,FoodProperties.MigrationMode.MIGRATE);
        assertThat(result.version()).isEqualTo("25");
        assertThat(sql.queryForObject("SELECT sharing_mode FROM fnb_table_occupancy",String.class)).isEqualTo("EXCLUSIVE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_flyway_history WHERE version IN ('1','2') AND success=1",Integer.class)).isEqualTo(2);
    }
    @Test void validateRefusesOldOccupancySchemaWithoutMigratingIt() {
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").target("1").load().migrate();
        assertThatThrownBy(() -> validator.initialize(source,FoodProperties.MigrationMode.VALIDATE))
                .isInstanceOf(RuntimeException.class);
        assertThat(columnExists("fnb_table_occupancy","sharing_mode")).isFalse();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_flyway_history WHERE version='2'",Integer.class)).isZero();
    }
    @Test void explicitPublishMigratesOnlyFood() {
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.MIGRATE).ready()).isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'fnb\\_%'", Integer.class)).isEqualTo(112);
        assertThat(tableExists("fnb_recipe")).isTrue();
        assertThat(tableExists("fnb_recipe_line")).isTrue();
        assertThat(tableExists("fnb_reservation_table")).isTrue();
        assertThat(tableExists("fnb_business_share_plan")).isTrue();
        assertThat(tableExists("fnb_catalog_sync_job")).isFalse();
        assertThat(tableExists("fnb_catalog_sync_item")).isFalse();
        assertThat(tableExists("flyway_schema_history")).isFalse();
    }
    @Test void similarlyNamedTablesCannotSupplyMissingColumns() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        sql.execute("CREATE TABLE sysXidempotent(request_hash VARCHAR(64))");
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.VALIDATE).missingCapabilities())
                .contains("sys_idempotent.request_hash");
    }
    @Test void validateDoesNotApplyCoreMigrations() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        var history = sql.queryForList("SELECT * FROM fnb_flyway_history ORDER BY installed_rank");
        var result = validator.initialize(source, FoodProperties.MigrationMode.VALIDATE);
        assertThat(result.validated()).isTrue();
        assertThat(result.ready()).isFalse();
        assertThat(result.missingCapabilities()).contains("sys_idempotent.request_hash");
        assertThat(sql.queryForList("SELECT * FROM fnb_flyway_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(tableExists("flyway_schema_history")).isFalse();
        assertThat(columnExists("sys_idempotent", "request_hash")).isFalse();
    }
    @Test void foodDoesNotSuppressCoreFlyway() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        runner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withPropertyValues("spring.flyway.enabled=true", "spring.flyway.locations=classpath:food-test-core-migration",
                        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
                    assertThat(context.getBean(Flyway.class).getConfiguration().getTable()).isEqualTo("flyway_schema_history");
                    assertThat(context.getBean(FoodSchemaReady.class).ready()).isTrue();
                    assertThat(tableExists("core_migration_probe")).isTrue();
                });
    }
    @Test void bindFoodMapperToPlatformFactory() {
        appliedFood();
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(SqlSessionFactory.class)
                    .hasSingleBean(PlatformTransactionManager.class).doesNotHaveBean(Flyway.class);
            var factory = context.getBean(SqlSessionFactory.class);
            assertThat(context.getBean("foodSqlSessionTemplate", SqlSessionTemplate.class).getSqlSessionFactory()).isSameAs(factory);
            var mapper = context.getBean(FoodMappers.TableAreaMapper.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                mapper.insert(area("A", "rollback")); throw new IllegalStateException("rollback-probe");
            })).hasMessage("rollback-probe");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class)).isZero();
            var a = area("A", "A1"); var b = area("B", "B1");
            mapper.insert(a); mapper.insert(b);
            assertThat(a.getCreatedBy()).isEqualTo("SYSTEM");
            a = mapper.selectById(a.getId());
            assertThat(a.getTenantNo()).isEqualTo("MAIN");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("A")));
            assertThat(mapper.selectList(null)).extracting(FnbTableArea::getEntityNo).containsExactly("A");
            var stale = mapper.selectById(a.getId());
            a.setName("changed"); assertThat(mapper.updateById(a)).isOne();
            stale.setName("lost"); assertThat(mapper.updateById(stale)).isZero();
            b.setName("cross-scope"); assertThat(mapper.updateById(b)).isZero();
            assertThat(mapper.deleteById(a.getId())).isOne();
            assertThat(mapper.selectById(a.getId())).isNull();
        });
    }
    @Test void concurrentUpdatesHaveOneWinner() {
        appliedFood();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var mapper = context.getBean(FoodMappers.TableAreaMapper.class);
            var original = area("A", "race"); mapper.insert(original);
            var first = mapper.selectById(original.getId()); var second = mapper.selectById(original.getId());
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<Future<Integer>>();
                for (var row : List.of(first, second)) futures.add(pool.submit(() -> {
                    DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("A")));
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race timeout");
                        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
                            row.setName("race-winner"); return mapper.updateById(row);
                        });
                    } finally { DataScopeContext.clear(); }
                }));
                start.countDown();
                assertThat(futures.get(0).get(10, TimeUnit.SECONDS)
                        + futures.get(1).get(10, TimeUnit.SECONDS)).isOne();
            }
            assertThat(mapper.selectById(original.getId()).getVersion()).isEqualTo(1);
        });
    }
    @Test void missingOutboxUniquenessBlocksReadiness() {
        appliedFood(); sql.execute("ALTER TABLE sys_outbox DROP INDEX uk_sys_outbox_source");
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.VALIDATE).missingCapabilities())
                .contains("sys_outbox.unique(source_system,source_event_no)");
    }
    @Test void rejectUntrackedFoodTables() {
        sql.execute("CREATE TABLE fnb_table_area(id BIGINT)");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.MIGRATE)).hasMessageContaining("untracked");
        assertThat(tableExists("fnb_flyway_history")).isFalse();
    }
    @Test void validateNeverCreatesHistory() {
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).hasMessageContaining("missing fnb_flyway_history");
        assertThat(tableExists("fnb_flyway_history")).isFalse();
    }
    @Test void rejectPendingHistory() {
        appliedFood(); sql.execute("DELETE FROM fnb_flyway_history WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_flyway_history WHERE version='1'", Integer.class)).isZero();
    }
    @Test void rejectChecksumDrift() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET checksum=1 WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT checksum FROM fnb_flyway_history WHERE version='1'", Integer.class)).isOne();
    }
    @Test void rejectFailedHistory() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET success=0 WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectUnknownFutureHistory() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET version='99' WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectPhysicalColumnDrift() {
        appliedFood(); sql.execute("ALTER TABLE fnb_table_area DROP COLUMN name");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).hasMessageContaining("fnb_table_area.name");
    }
    @Test void missingCoreCapabilitiesBlockMappers() {
        appliedFood(); sql.execute("DROP TABLE sys_outbox_delivery");
        var ready = validator.initialize(source, FoodProperties.MigrationMode.VALIDATE);
        assertThat(ready.validated()).isTrue(); assertThat(ready.ready()).isFalse();
        assertThat(ready.missingCapabilities()).contains("sys_outbox_delivery");
        runner().run(context -> assertThat(context).hasFailed());
        assertThat(tableExists("sys_outbox_delivery")).isFalse();
    }
    private boolean tableExists(String name) {
        return sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, name)>0;
    }
    private boolean columnExists(String table, String field) {
        return sql.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, field)>0;
    }
    private FnbTableArea area(String entity, String code) {
        var area = new FnbTableArea(); area.setEntityNo(entity); area.setAreaNo(code); area.setStoreNo("S"+entity);
        area.setCode(code); area.setName(code); area.setStatus("ACTIVE"); area.setSortNo(1); return area;
    }
}
