package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.service.impl.DiningPricingFactsServiceImpl;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DiningPricingFactsServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CheckCartService carts = mock(CheckCartService.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final ConfigRevisionService revisions = mock(ConfigRevisionService.class);
    private final DishOfferQueryPort offers = mock(DishOfferQueryPort.class);
    private final SalesRestrictionService limits = mock(SalesRestrictionService.class);
    private final StoreCardQueryPort stores = mock(StoreCardQueryPort.class);
    private final DiningPricingFactsServiceImpl service = new DiningPricingFactsServiceImpl(carts, catalog,
            revisions, offers, limits, stores, json);

    private OrderLineInput line(String ref) {
        return new OrderLineInput(ref, "M", "I", "R", "G", "S", new BigDecimal("2"), null, null,
                List.of(), List.of());
    }
    private CartVO cart() {
        return new CartVO("C", "A", "CHECK", "SESSION", "BUYER", "OPEN", "POS", "DINE_IN", null, 4L,
                List.of(line("L")));
    }
    private CatalogVO display(long price) {
        return new CatalogVO("A", "POS", "DINE_IN", Instant.now(),
                List.of(new CatalogMenuVO("M", "R", 1L, "Menu", 0, null, List.of())),
                List.of(new CatalogItemVO("M", "I", "CAT", "Category", "G", "Dish", null,
                        new DishVO("G", "SINGLE", 0, null, new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0),
                        List.of(), List.of(new CatalogSkuVO("S", null, null, price, "CNY", "KNOWN", 10, "KNOWN",
                                true, false, null, true, false, null)))));
    }
    private Offer offer(long price) { return new Offer("S", "G", "E", "A", price, "CNY", "KNOWN", 10, "KNOWN", true); }
    private void prepare() {
        var item = new MenuItemInput("I", "G", 0, true, "EXPLICIT", List.of("S"), "REPLACE", List.of(), List.of());
        var config = new MenuConfigResultVO(new MenuVO("M", "A", "Menu", "TIME", "UTC", 0, null,
                "DRAFT", 0L, 0L), new MenuScheduleVO(null, null,
                List.of(new WeekScheduleInput(3, "00:00", "23:59", 0)), List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CAT", "Category", 0, List.of(item)))),
                List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var body = new MenuPublicationSnapshot(4, "MENU", "M", 0,
                new MenuPublicationSnapshot.Captured("E", "A", config,
                        List.of(new DishVO("G", "SINGLE", 0,
                                new DishPresentation("Frozen dish", List.of(), List.of(), List.of(), List.of()),
                                new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0)),
                        List.of(new MenuPublicationSnapshot.ItemOptions("I", List.of())), List.of(), List.of()),
                List.of(new SkuIdentityFact("S", "G", "E", "A", 0)),
                List.of(new GoodsFact("G", "E", "A", "Original", null, true, "APPROVED", 0,
                        List.of(new SkuFact("S", "Small", 0)))), null);
        String doc = MenuPublicationDocuments.freeze(json, body);
        when(revisions.getRevision("E", "A", "R")).thenReturn(new ConfigRevisionContentVO(
                new ConfigRevisionVO("R", "MENU", "M", 1, Instant.EPOCH.atOffset(ZoneOffset.UTC),
                        PublicationDocuments.hash(json, doc), "EFFECTIVE"), doc));
        when(carts.getCart("E", "A", "C")).thenReturn(new CartResultVO(cart()));
        when(stores.cards(List.of("A"))).thenReturn(Map.of("A", new StoreCard("A", "Store", "E", null,
                "ACTIVE", true, "00:00-23:59", null, null, null, 0, 0)));
        when(catalog.read(anyString(), anyString(), any())).thenAnswer(a -> display(101));
        when(offers.getOffers("E", "A", List.of("S"))).thenReturn(List.of(offer(101)));
        when(limits.evaluate(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(a -> new SalesRestrictionEvaluationVO(false, null, true, Instant.now(), List.of()));
    }
    @Test void stableAuthoritativeFactsIgnoreReadClockAndContainNoCredentials() {
        prepare(); var result = service.staff("E", "A", "C", 4);
        assertThat(result.payload().amountMinor()).isEqualTo(202);
        assertThat(result.payload().subjectNo()).isNull();
        assertThat(result.sourceHash()).isEqualTo(PricingSnapshotHash.of(result.payload()));
        assertThat(json.writeValueAsString(result)).doesNotContain("accessToken", "asOf", "token_hash");
        verify(carts, times(2)).getCart("E", "A", "C");
    }
    @Test void authorizationFailurePrecedesAnyPriceOrCatalogRead() {
        when(carts.getCart("E", "A", "C")).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
        verifyNoInteractions(catalog, revisions, offers, limits, stores);
    }
    @Test void customerUsesOriginalCredentialsAndSessionScope() {
        prepare(); var access = new CartAccess("SESSION", "secret", "BUYER");
        when(carts.customerGetCart("C", access)).thenReturn(new CartResultVO(cart()));
        var result = service.customer("C", access, 4);
        assertThat(result.payload().subjectNo()).isEqualTo("SESSION");
        assertThat(json.writeValueAsString(result)).doesNotContain("secret");
        verify(carts, times(2)).customerGetCart("C", access);
    }
    @Test void qrCustomerMayOmitSessionAndUsesAuthorizedCartSessionForCatalog() {
        prepare(); var access = new CartAccess(null, "secret", "BUYER"); var old = cart();
        var qr = new CartVO(old.cartNo(), old.storeNo(), old.checkNo(), old.sessionNo(), old.buyerNo(),
                old.status(), "QR", old.fulfillment(), null, old.version(), old.lines());
        when(carts.customerGetCart("C", access)).thenReturn(new CartResultVO(qr));
        when(catalog.guest("A", "SESSION", "secret", "BUYER")).thenAnswer(a -> {
            var d = display(101);
            return new CatalogVO(d.storeNo(), "QR", d.fulfillment(), d.asOf(), d.menus(), d.items());
        });
        assertThat(service.customer("C", access, 4).payload().subjectNo()).isEqualTo("SESSION");
        verify(catalog, times(2)).guest("A", "SESSION", "secret", "BUYER");
    }
    @Test void changedPriceBetweenCompleteReadsRequiresRetry() {
        prepare(); var count = new AtomicInteger();
        when(catalog.read(anyString(), anyString(), any()))
                .thenAnswer(a -> display(count.incrementAndGet() == 1 ? 101 : 102));
        when(offers.getOffers("E", "A", List.of("S"))).thenAnswer(a -> List.of(offer(count.get() == 1 ? 101 : 102)));
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void staleCartVersionIsRejectedBeforeAnySourceRead() {
        prepare(); assertThatThrownBy(() -> service.staff("E", "A", "C", 3)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(catalog, offers);
    }
    @Test void aggregateStockAcrossRepeatedSkuLinesCannotBeBypassed() {
        prepare(); var c = cart();
        when(carts.getCart("E", "A", "C")).thenReturn(new CartResultVO(new CartVO(c.cartNo(), c.storeNo(),
                c.checkNo(), c.sessionNo(), c.buyerNo(), c.status(), c.orderSource(), c.fulfillment(), null, 4L,
                List.of(line("L1"), line("L2")))));
        when(offers.getOffers("E", "A", List.of("S"))).thenReturn(List.of(new Offer("S", "G", "E", "A", 101L,
                "CNY", "KNOWN", 3, "KNOWN", true)));
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
    }
    @Test void missingQuotaPriceAndFrozenGoodsReparentNeverBecomeUsableFacts() {
        prepare();
        when(limits.evaluate(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
        when(offers.getOffers("E", "A", List.of("S"))).thenReturn(List.of(new Offer("S", "OTHER", "E", "A", 101L,
                "CNY", "KNOWN", 10, "KNOWN", true)));
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
        when(offers.getOffers("E", "A", List.of("S"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.staff("E", "A", "C", 4)).isInstanceOf(BizException.class);
    }
    @Test void receiverRejectsTamperedConservationAndSubjectEvenWithRecomputedDigest() {
        prepare(); var result = service.staff("E", "A", "C", 4); var p = result.payload();
        var wrong = new DiningPriceFacts.Payload(p.entityNo(), p.subjectNo(), p.cart(), p.menus(), p.identities(),
                p.offers(), p.restrictions(), p.units(), p.composition(), p.currency(), 203L);
        assertThatThrownBy(() -> DiningPriceFactsPolicy.validate(new DiningPriceFacts(1, wrong,
                PricingSnapshotHash.of(wrong)), "E", "A", "C", 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiningPriceFactsPolicy.validate(result, "E", "A", "C", 4, "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
