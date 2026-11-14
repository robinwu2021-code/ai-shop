package ai.neargo.shop.spi.platform;

/** Regional policy owned by the platform; contact collection never proves a phone identity. */
public interface SmsAdmissionPort {
    /** Requires both regional verification admission and an available SMS channel before authentication. */
    void requireSmsEnabled(String phone);
}
