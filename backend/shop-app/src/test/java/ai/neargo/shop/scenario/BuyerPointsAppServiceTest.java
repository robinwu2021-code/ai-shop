package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.payclient.impl.BuyerPointsAppServiceImpl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class BuyerPointsAppServiceTest {
    @Test
    void buyer_sees_each_point_type_separately_including_retired_obligations() {
        var types = mock(PointsAdministrationService.class);
        var inspection = mock(PointsInspectionService.class);
        when(types.types())
                .thenReturn(
                        List.of(
                                new PointType(
                                        "CN",
                                        "CN",
                                        "CNY",
                                        2,
                                        new RedeemRate(2, 1),
                                        2000,
                                        365,
                                        PointType.Status.ACTIVE),
                                new PointType(
                                        "OLD",
                                        "CN",
                                        "CNY",
                                        2,
                                        new RedeemRate(1, 1),
                                        2000,
                                        365,
                                        PointType.Status.RETIRED)));
        when(inspection.account("CN", HolderType.USER, "U1"))
                .thenReturn(
                        new PointsInspectionService.AccountView(
                                new AccountSnapshot(
                                        "CN",
                                        HolderType.USER,
                                        "U1",
                                        Map.of(Bucket.AVAILABLE, 20L, Bucket.PENDING, 5L),
                                        false,
                                        null,
                                        0),
                                true,
                                2));
        when(inspection.account("OLD", HolderType.USER, "U1"))
                .thenReturn(
                        new PointsInspectionService.AccountView(
                                new AccountSnapshot(
                                        "OLD",
                                        HolderType.USER,
                                        "U1",
                                        Map.of(Bucket.AVAILABLE, 7L),
                                        false,
                                        null,
                                        0),
                                true,
                                1));
        var service = new BuyerPointsAppServiceImpl(types, inspection);
        var accounts = service.accounts("U1");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).balance()).isEqualTo(20);
        assertThat(accounts.get(0).pendingBalance()).isEqualTo(5);
        assertThat(accounts.get(0).ratePoints()).isEqualTo(2);
        assertThat(accounts.get(1).status()).isEqualTo("RETIRED");
        assertThat(accounts.get(1).balance()).isEqualTo(7);
        verify(inspection, never()).account(anyString(), eq(HolderType.USER), eq("U2"));
    }

    @Test
    void buyer_records_are_scoped_to_own_account_and_keep_cursor() {
        var types = mock(PointsAdministrationService.class);
        var inspection = mock(PointsInspectionService.class);
        var row =
                new PointsInspectionService.EntryView(
                        11,
                        "E11",
                        "V11",
                        HolderType.USER,
                        "U1",
                        Bucket.AVAILABLE,
                        8,
                        28,
                        1_700_000_000_000L);
        when(inspection.entries("CN", HolderType.USER, "U1", null, 0, 20))
                .thenReturn(new PointsInspectionService.EntryPage(List.of(row), 11, false));
        var page = new BuyerPointsAppServiceImpl(types, inspection).records("U1", "CN", 0, 20);
        assertThat(page.next()).isEqualTo(11);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().at()).isEqualTo(1_700_000_000_000L);
        assertThat(page.rows().getFirst().balanceAfter()).isEqualTo(28);
        verify(inspection).entries("CN", HolderType.USER, "U1", null, 0, 20);
    }
}
