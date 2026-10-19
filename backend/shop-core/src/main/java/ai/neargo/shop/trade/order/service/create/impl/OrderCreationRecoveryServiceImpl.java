package ai.neargo.shop.trade.order.service.create.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Stock is never reserved before its durable claim; recovery sends run after releasing the intent row lock. */
@Service
public class OrderCreationRecoveryServiceImpl implements OrderCreationRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(OrderCreationRecoveryServiceImpl.class);
    private static final String ENDPOINT = "POST /mp/order";
    private static final long LEASE_MS = 120_000L;
    private final CreateIntentMapper intents;
    private final PaymentMapper payments;
    private final StockPort stock;
    private final ObjectMapper json;
    private final TransactionTemplate independent;
    private final String authority;

    public OrderCreationRecoveryServiceImpl(CreateIntentMapper intents, PaymentMapper payments, StockPort stock,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Value("${shop.inventory.stock-authority:PLATFORM}") String authority) {
        this.intents = intents;
        this.payments = payments;
        this.stock = stock;
        this.json = json;
        this.authority = authority;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String register(String proposed, String buyer, String key, String hash, List<StockPort.SkuQty> needs) {
        return independent.execute(tx -> {
            OrdCreateIntent intent = new OrdCreateIntent();
            intent.setPayNo(proposed);
            intent.setUserNo(buyer);
            intent.setEndpoint(ENDPOINT);
            intent.setIdemKey(key == null || key.isBlank() ? null : key);
            intent.setRequestHash(hash);
            intent.setStockAuthority(authority);
            intent.setStockNeedsJson(json.writeValueAsString(needs));
            intent.setStatus(OrdCreateIntent.PREPARED);
            intent.setNextAttemptAt(System.currentTimeMillis() + LEASE_MS);
            try {
                intents.insert(intent);
                return proposed;
            } catch (DuplicateKeyException duplicate) {
                OrdCreateIntent old = intents.selectOne(Wrappers.<OrdCreateIntent>lambdaQuery()
                        .eq(OrdCreateIntent::getEndpoint, ENDPOINT).eq(OrdCreateIntent::getIdemKey, key)
                        .last("FOR UPDATE"));
                if (old == null || !Objects.equals(old.getUserNo(), buyer)) {
                    throw BizException.of(ErrorCode.NOT_FOUND);
                }
                if (!Objects.equals(old.getRequestHash(), hash)) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                return old.getPayNo();
            }
        });
    }

    @Override
    public void enlist(String payNo) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("creation requires the public order transaction");
        }
        OrdCreateIntent intent = intents.lock(payNo);
        if (intent == null || !OrdCreateIntent.PREPARED.equals(intent.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // A failed metadata update remains recoverable by inspecting the original payment.
                recover(payNo, System.currentTimeMillis(), true);
            }
        });
    }

    @Override
    public int recoverAbandoned(long now) {
        List<OrdCreateIntent> pending = intents.selectList(Wrappers.<OrdCreateIntent>lambdaQuery()
                .in(OrdCreateIntent::getStatus, OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING,
                        OrdCreateIntent.RECOVERY_REQUIRED)
                .le(OrdCreateIntent::getNextAttemptAt, now).orderByAsc(OrdCreateIntent::getId).last("LIMIT 100"));
        int resolved = 0;
        for (OrdCreateIntent intent : pending) {
            if (recover(intent.getPayNo(), now, false)) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean recover(String no, long now, boolean immediate) {
        try {
            OrdCreateIntent claimed = independent.execute(tx -> claim(no, now, immediate));
            if (claimed == null) {
                return false;
            }
            List<StockPort.SkuQty> needs = json.readValue(claimed.getStockNeedsJson(), new TypeReference<>() {});
            // A fresh platform transaction also avoids joining completed resources during afterCompletion.
            independent.executeWithoutResult(tx -> stock.release(no, needs));
            independent.executeWithoutResult(tx -> finish(no, OrdCreateIntent.RELEASED, null, now));
            return true;
        } catch (RuntimeException failure) {
            log.warn("[create-recovery] {} compensation pending: {}", no, failure.getClass().getSimpleName());
            try {
                independent.executeWithoutResult(tx -> finish(no, OrdCreateIntent.RECOVERY_REQUIRED,
                        failure instanceof BizException biz ? biz.errorCode().name()
                                : failure.getClass().getSimpleName(), now + LEASE_MS));
            } catch (RuntimeException metadataFailure) {
                log.warn("[create-recovery] {} durable claim retained", no);
            }
            return false;
        }
    }

    private OrdCreateIntent claim(String no, long now, boolean immediate) {
        OrdCreateIntent intent = intents.lock(no);
        if (intent == null || OrdCreateIntent.COMMITTED.equals(intent.getStatus())
                || OrdCreateIntent.RELEASED.equals(intent.getStatus())) {
            return null;
        }
        OrdOrder payment = payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, no).last("FOR UPDATE"));
        if (payment != null) {
            finish(no, OrdCreateIntent.COMMITTED, null, now);
            return null;
        }
        if (!immediate && intent.getNextAttemptAt() > now) {
            return null;
        }
        if (!Objects.equals(intent.getStockAuthority(), authority)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        finish(no, OrdCreateIntent.RECOVERING, null, now + LEASE_MS);
        return intent;
    }

    private void finish(String no, String state, String error, long next) {
        intents.update(null, Wrappers.<OrdCreateIntent>lambdaUpdate().eq(OrdCreateIntent::getPayNo, no)
                .in(OrdCreateIntent::getStatus, OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING,
                        OrdCreateIntent.RECOVERY_REQUIRED)
                .set(OrdCreateIntent::getUpdatedAt, LocalDateTime.now()).setSql("version = version + 1")
                .set(OrdCreateIntent::getStatus, state).set(OrdCreateIntent::getErrorCode, error)
                .set(OrdCreateIntent::getNextAttemptAt, next));
    }
}
