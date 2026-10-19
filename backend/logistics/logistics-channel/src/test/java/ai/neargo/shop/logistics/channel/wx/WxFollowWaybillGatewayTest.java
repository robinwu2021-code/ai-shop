package ai.neargo.shop.logistics.channel.wx;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxFollowWaybillGatewayTest {
    private final WxLogisticsClient client = mock(WxLogisticsClient.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void followsRealPurchaseAndQueriesItsOwnToken() throws Exception {
        when(client.post(eq("follow_waybill"), any(), any(Duration.class)))
                .thenReturn(json.readTree("{\"errcode\":0,\"waybill_token\":\"follow-token\"}"));
        when(client.post(eq("query_follow_trace"), any(), any(Duration.class)))
                .thenReturn(json.readTree("{\"errcode\":0,\"waybill_info\":{\"status\":4}}"));
        WxFollowWaybillGateway gateway = new WxFollowWaybillGateway(client);
        WaybillFollower.FollowCmd cmd = new WaybillFollower.FollowCmd(
                "buyer-openid", "420001", "YT123", "YTO", "13800138000",
                "[{\"name\":\"茶叶\",\"imageUrl\":\"https://example.test/a.png\"}]", "/pages/order/index?orderNo=O1");

        ChannelOutcome follow = gateway.follow(cmd);
        var trace = gateway.queryFollow(follow.ref(), "YTO", "YT123");

        assertThat(follow.kind()).isEqualTo(ChannelOutcome.Kind.OK);
        assertThat(follow.ref()).isEqualTo("follow-token");
        assertThat(trace).isPresent();
        assertThat(trace.orElseThrow().status()).isEqualTo(TraceStatus.SIGNED);
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq("follow_waybill"), body.capture(), any(Duration.class));
        assertThat(body.getValue()).containsEntry("openid", "buyer-openid")
                .containsEntry("trans_id", "420001")
                .containsEntry("waybill_id", "YT123")
                .containsEntry("receiver_phone", "13800138000")
                .containsEntry("delivery_id", "YTO");
    }

    @Test
    void notReadyAndStatusZeroDoNotDeclareInvalidWaybill() throws Exception {
        when(client.post(eq("follow_waybill"), any(), any(Duration.class)))
                .thenReturn(json.readTree("{\"errcode\":9300559,\"errmsg\":\"not yet visible\"}"));
        when(client.post(eq("query_follow_trace"), any(), any(Duration.class)))
                .thenReturn(json.readTree("{\"errcode\":0,\"waybill_info\":{\"status\":0}}"));
        WxFollowWaybillGateway gateway = new WxFollowWaybillGateway(client);

        assertThat(gateway.follow(new WaybillFollower.FollowCmd("o", "tx", "YT", "YTO", "13800138000", "[]", "/order"))
                .kind()).isEqualTo(ChannelOutcome.Kind.NOT_READY);
        assertThat(gateway.queryFollow("tk", "YTO", "YT")).isEmpty();
    }
}
