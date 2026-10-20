package ai.neargo.shop.message.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.message.notify.StoredValueNotificationService;
import ai.neargo.shop.message.notify.StoredValueNotificationService.Capability;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The client must not request native authorization for a simulated or unavailable template. */
@Profile("api")
@RestController
public class MpStoredValueNotificationController {
    private final StoredValueNotificationService notifications;
    public MpStoredValueNotificationController(StoredValueNotificationService notifications) {
        this.notifications = notifications;
    }
    @GetMapping("/mp/stored-value/notification-capability")
    public Capability capability() {
        SecurityUtils.currentUserNo();
        return notifications.capability();
    }
}
