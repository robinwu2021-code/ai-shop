package ai.neargo.shop.portal.internal;

import ai.neargo.shop.job.JobHandlerRegistry;
import ai.neargo.shop.job.JobTargetEndpoint;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the shared job target protocol without duplicating token, JSON or locking behavior. */
@RestController
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class JobHandlerEndpoint extends JobTargetEndpoint {
    public JobHandlerEndpoint(JobHandlerRegistry handlers, LockingTaskExecutor locks,
            @Value("${shop.job.internal-token:}") String token) {
        super(handlers, locks, token);
    }
}
