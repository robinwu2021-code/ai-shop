package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CashShiftContracts.CashShiftVO;
import ai.neargo.shop.food.dto.CashShiftContracts.EntryReq;
import ai.neargo.shop.food.dto.CashShiftContracts.PaymentRefReq;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;
import ai.neargo.shop.food.service.CashShiftService;
import ai.neargo.shop.idem.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 现金交班单次提交（POS B14 / B15，TDD-餐饮现金交班单次提交）。
 *
 * <p>交班发生在店里、常常离线，POS 是权威：服务端校验结构与归属后原样存档（四张既有表），
 * 差额按实盘减 POS 的应有重算；一笔外部记账只能挂一个班（{@code fnb_cash_payment_link.core_payment_ref} 唯一）。
 */
public class CashShiftServiceImpl implements CashShiftService {
    static final String ENDPOINT = "POST /biz/x/food/stores/{storeNo}/cash-shifts";
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,32}");
    private static final Map<String, Set<String>> KIND_DIRECTIONS = Map.of(
            "FLOAT_IN", Set.of("IN"), "EXPENSE", Set.of("OUT"), "DEPOSIT", Set.of("OUT"), "TRANSFER", Set.of("IN", "OUT"));
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(2);
    private static final Duration MAX_SHIFT = Duration.ofDays(3);

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdempotencyService idem;
    private final ObjectMapper json;
    private final Clock clock;

    public CashShiftServiceImpl(DataSource source, PlatformTransactionManager manager, IdempotencyService idem,
            ObjectMapper json, Clock clock) {
        this.db = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(manager);
        this.idem = idem;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public CashShiftVO submit(String entity, String store, String actor, SubmitReq req, String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw bad();
        validate(req);
        String hash = sha256(store + "|" + json.writeValueAsString(req));
        try {
            return tx.execute(t -> idem.execute(key, ENDPOINT + "|" + entity + "|" + store, actor, hash,
                    CashShiftVO.class, () -> insert(entity, store, actor, req)));
        } catch (DuplicateKeyException linkedElsewhere) {
            // 有外部记账已挂到别的班（core_payment_ref 唯一）
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private CashShiftVO insert(String entity, String store, String actor, SubmitReq req) {
        String shift = BizKey.next("FCSH");
        LocalDateTime now = utc(clock.instant());
        db.update("INSERT INTO fnb_cash_shift(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                + "shift_no,store_no,drawer_no,owner_no,currency,opening_minor,status,opened_at,closed_at)"
                + " VALUES('MAIN',?,?,?,?,?,?,?,?,?,?,?,'HANDED_OVER',?,?)",
                entity, now, actor, now, actor, shift, store, req.drawerNo(), req.ownerNo(), req.currency(),
                req.openingMinor(), utc(req.openedAt()), utc(req.closedAt()));
        var entries = req.entries() == null ? List.<EntryReq>of() : req.entries();
        for (int i = 0; i < entries.size(); i++) {
            EntryReq e = entries.get(i);
            db.update("INSERT INTO fnb_cash_entry(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "entry_no,shift_no,kind,amount_minor,direction,voucher_ref,approval_ref,operation_key)"
                    + " VALUES('MAIN',?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    entity, now, actor, now, actor, BizKey.next("FCEN"), shift, e.kind(), e.amountMinor(),
                    e.direction(), e.voucherRef(), e.approvalRef(), shift + ":" + i);
        }
        var refs = req.paymentRefs() == null ? List.<PaymentRefReq>of() : req.paymentRefs();
        for (PaymentRefReq r : refs) {
            db.update("INSERT INTO fnb_cash_payment_link(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "link_no,shift_no,core_payment_ref,kind,occurred_at) VALUES('MAIN',?,?,?,?,?,?,?,?,?,?)",
                    entity, now, actor, now, actor, BizKey.next("FCPL"), shift, r.recordNo(), r.kind(),
                    utc(r.occurredAt()));
        }
        db.update("INSERT INTO fnb_cash_count(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                + "count_no,shift_no,counted_minor,expected_minor,difference_minor,counted_by,accepted_by,approval_ref)"
                + " VALUES('MAIN',?,?,?,?,?,?,?,?,?,?,?,?,?)",
                entity, now, actor, now, actor, BizKey.next("FCCT"), shift, req.countedMinor(), req.expectedMinor(),
                req.countedMinor() - req.expectedMinor(), req.ownerNo(), req.acceptedBy(), req.approvalRef());
        return read(entity, store, shift);
    }

    @Override
    public CashShiftVO get(String entity, String store, String shiftNo) {
        if (shiftNo == null || !CODE.matcher(shiftNo).matches()) throw bad();
        return read(entity, store, shiftNo);
    }

    private CashShiftVO read(String entity, String store, String shift) {
        var rows = db.queryForList("SELECT s.shift_no,s.store_no,s.drawer_no,s.owner_no,s.currency,s.opening_minor,"
                + "s.status,s.opened_at,s.closed_at,c.counted_minor,c.expected_minor,c.difference_minor,c.accepted_by,"
                + "c.approval_ref FROM fnb_cash_shift s JOIN fnb_cash_count c ON c.entity_no=s.entity_no"
                + " AND c.shift_no=s.shift_no AND c.deleted=0 WHERE s.tenant_no='MAIN' AND s.entity_no=? AND s.store_no=?"
                + " AND s.shift_no=? AND s.deleted=0", entity, store, shift);
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        var row = rows.getFirst();
        Long in = db.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM fnb_cash_entry WHERE entity_no=?"
                + " AND shift_no=? AND direction='IN' AND deleted=0", Long.class, entity, shift);
        Long out = db.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM fnb_cash_entry WHERE entity_no=?"
                + " AND shift_no=? AND direction='OUT' AND deleted=0", Long.class, entity, shift);
        Integer entries = db.queryForObject("SELECT COUNT(*) FROM fnb_cash_entry WHERE entity_no=? AND shift_no=?"
                + " AND deleted=0", Integer.class, entity, shift);
        Integer links = db.queryForObject("SELECT COUNT(*) FROM fnb_cash_payment_link WHERE entity_no=? AND shift_no=?"
                + " AND deleted=0", Integer.class, entity, shift);
        return new CashShiftVO((String) row.get("shift_no"), (String) row.get("store_no"), (String) row.get("drawer_no"),
                (String) row.get("owner_no"), (String) row.get("currency"), ((Number) row.get("opening_minor")).longValue(),
                (String) row.get("status"), instant(row.get("opened_at")), instant(row.get("closed_at")),
                ((Number) row.get("counted_minor")).longValue(), ((Number) row.get("expected_minor")).longValue(),
                ((Number) row.get("difference_minor")).longValue(), in, out, entries, links,
                (String) row.get("accepted_by"), (String) row.get("approval_ref"));
    }

    private void validate(SubmitReq r) {
        Instant now = clock.instant();
        if (r == null || !code(r.drawerNo()) || !code(r.ownerNo()) || r.currency() == null
                || !r.currency().matches("[A-Z]{3}") || negativeOrMissing(r.openingMinor()) || r.openedAt() == null
                || r.closedAt() == null || r.closedAt().isBefore(r.openedAt())
                || r.closedAt().isAfter(now.plus(FUTURE_SKEW))
                || Duration.between(r.openedAt(), r.closedAt()).compareTo(MAX_SHIFT) > 0
                || negativeOrMissing(r.countedMinor()) || r.expectedMinor() == null
                || r.acceptedBy() != null && !code(r.acceptedBy())
                || r.approvalRef() != null && !REF.matcher(r.approvalRef()).matches()) throw bad();
        // 实盘与应有对不上：必须带差异审批引用（POS 本机主管授权）
        if (!r.countedMinor().equals(r.expectedMinor()) && r.approvalRef() == null) throw bad();
        var entries = r.entries() == null ? List.<EntryReq>of() : r.entries();
        if (entries.size() > 200) throw bad();
        for (EntryReq e : entries) {
            if (e == null || e.kind() == null || !KIND_DIRECTIONS.containsKey(e.kind())
                    || !KIND_DIRECTIONS.get(e.kind()).contains(e.direction()) || e.amountMinor() == null
                    || e.amountMinor() <= 0 || e.voucherRef() == null || !REF.matcher(e.voucherRef()).matches()
                    || e.approvalRef() != null && !REF.matcher(e.approvalRef()).matches()) throw bad();
        }
        var refs = r.paymentRefs() == null ? List.<PaymentRefReq>of() : r.paymentRefs();
        if (refs.size() > 1000) throw bad();
        Set<String> seen = new HashSet<>();
        for (PaymentRefReq p : refs) {
            if (p == null || p.recordNo() == null || !REF.matcher(p.recordNo()).matches()
                    || !Set.of("PAY", "REFUND").contains(p.kind()) || p.occurredAt() == null
                    || p.occurredAt().isAfter(now.plus(FUTURE_SKEW)) || !seen.add(p.recordNo())) throw bad();
        }
    }

    private static boolean code(String value) {
        return value != null && CODE.matcher(value).matches();
    }

    private static boolean negativeOrMissing(Long value) {
        return value == null || value < 0;
    }

    /** 业务时刻一律按 UTC 存取（TDD-餐饮门店基本信息与时间约定），不依赖 JVM 默认时区。 */
    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        return ((Timestamp) value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
