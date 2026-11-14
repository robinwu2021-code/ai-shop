package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.AppointmentSummaryVO;
import ai.neargo.shop.beauty.dto.AppointmentVO;
import ai.neargo.shop.beauty.dto.AvailabilityQuery;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.MyAppointmentQuery;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.NoShowVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed current-identity booking protocol; public bodies never bind delegated metadata. */
public interface BeautyBookingInternalApi {
    @PostExchange("/internal/beauty/booking/queryAvailability")
    AvailabilityVO queryAvailability(@RequestBody QueryAvailability request);
    record QueryAvailability(String entityNo, String currentStoreNo, String actorNo, AvailabilityQuery query,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/queryPublicAvailability")
    PublicAvailabilityVO queryPublicAvailability(@RequestBody QueryPublicAvailability request);
    record QueryPublicAvailability(PublicAvailabilityQuery query) { }
    @PostExchange("/internal/beauty/booking/previewAppointment")
    AppointmentPreviewVO previewAppointment(@RequestBody PreviewAppointment request);
    record PreviewAppointment(String entityNo, String currentStoreNo, String actorNo, PreviewAppointmentReq req,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/createAppointment")
    AppointmentVO createAppointment(@RequestBody CreateAppointment request);
    record CreateAppointment(String entityNo, String currentStoreNo, String actorNo, CreateAppointmentReq req,
            String idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/listAppointments")
    PageData<AppointmentSummaryVO> listAppointments(@RequestBody ListAppointments request);
    record ListAppointments(String entityNo, String currentStoreNo, String actorNo, AppointmentQuery query,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/getAppointment")
    AppointmentDetailVO getAppointment(@RequestBody GetAppointment request);
    record GetAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/previewAppointmentChange")
    AppointmentChangePreviewVO previewAppointmentChange(@RequestBody PreviewAppointmentChange request);
    record PreviewAppointmentChange(String entityNo, String currentStoreNo, String actorNo, String
            appointmentNo, PreviewAppointmentChangeReq req, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/changeAppointment")
    AppointmentVO changeAppointment(@RequestBody ChangeAppointment request);
    record ChangeAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            ChangeAppointmentReq req, String idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/cancelAppointment")
    CancellationVO cancelAppointment(@RequestBody CancelAppointment request);
    record CancelAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            CancelAppointmentReq req, String idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/recordNoShow")
    NoShowVO recordNoShow(@RequestBody RecordNoShow request);
    record RecordNoShow(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            RecordNoShowReq req, String idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/previewMyAppointment")
    AppointmentPreviewVO previewMyAppointment(@RequestBody PreviewMyAppointment request);
    record PreviewMyAppointment(String userNo, PreviewMyAppointmentReq req, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/createMyAppointment")
    MyAppointmentVO createMyAppointment(@RequestBody CreateMyAppointment request);
    record CreateMyAppointment(String userNo, CreateMyAppointmentReq req, String idempotencyKey,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/listMyAppointments")
    PageData<MyAppointmentVO> listMyAppointments(@RequestBody ListMyAppointments request);
    record ListMyAppointments(String userNo, MyAppointmentQuery query, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/getMyAppointment")
    MyAppointmentDetailVO getMyAppointment(@RequestBody GetMyAppointment request);
    record GetMyAppointment(String userNo, String appointmentNo, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/previewMyAppointmentChange")
    AppointmentChangePreviewVO previewMyAppointmentChange(@RequestBody PreviewMyAppointmentChange request);
    record PreviewMyAppointmentChange(String userNo, String appointmentNo, PreviewMyAppointmentChangeReq req,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/changeMyAppointment")
    MyAppointmentVO changeMyAppointment(@RequestBody ChangeMyAppointment request);
    record ChangeMyAppointment(String userNo, String appointmentNo, ChangeAppointmentReq req, String
            idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/cancelMyAppointment")
    CancellationVO cancelMyAppointment(@RequestBody CancelMyAppointment request);
    record CancelMyAppointment(String userNo, String appointmentNo, CancelAppointmentReq req, String
            idempotencyKey, DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/getOperation")
    OperationVO getOperation(@RequestBody GetOperation request);
    record GetOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo,
            DelegatedIdentity identity) { }
    @PostExchange("/internal/beauty/booking/recoverOperation")
    OperationVO recoverOperation(@RequestBody RecoverOperation request);
    record RecoverOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo,
            DelegatedIdentity identity) { }
}
