package ai.neargo.shop.trade.numbering;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.PlatformOrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit outermost order boundaries. Original transaction attributes and idempotency stay in place. */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class OrderCreationNumberReservations extends DefaultPointcutAdvisor {
    public record CartSelection(String buyerNo, List<OrderService.CreateOrderCommand.Item> items) {
        public CartSelection { items = List.copyOf(items); }
    }
    private static final ThreadLocal<CartSelection> CART = new ThreadLocal<>();
    public static Optional<CartSelection> cartSelection() { return Optional.ofNullable(CART.get()); }

    public OrderCreationNumberReservations(@Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers,
            PaymentMapper payments, StoreOrderMapper children, CartItemMapper carts) {
        setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        setPointcut(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return owns(targetClass.getSimpleName(), method.getName());
            }
        });
        setAdvice((MethodInterceptor) invocation -> {
            String type = ClassUtils.getUserClass(invocation.getThis()).getSimpleName();
            Object[] arguments = invocation.getArguments();
            boolean ownsCart = false;
            for (Object argument : arguments) {
                if (argument instanceof OrderService.CreateOrderCommand command
                        && (command.items() == null || command.items().isEmpty())) {
                    String buyer = arguments[0] instanceof String explicit ? explicit : SecurityUtils.currentUserNo();
                    if (CART.get() == null) {
                        List<TrdCartItem> selected = DataScopeContext.executeWithoutScope(() -> carts.selectList(
                                Wrappers.<TrdCartItem>lambdaQuery().eq(TrdCartItem::getUserNo, buyer)
                                        .eq(TrdCartItem::getSelected, true)));
                        CART.set(new CartSelection(buyer, selected.stream().map(row ->
                                new OrderService.CreateOrderCommand.Item(row.getGoodsNo(), row.getSkuNo(), row.getQty())).toList()));
                        ownsCart = true;
                    } else if (!buyer.equals(CART.get().buyerNo())) {
                        throw new IllegalStateException("Nested order creation changed cart buyer");
                    }
                }
            }
            try {
                Map<DocumentType, Integer> counts;
                if (invocation.getMethod().getName().equals("cancel")) {
                    counts = Map.of(DocumentType.AFTER_SALE, cancellationBudget((String) arguments[0], payments, children));
                } else counts = creationBudget(type, arguments);
                return numbers.withReservation(counts, () -> {
                    try { return invocation.proceed(); }
                    catch (RuntimeException | Error failure) { throw failure; }
                    catch (Throwable failure) { throw new InvocationFailure(failure); }
                });
            } catch (InvocationFailure failure) { throw failure.getCause(); }
            finally { if (ownsCart) CART.remove(); }
        });
    }
    static boolean owns(String type, String method) {
        return type.equals("OrderServiceImpl") && Set.of("create", "createFor", "createCoreFor", "createBound", "cancel").contains(method)
                || type.equals("PlatformOrderServiceImpl") && method.equals("createProxyOrder")
                || type.equals("CheckoutServiceImpl") && method.equals("place")
                || type.equals("CounterOrderPlacementServiceImpl") && method.equals("place");
    }
    static Map<DocumentType, Integer> creationBudget(String type, Object[] args) {
        // Confirmed checkout is scoped to one entity/store and freezes exactly one child.
        if (type.equals("CheckoutServiceImpl") || type.equals("CounterOrderPlacementServiceImpl")) {
            return Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 1);
        }
        int count = 0;
        for (Object argument : args) {
            if (argument instanceof OrderService.CreateOrderCommand command)
                count = command.items() == null || command.items().isEmpty()
                        ? cartSelection().map(selection -> selection.items().size()).orElse(0) : command.items().size();
            if (argument instanceof PlatformOrderService.ProxyOrderCommand command)
                count = command.items() == null ? 0 : command.items().size();
        }
        return Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, count);
    }
    private static int cancellationBudget(String no, PaymentMapper payments, StoreOrderMapper children) {
        String buyer = SecurityUtils.currentUserNo();
        OrdSubOrder child = children.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, no).eq(OrdSubOrder::getUserNo, buyer).last("limit 1"));
        String main = child == null ? no : child.getOrderNo();
        OrdOrder payment = payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, main).eq(OrdOrder::getUserNo, buyer).last("limit 1"));
        if (payment == null) return 0;
        return Math.toIntExact(children.selectCount(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getOrderNo, main).eq(OrdSubOrder::getUserNo, buyer)));
    }
    private static class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) { super(cause); }
    }
}
