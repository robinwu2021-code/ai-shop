package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.ServiceFormContracts.BookReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.EnterReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.FormVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.Guest;
import ai.neargo.shop.food.dto.ServiceFormContracts.Kind;
import ai.neargo.shop.food.dto.ServiceFormContracts.OrganizationReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.VersionReq;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.impl.ServiceFormServiceImpl;
import ai.neargo.shop.food.service.impl.ServiceSlotServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real roster counters and source/merchant isolation. Execution is deferred until all coding
 * finishes.
 */
public class ServiceFormPersistenceTest {
    private ServiceFormServiceImpl forms;
    private JdbcTemplate db;
    private Connection schemaConnection;

    @AfterEach
    void closeSchemaConnection() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
    }

    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");

    public static Timestamp utc(Integer precision) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 10, 24, 12, 0));
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:forms_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        schemaConnection = ds.getConnection();
        var ddl = new JdbcTemplate(new SingleConnectionDataSource(schemaConnection, true));
        ddl.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR"
                        + " 'ai.neargo.shop.food.scenario.ServiceFormPersistenceTest.utc'");
        ddl.execute("CREATE TABLE fnb_close_adjustment(id BIGINT PRIMARY KEY)");
        ddl.execute("CREATE TABLE fnb_business_share_plan(id BIGINT PRIMARY KEY)");
        for (String name :
                List.of(
                        "V17__food_service_collaboration_operations.sql",
                        "V24__food_service_forms.sql")) {
            String sql = Files.readString(Path.of("src/main/resources/db/industry/food/" + name));
            sql =
                    sql.replaceAll("(?m)^--.*$", "")
                            .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                            .replace("ENGINE=InnoDB", "")
                            .replace(" JSON ", " VARCHAR(100000) ")
                            .replace(
                                    "ADD COLUMN batch_no VARCHAR(32)  NULL,",
                                    "ADD COLUMN batch_no VARCHAR(32) NULL; ALTER TABLE"
                                        + " fnb_capacity_claim");
            for (String statement : sql.split(";"))
                if (!statement.isBlank()) ddl.execute(statement);
        }
        ddl.execute(
                "CREATE TABLE fnb_check(entity_no VARCHAR(32),store_no VARCHAR(32),check_no"
                        + " VARCHAR(32),party_size INT,status VARCHAR(16),service_state"
                        + " VARCHAR(16),version BIGINT,deleted INT DEFAULT 0)");
        db.update("INSERT INTO fnb_check VALUES ('E','S','CHECK',1,'OPEN','OPEN',0,0)");
        ddl.execute(
                "CREATE TABLE fnb_action_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,entity_no"
                        + " VARCHAR(32),store_no VARCHAR(32),action_no VARCHAR(32),object_type"
                        + " VARCHAR(32),object_no VARCHAR(32),action VARCHAR(64),operator_no"
                        + " VARCHAR(32),reason VARCHAR(255),request_no VARCHAR(64),after_ref"
                        + " VARCHAR(64),created_at TIMESTAMP,updated_at TIMESTAMP,created_by"
                        + " VARCHAR(32),updated_by VARCHAR(32))");
        var mapper = new FoodBusinessOperationsMapper(ds);
        var manager = new DataSourceTransactionManager(ds);
        var json = JsonMapper.builder().build();
        var slot =
                new ServiceSlotServiceImpl(
                        mapper,
                        mock(IdempotencyService.class),
                        Clock.fixed(now, ZoneOffset.UTC),
                        json);
        var card = mock(StoreCard.class);
        when(card.entityNo()).thenReturn("E");
        when(card.active()).thenReturn(true);
        forms =
                new ServiceFormServiceImpl(
                        new ServiceFormMapper(ds),
                        mapper,
                        manager,
                        slot,
                        slot,
                        () -> mock(ReservationService.class),
                        numbers -> Map.of("S", card),
                        json,
                        Clock.fixed(now, ZoneOffset.UTC));
    }

    private FormVO normal() {
        return forms.book(
                "E",
                "S",
                "A",
                new BookReq(
                        Kind.NORMAL,
                        null,
                        null,
                        null,
                        null,
                        1,
                        now.minusSeconds(60),
                        now.plusSeconds(3600),
                        null,
                        null,
                        2,
                        "normal dining"),
                "book");
    }

    @Test
    void attendeeEntryLimitIsPersistentAndOldKeyCannotIncrementAgain() {
        var form = normal();
        forms.enter(
                "E",
                "S",
                "A",
                form.formNo(),
                new EnterReq(0, "CHECK", 0, List.of(new Guest("guest", null, "ADULT")), "arrived"),
                "enter");
        var guest = forms.attendees("E", "S", form.formNo()).getFirst();
        forms.exit(
                "E",
                "S",
                "A",
                form.formNo(),
                guest.attendeeNo(),
                new VersionReq(0, "temporary exit"),
                "exit1");
        var admitted =
                forms.admit(
                        "E",
                        "S",
                        "A",
                        form.formNo(),
                        guest.attendeeNo(),
                        new VersionReq(1, "return"),
                        "admit");
        assertThat(admitted.entryCount()).isEqualTo(2);
        assertThat(
                        forms.admit(
                                "E",
                                "S",
                                "A",
                                form.formNo(),
                                guest.attendeeNo(),
                                new VersionReq(1, "return"),
                                "admit"))
                .isEqualTo(admitted);
        forms.exit(
                "E",
                "S",
                "A",
                form.formNo(),
                guest.attendeeNo(),
                new VersionReq(2, "exit"),
                "exit2");
        assertThatThrownBy(
                        () ->
                                forms.admit(
                                        "E",
                                        "S",
                                        "A",
                                        form.formNo(),
                                        guest.attendeeNo(),
                                        new VersionReq(3, "return again"),
                                        "over"))
                .isInstanceOf(BizException.class);
        assertThat(db.queryForObject("SELECT entry_count FROM fnb_service_attendee", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void corporateFormRequiresPersistedOwnedOrganizationAndBoundContact() {
        var org =
                forms.registerOrganization(
                        "E",
                        "S",
                        "A",
                        new OrganizationReq("Company", "CONTACT", "PO-ORG"),
                        "organization");
        var request =
                new BookReq(
                        Kind.CORPORATE,
                        null,
                        null,
                        null,
                        null,
                        1,
                        now,
                        now.plusSeconds(3600),
                        org.organizationNo(),
                        "FOREIGN-CONTACT",
                        1,
                        "corporate event");
        assertThatThrownBy(() -> forms.book("E", "S", "A", request, "bad-contact"))
                .isInstanceOf(BizException.class);
        var valid =
                new BookReq(
                        Kind.CORPORATE,
                        null,
                        null,
                        null,
                        null,
                        1,
                        now,
                        now.plusSeconds(3600),
                        org.organizationNo(),
                        "CONTACT",
                        1,
                        "corporate event");
        assertThat(forms.book("E", "S", "A", valid, "corporate").organizationNo())
                .isEqualTo(org.organizationNo());
        assertThatThrownBy(() -> forms.get("E", "FOREIGN", normal().formNo()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void cancellationHasExplicitTerminalSourceAndDoesNotCloseOriginalCheck() {
        var form = normal();
        var cancelled =
                forms.cancel(
                        "E",
                        "S",
                        "A",
                        form.formNo(),
                        new VersionReq(0, "guest cancelled"),
                        "cancel");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.sourceTransition()).isEqualTo("LOCAL_CAPACITY_RELEASED");
        assertThat(db.queryForObject("SELECT status FROM fnb_check", String.class))
                .isEqualTo("OPEN");
    }
}
