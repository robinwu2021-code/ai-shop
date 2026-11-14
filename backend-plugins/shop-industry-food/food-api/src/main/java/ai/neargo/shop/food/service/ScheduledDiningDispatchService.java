package ai.neargo.shop.food.service;

/** Persistent original-batch scheduled dispatch and print recovery, not a new ordering command. */
public interface ScheduledDiningDispatchService {
    record Report(int claimed, int completed, int pending) {}

    Report runDue(int limit);
}
