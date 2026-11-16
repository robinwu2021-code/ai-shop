package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.common.BizDetailedException;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PAY's rejected field key and reason reach the App as envelope data on 10400. */
class EntityProfileRemoteCallsTest {
  private static ServiceCallException pay(int status, String field, String reason) {
    return new ServiceCallException(
        CallOutcome.REMOTE_ERROR, "PAY", status, "PAY 返回 " + status, null, null, null, field, reason);
  }

  @Test
  void rejectedFieldBecomesDetailsOnBadRequest() {
    assertThatThrownBy(
            () -> EntityProfileRemoteCalls.call(() -> {
              throw pay(400, "registeredCity", "UNSUPPORTED_FIELD");
            }))
        .isInstanceOfSatisfying(BizDetailedException.class, e -> {
          assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
          assertThat(e.details()).isEqualTo(Map.of("field", "registeredCity", "reason", "UNSUPPORTED_FIELD"));
          var envelope = new GlobalExceptionHandler().onBizDetailed(e);
          assertThat(envelope.code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
          assertThat(envelope.data()).containsEntry("field", "registeredCity");
        });
  }

  @Test
  void withoutAFieldOrOnOtherStatusesTheMappingIsUnchanged() {
    assertThatThrownBy(() -> EntityProfileRemoteCalls.call(() -> {
          throw pay(400, null, null);
        }))
        .isExactlyInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).errorCode())
        .isEqualTo(ErrorCode.BAD_REQUEST);
    assertThatThrownBy(() -> EntityProfileRemoteCalls.call(() -> {
          throw pay(409, "registeredCity", "UNSUPPORTED_FIELD");
        }))
        .isExactlyInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).errorCode())
        .isEqualTo(ErrorCode.CONFLICT);
  }
}
