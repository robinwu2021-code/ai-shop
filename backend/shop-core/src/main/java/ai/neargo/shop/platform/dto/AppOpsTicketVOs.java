package ai.neargo.shop.platform.dto;

/** {@code POST /common/appops/enrollment-ticket}. */
public final class AppOpsTicketVOs {
    private AppOpsTicketVOs() {
    }

    /** {@code app}: nearboss / hxmall; {@code publicKey}: the installation's Ed25519 SPKI, unpadded Base64URL. */
    public record TicketReq(String app, String publicKey) {
    }

    /** One-time AppOps enrollment ticket; spend it on APP-API-14 within {@code expiresAt}. */
    public record TicketVO(String ticket, String expiresAt) {
    }
}
