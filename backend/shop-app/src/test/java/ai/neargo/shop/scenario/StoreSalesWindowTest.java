package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Definition;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Entry;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Group;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.SalesMode;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Window;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreSalesWindowTest {
    @Test
    void halfOpenLocalWeekdayWindowsRespectTimezoneAndRepeatedDstHour() {
        var daytime = new Window("1111100", 600, 720);
        assertThat(daytime.includes(Instant.parse("2026-10-21T02:00:00Z"), "Asia/Shanghai")).isTrue();
        assertThat(daytime.includes(Instant.parse("2026-10-21T04:00:00Z"), "Asia/Shanghai")).isFalse();
        assertThat(daytime.includes(Instant.parse("2026-10-24T02:00:00Z"), "Asia/Shanghai")).isFalse();
        var dst = new Window("0000001", 60, 120);
        assertThat(dst.includes(Instant.parse("2026-11-01T05:30:00Z"), "America/New_York")).isTrue();
        assertThat(dst.includes(Instant.parse("2026-11-01T06:30:00Z"), "America/New_York")).isTrue();
    }
    @Test
    void hiddenGroupHidesEntriesAndWindowIsIndependentOfDisplay() {
        var entry = new Entry("SKU", "G", 0, true, SalesMode.WINDOWED,
                List.of(new Window("1111100", 600, 720)));
        var hidden = new Definition("Asia/Shanghai", List.of(new Group("G", "group", 0, false)), List.of(entry));
        assertThat(hidden.visibleEntry("SKU")).isNull();
        var shown = new Definition("Asia/Shanghai", List.of(new Group("G", "group", 0, true)), List.of(entry));
        assertThat(shown.visibleEntry("SKU")).isEqualTo(entry);
        assertThat(entry.saleableAt(Instant.parse("2026-10-21T04:00:00Z"), "Asia/Shanghai")).isFalse();
    }
    @Test
    void malformedTimezoneReferencesAndWindowModesAreRejected() {
        assertThatThrownBy(() -> new Window("0000000", 600, 720)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new Window("1111111", 1380, 60)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new Entry("SKU", null, 0, true, SalesMode.WINDOWED, List.of()))
                .isInstanceOf(BizException.class);
        var entry = new Entry("SKU", "MISSING", 0, true, SalesMode.UNRESTRICTED, List.of());
        assertThatThrownBy(() -> new Definition("Asia/Shanghai", List.of(), List.of(entry)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new Definition("invalid", List.of(), List.of())).isInstanceOf(BizException.class);
    }
}
