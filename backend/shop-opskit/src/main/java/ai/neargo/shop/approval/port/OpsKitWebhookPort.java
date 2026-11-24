package ai.neargo.shop.approval.port;

import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.spi.approval.ApprovalWebhookPort;

/** 回调交给 OpsKit client：验签 → 过滤他系统事件 → 去重 → 分发（执行节点 / 结束）→ 自动回复执行结果。 */
public class OpsKitWebhookPort implements ApprovalWebhookPort {

    private final ApprovalClient client;

    public OpsKitWebhookPort(ApprovalClient client) {
        this.client = client;
    }

    @Override
    public Reply handle(String signatureHeader, String rawBody) {
        var r = client.handleWebhook(signatureHeader, rawBody);
        return new Reply(r.status(), r.message());
    }
}
