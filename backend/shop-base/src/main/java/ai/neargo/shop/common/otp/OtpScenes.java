package ai.neargo.shop.common.otp;

import java.util.Set;

public final class OtpScenes {
    public static final String LOGIN = "LOGIN";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PHONE_CHANGE = "PHONE_CHANGE";
    public static final String EMAIL_CHANGE = "EMAIL_CHANGE";
    public static final String ORDER_PICKUP_CONFIRM = "ORDER_PICKUP_CONFIRM";
    public static final Set<String> ALL = Set.of(LOGIN, PASSWORD_RESET, PHONE_CHANGE, EMAIL_CHANGE, ORDER_PICKUP_CONFIRM);
    private OtpScenes() {}
}
