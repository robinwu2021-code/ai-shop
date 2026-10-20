package ai.neargo.shop.trade.numbering;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService.WorkItem;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds local numbers only after the existing service has authorized and built its result. */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class OrderDocumentNumbers extends DefaultPointcutAdvisor {
    private final DocumentNumberCoordinator numbers;
    public OrderDocumentNumbers(@Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.numbers = numbers;
        setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        setPointcut(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return Set.of("OrderServiceImpl", "MerchantOrderServiceImpl", "PlatformOrderServiceImpl")
                        .contains(targetClass.getSimpleName())
                        && (method.getReturnType() == OrderVO.class || method.getReturnType() == PageData.class
                            || method.getReturnType() == List.class);
            }
        });
        setAdvice((MethodInterceptor) invocation -> decorate(invocation.proceed()));
    }
    public Object decorate(Object result) {
        List<OrderVO> views = new ArrayList<>(); collect(result, views);
        if (views.isEmpty()) return result;
        Set<DocumentSource> mains = new LinkedHashSet<>(); Set<DocumentSource> children = new LinkedHashSet<>();
        for (OrderVO view : views) {
            if (text(view.payOrderNo())) mains.add(source("TRADE_ORDER", view.payOrderNo()));
            if (text(view.orderNo())) {
                if (main(view)) mains.add(source("TRADE_ORDER", view.orderNo()));
                else children.add(source("TRADE_SUBORDER", view.orderNo()));
            }
        }
        Map<DocumentSource, String> mainNumbers = mains.isEmpty() ? Map.of() : numbers.findAll(DocumentType.TRADE_ORDER, mains);
        Map<DocumentSource, String> childNumbers = children.isEmpty() ? Map.of() : numbers.findAll(DocumentType.TRADE_SUBORDER, children);
        return replace(result, mainNumbers, childNumbers);
    }
    private static void collect(Object value, List<OrderVO> into) {
        if (value instanceof OrderVO order) {
            into.add(order); if (order.subOrders() != null) order.subOrders().forEach(child -> collect(child, into));
        } else if (value instanceof WorkItem work) collect(work.order(), into);
        else if (value instanceof PageData<?> page) page.records().forEach(row -> collect(row, into));
        else if (value instanceof List<?> rows) rows.forEach(row -> collect(row, into));
    }
    private static Object replace(Object value, Map<DocumentSource, String> mains, Map<DocumentSource, String> children) {
        if (value instanceof OrderVO order) {
            String own = !text(order.orderNo()) ? null : (main(order) ? mains : children)
                    .get(source(main(order) ? "TRADE_ORDER" : "TRADE_SUBORDER", order.orderNo()));
            String pay = text(order.payOrderNo()) ? mains.get(source("TRADE_ORDER", order.payOrderNo())) : null;
            List<OrderVO> nested = order.subOrders() == null ? null : order.subOrders().stream()
                    .map(child -> (OrderVO) replace(child, mains, children)).toList();
            return order.withNumberedChildren(nested, own, pay);
        }
        if (value instanceof WorkItem work) return new WorkItem((OrderVO) replace(work.order(), mains, children),
                work.actionKind(), work.priority(), work.afterSaleNo(), work.afterSaleStatus());
        if (value instanceof PageData<?> page) return PageData.of(page.records().stream()
                .map(row -> replace(row, mains, children)).toList(), page.total(), page.page(), page.size());
        if (value instanceof List<?> rows) return rows.stream().map(row -> replace(row, mains, children)).toList();
        return value;
    }
    private static boolean main(OrderVO order) { return order.orderNo().equals(order.payOrderNo()); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static DocumentSource source(String resource, String no) { return new DocumentSource("MAIN", "CORE", resource, no); }
}
