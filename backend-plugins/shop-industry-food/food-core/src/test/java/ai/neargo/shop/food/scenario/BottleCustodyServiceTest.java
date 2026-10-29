package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;
import ai.neargo.shop.food.mapper.BottleCustodyMappers.CustodyMapper;
import ai.neargo.shop.food.mapper.BottleCustodyMappers.MovementMapper;
import ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Actual additive V29/MyBatis transactions; account/catalog/material facts are isolated original
 * owner doubles.
 */
class BottleCustodyServiceTest extends FoodWriteGuardFixture {
    static final Instant NOW = Instant.parse("2026-10-29T05:00:00Z");

    static final class Time extends Clock {
        Instant now = NOW;

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException();
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    record Context(
            BottleCustodyServiceImpl service, Fixture fixture, Time time, AtomicBoolean offline) {}

    void run(Consumer<Context> work) {
        runner().run(
                        context -> {
                            var fixture = prepare(context);
                            try {
                                String v1 =
                                        new String(
                                                new ClassPathResource(
                                                                "db/industry/food/V1__food_business_tables.sql")
                                                        .getInputStream()
                                                        .readAllBytes(),
                                                StandardCharsets.UTF_8);
                                int start = v1.indexOf("CREATE TABLE `fnb_bottle_custody`");
                                int end = v1.indexOf(";", start);
                                if (start < 0 || end < 0)
                                    throw new AssertionError("Original custody DDL missing");
                                String original =
                                        v1.substring(start, end + 1)
                                                .replaceAll(
                                                        "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin",
                                                        "")
                                                .replace("ENGINE=InnoDB COMMENT='酒水寄存'", "");
                                String ddl =
                                        original
                                                + "\n"
                                                + new String(
                                                                new ClassPathResource(
                                                                                "db/industry/food/V29__food_bottle_custody.sql")
                                                                        .getInputStream()
                                                                        .readAllBytes(),
                                                                StandardCharsets.UTF_8)
                                                        .replaceAll(
                                                                "(?i)ENGINE=InnoDB DEFAULT"
                                                                        + " CHARSET=utf8mb4"
                                                                        + " COLLATE=utf8mb4_bin",
                                                                "");
                                new ResourceDatabasePopulator(
                                                new ByteArrayResource(
                                                        ddl.getBytes(StandardCharsets.UTF_8)))
                                        .execute(fixture.source());
                            } catch (IOException failure) {
                                throw new AssertionError(failure);
                            }
                            var template = context.getBean(SqlSessionTemplate.class);
                            template.getConfiguration().addMapper(CustodyMapper.class);
                            template.getConfiguration().addMapper(MovementMapper.class);
                            var offline = new AtomicBoolean();
                            StoreCardQueryPort stores =
                                    numbers -> {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isFalse();
                                        if (offline.get())
                                            throw new IllegalStateException(
                                                    "Original owners offline");
                                        return Map.of(
                                                "S",
                                                new StoreCard(
                                                        "S",
                                                        "Store",
                                                        "E",
                                                        null,
                                                        "ACTIVE",
                                                        true,
                                                        "09:00-22:00",
                                                        null,
                                                        null,
                                                        null,
                                                        0,
                                                        0));
                                    };
                            CustomerAccountQueryPort accounts =
                                    query -> {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isFalse();
                                        if (offline.get())
                                            throw new IllegalStateException(
                                                    "Original account owner offline");
                                        return new CustomerAccountQueryPort.Result(query, true);
                                    };
                            var goods = mock(GoodsCatalogQueryPort.class);
                            when(goods.getSku("E", "S", "SKU"))
                                    .thenReturn(
                                            new GoodsCatalogQueryPort.SkuIdentityFact(
                                                    "SKU", "GOODS", "E", "S", 1));
                            var materials = mock(MaterialCatalogQueryPort.class);
                            var material =
                                    new MaterialCatalogQueryPort.MaterialFact(
                                            "REAL_MATERIAL",
                                            "E",
                                            "Bottle",
                                            "PIECE",
                                            0,
                                            "OWN",
                                            "BASE_ONLY",
                                            "INTEGER_ONLY",
                                            MaterialCatalogFacts.definitionHash(
                                                    "REAL_MATERIAL",
                                                    "E",
                                                    "Bottle",
                                                    "PIECE",
                                                    0,
                                                    "OWN"));
                            when(materials.getSkuMaterials("E", "S", List.of("SKU")))
                                    .thenReturn(
                                            List.of(
                                                    new MaterialCatalogQueryPort.SkuMaterialFact(
                                                            "SKU", material)));
                            var time = new Time();
                            var service =
                                    new BottleCustodyServiceImpl(
                                            template.getMapper(CustodyMapper.class),
                                            template.getMapper(MovementMapper.class),
                                            stores,
                                            accounts,
                                            goods,
                                            materials,
                                            JsonMapper.builder().findAndAddModules().build(),
                                            time,
                                            context.getBean(PlatformTransactionManager.class));
                            work.accept(new Context(service, fixture, time, offline));
                        });
    }

    Deposit deposit() {
        return new Deposit(
                "CUSTOMER",
                "SKU",
                new BigDecimal("2"),
                "PIECE",
                NOW.plusSeconds(3600),
                "Customer original bottle deposit",
                "RECEIPT");
    }

    Withdraw take(long version, String qty) {
        return new Withdraw(
                version, "CONSUME", new BigDecimal(qty), "Customer requested use", "CUSTOMER");
    }

    @Test
    void realPropertyLedgerUsesActualSkuAndExactPartialQuantity() {
        run(
                c -> {
                    var receipt = c.service().deposit("E", "S", "STAFF", deposit(), "deposit");
                    var taken =
                            c.service()
                                    .withdraw(
                                            "E",
                                            "S",
                                            "STAFF",
                                            receipt.custody().custodyNo(),
                                            take(0, "0.5"),
                                            "take");
                    assertThat(taken.custody().remaining()).isEqualByComparingTo("1.5");
                    assertThat(
                                    c.service()
                                            .movements(
                                                    "E", "S", receipt.custody().custodyNo(), 1, 20)
                                            .total())
                            .isEqualTo(2);
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM fnb_bottle_custody",
                                                    Integer.class))
                            .isOne();
                    assertThat(taken.custody().customerNo()).isEqualTo("CUSTOMER");
                });
    }

    @Test
    void originalOwnerReferenceAndUnknownHistoricDepositRemainReadableWithoutInventingTotals() {
        run(
                c -> {
                    c.fixture()
                            .sql()
                            .execute(
                                    "INSERT INTO"
                                        + " fnb_bottle_custody(tenant_no,entity_no,store_no,custody_no,owner_ref,sku_no,unit,remaining_qty,expires_at,status,created_at,created_by,updated_at,updated_by)"
                                        + " VALUES('MAIN','E','S','OLD','CUSTOMER','SKU','PIECE',2,'2026-10-30"
                                        + " 05:00:00','ACTIVE','2026-10-29"
                                        + " 05:00:00','A','2026-10-29 05:00:00','A')");
                    var original = c.service().get("E", "S", "OLD");
                    assertThat(original.customerNo()).isEqualTo("CUSTOMER");
                    assertThat(original.deposited()).isNull();
                    assertThat(original.remaining()).isEqualByComparingTo("2");
                    assertThat(c.service().list("E", "S", "CUSTOMER", 1, 20).total()).isOne();
                    var returned =
                            c.service()
                                    .withdraw(
                                            "E",
                                            "S",
                                            "A",
                                            "OLD",
                                            new Withdraw(
                                                    0L,
                                                    "RETURN",
                                                    BigDecimal.ONE,
                                                    "Original customer collects property",
                                                    "CUSTOMER"),
                                            "LEGACY_RETURN");
                    assertThat(returned.custody().deposited()).isNull();
                    assertThat(returned.custody().remaining()).isEqualByComparingTo("1");
                });
    }

    @Test
    void completedDepositReplaysOriginalReceiptWhileOwnersAreOffline() {
        run(
                c -> {
                    var first = c.service().deposit("E", "S", "STAFF", deposit(), "same");
                    c.offline().set(true);
                    assertThat(c.service().deposit("E", "S", "STAFF", deposit(), "same"))
                            .isEqualTo(first);
                    assertThatThrownBy(
                                    () -> c.service().deposit("E", "S", "OTHER", deposit(), "same"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM fnb_bottle_movement",
                                                    Integer.class))
                            .isOne();
                });
    }

    @Test
    void expiryBlocksConsumptionButCustomerMayCollectRemainingProperty() {
        run(
                c -> {
                    var first = c.service().deposit("E", "S", "STAFF", deposit(), "deposit");
                    c.time().now = NOW.plusSeconds(3601);
                    assertThatThrownBy(
                                    () ->
                                            c.service()
                                                    .withdraw(
                                                            "E",
                                                            "S",
                                                            "STAFF",
                                                            first.custody().custodyNo(),
                                                            take(0, "1"),
                                                            "expired"))
                            .isInstanceOf(RuntimeException.class);
                    var returned =
                            c.service()
                                    .withdraw(
                                            "E",
                                            "S",
                                            "STAFF",
                                            first.custody().custodyNo(),
                                            new Withdraw(
                                                    0L,
                                                    "RETURN",
                                                    new BigDecimal("2"),
                                                    "Return expired property",
                                                    "CUSTOMER"),
                                            "return");
                    assertThat(returned.custody().status()).isEqualTo("CLOSED");
                    assertThat(returned.custody().remaining()).isZero();
                });
    }

    @Test
    void quantityVersionAndCustomerScopeCannotBeOverridden() {
        run(
                c -> {
                    var first = c.service().deposit("E", "S", "STAFF", deposit(), "deposit");
                    String number = first.custody().custodyNo();
                    assertThatThrownBy(
                                    () ->
                                            c.service()
                                                    .withdraw(
                                                            "E",
                                                            "S",
                                                            "STAFF",
                                                            number,
                                                            take(0, "3"),
                                                            "over"))
                            .isInstanceOf(RuntimeException.class);
                    assertThatThrownBy(() -> c.service().get("E", "OTHER", number))
                            .isInstanceOf(RuntimeException.class);
                    assertThatThrownBy(
                                    () ->
                                            c.service()
                                                    .withdraw(
                                                            "E",
                                                            "S",
                                                            "STAFF",
                                                            number,
                                                            new Withdraw(
                                                                    0L,
                                                                    "CONSUME",
                                                                    BigDecimal.ONE,
                                                                    "Wrong owner",
                                                                    "OTHER"),
                                                            "wrong"))
                            .isInstanceOf(RuntimeException.class);
                    c.fixture()
                            .sql()
                            .update(
                                    "UPDATE fnb_bottle_custody SET version=128 WHERE custody_no=?",
                                    number);
                    assertThat(
                                    c.service()
                                            .withdraw(
                                                    "E",
                                                    "S",
                                                    "STAFF",
                                                    number,
                                                    take(128, "1"),
                                                    "version128")
                                            .custody()
                                            .version())
                            .isEqualTo(129);
                    assertThat(c.service().get("E", "S", number).remaining())
                            .isEqualByComparingTo("1");
                });
    }

    @Test
    void concurrentWithdrawalsCannotSpendTheSameRemainingQuantity() {
        run(
                c -> {
                    var first = c.service().deposit("E", "S", "STAFF", deposit(), "deposit");
                    var gate = new CountDownLatch(1);
                    try (var workers = Executors.newFixedThreadPool(2)) {
                        var futures =
                                IntStream.range(0, 2)
                                        .mapToObj(
                                                i ->
                                                        workers.submit(
                                                                () -> {
                                                                    gate.await();
                                                                    try {
                                                                        c.service()
                                                                                .withdraw(
                                                                                        "E",
                                                                                        "S",
                                                                                        "STAFF",
                                                                                        first.custody()
                                                                                                .custodyNo(),
                                                                                        take(
                                                                                                0,
                                                                                                "2"),
                                                                                        "take" + i);
                                                                        return true;
                                                                    } catch (
                                                                            RuntimeException
                                                                                    rejected) {
                                                                        return false;
                                                                    }
                                                                }))
                                        .toList();
                        gate.countDown();
                        int successes = 0;
                        for (var future : futures)
                            if (future.get(10, TimeUnit.SECONDS)) successes++;
                        assertThat(successes).isOne();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    assertThat(c.service().get("E", "S", first.custody().custodyNo()).remaining())
                            .isZero();
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM fnb_bottle_movement",
                                                    Integer.class))
                            .isEqualTo(2);
                });
    }
}
