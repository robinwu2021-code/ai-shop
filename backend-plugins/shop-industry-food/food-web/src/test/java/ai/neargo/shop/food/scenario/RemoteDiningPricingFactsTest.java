package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.foodclient.FoodClientConfiguration;
import ai.neargo.shop.food.foodclient.RemoteDiningPricingFactsService;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.DiningPriceSourcePolicy;
import ai.neargo.shop.food.port.FoodPricingSourceFactsPortImpl;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.spi.PricingSourceInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class RemoteDiningPricingFactsTest {
    private final ObjectMapper json = new ObjectMapper();
    DiningPriceFacts receipt(String subject) {
        String document = """
                {"entityNo":"E","subjectNo":null,"currency":"CNY","amountMinor":200,
                 "cart":{"cartNo":"C","storeNo":"A","checkNo":"CHECK","sessionNo":"SESSION",
                  "buyerNo":"BUYER","status":"OPEN","orderSource":"POS","fulfillment":"DINE_IN","version":4,
                  "lines":[{"sourceLineRef":"L","menuNo":"M","menuItemNo":"I","menuRevisionNo":"R",
                  "goodsNo":"G","skuNo":"S","qty":2,"selections":[],"bundleChoices":[]}]},
                 "menus":[{"menuNo":"M","revisionNo":"R","revision":1,"contentHash":"HASH","dependencies":[]}],
                 "identities":[{"skuNo":"S","goodsNo":"G","entityNo":"E","storeNo":"A","version":0}],
                 "offers":[{"skuNo":"S","goodsNo":"G","entityNo":"E","storeNo":"A","priceMinor":100,
                  "currency":"CNY","pricingStatus":"KNOWN","available":10,"stockStatus":"KNOWN","onSale":true}],
                 "restrictions":[{"skuNo":"S","blocked":false,"originalStockKnown":true,"rules":[]}],
                 "units":[{"sourceLineNo":"L","componentRef":"HASH","kind":"MAIN","goodsNo":"G","skuNo":"S",
                  "priceRevision":"HASH","quantity":2,"unitPriceMinor":100,"amountMinor":200}]}
                """.replace("HASH", "a".repeat(64));
        var p = json.readValue(document, DiningPriceFacts.Payload.class);
        var catalog = new CatalogVO("A", "POS", "DINE_IN", null,
                List.of(new CatalogMenuVO("M", "R", 1L, "Menu", 0, null, List.of())),
                List.of(new CatalogItemVO("M", "I", "CAT", "Category", "G", "Dish", null,
                        new DishVO("G", "SINGLE", 0, null, new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0),
                        List.of(), List.of(new CatalogSkuVO("S", null, null, 100L, "CNY", "KNOWN", 10,
                                "KNOWN", true, false, null, true, false, null)))));
        var units = DiningPriceSourcePolicy.compose(p.cart(), catalog,
                Map.of("S", p.offers().getFirst()), json);
        p = new DiningPriceFacts.Payload(p.entityNo(), subject, p.cart(), p.menus(), p.identities(), p.offers(),
                p.restrictions(), units, new DiningPriceFacts.Composition(catalog.menus(), catalog.items()),
                p.currency(), p.amountMinor());
        return new DiningPriceFacts(1, p, PricingSnapshotHash.of(p));
    }
    @Test void typedWirePreservesScopeCredentialsAndVersionAndRejectsMalformedReceiptsWithoutRetry() throws Exception {
        var response = new AtomicReference<>(json.writeValueAsString(receipt(null)));
        var status = new AtomicInteger(200); var calls = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of(PricingSourceInternalApi.STAFF_PATH, PricingSourceInternalApi.CUSTOMER_PATH)) {
            server.createContext(path, ex -> {
                assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                calls.add(path + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status.get(), body.length); ex.getResponseBody().write(body); ex.close();
            });
        }
        server.start();
        try {
            var http = new InternalHttp(n -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "wire");
            var service = new RemoteDiningPricingFactsService(http.client(ServiceName.FOOD,
                    PricingSourceInternalApi.class, Duration.ofSeconds(5)));
            var adapter = new FoodPricingSourceFactsPortImpl(service);
            var source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);
            var projected = adapter.staff(new Scope("MAIN", "E", "A"), source);
            assertThat(projected.prices().baseTotal().minor()).isEqualTo(200);
            assertThat(projected.invoiceBases()).hasSize(1);
            response.set(json.writeValueAsString(receipt("SESSION")));
            var access = new CartAccess("SESSION", "secret", "BUYER");
            assertThat(adapter.customer(source, new Credentials("SESSION", "secret", "BUYER")).subjectNo())
                    .isEqualTo("SESSION");
            assertThat(calls.get(1)).contains("\"accessToken\":\"secret\"", "\"expectedVersion\":4");
            response.set(json.writeValueAsString(receipt(null)));
            assertThatThrownBy(() -> service.customer("C", access, 4)).isInstanceOf(BizException.class);
            response.set(json.writeValueAsString(receipt(null)).replace("\"amountMinor\":200", "\"amountMinor\":201"));
            assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
            response.set(json.writeValueAsString(receipt(null)));
            assertThatThrownBy(() -> service.staff("OTHER", "A", "C", 4)).isInstanceOf(BizException.class);
            for (int code : List.of(403, 409, 503)) {
                status.set(code);
                ErrorCode expected = switch (code) { case 403 -> ErrorCode.FORBIDDEN;
                    case 409 -> ErrorCode.CONFLICT; default -> ErrorCode.PRICING_OWNER_UNSUPPORTED; };
                assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(expected));
            }
            assertThat(calls).hasSize(8);
        } finally { server.stop(0); }
    }
    @Test void receiverMustNotAcceptCartWithPaidSelectionOmittedFromUnitsEvenAfterRehash() {
        var p = receipt(null).payload(); var c = p.cart(); var line = c.lines().getFirst();
        var selected = new OrderLineInput(line.sourceLineRef(), line.menuNo(), line.menuItemNo(), line.menuRevisionNo(),
                line.goodsNo(), line.skuNo(), line.qty(), null, null,
                List.of(new CartSelectionInput("GROUP", "MODIFIER", "MOD", BigDecimal.ONE, 7L)), List.of());
        var cart = new CartVO(c.cartNo(), c.storeNo(), c.checkNo(), c.sessionNo(), c.buyerNo(), c.status(),
                c.orderSource(), c.fulfillment(), c.scheduledAt(), c.version(), List.of(selected));
        var payload = new DiningPriceFacts.Payload(p.entityNo(), null, cart, p.menus(), p.identities(), p.offers(),
                p.restrictions(), p.units(), p.composition(), p.currency(), p.amountMinor());
        var bad = new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload));
        assertThatThrownBy(() -> DiningPriceFactsPolicy.validate(bad, "E", "A", "C", 4, null))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void clientAssemblyLoadsOneSourceServiceAndHonorsExplicitLocalOverride() {
        var runner = new ApplicationContextRunner().withUserConfiguration(FoodClientConfiguration.class)
                .withPropertyValues("shop.food.enabled=true", "shop.food.deployment=standalone",
                        "spring.profiles.active=api")
                .withBean(InternalHttp.class, () -> new InternalHttp(n -> Optional.of("http://127.0.0.1:1"), "wire"));
        runner.run(c -> assertThat(c).hasNotFailed().hasSingleBean(DiningPricingFactsService.class)
                .hasSingleBean(RemoteDiningPricingFactsService.class));
        runner.withBean(DiningPricingFactsService.class, () -> new RemoteDiningPricingFactsService(null))
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(DiningPricingFactsService.class));
        runner.withPropertyValues("shop.food.enabled=false")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(DiningPricingFactsService.class));
    }
    @Test void receiptRejectsNumericStringsFractionsUnknownFieldsAndBooleanCoercion() {
        String valid = json.writeValueAsString(receipt(null));
        for (String invalid : List.of(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"revision\":1", "\"revision\":1.5"),
                valid.replace("\"blocked\":false", "\"blocked\":\"false\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"callerPrice\":1"))) {
            assertThatThrownBy(() -> json.readValue(invalid, DiningPriceFacts.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
