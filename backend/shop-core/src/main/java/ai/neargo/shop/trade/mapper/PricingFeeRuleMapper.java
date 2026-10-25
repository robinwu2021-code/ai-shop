package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.trade.entity.PricingFeeEvidenceRow;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

/** CORE owns fee definitions and scope applications; no industry table is accessed. */
@Repository
public class PricingFeeRuleMapper {
    private final JdbcTemplate db;

    private List<PricingFeeEvidenceRow> rows(String sql, Object... args) {
        return db.queryForList(sql, args).stream().map(PricingFeeEvidenceRow::new).toList();
    }

    public PricingFeeRuleMapper(@Qualifier("dataSource") DataSource source) {
        db = new JdbcTemplate(source);
    }

    public List<PricingFeeEvidenceRow> rule(String e, String s, String rule, boolean lock) {
        return rows(
                "SELECT * FROM ord_pricing_fee_rule WHERE entity_no=? AND store_no=? AND rule_no=?"
                        + (lock ? " FOR UPDATE" : ""),
                e,
                s,
                rule);
    }

    public List<PricingFeeEvidenceRow> revision(String e, String s, String revision) {
        return rows(
                "SELECT * FROM ord_pricing_fee_revision WHERE entity_no=? AND store_no=? AND"
                        + " revision_no=?",
                e,
                s,
                revision);
    }

    public List<PricingFeeEvidenceRow> command(String e, String s, String key) {
        return rows(
                "SELECT * FROM ord_pricing_fee_revision WHERE entity_no=? AND store_no=? AND"
                        + " request_key=?",
                e,
                s,
                key);
    }

    public int insertRule(
            String e, String s, String rule, String revision, String status, String actor) {
        return db.update(
                "INSERT INTO ord_pricing_fee_rule"
                    + " (entity_no,store_no,rule_no,revision_no,status,version,updated_at,updated_by)"
                    + " VALUES (?,?,?,?,?,0,UTC_TIMESTAMP(3),?)",
                e,
                s,
                rule,
                revision,
                status,
                actor);
    }

    public int updateRule(
            String e,
            String s,
            String rule,
            String revision,
            String status,
            long version,
            String actor) {
        return db.update(
                "UPDATE ord_pricing_fee_rule SET"
                    + " revision_no=?,status=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND rule_no=? AND version=?",
                revision,
                status,
                actor,
                e,
                s,
                rule,
                version);
    }

    public int insertRevision(
            String e,
            String s,
            String rule,
            String revision,
            String definition,
            String key,
            String hash,
            String actor,
            long version,
            String reason) {
        return db.update(
                "INSERT INTO ord_pricing_fee_revision"
                    + " (entity_no,store_no,rule_no,revision_no,definition_snapshot,request_key,request_hash,actor_no,version,reason,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                e,
                s,
                rule,
                revision,
                definition,
                key,
                hash,
                actor,
                version,
                reason);
    }

    public List<PricingFeeEvidenceRow> scope(
            String e, String s, String no, String rule, boolean lock) {
        return rows(
                "SELECT * FROM ord_pricing_fee_scope WHERE entity_no=? AND store_no=? AND"
                        + " scope_no=? AND rule_no=?"
                        + (lock ? " FOR UPDATE" : ""),
                e,
                s,
                no,
                rule);
    }

    public int createScope(String e, String s, String no, String rule, String currency) {
        return db.update(
                "INSERT INTO ord_pricing_fee_scope"
                    + " (entity_no,store_no,scope_no,rule_no,currency,goods_basis_minor,fee_minor,version)"
                    + " VALUES (?,?,?,?,?,0,0,0) ON DUPLICATE KEY UPDATE scope_no=VALUES(scope_no)",
                e,
                s,
                no,
                rule,
                currency);
    }

    public List<PricingFeeEvidenceRow> applications(String e, String s, String no, String rule) {
        return rows(
                "SELECT * FROM ord_pricing_fee_application WHERE entity_no=? AND store_no=? AND"
                        + " scope_no=? AND rule_no=? ORDER BY id DESC",
                e,
                s,
                no,
                rule);
    }

    public List<PricingFeeEvidenceRow> quoteApplications(String e, String s, String quote) {
        return rows(
                "SELECT * FROM ord_pricing_fee_application WHERE entity_no=? AND store_no=? AND"
                        + " quote_no=? ORDER BY id",
                e,
                s,
                quote);
    }

    public int application(
            String app,
            String e,
            String s,
            String order,
            String sub,
            String quote,
            String rule,
            String revision,
            String scope,
            String currency,
            long fee,
            String hash) {
        return db.update(
                "INSERT INTO ord_pricing_fee_application"
                    + " (application_no,entity_no,store_no,order_no,sub_order_no,quote_no,rule_no,revision_no,scope_no,currency,fee_minor,adjusted_fee_minor,source_hash,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                app,
                e,
                s,
                order,
                sub,
                quote,
                rule,
                revision,
                scope,
                currency,
                fee,
                fee,
                hash);
    }

    public int updateScope(
            String e, String s, String no, String rule, long version, long goods, long fee) {
        return db.update(
                "UPDATE ord_pricing_fee_scope SET"
                    + " goods_basis_minor=goods_basis_minor+?,fee_minor=fee_minor+?,version=version+1"
                    + " WHERE entity_no=? AND store_no=? AND scope_no=? AND rule_no=? AND"
                    + " version=?",
                goods,
                fee,
                e,
                s,
                no,
                rule,
                version);
    }

    public int advice(
            String e, String s, String app, String quote, long current, long target, String hash) {
        return db.update(
                "INSERT INTO ord_pricing_fee_advice"
                    + " (entity_no,store_no,application_no,quote_no,current_fee_minor,target_fee_minor,source_hash,status)"
                    + " VALUES (?,?,?,?,?,?,?,'OPEN')",
                e,
                s,
                app,
                quote,
                current,
                target,
                hash);
    }

    public List<PricingFeeEvidenceRow> adjustment(
            String e, String s, String app, String hash, boolean lock) {
        return rows(
                "SELECT d.status AS"
                    + " advice_status,d.current_fee_minor,d.target_fee_minor,d.source_hash AS"
                    + " advice_hash,a.* FROM ord_pricing_fee_advice d JOIN"
                    + " ord_pricing_fee_application a ON a.entity_no=d.entity_no AND"
                    + " a.store_no=d.store_no AND a.application_no=d.application_no JOIN"
                    + " ord_industry_order_source n ON n.entity_no=d.entity_no AND"
                    + " n.store_no=d.store_no AND n.quote_no=d.quote_no AND n.status IN"
                    + " ('BOUND','ACCEPTED') AND n.deleted=0 WHERE d.entity_no=? AND d.store_no=?"
                    + " AND d.application_no=? AND d.source_hash=?"
                        + (lock ? " FOR UPDATE" : ""),
                e,
                s,
                app,
                hash);
    }

    public int completeAdvice(String e, String s, String app, String hash) {
        return db.update(
                "UPDATE ord_pricing_fee_advice SET status='APPLIED' WHERE entity_no=? AND"
                        + " store_no=? AND application_no=? AND source_hash=? AND status='OPEN'",
                e,
                s,
                app,
                hash);
    }

    public int adjustApplication(String e, String s, String app, long current, long target) {
        return db.update(
                "UPDATE ord_pricing_fee_application SET adjusted_fee_minor=? WHERE entity_no=? AND"
                        + " store_no=? AND application_no=? AND adjusted_fee_minor=?",
                target,
                e,
                s,
                app,
                current);
    }

    public int reduceScope(String e, String s, String no, String rule, long amount) {
        return db.update(
                "UPDATE ord_pricing_fee_scope SET fee_minor=fee_minor-?,version=version+1 WHERE"
                    + " entity_no=? AND store_no=? AND scope_no=? AND rule_no=? AND fee_minor>=?",
                amount,
                e,
                s,
                no,
                rule,
                amount);
    }

    public List<PricingFeeEvidenceRow> pendingAdjustments(String e, String store, String scope) {
        return rows(
                "SELECT d.source_hash AS"
                    + " advice_hash,d.current_fee_minor,d.target_fee_minor,a.application_no,a.order_no,a.sub_order_no,a.currency"
                    + " FROM ord_pricing_fee_advice d JOIN ord_pricing_fee_application a ON"
                    + " a.entity_no=d.entity_no AND a.store_no=d.store_no AND"
                    + " a.application_no=d.application_no JOIN ord_industry_order_source n ON"
                    + " n.entity_no=d.entity_no AND n.store_no=d.store_no AND n.quote_no=d.quote_no"
                    + " AND n.status IN ('BOUND','ACCEPTED') AND n.deleted=0 WHERE d.entity_no=?"
                    + " AND d.store_no=? AND a.scope_no=? AND d.status='OPEN' ORDER BY d.id LIMIT"
                    + " 200",
                e,
                store,
                scope);
    }

    public List<PricingFeeEvidenceRow> originalReference(
            String e, String store, String application) {
        return rows(
                "SELECT"
                    + " n.source_system,n.source_type,n.source_no,n.source_version,n.order_no,n.sub_order_no"
                    + " FROM ord_pricing_fee_application a JOIN ord_industry_order_source n ON"
                    + " n.entity_no=a.entity_no AND n.store_no=a.store_no AND n.order_no=a.order_no"
                    + " AND n.sub_order_no=a.sub_order_no AND n.deleted=0 WHERE a.entity_no=? AND"
                    + " a.store_no=? AND a.application_no=?",
                e,
                store,
                application);
    }

    public List<PricingFeeEvidenceRow> amendmentSource(
            Reference ref, String amendment, String hash) {
        return rows(
                "SELECT n.allocations_json,a.amendment_no FROM ord_industry_order_source n JOIN"
                        + " ord_industry_amendment a ON a.tenant_no=n.tenant_no AND"
                        + " a.entity_no=n.entity_no AND a.store_no=n.store_no AND"
                        + " a.sub_order_no=n.sub_order_no WHERE n.tenant_no=? AND n.entity_no=? AND"
                        + " n.store_no=? AND n.order_no=? AND n.sub_order_no=? AND n.deleted=0 AND"
                        + " a.amendment_no=? AND a.source_hash=? AND a.status='CONFIRMED'",
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.scope().storeNo(),
                ref.orderNo(),
                ref.subOrderNo(),
                amendment,
                hash);
    }

    public List<PricingFeeEvidenceRow> orderApplications(Reference ref) {
        return rows(
                "SELECT a.*,q.result_snapshot FROM ord_pricing_fee_application a JOIN"
                    + " ord_price_quote q ON q.tenant_no=a.tenant_no AND q.entity_no=a.entity_no"
                    + " AND q.store_no=a.store_no AND q.quote_no=a.quote_no WHERE a.entity_no=? AND"
                    + " a.store_no=? AND a.order_no=? AND a.sub_order_no=? ORDER BY"
                    + " a.scope_no,a.rule_no,a.id",
                ref.scope().entityNo(),
                ref.scope().storeNo(),
                ref.orderNo(),
                ref.subOrderNo());
    }

    public List<PricingFeeEvidenceRow> confirmedReductions(Reference ref) {
        return rows(
                "SELECT l.order_item_id,l.source_component_ref,SUM(l.quantity) AS quantity FROM"
                    + " ord_industry_amendment a JOIN ord_industry_amendment_line l ON"
                    + " l.amendment_no=a.amendment_no WHERE a.tenant_no=? AND a.entity_no=? AND"
                    + " a.store_no=? AND a.order_no=? AND a.sub_order_no=? AND a.status='CONFIRMED'"
                    + " GROUP BY l.order_item_id,l.source_component_ref",
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.scope().storeNo(),
                ref.orderNo(),
                ref.subOrderNo());
    }

    public List<PricingFeeEvidenceRow> contribution(String app) {
        return rows(
                "SELECT current_goods_basis_minor,current_quantity FROM ord_pricing_fee_application"
                        + " WHERE application_no=? FOR UPDATE",
                app);
    }

    public int setContribution(String app, long remaining) {
        return db.update(
                "UPDATE ord_pricing_fee_application SET current_goods_basis_minor=? WHERE"
                        + " application_no=?",
                remaining,
                app);
    }

    public int changeGoodsBasis(String e, String store, String scope, String rule, long delta) {
        return db.update(
                "UPDATE ord_pricing_fee_scope SET"
                    + " goods_basis_minor=goods_basis_minor+?,version=version+1 WHERE entity_no=?"
                    + " AND store_no=? AND scope_no=? AND rule_no=? AND goods_basis_minor+?>=0",
                delta,
                e,
                store,
                scope,
                rule,
                delta);
    }

    public List<PricingFeeEvidenceRow> reconciliation(String e, String app, String amendment) {
        return rows(
                "SELECT source_hash FROM ord_pricing_fee_reconciliation WHERE entity_no=? AND"
                        + " application_no=? AND amendment_no=?",
                e,
                app,
                amendment);
    }

    public int recordReconciliation(
            String e,
            String store,
            String app,
            String amendment,
            String hash,
            long before,
            long after) {
        return db.update(
                "INSERT INTO"
                    + " ord_pricing_fee_reconciliation(entity_no,store_no,application_no,amendment_no,source_hash,before_goods_minor,after_goods_minor)"
                    + " VALUES(?,?,?,?,?,?,?)",
                e,
                store,
                app,
                amendment,
                hash,
                before,
                after);
    }

    public int initializeContribution(String app, long goods, BigDecimal quantity) {
        return db.update(
                "UPDATE ord_pricing_fee_application SET"
                    + " original_goods_basis_minor=?,current_goods_basis_minor=?,current_quantity=?"
                    + " WHERE application_no=?",
                goods,
                goods,
                quantity,
                app);
    }

    public int supersedeAdvice(String e, String store, String scope, String rule) {
        return db.update(
                "UPDATE ord_pricing_fee_advice SET status='SUPERSEDED' WHERE status='OPEN' AND"
                    + " application_no IN (SELECT application_no FROM ord_pricing_fee_application"
                    + " WHERE entity_no=? AND store_no=? AND scope_no=? AND rule_no=?)",
                e,
                store,
                scope,
                rule);
    }

    public int quantityContribution(String app, BigDecimal quantity) {
        return db.update(
                "UPDATE ord_pricing_fee_application SET current_quantity=? WHERE application_no=?",
                quantity,
                app);
    }
}
