package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Only observed submitted entities are projected; no global merchant scan or duplicate human
 * approval.
 */
@Component
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
public class EntityRegistrationProjectionCoordinator {
  private static final String LOCK = "entity-registration-projection";

  private final EntityRegistrationProjection projection;
  private final PayEntityProfileApi pay;
  private final StoreSyncProperties properties;

  public EntityRegistrationProjectionCoordinator(
      EntityRegistrationProjection projection,
      PayEntityProfileApi pay,
      StoreSyncProperties properties) {
    this.projection = projection;
    this.pay = pay;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${shop.pay.entity-profiles.projection-delay:30000}")
  // One scan is at most 100 targets (pending() LIMIT 100), each one bounded PAY call.
  @SchedulerLock(name = LOCK, lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
  public void run() {
    for (var target : projection.pending()) {
      try {
        project(target);
      } catch (ServiceCallException failure) {
        LoggerFactory.getLogger(getClass())
            .warn("Entity registration projection deferred outcome={}", failure.outcome());
      } catch (BizException failure) {
        LoggerFactory.getLogger(getClass())
            .warn("Entity registration projection rejected code={}", failure.errorCode());
      } finally {
        projection.checked(target);
      }
    }
  }

  public void project(EntityRegistrationProjection.Target target) {
    long intended = Long.MAX_VALUE;
    try {
      var original =
          ServiceCalls.call(
              ServiceName.PAY,
              () ->
                  pay.result(
                      target.entityNo(),
                      target.requestNo(),
                      properties.sourceScope(),
                      target.actorRef()));
      if (original != null
          && original.get("submittedProfileVersion") instanceof Number version
          && original.get("submittedRevisionNo") instanceof String) intended = version.longValue();
    } catch (ServiceCallException missing) {
      if (missing.statusCode() != 404) throw missing;
      // A rejected/uncertain newer attempt cannot hide an already approved effective registration.
    }
    Map<String, Object> facts =
        ServiceCalls.call(
            ServiceName.PAY,
            () -> pay.approvedRegistration(target.entityNo(), properties.sourceScope()));
    if (facts == null || !Boolean.TRUE.equals(facts.get("available"))) return;
    if (!target.entityNo().equals(facts.get("entityNo"))) throw BizException.of(ErrorCode.CONFLICT);
    projection.apply(
        target,
        new EntityRegistrationProjection.Approved(
            target.entityNo(),
            (String) facts.get("market"),
            ((Number) facts.get("revision")).longValue(),
            (String) facts.get("revisionRef"),
            (String) facts.get("name"),
            (String) facts.get("legalForm"),
            (String) facts.get("registrationNo"),
            (String) facts.get("evidenceRef")),
        intended);
  }
}
