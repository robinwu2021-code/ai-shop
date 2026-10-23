package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.spi.KitchenInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;
import java.util.function.Supplier;

/** Protocol adapter preserves original command keys; timeouts never create a replacement operation. */
public class RemoteKitchenTaskService implements KitchenTaskService {
    private final KitchenInternalApi api;
    public RemoteKitchenTaskService(KitchenInternalApi api) { this.api=api; }
    @Override public FrozenRoutes freezeRoutes(String entity,String store,List<String> skus) {
        return invoke(() -> api.freezeRoutes(new KitchenInternalApi.FreezeQuery(entity,store,skus)));
    }
    @Override public TicketVO accepted(String entity,String store,String batch,String key) {
        return invoke(() -> api.accepted(new KitchenInternalApi.DispatchCommand(entity,store,batch,key)));
    }
    @Override public List<TaskVO> queue(String entity,String store,String station,String batch) {
        return invoke(() -> api.queue(new KitchenInternalApi.QueueQuery(entity,store,station,batch)));
    }
    @Override public EventVO act(String entity,String store,String actor,String task,TaskActionReq req,String key) {
        return invoke(() -> api.act(new KitchenInternalApi.TaskCommand(entity,store,actor,task,key,req)));
    }
    @Override public TicketVO remake(String entity,String store,String actor,String task,RemakeReq req,String key) {
        return invoke(() -> api.remake(new KitchenInternalApi.RemakeCommand(entity,store,actor,task,key,req)));
    }
    @Override public List<EventVO> events(String entity,String store,String task) {
        return invoke(() -> api.events(new KitchenInternalApi.TaskQuery(entity,store,task)));
    }
    @Override public CourseVO createCourse(String entity,String store,String actor,String check,CourseReq req,String key) {
        return invoke(() -> api.createCourse(new KitchenInternalApi.CreateCourseCommand(entity,store,actor,check,key,req)));
    }
    @Override public List<CourseVO> courses(String entity,String store,String check) {
        return invoke(() -> api.courses(new KitchenInternalApi.CourseQuery(entity,store,check)));
    }
    @Override public CourseVO actCourse(String entity,String store,String actor,String course,CourseActionReq req,String key) {
        return invoke(() -> api.actCourse(new KitchenInternalApi.CourseCommand(entity,store,actor,course,key,req)));
    }
    @Override public HandoverVO handover(String entity,String store,String actor,String batch,HandoverReq req,
            String key) {
        return invoke(() -> api.handover(new KitchenInternalApi.HandoverCommand(entity,store,actor,batch,key,req)));
    }
    private static <T> T invoke(Supplier<T> call) {
        try {
            T result=ServiceCalls.call(ServiceName.FOOD,call); if (result==null) throw new IllegalStateException("Missing FOOD kitchen response");
            return result;
        } catch (ServiceCallException failure) {
            throw switch(failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}
