package ai.neargo.shop.promotion.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionCampaignMapper;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/** Runs outside AuthService's existing transaction; pins the campaign used for this registration. */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class InvitationDocumentNumberReservationAdvisor extends DefaultPointcutAdvisor {
    private record Snapshot(MktFissionCampaign campaign) { }
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    public static boolean hasSnapshot() { return CURRENT.get() != null; }
    public static Optional<MktFissionCampaign> currentCampaign() {
        return Optional.ofNullable(CURRENT.get()).map(Snapshot::campaign);
    }
    public InvitationDocumentNumberReservationAdvisor(CoreMarketingDocumentNumbers numbers,
            FissionCampaignMapper campaigns) {
        setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        setPointcut(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return AuthServiceImpl.class.isAssignableFrom(targetClass)
                        && (method.getName().equals("login") || method.getName().equals("loginByWxPhone"));
            }
        });
        setAdvice((MethodInterceptor) invocation -> {
            String inviter = null;
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof AuthService.LoginCommand command) inviter = command.inviterNo();
                if (argument instanceof AuthService.WxPhoneLoginCommand command) inviter = command.inviterNo();
            }
            if (inviter == null || inviter.isBlank() || hasSnapshot()) return invocation.proceed();
            MktFissionCampaign campaign = DataScopeContext.executeWithoutScope(() -> campaigns.selectOne(
                    Wrappers.<MktFissionCampaign>lambdaQuery().eq(MktFissionCampaign::getEnabled, true)
                            .orderByDesc(MktFissionCampaign::getId).last("limit 1")));
            int count = campaign == null || !MktFissionCampaign.COUPON.equals(campaign.getRewardType())
                    || campaign.getCouponNo() == null || campaign.getCouponNo().isBlank() ? 0
                    : Math.addExact(nonnegative(campaign.getInviterCount()), nonnegative(campaign.getInviteeCount()));
            CURRENT.set(new Snapshot(campaign));
            try {
                return numbers.withReservation(Map.of(DocumentType.MARKETING_USER_COUPON, count), () -> {
                    try { return invocation.proceed(); }
                    catch (RuntimeException | Error exception) { throw exception; }
                    catch (Throwable exception) { throw new ProceedFailure(exception); }
                });
            } catch (ProceedFailure exception) { throw exception.getCause(); }
            finally { CURRENT.remove(); }
        });
    }
    private static int nonnegative(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private static final class ProceedFailure extends RuntimeException {
        ProceedFailure(Throwable cause) { super(cause); }
    }
}
