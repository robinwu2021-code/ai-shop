package ai.neargo.shop.trade.order.points.earning;

import ai.neargo.shop.spi.points.PointsEarnPort.*;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.*;

/** Transaction-owned policies. Published values remain unchanged while a draft is edited. */
@Service
@Transactional("transactionManager")
public class PointsEarningRules {
    public record Rule(String typeCode, String scope, long revision, Long activeRevision,
                       String mode, long value, String activeMode, Long activeValue,
                       String actor, String remark, long at, String action) {
        public Rule(String typeCode, String scope, long revision, Long activeRevision,
                    String mode, long value, String activeMode, Long activeValue, String actor, String remark, long at) {
            this(typeCode,scope,revision,activeRevision,mode,value,activeMode,activeValue,actor,remark,at,"IMPORT");
        }
    }
    public record Change(String requestId, String typeCode, String scope, long expectedRevision,
                         String action, Long ratioBp, String remark) {}
    public record Matched(String scope, long revision, EarnRule rule) {}
    public record Page(List<Rule> rows, long total, long page, int size) {}
    private final JdbcTemplate jdbc;
    private final PointsEarningCatalogPort catalog;
    private final ObjectMapper json = new ObjectMapper();
    public PointsEarningRules(@Qualifier("dataSource") DataSource source, PointsEarningCatalogPort catalog) {
        this.jdbc = new JdbcTemplate(source); this.catalog = catalog;
    }
    public void ensureDefault(String type, EarnRule legacy, long legacyRevision) {
        var value = new Rule(type, "DEFAULT", 1, 1L, legacy.mode().name(), legacy.value(),
                legacy.mode().name(), legacy.value(), "SYSTEM", "迁入原兜底规则，原修订 " + legacyRevision, System.currentTimeMillis());
        int created = jdbc.update("INSERT IGNORE INTO ord_points_earn_rule(type_code,scope_no,version,payload_json) VALUES(?, 'DEFAULT', 1, ?)", type, encode(value));
        if (created == 1) jdbc.update("INSERT INTO ord_points_earn_rule_revision(request_id,type_code,scope_no,revision,command_json,payload_json) VALUES(?,?,'DEFAULT',1,?,?)",
                "EARN:IMPORT:" + type, type, "IMPORT", encode(value));
    }
    public List<Rule> all(String type) {
        return jdbc.query("SELECT payload_json FROM ord_points_earn_rule WHERE type_code=? ORDER BY CASE WHEN scope_no='DEFAULT' THEN 0 ELSE 1 END,scope_no",
                (r,n) -> decode(r.getString(1)), type);
    }
    public Page page(String type, long page, int size) {
        require(page >= 1 && page <= 1000000 && size >= 1 && size <= 100, "POINTS_EARN_PAGE_INVALID");
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM ord_points_earn_rule WHERE type_code=?", Long.class, type);
        var rows = jdbc.query("SELECT payload_json FROM ord_points_earn_rule WHERE type_code=? ORDER BY CASE WHEN scope_no='DEFAULT' THEN 0 ELSE 1 END,scope_no LIMIT ? OFFSET ?",
                (r,n) -> decode(r.getString(1)), type, size, (page-1)*size);
        return new Page(rows,total,page,size);
    }
    public List<Rule> history(String type, String scope) {
        return jdbc.query("SELECT payload_json FROM ord_points_earn_rule_revision WHERE type_code=? AND scope_no=? ORDER BY revision DESC",
                (r,n) -> decode(r.getString(1)), type, scope);
    }
    public Rule change(Change c, String actor) {
        require(c != null && nonblank(c.requestId()) && c.requestId().length() <= 100 && nonblank(c.typeCode())
                && c.typeCode().length() <= 64 && nonblank(c.scope()) && c.scope().length() <= 64 && nonblank(actor)
                && c.remark() != null && c.remark().length() <= 500 && c.expectedRevision() >= 0, "POINTS_EARN_INPUT_INVALID");
        require(Set.of("SAVE", "PUBLISH", "DISABLE").contains(c.action() == null ? "" : c.action()), "POINTS_EARN_ACTION_INVALID");
        var receipt = jdbc.query("SELECT command_json,payload_json FROM ord_points_earn_rule_revision WHERE request_id=?", (r,n) -> new String[]{r.getString(1),r.getString(2)}, c.requestId());
        String command = encode(Map.of("command",c,"actor",actor));
        if (!receipt.isEmpty()) {
            require(json.readTree(command).equals(json.readTree(receipt.getFirst()[0])), "POINTS_IDEMPOTENCY_CONFLICT");
            return decode(receipt.getFirst()[1]);
        }
        var found = jdbc.query("SELECT payload_json FROM ord_points_earn_rule WHERE type_code=? AND scope_no=? FOR UPDATE", (r,n) -> decode(r.getString(1)), c.typeCode(),c.scope());
        Rule prior = found.isEmpty() ? null : found.getFirst();
        require((prior == null ? 0 : prior.revision()) == c.expectedRevision(), "POINTS_EARN_REVISION_CONFLICT");
        if (!"DEFAULT".equals(c.scope())) require(catalog.categories().stream().anyMatch(x -> c.scope().equals(x.categoryNo())), "POINTS_CATEGORY_NOT_FOUND");
        long rev = c.expectedRevision()+1;
        Long active = prior == null ? null : prior.activeRevision();
        String activeMode = prior == null ? null : prior.activeMode();
        Long activeValue = prior == null ? null : prior.activeValue();
        String mode = prior == null ? "RATIO" : prior.mode();
        long value = prior == null ? 0 : prior.value();
        if ("SAVE".equals(c.action())) {
            require(c.ratioBp() != null && c.ratioBp() >= 0 && c.ratioBp() <= 10000, "POINTS_EARN_RATIO_INVALID");
            mode = "RATIO"; value = c.ratioBp();
        } else {
            require(prior != null, "POINTS_EARN_RULE_NOT_FOUND");
            if ("PUBLISH".equals(c.action())) {
                require("RATIO".equals(mode), "POINTS_EARN_LEGACY_REQUIRES_RATIO");
                active = rev; activeMode = mode; activeValue = value;
            } else {
                require(!"DEFAULT".equals(c.scope()), "POINTS_EARN_DEFAULT_REQUIRED");
                active = null; activeMode = null; activeValue = null;
            }
        }
        var next = new Rule(c.typeCode(),c.scope(),rev,active,mode,value,activeMode,activeValue,actor,c.remark(),System.currentTimeMillis(),c.action());
        if (prior == null) jdbc.update("INSERT INTO ord_points_earn_rule(type_code,scope_no,version,payload_json) VALUES(?,?,?,?)", c.typeCode(),c.scope(),rev,encode(next));
        else require(jdbc.update("UPDATE ord_points_earn_rule SET version=?,payload_json=? WHERE type_code=? AND scope_no=? AND version=?",rev,encode(next),c.typeCode(),c.scope(),prior.revision()) == 1, "POINTS_EARN_REVISION_CONFLICT");
        jdbc.update("INSERT INTO ord_points_earn_rule_revision(request_id,type_code,scope_no,revision,command_json,payload_json) VALUES(?,?,?,?,?,?)",c.requestId(),c.typeCode(),c.scope(),rev,command,encode(next));
        return next;
    }
    public Matched resolve(String type, String category, EarnRule legacy, long revision) {
        ensureDefault(type, legacy, revision);
        return match(all(type), catalog.ancestry(category));
    }
    public Matched resolveGoods(String type, String goods, EarnRule legacy, long revision) {
        return resolve(type,catalog.goodsCategory(goods),legacy,revision);
    }
    public static Matched match(List<Rule> rules, List<String> ancestry) {
        var scopes = new ArrayList<>(ancestry); scopes.add("DEFAULT");
        for (var scope: scopes) for (var r: rules) if (scope.equals(r.scope()) && r.activeRevision() != null) {
            return new Matched(scope,r.activeRevision(),new EarnRule(RuleMode.valueOf(r.activeMode()),r.activeValue()));
        }
        throw new IllegalArgumentException("POINTS_EARN_DEFAULT_REQUIRED");
    }
    public static long calculate(long cash, long ratio, long ratePoints, long rateMinor) {
        require(cash >= 0 && ratio >= 0 && ratio <= 10000 && ratePoints > 0 && rateMinor > 0,"POINTS_EARN_INPUT_INVALID");
        long money = BigInteger.valueOf(cash).multiply(BigInteger.valueOf(ratio)).divide(BigInteger.valueOf(10000)).longValueExact();
        // Equivalent to RedeemRate.pointsForMinor followed by floorAligned; parameters must be reduced.
        long gcd = BigInteger.valueOf(ratePoints).gcd(BigInteger.valueOf(rateMinor)).longValueExact();
        long p = ratePoints/gcd, m = rateMinor/gcd;
        return BigInteger.valueOf(money).divide(BigInteger.valueOf(m)).multiply(BigInteger.valueOf(p)).longValueExact();
    }
    private Rule decode(String body) { return json.readValue(body, Rule.class); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private static boolean nonblank(String s) { return s != null && !s.isBlank(); }
    private static void require(boolean ok, String code) { if (!ok) throw new IllegalArgumentException(code); }
}
