package ai.neargo.shop.food.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.biz.BizStoreProfileController;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** POS 需求 B6：门店基本信息。营业日按门店时区减切日分钟推算；币种取主体市场，查不到不兜底。 */
class StoreProfileAssemblerTest {
    private final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    private final StoreOperationService policies = mock(StoreOperationService.class);
    private final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    private final MarketPort markets = mock(MarketPort.class);

    StoreProfileAssembler at(String instant, boolean platformPorts) {
        when(stores.cards(anyList())).thenReturn(Map.of("ST1", new StoreCard("ST1", "海岸店", "M1", null, "ACTIVE",
                true, null, null, null, null, 0, 0)));
        when(policies.getPolicy(eq("M1"), eq("ST1"), any())).thenReturn(new StoreOperationPolicyResultVO(
                "P1", "Asia/Shanghai", 240, "POSTPAY", List.of(), 1));
        return new StoreProfileAssembler(stores, policies, () -> platformPorts ? merchants : null,
                () -> platformPorts ? markets : null, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void businessDayFollowsStoreTimezoneAndCutover() {
        when(merchants.marketOf("M1")).thenReturn("CN");
        when(markets.find("CN")).thenReturn(Optional.of(
                new MarketPort.MarketBrief("CN", "中国大陆", "CNY", 2, "Asia/Shanghai", 1, true)));
        // 上海 11-13 10:30 → 营业日 11-13，起 11-13 04:00（= 11-12T20:00Z），止 + 24h
        var day = at("2026-11-13T02:30:00Z", true).profile("M1", "ST1");
        assertThat(day.businessDate()).isEqualTo(LocalDate.parse("2026-11-13"));
        assertThat(day.businessDayStartsAt()).isEqualTo(Instant.parse("2026-11-12T20:00:00Z"));
        assertThat(day.businessDayEndsAt()).isEqualTo(Instant.parse("2026-11-13T20:00:00Z"));
        assertThat(day.serverTime()).isEqualTo(Instant.parse("2026-11-13T02:30:00Z"));
        assertThat(day.currency()).isEqualTo("CNY");
        assertThat(day.currencyScale()).isEqualTo(2);
        assertThat(day.market()).isEqualTo("CN");
        assertThat(day.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(day.cutoverMinute()).isEqualTo(240);
        assertThat(day.payPolicy()).isEqualTo("POSTPAY");
        assertThat(day.storeName()).isEqualTo("海岸店");
        // 上海 11-14 02:00（切日 04:00 之前）仍属 11-13
        var lateNight = at("2026-11-13T18:00:00Z", true).profile("M1", "ST1");
        assertThat(lateNight.businessDate()).isEqualTo(LocalDate.parse("2026-11-13"));
        // 上海 11-14 04:00 整切到 11-14
        var cutover = at("2026-11-13T20:00:00Z", true).profile("M1", "ST1");
        assertThat(cutover.businessDate()).isEqualTo(LocalDate.parse("2026-11-14"));
        assertThat(cutover.businessDayStartsAt()).isEqualTo(Instant.parse("2026-11-13T20:00:00Z"));
    }

    @Test
    void currencyIsNullWhenMarketIsMissingUnknownOrPortsAbsent() {
        when(merchants.marketOf("M1")).thenReturn(null);
        var missing = at("2026-11-13T02:30:00Z", true).profile("M1", "ST1");
        assertThat(missing.market()).isNull();
        assertThat(missing.currency()).isNull();
        assertThat(missing.currencyScale()).isNull();
        assertThat(missing.businessDate()).isEqualTo(LocalDate.parse("2026-11-13"));

        when(merchants.marketOf("M1")).thenReturn("XX");
        when(markets.find("XX")).thenReturn(Optional.empty());
        var unknown = at("2026-11-13T02:30:00Z", true).profile("M1", "ST1");
        assertThat(unknown.market()).isNull();
        assertThat(unknown.currency()).isNull();

        assertThat(at("2026-11-13T02:30:00Z", false).profile("M1", "ST1").currency()).isNull();
    }

    @Test
    void foreignStoreOrMissingPolicyIsNotFound() {
        var assembler = at("2026-11-13T02:30:00Z", true);
        assertThatThrownBy(() -> assembler.profile("OTHER", "ST1")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        when(policies.getPolicy(eq("M1"), eq("ST1"), any())).thenReturn(null);
        assertThatThrownBy(() -> assembler.profile("M1", "ST1")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void readableWithOrderViewWhichClerksAndTerminalsHave() throws Exception {
        var rule = BizStoreProfileController.class.getMethod("profile", String.class,
                jakarta.servlet.http.HttpServletRequest.class).getAnnotation(PreAuthorize.class);
        assertThat(rule.value()).isEqualTo("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')");
    }
}
