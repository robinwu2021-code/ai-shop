package ai.neargo.shop.printing.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.print.api.PrintActor;
import ai.neargo.shop.print.api.PrintFailure;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.aopalliance.intercept.MethodInterceptor;

/** Merchant SELF scope is replaced only after selected-store authorization. Ops retain data scope. */
@Component
public class PrintMerchantScopeBoundary implements BeanPostProcessor {
    @Override public Object postProcessAfterInitialization(Object bean,String name) {
        String type=org.springframework.aop.support.AopUtils.getTargetClass(bean).getName();
        if(!type.startsWith("ai.neargo.shop.printing.center.service.")
                && !type.startsWith("ai.neargo.shop.printing.center.operation.")) return bean;
        var proxy=new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) call -> {
            var user=SecurityUtils.currentUser().orElse(null);
            if(user==null || user.realm()!=Realm.MERCHANT) return call.proceed();
            PrintActor actor=null;
            for(Object arg:call.getArguments()) if(arg instanceof PrintActor candidate) actor=candidate;
            if(actor==null) return call.proceed();
            var context=BizContext.current();
            if(!user.tenantNo().equals(actor.tenantNo())
                    || !BizContext.requireMerchantNo().equals(actor.entityNo())
                    || !BizContext.requireStoreNo().equals(actor.storeNo())
                    || context.storeNos()==null || !context.storeNos().contains(actor.storeNo()))
                throw new PrintFailure("print.forbidden");
            return DataScopeContext.executeWithoutScope(() -> {
                try { return call.proceed(); }
                catch(RuntimeException|Error ex) {throw ex;}
                catch(Throwable ex) {throw new IllegalStateException(ex);}
            });
        });
        return proxy.getProxy();
    }
}
