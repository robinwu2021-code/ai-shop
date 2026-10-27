package ai.neargo.shop.pay.channel.http;

import java.io.IOException;
import java.time.Duration;

/**
 * 裸 HTTP 发送。<b>只有 {@link RecordingChannelClient} 可以调它</b>（{@code RecordingClientOnlyTest}）：
 * 绕过记录型客户端发出去的请求，在调用记录里一个字都没有，而那正是对账对不上时第一个要查的。
 *
 * <p>连接没建成（拒绝、DNS、连接超时）抛 {@link java.net.ConnectException} 或
 * {@link java.net.http.HttpConnectTimeoutException}：调用方据此判断「请求根本没离开本机」。其余 IO 异常一律视为「可能已发出」。
 */
public interface ChannelTransport {

    ChannelHttpResponse exchange(ChannelHttpRequest request, Duration readTimeout)
            throws IOException, InterruptedException;
}
