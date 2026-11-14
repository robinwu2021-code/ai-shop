package ai.neargo.shop.pay.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.mapper.ChannelMappers.ChannelMessageMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S8：{@code stl_channel_message} 停写开关 {@code shop.pay.log.recording}（TDD-支付服务-I2渠道统一封装）。
 * 四向记录接管之后，旧报文表每笔调用、每条回调还要再写一遍 —— 同一件事两张表，迟早对不上。
 */
class ChannelMessageRecordingSwitchTest {

    private final ChannelMessageMapper mapper = mock(ChannelMessageMapper.class);

    @Test
    @DisplayName("★★ 默认 both：照写（切换前行为不变）")
    void bothKeepsWriting() {
        var recorder = new ChannelMessageRecorder(mapper, 90);
        recorder.setRecording("both");
        assertThat(recorder.writing()).isTrue();
        recorder.received("WECHAT", "/pay/callback/WECHAT", Map.of(), "{}");
        recorder.sent("WECHAT", "/v3/pay", "OD1", true, null, Map.of());
        verify(mapper, times(2)).insert(any(StlChannelMessage.class));
    }

    @Test
    @DisplayName("★★★ new：回调与外发都不再写旧表；回调拿到的报文号为空，回填自然跳过")
    void newStopsLegacyWrites() {
        var recorder = new ChannelMessageRecorder(mapper, 90);
        recorder.setRecording(" NEW ");
        assertThat(recorder.received("WECHAT", "/pay/callback/WECHAT", Map.of(), "{}")).isNull();
        recorder.sent("WECHAT", "/v3/pay", "OD1", true, null, Map.of());
        recorder.settle("CM-1", "ACCEPTED", null, "OD1", null, Map.of());
        verifyNoInteractions(mapper);
        verify(mapper, never()).insert(any(StlChannelMessage.class));
    }
}
