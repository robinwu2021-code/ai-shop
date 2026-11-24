package ai.neargo.shop.spi.approval;

/**
 * OpsKit 回调入口（{@code POST /internal/opskit/webhook}）。网关只负责把签名头与<b>原始请求体</b>交进来，
 * 按返回的状态码应答；验签、去重、过滤他系统事件、分发都在实现里。
 */
public interface ApprovalWebhookPort {

    Reply handle(String signatureHeader, String rawBody);

    record Reply(int status, String message) {
    }
}
