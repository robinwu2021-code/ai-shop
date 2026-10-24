package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import java.time.Instant;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.ListCheckActionsQuery;
import ai.neargo.shop.food.dto.ListChecksQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.TransferCheckReq;

/** Reception and table topology; never changes the ownership of retail transactions or payments. */
public interface CheckService {
    CheckResultVO openCheck(String entityNo,String storeNo,String actorNo,OpenCheckReq req,String idempotencyKey);
    /** Internal same-database queue command; implementations must join the queue owner's transaction. */
    default CheckResultVO openQueuedCheck(String entityNo,String storeNo,String actorNo,OpenCheckReq req,String idempotencyKey) {
        throw new UnsupportedOperationException("Atomic queue seating is unavailable");
    }
    /** Internal reservation arrival joins the reservation transaction and respects its original bounded window. */
    default CheckResultVO openReservedCheck(String entity, String store, String actor, OpenCheckReq request,
            String key, Instant reservedUntil) {
        throw new UnsupportedOperationException("Atomic reservation arrival unavailable");
    }
    PageData<CheckVO> listChecks(String entityNo,String storeNo,ListChecksQuery query);
    CheckDetailVO getCheck(String entityNo,String storeNo,String checkNo);
    CheckResultVO saveSeats(String entityNo,String storeNo,String checkNo,String actorNo,SaveCheckSeatsReq req,String key);
    OperationVO clearCheck(String entityNo,String storeNo,String checkNo,String actorNo,ClearCheckReq req,String key);
    OperationResultVO transferCheck(String entityNo,String storeNo,String checkNo,String actorNo,TransferCheckReq req,String key);
    OperationResultVO combineTables(String entityNo,String storeNo,String checkNo,String actorNo,CombineTablesReq req,String key);
    CheckResultVO openSharedCheck(String entityNo,String storeNo,String tableNo,String actorNo,OpenSharedCheckReq req,String key);
    OperationResultVO getTableOperation(String entityNo,String storeNo,String operationNo);
    PageData<ActionLogVO> listActions(String entityNo,String storeNo,String checkNo,ListCheckActionsQuery query);
}
