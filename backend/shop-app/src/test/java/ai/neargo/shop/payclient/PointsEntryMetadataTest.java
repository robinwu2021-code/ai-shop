package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointsEntryMetadataTest {
    @Test void metadata_keeps_only_header_and_reuses_same_voucher() {
        var inspection = mock(PointsInspectionService.class);
        when(inspection.transaction("P", "V")).thenReturn(new PointsInspectionService.TransactionDetail(
            PointsInspectionService.TransactionDirection.EARN,
            new PointsInspectionService.VoucherView(1, "V", "GRANT:ORDER", "CNY", 1, 1), null));
        var reader = new PointsEntryMetadata(inspection, "P");
        assertThat(reader.forVoucher("V").direction()).isEqualTo("EARN");
        assertThat(reader.forVoucher("V").businessRef()).isEqualTo("GRANT:ORDER");
        verify(inspection, times(1)).transaction("P", "V");
    }
    @Test void other_ledger_entries_remain_readable_without_fabricated_order() {
        var inspection = mock(PointsInspectionService.class);
        when(inspection.transaction("P", "V")).thenThrow(new IllegalArgumentException("POINTS_TRANSACTION_DIRECTION_UNSUPPORTED"));
        assertThat(new PointsEntryMetadata(inspection, "P").forVoucher("V").direction()).isNull();
    }
    @Test void hold_and_release_are_not_presented_as_consumption() {
        var inspection = mock(PointsInspectionService.class);
        for (var prefix : java.util.List.of("HOLD", "RELEASE", "CANCEL", "FINALIZE")) {
            when(inspection.transaction("P", prefix)).thenReturn(new PointsInspectionService.TransactionDetail(
                PointsInspectionService.TransactionDirection.REDEEM,
                new PointsInspectionService.VoucherView(1, prefix, prefix + ":ORDER", "CNY", 1, 1), null));
        }
        var reader = new PointsEntryMetadata(inspection, "P");
        assertThat(reader.forVoucher("HOLD").direction()).isEqualTo("HOLD");
        assertThat(reader.forVoucher("RELEASE").direction()).isEqualTo("RELEASE");
        assertThat(reader.forVoucher("CANCEL").direction()).isEqualTo("RELEASE");
        assertThat(reader.forVoucher("FINALIZE").direction()).isEqualTo("REDEEM");
    }
    @Test void unrelated_query_errors_are_not_hidden() {
        var inspection = mock(PointsInspectionService.class);
        when(inspection.transaction("P", "V")).thenThrow(new IllegalArgumentException("POINTS_VOUCHER_UNKNOWN"));
        assertThatThrownBy(() -> new PointsEntryMetadata(inspection, "P").forVoucher("V"))
            .hasMessage("POINTS_VOUCHER_UNKNOWN");
    }
}
