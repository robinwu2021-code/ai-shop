package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.ScheduledDiningDispatchService;

import org.springframework.scheduling.annotation.Scheduled;

public final class ScheduledDiningDispatchJob {
    private final ScheduledDiningDispatchService dispatch;
    private final QueueNotificationServiceImpl notices;

    public ScheduledDiningDispatchJob(
            ScheduledDiningDispatchService dispatch, QueueNotificationServiceImpl notices) {
        this.dispatch = dispatch;
        this.notices = notices;
    }

    @Scheduled(fixedDelayString = "${shop.food.scheduled-dispatch.delay-ms:15000}")
    public void dispatch() {
        dispatch.runDue(100);
    }

    @Scheduled(fixedDelayString = "${shop.food.queue-notice.delay-ms:15000}")
    public void notifications() {
        notices.runDue(100);
    }
}
