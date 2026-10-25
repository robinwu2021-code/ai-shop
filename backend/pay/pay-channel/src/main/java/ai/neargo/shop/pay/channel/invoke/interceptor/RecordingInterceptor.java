package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.InvocationListener;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;

/**
 * 序 40：<b>发出前</b>写 {@code pay_channel_call_log}，返回后回写结果（渠道接入统一封装设计 §5.2）。
 * 在校验与可用性之后 —— 没过校验的命令从没发往渠道，不算一次「调用」；
 * 在重试之外 —— 但借监听者在每次再发时关上一行、开新一行，所以<b>重试每次一行</b>，{@code retry_of_id} 串起来。
 */
public final class RecordingInterceptor implements ChannelInterceptor {

    public static final int ORDER = 40;
    private final ChannelCallRecorder recorder;

    public RecordingInterceptor(ChannelCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        recorder.start(inv, null);
        inv.addListener(new InvocationListener() {
            @Override
            public void attemptFinished(Invocation i, ChannelResult result) {
                recorder.finish(i, result);
            }

            @Override
            public void attemptStarted(Invocation i) {
                recorder.start(i, i.callLogId());
            }
        });
        // 最后一次发送的结果由执行器在出管线时 inv.end(result) 通知到上面的监听者，这里不再写一遍
        return next.proceed(inv);
    }

    @Override
    public int order() {
        return ORDER;
    }
}
