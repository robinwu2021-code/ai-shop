package ai.neargo.shop.paybridge;

import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.paybridge.PaymentCollectionSourceProperties.Binding;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable source grants; configuration supplies provenance, never payment authorization evidence. */
public final class PaymentCollectionSourceBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentCollectionSourceBootstrap.class);
    private final PaymentCollectionSourceProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** 门店与主体在平台基础库：只读核对，经商户域端口 */
    private final ai.neargo.shop.spi.user.PaymentCollectionFactsPort facts;

    public PaymentCollectionSourceBootstrap(PaymentCollectionSourceProperties properties,
            DataSource dataSource, PlatformTransactionManager manager, ai.neargo.shop.spi.user.PaymentCollectionFactsPort facts) {
        this.properties = properties;
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(manager);
        this.facts = facts;
    }

    public void provision() {
        var bindings = properties.sourceBindings();
        if (bindings.isEmpty()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            for (var binding : bindings) {
                provision(binding);
            }
        });
        for (var b : bindings) {
            LOG.info("Payment collection source verified environment={} payAppId={} sourceType={}"
                            + " sourceId={} paymentStoreId={} shopStoreId={} storeNo={} entityNo={}"
                            + " market={} fingerprint={}",
                    b.environment(), b.payAppId(), b.sourceType(), b.sourceId(), b.paymentStoreId(),
                    b.shopStoreId(), b.storeNo(), b.entityNo(), b.market(), fingerprint(b));
        }
    }

    private void provision(Binding b) {
        // 启动时的一次性核对：门店坐标（id、门店号、主体、市场）与平台基础库一致才授予来源
        var shop = facts.storeByNo(b.storeNo());
        if (shop.isEmpty() || shop.get().storeId() != b.shopStoreId()
                || !b.entityNo().equals(shop.get().entityNo()) || !b.market().equals(shop.get().market())) {
            throw new IllegalStateException("Payment collection SHOP coordinates conflict: "
                    + fingerprint(b));
        }
        // The existing primary key arbitrates concurrent starts. A duplicate changes no grant.
        jdbc.update("INSERT INTO payment_collection_source"
                        + " (environment,pay_app_id,source_type,source_id,payment_store_id,"
                        + " store_no,entity_no,enabled) VALUES (?,?,?,?,?,?,?,TRUE)"
                        + " ON DUPLICATE KEY UPDATE pay_app_id=pay_app_id",
                b.environment(), b.payAppId(), b.sourceType(), b.sourceId(), b.paymentStoreId(),
                b.storeNo(), b.entityNo());
        var targets = jdbc.query("SELECT store_no,entity_no,enabled FROM payment_collection_source"
                        + " WHERE environment=? AND pay_app_id=? AND source_type=? AND source_id=?"
                        + " AND payment_store_id=? FOR UPDATE",
                (rs, row) -> b.storeNo().equals(rs.getString("store_no"))
                        && b.entityNo().equals(rs.getString("entity_no")) && rs.getBoolean("enabled"),
                b.environment(), b.payAppId(), b.sourceType(), b.sourceId(), b.paymentStoreId());
        if (targets.size() != 1 || !targets.getFirst()) {
            throw new IllegalStateException("Payment collection source mapping conflict: "
                    + fingerprint(b));
        }
    }

    private static String fingerprint(Binding b) {
        return IdempotencyScope.requestHash("payment-collection-source-v1", b.environment(),
                Long.toString(b.payAppId()), b.sourceType(), Long.toString(b.sourceId()),
                Long.toString(b.paymentStoreId()), Long.toString(b.shopStoreId()), b.storeNo(),
                b.entityNo(), b.market());
    }
}
