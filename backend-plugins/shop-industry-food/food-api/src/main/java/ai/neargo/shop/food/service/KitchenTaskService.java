package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.PackingReq;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import java.util.List;

/** Shared embedded/remote kitchen boundary; caller must supply authenticated entity and store scope. */
public interface KitchenTaskService {
    FrozenRoutes freezeRoutes(String entity, String store, List<String> skus);
    TicketVO accepted(String entity, String store, String batch, String dispatchKey);
    List<TaskVO> queue(String entity, String store, String station, String batch);
    EventVO act(String entity, String store, String actor, String task, TaskActionReq req, String key);
    TicketVO remake(String entity, String store, String actor, String task, RemakeReq req, String key);
    HandoverVO handover(String entity,String store,String actor,String batch,HandoverReq req,String key);
    CustodyReceiptVO confirmPacking(String entity,String store,String actor,String batch,PackingReq req,String key);
    List<CustodyReceiptVO> custodyReceipts(String entity,String store,String batch);
    List<EventVO> events(String entity, String store, String task);
    CourseVO createCourse(String entity, String store, String actor, String check, CourseReq req, String key);
    List<CourseVO> courses(String entity, String store, String check);
    CourseVO actCourse(String entity, String store, String actor, String course, CourseActionReq req, String key);
}
