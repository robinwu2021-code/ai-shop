package ai.neargo.shop.invoice.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shop.invoice.delivery.enabled", havingValue = "true")
public class InvoiceDeliveryJob implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(InvoiceDeliveryJob.class);
    private final InvoiceDeliveryService deliveries;
    private final boolean localWorker;
    private ScheduledExecutorService executor;

    public InvoiceDeliveryJob(InvoiceDeliveryService deliveries,
            @Value("${shop.invoice.delivery.local-worker:false}") boolean localWorker) {
        this.deliveries = deliveries;
        this.localWorker = localWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!localWorker) return;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon().name("invoice-delivery").factory());
        executor.scheduleWithFixedDelay(() -> {
            try {
                run(null);
            } catch (RuntimeException failure) {
                log.warn("INVOICE_DELIVERY_SWEEP_FAILED type={}", failure.getClass().getSimpleName());
            }
        }, 5, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) executor.shutdown();
    }

    @Override public String name() { return "invoice-delivery"; }

    @Bean
    public JobDeclaration invoiceDeliveryDeclaration() {
        return new JobDeclaration(name(), "發票郵件交付", "發送已排入的發票並保存 SMTP 結果",
                "invoice-domain", "25 * * * * *", true, 240, 300, true, false);
    }

    @Override public JobResult run(JobInvocation invocation) {
        deliveries.dispatchDue();
        return JobResult.ok("invoice delivery checked");
    }
}
