package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.AppointmentSummaryVO;
import ai.neargo.shop.beauty.dto.AppointmentVO;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.MyAppointmentQuery;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.NoShowVO;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.common.PageData;

/** Current authenticated scope is verified for every appointment operation. */
public interface AppointmentService {
    /** Local opening is delivered independently of the Task59 remote reception protocol. */
    default WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo,
            String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey) {
        throw new UnsupportedOperationException("reception wire is owned by Task59");
    }

    AppointmentPreviewVO previewAppointment(String entityNo, String currentStoreNo, String actorNo,
            PreviewAppointmentReq req);
    AppointmentVO createAppointment(String entityNo, String currentStoreNo, String actorNo, CreateAppointmentReq
            req, String idempotencyKey);
    PageData<AppointmentSummaryVO> listAppointments(String entityNo, String currentStoreNo, String actorNo,
            AppointmentQuery query);
    AppointmentDetailVO getAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo);
    AppointmentChangePreviewVO previewAppointmentChange(String entityNo, String currentStoreNo, String actorNo,
            String appointmentNo, PreviewAppointmentChangeReq req);
    AppointmentVO changeAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            ChangeAppointmentReq req, String idempotencyKey);
    CancellationVO cancelAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            CancelAppointmentReq req, String idempotencyKey);
    NoShowVO recordNoShow(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            RecordNoShowReq req, String idempotencyKey);
    AppointmentPreviewVO previewMyAppointment(String userNo, PreviewMyAppointmentReq req);
    MyAppointmentVO createMyAppointment(String userNo, CreateMyAppointmentReq req, String idempotencyKey);
    PageData<MyAppointmentVO> listMyAppointments(String userNo, MyAppointmentQuery query);
    MyAppointmentDetailVO getMyAppointment(String userNo, String appointmentNo);
    AppointmentChangePreviewVO previewMyAppointmentChange(String userNo, String appointmentNo,
            PreviewMyAppointmentChangeReq req);
    MyAppointmentVO changeMyAppointment(String userNo, String appointmentNo, ChangeAppointmentReq req, String
            idempotencyKey);
    CancellationVO cancelMyAppointment(String userNo, String appointmentNo, CancelAppointmentReq req, String
            idempotencyKey);
}
