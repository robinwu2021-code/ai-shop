package ai.neargo.commerce.api.port;

import java.util.List;
import ai.neargo.commerce.api.common.*;

/** UNKNOWN must query the original receipt; SENT means accepted/persisted, not read. */
public interface CommerceNotificationPort {
 List<NotificationRecipient> audience(NotificationAudienceQuery query);
  DeliveryOutcome send(NotificationCommand command);
  DeliveryOutcome queryReceipt(NotificationReceiptQuery query);

}
