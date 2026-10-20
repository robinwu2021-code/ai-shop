package ai.neargo.shop.pay.points.type;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.impl.PointTypeServiceImpl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PointTypeCurrencyTest {
    private final MarketService markets = mock(MarketService.class);
    private final PointsReadStore read = mock(PointsReadStore.class);
    private final PointsConfigurationStore config = mock(PointsConfigurationStore.class);
    private final PointTypeService service = new PointTypeServiceImpl(markets, config, read, mock(TypeRetirementService.class));

    PointTypeCurrencyTest() {
        when(markets.find("CN")).thenReturn(Optional.of(new MarketService.MarketRow("CN", "China", "CNY", 2, "Asia/Shanghai", 1, true, 1)));
        when(read.types()).thenReturn(List.of());
    }
    private PointTypeService.CreateType command(String currency, long points, long minor) {
        return new ObjectMapper().readValue("{\"typeCode\":\"TEST\",\"name\":\"Test\",\"currency\":\"" + currency + "\",\"rate\":{\"pointsUnit\":" + points + ",\"minorUnit\":" + minor + "}}", PointTypeService.CreateType.class);
    }
    @Test void saves_currency_precision_and_selected_rate_instead_of_ignoring_them() {
        var usd = service.create(command("USD", 150, 100), "OPS");
        assertThat(usd.currency()).isEqualTo("USD"); assertThat(usd.scale()).isEqualTo(2);
        assertThat(usd.rate()).isEqualTo(new RedeemRate(3, 2));
        verify(config).createType(usd, "OPS");
    }
    @Test void dinar_uses_three_decimal_minor_units_without_assuming_cents() {
        var kwd = service.create(command("KWD", 2500, 1000), "OPS");
        assertThat(kwd.currency()).isEqualTo("KWD"); assertThat(kwd.scale()).isEqualTo(3);
        assertThat(kwd.rate()).isEqualTo(new RedeemRate(5, 2));
    }
    @Test void omitted_settings_remain_compatible_with_the_existing_default() {
        var type = service.create(new PointTypeService.CreateType("DEFAULT", "Default"), "OPS");
        assertThat(type.market()).isEqualTo("CN"); assertThat(type.currency()).isEqualTo("CNY");
        assertThat(type.rate()).isEqualTo(new RedeemRate(1, 1));
    }
    @Test void fractional_or_nonpositive_rate_numbers_are_not_silently_rounded() {
        for (String value : List.of("0", "-1", "1.5")) {
            String json = "{\"typeCode\":\"INVALID\",\"name\":\"Invalid\",\"currency\":\"USD\",\"rate\":{\"pointsUnit\":" + value + ",\"minorUnit\":1}}";
            assertThatThrownBy(() -> new ObjectMapper().readValue(json, PointTypeService.CreateType.class))
                    .isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(config);
    }
    @Test void omitted_settings_keep_the_original_operator_receipt_payload() {
        record LegacyType(String typeCode, String name) {}
        var json = new ObjectMapper();
        assertThat(json.writeValueAsString(new PointTypeService.CreateType("OLD", "Old")))
                .isEqualTo(json.writeValueAsString(new LegacyType("OLD", "Old")));
    }
    @Test void invalid_currency_is_rejected_before_any_database_write() {
        for (String currency : List.of("BAD", "usd", "XXX", "XTS")) {
            assertThatThrownBy(() -> service.create(command(currency, 1, 1), "OPS")).hasMessage("POINTS_CURRENCY_INVALID");
        }
        verifyNoInteractions(config);
    }
}
