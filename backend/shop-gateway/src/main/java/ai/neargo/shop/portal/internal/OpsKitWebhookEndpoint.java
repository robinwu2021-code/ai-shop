package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.approval.ApprovalWebhookPort;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpsKit 回调（TDD-OpsKit审批对接 §1.2，契约 §4）：{@code POST /internal/opskit/webhook}。
 *
 * <p>只做搬运：把签名头与<b>原始请求体字节</b>交给 {@link ApprovalWebhookPort}（验签要用原文，不能先反序列化），按返回的状态码应答 ——
 * 401 签名不对、500 处理失败（OpsKit 会退避重投）、200 其余。没启用 OpsKit 时没有实现，回 404。
 *
 * <p>放在 {@code portal.internal}：{@code /internal} 端点只许在这个包（CommonPackageIsolationTest）。
 * {@code /internal/**} 不进安全链、nginx 对外 deny；可信度来自 HMAC 签名，而不是来源地址。
 */
@RestController
@RequestMapping("/internal/opskit")
public class OpsKitWebhookEndpoint {

    private final ObjectProvider<ApprovalWebhookPort> webhook;

    public OpsKitWebhookEndpoint(ObjectProvider<ApprovalWebhookPort> webhook) {
        this.webhook = webhook;
    }

    @PostMapping(value = "/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> webhook(@RequestHeader(value = "X-OpsKit-Signature", required = false) String signature,
                                          @RequestBody(required = false) byte[] body) {
        ApprovalWebhookPort port = webhook.getIfAvailable();
        if (port == null) {
            return ResponseEntity.status(404).body("opskit not enabled");
        }
        var reply = port.handle(signature, body == null ? null : new String(body, StandardCharsets.UTF_8));
        return ResponseEntity.status(reply.status()).body(reply.message());
    }
}
