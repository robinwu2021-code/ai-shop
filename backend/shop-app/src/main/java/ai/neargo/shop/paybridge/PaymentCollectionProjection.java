package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.spi.pay.MarketPort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

/** Local payment message and shared stream are one transactional effect, in both outbox modes. */
public final class PaymentCollectionProjection implements OutboxConsumer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final MessageService messages;
    private final BizIdentityResolver identities;
    private final Clock clock;
    private final MarketPort markets;

    public PaymentCollectionProjection(
            DataSource ds,
            PlatformTransactionManager manager,
            ObjectMapper json,
            MessageService messages,
            BizIdentityResolver identities,
            MarketPort markets,
            Clock clock) {
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(manager);
        this.json = json;
        this.messages = messages;
        this.identities = identities;
        this.markets = markets;
        this.clock = clock;
    }

    @Override
    public String handlerId() {
        return "merchant-collection-message";
    }

    @Override
    public boolean supports(String type) {
        return PaymentCollectionWork.TYPE.equals(type);
    }

    @Override
    public void consume(SysOutbox event) {
        String receipt = json.readTree(event.getPayload()).path("receiptNo").asString();
        transaction.executeWithoutResult(
                status -> {
                    var row =
                            jdbc.queryForMap(
                                    "SELECT * FROM payment_collection_receipt WHERE receipt_no=?"
                                            + " FOR UPDATE",
                                    receipt);
                    if (row.get("projected_at") != null) return;
                    PaymentCollectionFact fact =
                            json.readValue(
                                    (String) row.get("payload"), PaymentCollectionFact.class);
                    String entity = (String) row.get("entity_no"),
                            store = (String) row.get("store_no");
                    var market = markets.find(fact.market()).orElseThrow();
                    if (!market.currency().equals(fact.currency()))
                        throw new IllegalStateException("payment currency mismatch");
                    String amount =
                            BigDecimal.valueOf(fact.paidMinor(), market.currencyScale())
                                    .toPlainString();
                    var recipients = new TreeMap<String, LoginUser>();
                    jdbc.queryForList(
                                    "SELECT mch_account_no,user_no FROM mch_account WHERE"
                                            + " entity_no=? AND status='ACTIVE' AND deleted=0",
                                    entity)
                            .forEach(
                                    account -> {
                                        String mch = (String) account.get("mch_account_no"),
                                                usr = (String) account.get("user_no");
                                        if (mch != null)
                                            add(
                                                    recipients,
                                                    LoginUser.merchant(mch, ""),
                                                    entity,
                                                    store);
                                        if (usr != null)
                                            add(
                                                    recipients,
                                                    LoginUser.merchantByUser(usr, ""),
                                                    entity,
                                                    store);
                                    });
                    for (var entry : recipients.entrySet()) {
                        String scope = entry.getKey();
                        LoginUser user = entry.getValue();
                        jdbc.update(
                                "INSERT INTO payment_collection_scope(scope_key,seq) VALUES (?,0)"
                                        + " ON DUPLICATE KEY UPDATE seq=seq",
                                scope);
                        long sequence =
                                jdbc.queryForObject(
                                                "SELECT seq FROM payment_collection_scope WHERE"
                                                        + " scope_key=? FOR UPDATE",
                                                Long.class,
                                                scope)
                                        + 1;
                        String dedup =
                                "COLLECT:"
                                        + fact.eventId()
                                        + ":"
                                        + user.subjectKind()
                                        + ":"
                                        + user.userNo();
                        messages.pushTo(
                                MsgMessage.RECEIVER_STAFF,
                                user.userNo(),
                                MessageService.TRADE,
                                "收款成功",
                                fact.currency() + " " + amount,
                                "/pages/payment-order/index?payOrderNo="
                                        + fact.payOrderNo()
                                        + "&store="
                                        + store,
                                dedup);
                        jdbc.update(
                                "UPDATE notify_message SET"
                                    + " biz_type='PAYMENT',biz_no=?,store_no=?,entity_no=? WHERE"
                                    + " dedup_key=?",
                                fact.payOrderNo(),
                                store,
                                entity,
                                dedup);
                        String message =
                                jdbc.queryForObject(
                                        "SELECT message_no FROM notify_message WHERE dedup_key=?",
                                        String.class,
                                        dedup);
                        jdbc.update(
                                "INSERT INTO"
                                    + " payment_collection_stream(scope_key,seq,event_id,message_no,pay_order_no,store_no,paid_at,created_at)"
                                    + " VALUES (?,?,?,?,?,?,?,?)",
                                scope,
                                sequence,
                                fact.eventId(),
                                message,
                                fact.payOrderNo(),
                                store,
                                fact.succeededAt(),
                                clock.millis());
                        jdbc.update(
                                "UPDATE payment_collection_scope SET seq=? WHERE scope_key=?",
                                sequence,
                                scope);
                    }
                    jdbc.update(
                            "UPDATE payment_collection_receipt SET projected_at=? WHERE"
                                    + " receipt_no=?",
                            clock.millis(),
                            receipt);
                });
    }

    private void add(Map<String, LoginUser> out, LoginUser user, String entity, String store) {
        var ctx = identities.resolveStrict(user, store);
        if (entity.equals(ctx.merchantNo())
                && ctx.hasStoreAccess(store)
                && ctx.withStore(store).can(BizPerms.PAY_ORDER_VIEW))
            out.put(scope(user, entity, store), user);
    }

    public static String scope(LoginUser user, String entity, String store) {
        // Length framing prevents any identifier delimiter collision.
        return Stream.of(
                        "SANDBOX",
                        user.tenantNo(),
                        user.subjectKind().name(),
                        user.userNo(),
                        entity,
                        store)
                .map(s -> s.length() + ":" + s)
                .collect(Collectors.joining());
    }

    /** Expiry floor and deletions commit together under the same scope lock as append. */
    public void cleanup(long before) {
        for (String scope :
                jdbc.queryForList(
                        "SELECT DISTINCT scope_key FROM payment_collection_stream WHERE"
                                + " created_at<?",
                        String.class,
                        before)) {
            transaction.executeWithoutResult(
                    status -> {
                        jdbc.queryForObject(
                                "SELECT seq FROM payment_collection_scope WHERE scope_key=? FOR"
                                        + " UPDATE",
                                Long.class,
                                scope);
                        Long floor =
                                jdbc.queryForObject(
                                        "SELECT MAX(seq) FROM payment_collection_stream WHERE"
                                                + " scope_key=? AND created_at<?",
                                        Long.class,
                                        scope,
                                        before);
                        if (floor != null) {
                            jdbc.update(
                                    "UPDATE payment_collection_scope SET"
                                            + " floor_seq=GREATEST(floor_seq,?) WHERE scope_key=?",
                                    floor,
                                    scope);
                            jdbc.update(
                                    "DELETE FROM payment_collection_stream WHERE scope_key=? AND"
                                            + " seq<=?",
                                    scope,
                                    floor);
                        }
                    });
        }
    }
}
