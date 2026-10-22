package ai.neargo.shop.food.api;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Adapt only food B-side SQL to the entity already resolved by the original merchant identity chain. */
public final class FoodBizDataScopeInterceptor implements HandlerInterceptor {
    private static final String SAVED=FoodBizDataScopeInterceptor.class.getName()+".scope";
    private record Saved(Object handler,DataScopeSpec previous) { }

    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if(!foodHandler(handler)) return true;
        stack(request).push(new Saved(handler,DataScopeContext.current()));
        var user=SecurityUtils.currentUser();var context=BizContext.current();
        if(user.isPresent() && user.get().realm()==Realm.MERCHANT && context.merchantNo()!=null
                && !context.merchantNo().isBlank()) {
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT,Set.of(context.merchantNo())));
        }
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,
            Exception error) {
        if(!foodHandler(handler) || !(request.getAttribute(SAVED) instanceof Deque<?>)) return;
        var states=stack(request);
        if(states.isEmpty() || states.peek().handler()!=handler) return;
        var saved=states.pop();
        if(saved.previous()==null) DataScopeContext.clear();else DataScopeContext.set(saved.previous());
        if(states.isEmpty()) request.removeAttribute(SAVED);
    }
    private static boolean foodHandler(Object handler) {
        return handler instanceof HandlerMethod method
                && method.getBeanType().getPackageName().equals("ai.neargo.shop.food.api.biz");
    }
    @SuppressWarnings("unchecked")
    private static Deque<Saved> stack(HttpServletRequest request) {
        if(request.getAttribute(SAVED) instanceof Deque<?> states) return (Deque<Saved>)states;
        var states=new ArrayDeque<Saved>();request.setAttribute(SAVED,states);return states;
    }
}
