package ai.neargo.shop.portal.internal;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.food.FoodProductionStockPort;
import ai.neargo.shop.spi.food.FoodProductionStockInternalApi;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
@RestController
public class InternalFoodProductionStockController {
    private final FoodProductionStockPort service;private final String token;private final ObjectMapper json;
    public InternalFoodProductionStockController(FoodProductionStockPort service,@Value("${shop.services.internal-token:}")String token,ObjectMapper json){this.service=service;this.token=token;this.json=json;}
    @PostMapping(FoodProductionStockInternalApi.BASE+"owner") public ResponseEntity<?> owner(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return invoke(body,given,service::owner);}
    @PostMapping(FoodProductionStockInternalApi.BASE+"consume") public ResponseEntity<?> consume(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return invoke(body,given,service::consume);}
    @PostMapping(FoodProductionStockInternalApi.BASE+"query") public ResponseEntity<?> query(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return invoke(body,given,service::query);}
    @PostMapping(FoodProductionStockInternalApi.BASE+"reverse") public ResponseEntity<?> reverse(
            @RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){
        return InternalServiceResponses.invoke(token,given,()->{
            if(body==null||body.length==0||body.length>262144)throw BizException.of(ErrorCode.BAD_REQUEST);
            final InventoryProductionPort.Reverse command;try{command=json.readValue(body,InventoryProductionPort.Reverse.class);}
            catch(RuntimeException bad){throw BizException.of(ErrorCode.BAD_REQUEST);}return service.reverse(command);
        });
    }
    private ResponseEntity<?> invoke(byte[] body,String given,java.util.function.Function<InventoryProductionPort.Command,Object> action){return InternalServiceResponses.invoke(token,given,()->{
        if(body==null||body.length==0||body.length>262144)throw BizException.of(ErrorCode.BAD_REQUEST);
        final InventoryProductionPort.Command command;try{command=json.readValue(body,InventoryProductionPort.Command.class);}catch(RuntimeException bad){throw BizException.of(ErrorCode.BAD_REQUEST);}return action.apply(command);});}
}
