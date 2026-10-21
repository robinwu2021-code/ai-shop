package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingResponseValidationTest {
    @Test void authenticatedPreviewRequiresDurablePreviewIdentity() {
        var api = mock(BeautyBookingInternalApi.class);
        var at = OffsetDateTime.parse("2026-10-22T10:00Z");
        when(api.previewMyAppointment(any())).thenReturn(new AppointmentPreviewVO(null, at, "P", at,
                at.plusMinutes(30), List.of(), List.of(), 100));
        var remote = new RemoteAppointmentService(api);
        DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "CONSUMER", "USR", "U"), () -> {
            assertThatThrownBy(() -> remote.previewMyAppointment("U", new PreviewMyAppointmentReq("S",
                    List.of(new AppointmentItemReq("SKU", 1)), at, null))).isInstanceOf(IllegalStateException.class);
            return null;
        });
    }
}
