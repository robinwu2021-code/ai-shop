package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.Query;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import java.time.LocalDate;

/** Store-scoped reception queue; all seating delegates to the existing check resource owner. */
public interface QueueService {
    QueueVO issue(String entity,String store,String actor,IssueReq request,String key);
    PageData<QueueVO> list(String entity,String store,String actor,Query query);
    QueueVO get(String entity,String store,String actor,String ticketNo);
    QueueVO callNext(String entity,String store,String actor,LocalDate businessDate,String key);
    QueueVO change(String entity,String store,String actor,String ticketNo,String action,ChangeReq request,String key);
    QueueVO seat(String entity,String store,String actor,String ticketNo,SeatQueueReq request);
    QueueVO recoverSeating(String entity,String store,String actor,String ticketNo);
}
