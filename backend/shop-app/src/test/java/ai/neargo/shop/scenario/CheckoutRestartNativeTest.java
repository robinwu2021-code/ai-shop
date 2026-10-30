package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;

/**
 * Two independently booted and closed application contexts share only the native owner database.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutRestartNativeTest {
    @Test
    void lostConfirmationAndPlaceResponsesRecoverOriginalOrderAfterContextRestart() throws SQLException {
        assertThat(System.getProperty("checkout.test.native")).isEqualTo("true");
        String nativeUrl = System.getenv("CHECKOUT_NATIVE_MYSQL_URL");
        assertThat(nativeUrl != null && nativeUrl.startsWith("jdbc:mysql://127.0.0.1:"))
                .as("native execution requires the owned runner MySQL configuration")
                .isTrue();
        Fixture fixture = new Fixture();
        CheckoutPort.CheckoutQuote quote;
        CheckoutPort.BuyerConfirmation confirmation;
        CheckoutPort.CheckoutResult created;
        String sourceLine;
        CheckoutPort firstOwner;
        ConfigurableApplicationContext first = boot();
        try {
            first.getAutowireCapableBeanFactory().autowireBean(fixture);
            requireNativeDatabase(fixture);
            fixture.fixture();
            firstOwner = fixture.checkout;
            quote =
                    fixture.asMerchant(
                            () -> fixture.checkout.prepare(fixture.scope, fixture.request(null)));
            confirmation = fixture.asBuyer(() -> fixture.confirm(quote));
            // A lost HTTP response leaves only committed authorization; the original key recovers
            // it.
            assertThat(fixture.asBuyer(() -> fixture.confirm(quote))).isEqualTo(confirmation);
            created =
                    fixture.asBuyer(
                            () -> fixture.checkout.place(fixture.place(quote, confirmation)));
            sourceLine =
                    fixture.jdbc.queryForObject(
                            "SELECT source_line_no FROM ord_checkout_line WHERE operation_no=?",
                            String.class,
                            quote.operation().operationNo());
            fixture.jdbc.update("DELETE FROM sys_idempotent WHERE user_no=?", fixture.buyer);
            fixture.jdbc.update(
                    "UPDATE ord_order SET status='PAID',pay_channel='WECHAT',pay_trade_no=? WHERE"
                            + " order_no=?",
                    "T-" + fixture.sku,
                    created.orderNo());
            fixture.jdbc.update(
                    "UPDATE ord_sub_order SET status='FULFILLING' WHERE sub_order_no=?",
                    created.subOrderNo());
            fixture.jdbc.update(
                    "INSERT INTO stl_payment(payment_no,direction,order_no,user_no,pay_channel,"
                            + "amount_minor,currency,status,trade_no,created_at,updated_at) "
                            + "VALUES (?,'PAY',?,?,'WECHAT',303,'CNY','SUCCESS',?,NOW(),NOW())",
                    "P-" + fixture.sku,
                    created.orderNo(),
                    fixture.buyer,
                    "T-" + fixture.sku);
        } finally {
            first.close();
        }
        assertThat(first.isActive()).isFalse();
        try (ConfigurableApplicationContext second = boot()) {
            assertThat(second).isNotSameAs(first);
            second.getAutowireCapableBeanFactory().autowireBean(fixture);
            requireNativeDatabase(fixture);
            assertThat(fixture.checkout).isNotSameAs(firstOwner);
            fixture.testClock.at = quote.expiresAt().plusSeconds(60);
            assertThat(
                            fixture.asMerchant(
                                            () ->
                                                    fixture.checkout.findOperation(
                                                            fixture.scope, quote.operation()))
                                    .orderNo())
                    .isEqualTo(created.orderNo());
            assertThat(
                            fixture.asBuyer(
                                            () ->
                                                    fixture.checkout.place(
                                                            fixture.place(quote, confirmation)))
                                    .orderNo())
                    .isEqualTo(created.orderNo());
            var source =
                    fixture.asMerchant(
                            () ->
                                    fixture.checkout.source(
                                            fixture.scope,
                                            new CheckoutPort.SourceLookup(
                                                    quote.operation(), created.subOrderNo())));
            assertThat(source.financialState()).isEqualTo("PAID");
            assertThat(source.lines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.sourceLineNo()).isEqualTo(sourceLine));
            assertThat(
                            fixture.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                    Long.class,
                                    fixture.buyer))
                    .isEqualTo(1);
        }
    }

    private static void requireNativeDatabase(Fixture fixture) throws SQLException {
        try (var connection = fixture.dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(
                        ShopApplication.class, CheckoutFlowFixture.ClockConfig.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--shop.member-code.enabled=true",
                        "--shop.catalog.publication.archive-only=true",
                        "--shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    private static final class Fixture extends CheckoutFlowFixture {}
}
