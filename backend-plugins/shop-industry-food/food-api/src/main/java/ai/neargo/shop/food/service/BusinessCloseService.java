package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ProposeCarryReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ApproveCarryReq;
import java.util.List;
import java.time.LocalDate;

/** Non-cash source reporting and immutable closing evidence. Every method receives trusted platform scope. */
public interface BusinessCloseService {
    DayVO open(String entity, String store, String actor, LocalDate date, String key);
    ReportVO report(String entity, String store, String actor, PreviewReq request);
    CloseVO preview(String entity, String store, String actor, PreviewReq request, String key);
    CloseVO get(String entity, String store, String actor, String closeNo);
    CloseVO sign(String entity, String store, String actor, SignReq request, String key);
    List<ExceptionVO> exceptions(String entity, String store, String actor, String closeNo);
    ExceptionVO proposeCarry(String entity, String store, String actor, String closeNo, String exceptionNo,
            ProposeCarryReq request, String key);
    ExceptionVO approveCarry(String entity, String store, String actor, String closeNo, String exceptionNo,
            ApproveCarryReq request, String key);
    OperationVO adjust(String entity, String store, String actor, AdjustmentReq request, String key);
}
