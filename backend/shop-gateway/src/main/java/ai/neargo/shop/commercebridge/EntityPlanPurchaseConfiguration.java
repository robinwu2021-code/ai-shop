package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "ops"})
@ConditionalOnProperty(
    name = {
      "commerce.enabled",
      "commerce.mall.http.enabled",
      "commerce.mall.enabled",
      "commerce.mall.payment.enabled",
      "commerce.mall.offline.enabled"
    },
    havingValue = "true")
public class EntityPlanPurchaseConfiguration {
  @Bean
  EntityPlanRefundService entityPlanRefundService(EntityPlanPurchaseService plans,OfflineCashRefundPort refunds,OfflineCollectionPort cash){return new ai.neargo.shop.merchant.plan.impl.EntityPlanRefundServiceImpl(plans,refunds,cash);}
  @Bean
  EntityPlanPurchaseService entityPlanPurchaseService(
      JdbcTemplate jdbc,
      @Qualifier("transactionManager") PlatformTransactionManager tx,
      ObjectMapper json,
      PlatformPaymentPort payments,
      OfflineCollectionPort money,
      PrivateEvidencePort media,
      MoneyPolicyPort policies,
      StoreOwnershipWritePort ownership,
      MchEntityMapper entities,
      StoreOperationalReviewService reviews,
      @Value("${commerce.mall.offline.profile-no:}") String profile) {
    return new EntityPlanPurchaseServiceImpl(
        jdbc,
        tx,
        json,
        payments,
        money,
        media,
        policies,
        ownership,
        entity -> entities.financialVisibleEntity(entity)!=null,
        Clock.systemUTC(),
        profile, reviews);
  }
}
