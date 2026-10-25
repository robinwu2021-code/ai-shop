package ai.neargo.shop.spi.printing;

import ai.neargo.shop.print.api.*;

import java.util.*;

public interface PrintNotificationRecipientsPort {
    List<String> recipients(PrintScope scope, Set<String> roleKeys);
}
