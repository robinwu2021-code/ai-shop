package ai.neargo.shop.inventory.api.internal;
import ai.neargo.shop.common.*;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi.*;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
@RestController @ConditionalOnInventory
public class InternalFoodStockProjectionController {
    private final InventoryFoodStockProjectionPort port;private final String token;private final ObjectMapper json;
    public InternalFoodStockProjectionController(InventoryFoodStockProjectionPort port,@Value("${shop.services.internal-token:}")String token,ObjectMapper json){this.port=port;this.token=token;this.json=json;}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"validate-mappings") public ResponseEntity<?> validateMappings(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,MappingQuery.class,c->port.validateMappings(c.scope(),c.mappings()));}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"activate") public ResponseEntity<?> activate(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,SessionCommand.class,c->port.activate(c.scope(),c.generation(),c.operationKey()));}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"deactivate") public ResponseEntity<?> deactivate(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,SessionCommand.class,c->port.deactivate(c.scope(),c.generation(),c.operationKey()));}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"apply") public ResponseEntity<?> apply(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,Apply.class,c->port.apply(c.scope(),c.generation(),c.eventNo(),c.payloadHash(),c.snapshot()));}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"receipt") public ResponseEntity<?> receipt(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,Query.class,c->new ReceiptLookup(port.queryReceipt(c.scope(),c.eventNo(),c.payloadHash())));}
    @PostMapping(InventoryFoodStockProjectionInternalApi.BASE+"query") public ResponseEntity<?> query(@RequestBody(required=false)byte[] body,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return call(body,given,Items.class,c->port.queryProjection(c.scope(),c.itemNos()));}
    private <T>ResponseEntity<?> call(byte[] body,String given,Class<T> type,java.util.function.Function<T,Object> action){return InternalServiceResponses.invoke(token,given,()->{
        if(body==null||body.length==0||body.length>262144)throw BizException.of(ErrorCode.BAD_REQUEST);
        final T command;try{command=json.readValue(body,type);}catch(RuntimeException invalid){throw BizException.of(ErrorCode.BAD_REQUEST);}if(command==null)throw BizException.of(ErrorCode.BAD_REQUEST);return action.apply(command);});}
}
