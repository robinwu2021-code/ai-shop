package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.EntityCreationService;
import ai.neargo.shop.payclient.EntityProfileRemoteCalls;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Entity creation commits first; retries recover it and reuse one private-profile initialization
 * command.
 */
@Component
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
public class EntityCreationProfileInitializer {
  private final PayEntityProfileApi pay;
  private final StoreSyncProperties properties;
  private final ObjectMapper json;

  public EntityCreationProfileInitializer(
      PayEntityProfileApi pay, StoreSyncProperties properties, ObjectMapper json) {
    this.pay = pay;
    this.properties = properties;
    this.json = json;
  }

  public String hash(Map<String, String> fields) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      json.writeValueAsString(new TreeMap<>(fields))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void initialize(
      String actor, EntityCreationService.Result result, Map<String, String> initial) {
    var fields = new LinkedHashMap<>(initial);
    fields.put("name", result.entity().name());
    fields.put("legalForm", result.entity().legalForm());
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceScope", properties.sourceScope());
    body.put("actorRef", actor);
    body.put("requestNo", result.profileRequestNo());
    body.put("expectedVersion", 0);
    body.put("schemaVersion", "TW_ENTITY_1");
    body.put("fields", fields);
    body.put("documents", Map.of());
    body.put("confirmed", false);
    var saved = EntityProfileRemoteCalls.call(() -> pay.save(result.entity().entityNo(), body));
    if (!complete(result, saved)) throw BizException.of(ErrorCode.CONFLICT);
  }

  public EntityCreationService.Result status(String actor, EntityCreationService.Result result) {
    if ("NOT_REQUESTED".equals(result.profileInitialization())) return result;
    String state;
    try {
      var receipt =
          ServiceCalls.call(
              ServiceName.PAY,
              () ->
                  pay.result(
                      result.entity().entityNo(),
                      result.profileRequestNo(),
                      properties.sourceScope(),
                      actor));
      state = complete(result, receipt) ? "COMPLETE" : "UNKNOWN";
    } catch (ServiceCallException failure) {
      state = failure.statusCode() == 404 ? "PENDING" : "UNKNOWN";
    }
    return new EntityCreationService.Result(
        result.requestNo(), result.entity(), result.replayed(), state, result.profileRequestNo());
  }

  private boolean complete(EntityCreationService.Result result, Map<String, Object> receipt) {
    return receipt != null
        && result.entity().entityNo().equals(receipt.get("entityNo"))
        && receipt.get("version") instanceof Number n
        && n.longValue() > 0;
  }
}
