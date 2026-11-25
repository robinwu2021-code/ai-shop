package ai.neargo.shop.merchant.paysync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 门店同步投递失败时可重投（UME166 2026-11-19：支付侧并发死锁返回 500，原先被记成结果未知、阻塞该门店后续同步）。
 * 同步按事件号与版本幂等，所以 5xx、429、不可达都抛 {@link RetryableOutboxException} 交给发件箱退避重试；4xx 仍只记录不重投。
 */
@ExtendWith(OutputCaptureExtension.class)
class NearPayStoreSyncConsumerRetryTest {
    final StoreSnapshotReader reader = mock(StoreSnapshotReader.class);
    final HttpClient http = mock(HttpClient.class);
    final StoreSyncProperties props = new StoreSyncProperties(true, "http://127.0.0.1:18093", "AI_SHOP_UME", "cred", "MAIN", "TW",
            null, null, null);
    final NearPayStoreSyncConsumer consumer = new NearPayStoreSyncConsumer(reader, props, "token", http);

    NearPayStoreSyncConsumerRetryTest() {
        when(reader.read("STUME1")).thenReturn(Optional.of(new StoreSnapshotReader.Snapshot(1280, "STUME1", "MUME1", "甲", null, "TW",
                "查爾斯手機維修士林店", null, null, null, null, null, "ACTIVE", false, 1_793_952_472_000L)));
    }

    private SysOutbox event() {
        var e = new SysOutbox();
        e.setAggregateId("STUME1");
        return e;
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) resp);
    }

    @Test
    void serverErrorsAndThrottlingAreRetryable(CapturedOutput logs) throws Exception {
        respond(500, "{\"status\":\"response-marker-500\"}");
        assertThatThrownBy(() -> consumer.consume(event())).isInstanceOf(RetryableOutboxException.class).hasMessageContaining("500");
        respond(429, "{\"status\":\"response-marker-429\"}");
        assertThatThrownBy(() -> consumer.consume(event())).isInstanceOf(RetryableOutboxException.class);
        assertThat(logs.getAll()).contains("httpStatus=500 resultCode=SERVER_ERROR", "httpStatus=429 resultCode=RATE_LIMITED")
                .doesNotContain("response-marker-");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreachablePaymentServiceIsRetryable() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new java.net.ConnectException("refused"));
        assertThatThrownBy(() -> consumer.consume(event())).isInstanceOf(RetryableOutboxException.class);
    }

    @Test
    void successAndClientRejectionsAreNotRetried(CapturedOutput logs) throws Exception {
        for (String result : new String[]{"APPLIED", "DUPLICATE", "STALE", "response-marker-unknown"}) {
            respond(200, "{\"status\":\"" + result + "\",\"issue\":\"response-marker-200\"}");
            assertThatCode(() -> consumer.consume(event())).doesNotThrowAnyException();
        }
        respond(200, "response-marker-invalid-json");
        assertThatCode(() -> consumer.consume(event())).doesNotThrowAnyException();
        respond(409, "{\"status\":\"APPLIED\",\"issue\":\"response-marker-409\"}");
        assertThatCode(() -> consumer.consume(event())).as("4xx 只记录，不重投").doesNotThrowAnyException();
        assertThat(logs.getAll()).contains("storeNo=STUME1 revision=1793952472001",
                "httpStatus=200 resultCode=APPLIED", "httpStatus=200 resultCode=DUPLICATE",
                "httpStatus=200 resultCode=STALE", "httpStatus=200 resultCode=UNKNOWN_STATUS",
                "httpStatus=409 resultCode=HTTP_REJECTED").doesNotContain("response-marker-");
    }
}
