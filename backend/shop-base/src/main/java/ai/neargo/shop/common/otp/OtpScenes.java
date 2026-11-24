package ai.neargo.shop.common.otp;

import java.util.Set;

public final class OtpScenes {
    public static final String LOGIN = "LOGIN";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PHONE_CHANGE = "PHONE_CHANGE";
    public static final String EMAIL_CHANGE = "EMAIL_CHANGE";
    public static final String ORDER_PICKUP_CONFIRM = "ORDER_PICKUP_CONFIRM";
    public static final String INVOICE_ARTIFACT_ACCESS = "INVOICE_ARTIFACT_ACCESS";
    public static final String POS_PIN_RECOVERY = "POS_PIN_RECOVERY";
    public static final Set<String> ALL = Set.of(LOGIN, PASSWORD_RESET, PHONE_CHANGE, EMAIL_CHANGE,
            ORDER_PICKUP_CONFIRM, INVOICE_ARTIFACT_ACCESS, POS_PIN_RECOVERY);
    private OtpScenes() {}
}
