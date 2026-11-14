package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Real beauty scope/identity receipts/MySQL; checkout is a controlled external contract, not host
 * trade.
 */
abstract class SourceFixture extends ReceptionFixture {
    final Map<String, CheckoutPort.CheckoutQuote> quotes = new HashMap<>();
    final AtomicInteger prepares = new AtomicInteger();
    final String customerReference = "cr1_" + "c".repeat(43);
    boolean sourceAllowed = true;
    boolean checkoutAvailable = true;
    Runnable customerResolution = () -> {};
    boolean losePrepareResponse;
    boolean failPrepareBeforeAccept;
    String failSourceOperation;
    String failQuoteOperation;
    String missingFindOperation;
    final Map<String, String> ownerPhases = new ConcurrentHashMap<>();
    Consumer<CheckoutPort.SourceLookup> sourceRead = lookup -> {};
    final Set<String> created = ConcurrentHashMap.newKeySet();
    final Map<String, CheckoutPort.CheckoutSourceFacts> sourceFacts = new ConcurrentHashMap<>();
    CheckoutPort.PrepareCheckoutReq prepared;

    @Override
    ApplicationContextRunner business() {
        var base = super.business();
        if (checkoutAvailable) {
            base = base.withBean(CheckoutPort.class, this::checkout);
        }
        return base.withInitializer(
                application ->
                        application
                                .getBeanFactory()
                                .addBeanPostProcessor(
                                        new BeanPostProcessor() {
                                            @Override
                                            public Object postProcessAfterInitialization(
                                                    Object bean, String name) {
                                                if (bean instanceof CustomerIdentityPort) {
                                                    return Proxy.newProxyInstance(
                                                            bean.getClass().getClassLoader(),
                                                            new Class<?>[] {
                                                                CustomerIdentityPort.class
                                                            },
                                                            (proxy, method, arguments) -> {
                                                                if (method.getName()
                                                                        .equals(
                                                                                "resolveClaimReference")) {
                                                                    customerResolution.run();
                                                                }
                                                                try {
                                                                    return method.invoke(
                                                                            bean, arguments);
                                                                } catch (
                                                                        InvocationTargetException
                                                                                failure) {
                                                                    throw failure.getCause();
                                                                }
                                                            });
                                                }
                                                if (!(bean
                                                        instanceof
                                                        MerchantOperatorAccessPort delegate)) {
                                                    return bean;
                                                }
                                                return (MerchantOperatorAccessPort)
                                                        q -> {
                                                            outside();
                                                            if (BizPerms.CHECKOUT_PREPARE.equals(
                                                                            q.permission())
                                                                    || BizPerms.ORDER_VIEW.equals(
                                                                            q.permission())) {
                                                                return allowed
                                                                        && sourceAllowed
                                                                        && !q.operatorNo()
                                                                                .equals(
                                                                                        revokedActor)
                                                                        && "E".equals(q.entityNo())
                                                                        && "S".equals(q.storeNo());
                                                            }
                                                            return delegate.allowed(q);
                                                        };
                                            }
                                        }));
    }

    CheckoutPort checkout() {
        return (CheckoutPort)
                Proxy.newProxyInstance(
                        CheckoutPort.class.getClassLoader(),
                        new Class<?>[] {CheckoutPort.class},
                        (proxy, method, args) -> {
                            outside();
                            if ("prepare".equals(method.getName())) {
                                prepares.incrementAndGet();
                                if (failPrepareBeforeAccept) {
                                    throw new UncheckedIOException(
                                            new IOException("controlled transport before accept"));
                                }
                                prepared = (CheckoutPort.PrepareCheckoutReq) args[1];
                                assertThat(
                                                sql.queryForObject(
                                                        "SELECT COUNT(*) FROM"
                                                                + " bty_service_submission WHERE"
                                                                + " submission_no=?",
                                                        Integer.class,
                                                        prepared.operation().operationNo()))
                                        .isOne();
                                var lines =
                                        prepared.lines().stream()
                                                .map(
                                                        line ->
                                                                new CheckoutPort.QuoteLine(
                                                                        line.clientLineRef(),
                                                                        line.skuNo(),
                                                                        "G",
                                                                        line.quantity(),
                                                                        "COUNT",
                                                                        1000,
                                                                        line.quantity() * 1000L,
                                                                        0,
                                                                        line.quantity() * 1000L,
                                                                        "a".repeat(64)))
                                                .toList();
                                long due =
                                        lines.stream()
                                                .mapToLong(CheckoutPort.QuoteLine::dueMinor)
                                                .sum();
                                var quote =
                                        new CheckoutPort.CheckoutQuote(
                                                prepared.operation(),
                                                "Q",
                                                1,
                                                "a".repeat(64),
                                                Instant.now().plusSeconds(300),
                                                "USR1",
                                                "E",
                                                "S",
                                                "CNY",
                                                lines,
                                                due,
                                                0,
                                                due,
                                                prepared.payMode(),
                                                "STORE_VERIFY",
                                                "SERVICE_EXECUTION",
                                                List.of(),
                                                List.of(),
                                                "QUOTED",
                                                1);
                                quotes.put(prepared.operation().operationNo(), quote);
                                if (losePrepareResponse) {
                                    throw new UncheckedIOException(
                                            new IOException("controlled lost prepare response"));
                                }
                                return quote;
                            }
                            if ("readQuote".equals(method.getName())
                                    || "findOperation".equals(method.getName())) {
                                var op = (CheckoutPort.OperationRef) args[1];
                                if (op.operationNo().equals(failQuoteOperation)) {
                                    throw new UncheckedIOException(
                                            new IOException("original quote read unavailable"));
                                }
                                if ("findOperation".equals(method.getName())
                                        && op.operationNo().equals(missingFindOperation)) {
                                    throw BizException.of(ErrorCode.NOT_FOUND);
                                }
                                var quote = quotes.get(op.operationNo());
                                if (quote == null) {
                                    throw BizException.of(ErrorCode.NOT_FOUND);
                                }
                                return "readQuote".equals(method.getName())
                                        ? quote
                                        : new CheckoutPort.CheckoutResult(
                                                op,
                                                ownerPhases.getOrDefault(
                                                        op.operationNo(),
                                                        created.contains(op.operationNo())
                                                                ? "CREATED"
                                                                : "QUOTED"),
                                                1,
                                                created.contains(op.operationNo())
                                                                && !ownerPhases.containsKey(
                                                                        op.operationNo())
                                                        ? "ORD-" + op.operationNo()
                                                        : null,
                                                created.contains(op.operationNo())
                                                                && !ownerPhases.containsKey(
                                                                        op.operationNo())
                                                        ? "SUB-" + op.operationNo()
                                                        : null,
                                                null,
                                                null,
                                                Instant.now());
                            }
                            if ("source".equals(method.getName())) {
                                var lookup = (CheckoutPort.SourceLookup) args[1];
                                sourceRead.accept(lookup);
                                if (lookup.operation().operationNo().equals(failSourceOperation)) {
                                    throw new UncheckedIOException(
                                            new IOException("controlled source unavailable"));
                                }
                                var facts = sourceFacts.get(lookup.operation().operationNo());
                                if (facts == null) {
                                    throw BizException.of(ErrorCode.NOT_FOUND);
                                }
                                return facts;
                            }
                            throw new AssertionError(
                                    "No beauty consumer/payment writes: " + method.getName());
                        });
    }

    WorkOrderVO open(ApplicationContext context, int quantity, String key) {
        login(LoginUser.merchant("A", "operator"));
        return context.getBean(WorkOrderService.class)
                .openWorkOrder(
                        "E",
                        "S",
                        "A",
                        new OpenWorkOrderReq(
                                customerReference,
                                "S",
                                List.of(new AppointmentItemReq("SKU", quantity)),
                                null),
                        key);
    }

    SubmitServicesReq selection(WorkOrderVO root, String taskNo) {
        return new SubmitServicesReq(
                0,
                root.scopeRevision(),
                List.of(new SubmitServicesReq.TaskSelection(taskNo, 0, 0)),
                List.of(),
                new SubmitServicesReq.SourceSelection(
                        "PREPARE",
                        customerReference,
                        "ONLINE",
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()));
    }

    void settled(String operationNo, int quantity) {
        created.add(operationNo);
        var quote = quotes.get(operationNo);
        sourceFacts.put(
                operationNo,
                new CheckoutPort.CheckoutSourceFacts(
                        quote.operation(),
                        "ORD-" + operationNo,
                        "SUB-" + operationNo,
                        quote.buyerNo(),
                        "E",
                        "S",
                        1,
                        "a".repeat(64),
                        "PAID",
                        "CNY",
                        quote.dueMinor(),
                        quote.dueMinor(),
                        0L,
                        "PAID",
                        List.of(
                                new CheckoutPort.SourceLine(
                                        "LINE",
                                        quote.lines().getFirst().clientLineRef(),
                                        "G",
                                        "SKU",
                                        quantity,
                                        quantity,
                                        "COUNT",
                                        quote.dueMinor())),
                        List.of(
                                new CheckoutPort.PaymentFact(
                                        "PO-" + operationNo,
                                        "PAY-" + operationNo,
                                        "OUT-" + operationNo,
                                        "CASH",
                                        "TRADE-" + operationNo,
                                        "SUCCESS",
                                        quote.dueMinor(),
                                        "CNY",
                                        "RC-" + operationNo,
                                        Instant.now())),
                        "NOT_STARTED",
                        Instant.now()));
    }

    void existingOwner(String operation, int quantity) {
        var op = new CheckoutPort.OperationRef("BEAUTY", operation);
        long due = quantity * 1000L;
        quotes.put(
                operation,
                new CheckoutPort.CheckoutQuote(
                        op,
                        "Q-" + operation,
                        1,
                        "a".repeat(64),
                        Instant.now().plusSeconds(300),
                        "USR1",
                        "E",
                        "S",
                        "CNY",
                        List.of(
                                new CheckoutPort.QuoteLine(
                                        "ORIGINAL",
                                        "SKU",
                                        "G",
                                        quantity,
                                        "COUNT",
                                        1000,
                                        due,
                                        0,
                                        due,
                                        "b".repeat(64))),
                        due,
                        0,
                        due,
                        "ONLINE",
                        "STORE_VERIFY",
                        "SERVICE_EXECUTION",
                        List.of(),
                        List.of(),
                        "CREATED",
                        3));
        settled(operation, quantity);
    }

    SubmitServicesReq existing(WorkOrderVO root, String task, String operation) {
        return new SubmitServicesReq(
                0,
                root.scopeRevision(),
                List.of(new SubmitServicesReq.TaskSelection(task, 0, 0)),
                List.of(),
                new SubmitServicesReq.SourceSelection(
                        "EXISTING",
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        operation,
                        "SUB-" + operation,
                        1L,
                        "a".repeat(64),
                        List.of(new SubmitServicesReq.ExistingLineSelection(task, "LINE"))));
    }
}
