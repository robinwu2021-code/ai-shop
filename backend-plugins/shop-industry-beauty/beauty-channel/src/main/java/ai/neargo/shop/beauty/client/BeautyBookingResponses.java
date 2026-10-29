package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentVO;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.NoShowVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.common.PageData;

/** Required response fields distinguish real success from a public HTTP200 error envelope. */
final class BeautyBookingResponses {
    private BeautyBookingResponses() { }
    static <T> T require(T value) {
        boolean valid = switch (value) {
            case AppointmentVO v -> text(v.appointmentNo()) && text(v.status()) && text(v.operationNo())
                    && text(v.operationState());
            case MyAppointmentVO v -> text(v.appointmentNo()) && text(v.status()) && text(v.operationNo())
                    && text(v.operationState());
            case CancellationVO v -> text(v.appointmentNo()) && text(v.status()) && text(v.operationNo())
                    && text(v.operationState());
            case NoShowVO v -> text(v.appointmentNo()) && text(v.status()) && text(v.operationNo())
                    && text(v.operationState());
            case OperationVO v -> text(v.operationNo()) && text(v.appointmentNo()) && text(v.state()) &&
                    text(v.action());
            case AppointmentPreviewVO v -> v.expiresAt() != null && text(v.policyVersionNo()) && v.startAt() != null
                    && v.endAt() != null && v.items() != null && v.assignments() != null;
            case AppointmentChangePreviewVO v -> text(v.previewNo()) && v.expectedRevision() != null
                    && require(v.preview()) != null;
            case AppointmentDetailVO v -> v.appointment() != null && text(v.appointment().appointmentNo())
                    && v.items() != null && v.assignments() != null && v.availableActions() != null;
            case MyAppointmentDetailVO v -> v.appointment() != null && text(v.appointment().appointmentNo())
                    && v.items() != null && v.assignments() != null && v.availableActions() != null;
            case AvailabilityVO v -> v.candidates() != null && v.expiresAt() != null;
            case PublicAvailabilityVO v -> v.candidates() != null && v.expiresAt() != null;
            case PageData<?> v -> v.records() != null && v.page() > 0 && v.size() > 0 && v.total() >= 0;
            case null -> false;
            default -> false;
        };
        if (!valid) { throw new IllegalStateException("incomplete booking success response"); }
        return value;
    }
    static AppointmentPreviewVO durablePreview(AppointmentPreviewVO preview) {
        require(preview);
        if (!text(preview.previewNo())) { throw new IllegalStateException("durable preview identity required"); }
        return preview;
    }
    static <T> T identity(T value, String expected) {
        String actual = switch (value) {
            case OperationVO v -> v.operationNo();
            case AppointmentVO v -> v.appointmentNo();
            case MyAppointmentVO v -> v.appointmentNo();
            case CancellationVO v -> v.appointmentNo();
            case NoShowVO v -> v.appointmentNo();
            case AppointmentDetailVO v -> v.appointment().appointmentNo();
            case MyAppointmentDetailVO v -> v.appointment().appointmentNo();
            default -> null;
        };
        if (!expected.equals(actual)) { throw new IllegalStateException("different booking response identity"); }
        return value;
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
