package ai.neargo.shop.pay.points.operation;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.spi.points.PointsOperationsPort.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.function.Supplier;
/** Request receipts and business-operation receipts share the existing inbox metadata transaction. */
@Service public class PointsOperationJournal {
    private final PointsConfigurationStore config;
    private final PointsFactStore facts;
    private final ObjectMapper json=new ObjectMapper();
    public PointsOperationJournal(PointsConfigurationStore config, PointsFactStore facts) {
        this.config=config;
        this.facts=facts;
    }
    public record Stored(JsonNode intent, JsonNode result, Receipt receipt) {
    }
    public record Intent(Action action, Operation operation, String currency, Object payload) {
    }
    @Transactional("payTxManager") public <T> T apply(Context c, Action action, Object payload, Class<T> resultClass, Supplier<T> work, java.util.function.Function<T, Receipt> receipt) {
        String opKey=PointsOperationKeys.key(c==null?null:c.operation());
        if(c.requestId()==null||c.requestId().isBlank()||c.requestId().length()>100||c.currency()==null)throw new IllegalArgumentException("POINTS_OPERATION_INPUT_INVALID");
        config.lockTypeAndPool(c.operation().typeCode());
        String request="P2:R:"+PointsDigests.sha256(c.requestId());
        String operation=operationKey(c.operation(), action);
        JsonNode intent=json.readTree(json.writeValueAsString(new Intent(action, c.operation(), c.currency(), payload)));
        var prior=facts.lockInbox(request);
        if(prior!=null)return replay(prior, intent, resultClass);
        var existing=facts.lockInbox(operation);
        if(existing!=null) {
            var stored=stored(existing);
            T result=check(stored, intent, resultClass);
            save(c.operation().typeCode(), request, stored);
            return result;
        }
        T result=work.get();
        var saved=new Stored(intent, json.valueToTree(result), receipt.apply(result));
        save(c.operation().typeCode(), operation, saved);
        save(c.operation().typeCode(), request, saved);
        return result;
    }
    public Stored find(Operation op, Action action) {
        var row=facts.inbox(operationKey(op, action));
        return row==null?null:stored(row);
    }
    private <T>T replay(java.util.Map<String, Object> row, JsonNode intent, Class<T> cls) {
        return check(stored(row), intent, cls);
    }
    private <T>T check(Stored prior, JsonNode intent, Class<T> cls) {
        if(!prior.intent().equals(intent))throw new IllegalArgumentException("POINTS_OPERATION_IDEMPOTENCY_CONFLICT");
        return json.treeToValue(prior.result(), cls);
    }
    private Stored stored(java.util.Map<String, Object> row) {
        return json.readValue((String)row.get("payload_json"), Stored.class);
    }
    private void save(String type, String key, Stored value) {
        String body=json.writeValueAsString(value);
        facts.recordEvent(type, key, PointsDigests.sha256(body), body, "OPERATION_V2");
    }
    private String operationKey(Operation op, Action action) {
        return "P2:O:"+action+":"+PointsOperationKeys.key(op);
    }
}
