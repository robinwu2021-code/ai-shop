package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;
import ai.neargo.shop.food.mapper.DiningDeliveryMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.food.service.impl.DiningDeliveryServiceImpl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Actual address and cart CAS persistence; original cart service remains the only caller
 * authorization authority. Not executed in coding phase.
 */
class DiningDeliveryServiceTest {
    JdbcTemplate jdbc;
    DiningDeliveryService service;
    CheckCartService carts;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:delivery_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        String ddl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/industry/food/V22__food_cart_delivery_source.sql"))
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                        .replace("ENGINE=InnoDB", "");
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        jdbc.execute(
                "CREATE TABLE fnb_check_cart(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no"
                    + " VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),created_at"
                    + " TIMESTAMP,created_by VARCHAR(32),updated_at TIMESTAMP,updated_by"
                    + " VARCHAR(32),version BIGINT,deleted INT,cart_no VARCHAR(32),check_no"
                    + " VARCHAR(32),session_no VARCHAR(32),buyer_no VARCHAR(32),status"
                    + " VARCHAR(16),order_source VARCHAR(16),fulfillment VARCHAR(16),scheduled_at"
                    + " TIMESTAMP)");
        jdbc.update(
                "INSERT INTO"
                        + " fnb_check_cart(tenant_no,entity_no,store_no,version,deleted,cart_no,"
                        + "session_no,status,order_source,fulfillment)"
                        + " VALUES('MAIN','E','S',0,0,'CART','PARTY','OPEN','WAITER','DELIVERY')");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(plugins);
        configuration.addMapper(CheckCartMapper.class);
        configuration.addMapper(DiningDeliveryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var template = new SqlSessionTemplate(factory.getObject());
        carts = mock(CheckCartService.class);
        when(carts.getCart("E", "S", "CART")).thenAnswer(invocation -> new CartResultVO(cart()));
        when(carts.customerGetCart(eq("CART"), any()))
                .thenAnswer(invocation -> new CartResultVO(cart()));
        service =
                new DiningDeliveryServiceImpl(
                        carts,
                        template.getMapper(CheckCartMapper.class),
                        template.getMapper(DiningDeliveryMapper.class),
                        mock(OutboxPublisher.class),
                        JsonMapper.builder().build(),
                        new DataSourceTransactionManager(source),
                        Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
    }

    CartVO cart() {
        return new CartVO(
                "CART",
                "S",
                null,
                "PARTY",
                null,
                "OPEN",
                "WAITER",
                "DELIVERY",
                null,
                jdbc.queryForObject("SELECT version FROM fnb_check_cart", Long.class),
                List.of());
    }

    SaveReq request(long version, String address) {
        return new SaveReq(version, "Receiver", "+852 1234 5678", address, "front desk");
    }

    @Test
    void addressChangeAdvancesCartCasAndOriginalKeyNeverReappliesAfterLaterChange() {
        var first =
                service.saveStaff("E", "S", "STAFF", "CART", request(0, "Address A"), "address1");
        assertThat(first.cartVersion()).isEqualTo(1);
        assertThat(first.revision()).isZero();
        var second =
                service.saveStaff("E", "S", "STAFF", "CART", request(1, "Address B"), "address2");
        assertThat(second.cartVersion()).isEqualTo(2);
        assertThat(second.revision()).isEqualTo(1);
        assertThat(PricingSnapshotHash.of(first)).isNotEqualTo(PricingSnapshotHash.of(second));
        assertThatThrownBy(
                        () ->
                                service.saveStaff(
                                        "E",
                                        "S",
                                        "STAFF",
                                        "CART",
                                        request(0, "changed original"),
                                        "address1"))
                .isInstanceOf(BizException.class);
        assertThat(
                        service.saveStaff(
                                "E", "S", "STAFF", "CART", request(0, "Address A"), "address1"))
                .isEqualTo(first);
        assertThat(service.get("E", "S", "CART").address()).isEqualTo("Address B");
        assertThat(jdbc.queryForObject("SELECT version FROM fnb_check_cart", Long.class))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                service.saveStaff(
                                        "E", "S", "STAFF", "CART", request(0, "wrong"), "stale"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void clearingInstructionChangesActualStoredSourceAndQuoteHash() {
        var first = service.saveStaff("E", "S", "STAFF", "CART", request(0, "Address"), "address");
        var cleared =
                service.saveStaff(
                        "E",
                        "S",
                        "STAFF",
                        "CART",
                        new SaveReq(1L, "Receiver", "+852 1234 5678", "Address", null),
                        "clear");
        assertThat(cleared.deliveryInstruction()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delivery_instruction FROM fnb_cart_delivery", String.class))
                .isNull();
        var actual = service.get("E", "S", "CART");
        assertThat(actual.deliveryInstruction()).isNull();
        assertThat(PricingSnapshotHash.of(actual)).isEqualTo(PricingSnapshotHash.of(cleared));
        assertThat(PricingSnapshotHash.of(actual)).isNotEqualTo(PricingSnapshotHash.of(first));
    }

    @Test
    void coordinatesPersistInSourceHashAndClearingBothDoesNotRetainOldLocation() {
        var point =
                service.saveStaff(
                        "E",
                        "S",
                        "STAFF",
                        "CART",
                        new SaveReq(
                                0L,
                                "Receiver",
                                "+852 1234 5678",
                                "Address",
                                null,
                                22301234,
                                114123456),
                        "point");
        assertThat(service.get("E", "S", "CART").latE6()).isEqualTo(22301234);
        assertThat(jdbc.queryForObject("SELECT lng_e6 FROM fnb_cart_delivery", Integer.class))
                .isEqualTo(114123456);
        var clear =
                service.saveStaff(
                        "E",
                        "S",
                        "STAFF",
                        "CART",
                        new SaveReq(1L, "Receiver", "+852 1234 5678", "Address", null),
                        "clear-point");
        assertThat(service.get("E", "S", "CART").latE6()).isNull();
        assertThat(jdbc.queryForObject("SELECT lng_e6 FROM fnb_cart_delivery", Integer.class))
                .isNull();
        assertThat(PricingSnapshotHash.of(point)).isNotEqualTo(PricingSnapshotHash.of(clear));
        assertThatThrownBy(() -> new SaveReq(2L, "Receiver", "123456", "Address", null, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new SaveReq(2L, "Receiver", "123456", "Address", null, 90000001, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM fnb_check_cart", Long.class))
                .isEqualTo(2);
    }

    @Test
    void getOnlyReadsRealDeliveryCartAndCannotCrossStore() {
        service.saveStaff("E", "S", "STAFF", "CART", request(0, "Address"), "address");
        assertThatThrownBy(() -> service.get("E", "OTHER", "CART"))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE fnb_check_cart SET fulfillment='PICKUP'");
        assertThatThrownBy(() -> service.get("E", "S", "CART")).isInstanceOf(BizException.class);
    }

    @Test
    void customerOwnerFailureCannotPersistAddressAndAuthorizedCustomerUsesOriginalCart() {
        var wrong = new CartAccess("OTHER", "secret", null);
        when(carts.customerGetCart("CART", wrong)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.saveCustomer("CART", wrong, request(0, "Address"), "bad"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fnb_cart_delivery", Integer.class))
                .isZero();
        var result =
                service.saveCustomer(
                        "CART",
                        new CartAccess("PARTY", "secret", null),
                        request(0, "Address"),
                        "good");
        assertThat(result.cartVersion()).isEqualTo(1);
    }
}
