package ai.neargo.shop.print.api;

/** Read access is granted by a short-lived opaque signature issued after current authorization. */
public interface PrintPrivateDownloadPort {
    byte[] read(String objectKey, long expiresAt, String signature);
}
