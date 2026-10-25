package ai.neargo.shop.food.service;

/** Local queue owner transaction only; this records an intention and never contacts a supplier. */
public interface QueueNotificationLifecycle {
    void called(
            String entity,
            String store,
            String ticket,
            long ticketVersion,
            String contactRef,
            String actor);
}
