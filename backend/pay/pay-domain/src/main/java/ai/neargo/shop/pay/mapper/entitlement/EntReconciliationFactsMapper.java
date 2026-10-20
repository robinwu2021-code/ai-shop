package ai.neargo.shop.pay.mapper.entitlement;

import java.util.Map;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only independent facts, always bound to the payment resource and caller's snapshot transaction. */
public interface EntReconciliationFactsMapper {
    @Select("""
            SELECT COALESCE(SUM(principal_delta),0) AS principal,
                   COALESCE(SUM(bonus_delta),0) AS bonus,COALESCE(MAX(account_seq),0) AS seq,
                   COUNT(*) AS entries FROM sv_voucher
            WHERE tenant_no=#{tenant} AND account_no=#{account} AND deleted=0
            """)
    Map<String,Object> vouchers(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT COALESCE(SUM(principal_minor),0) AS principal,COALESCE(SUM(bonus_minor),0) AS bonus,
                   COALESCE(SUM(held_principal_minor),0) AS held_principal,
                   COALESCE(SUM(held_bonus_minor),0) AS held_bonus FROM sv_lot
            WHERE tenant_no=#{tenant} AND account_no=#{account} AND deleted=0
            """)
    Map<String,Object> lots(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT COALESCE(SUM(e.principal_delta),0) AS principal,COALESCE(SUM(e.bonus_delta),0) AS bonus
            FROM sv_entry e JOIN sv_voucher v ON v.tenant_no=e.tenant_no AND v.voucher_no=e.voucher_no
            WHERE v.tenant_no=#{tenant} AND v.account_no=#{account} AND v.deleted=0 AND e.deleted=0
            """)
    Map<String,Object> entries(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT COALESCE(SUM(a.principal_minor),0) AS principal,COALESCE(SUM(a.bonus_minor),0) AS bonus
            FROM sv_hold_allocation a JOIN sv_lot l ON l.tenant_no=a.tenant_no AND l.lot_no=a.lot_no
            JOIN ent_use_intent u ON u.tenant_no=a.tenant_no AND u.operation_no=a.operation_no
            WHERE l.tenant_no=#{tenant} AND l.account_no=#{account} AND l.deleted=0 AND a.deleted=0
            AND u.deleted=0 AND u.status='HELD' AND u.asset_kind='STORED_VALUE'
            """)
    Map<String,Object> moneyHolds(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT p.pool,p.book,COALESCE(SUM(CASE WHEN p.side='DEBIT' THEN p.amount_minor
                   ELSE -p.amount_minor END),0) AS net
            FROM sv_posting_line p JOIN sv_voucher v ON v.tenant_no=p.tenant_no AND v.voucher_no=p.voucher_no
            WHERE v.tenant_no=#{tenant} AND v.account_no=#{account} AND v.deleted=0 AND p.deleted=0
            GROUP BY p.pool,p.book
            """)
    List<Map<String,Object>> journal(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT COALESCE(SUM(h.units),0) AS reserved FROM (
                SELECT a.units FROM ent_use_allocation a
                JOIN ent_use_intent u ON u.tenant_no=a.tenant_no AND u.operation_no=a.operation_no
                WHERE a.tenant_no=#{tenant} AND a.instance_no=#{instance} AND a.deleted=0
                AND u.deleted=0 AND u.status='HELD'
                UNION ALL
                SELECT 1 AS units FROM ent_legacy_coupon_operation p
                WHERE p.tenant_no=#{tenant} AND p.instance_no=#{instance} AND p.deleted=0
                AND p.status='HELD' AND p.ever_held=1
            ) h
            """)
    Map<String,Object> rightsHolds(@Param("tenant") String tenant,@Param("instance") String instance);

    @Select("""
            SELECT a.period_key AS period,a.service_day AS service_day_key,
                   COALESCE(SUM(CASE WHEN u.status='HELD' THEN a.units ELSE 0 END),0) AS reserved,
                   COALESCE(SUM(CASE WHEN u.status='COMMITTED' THEN a.units-a.reversed_units ELSE 0 END),0) AS used
            FROM ent_use_allocation a JOIN ent_use_intent u
            ON u.tenant_no=a.tenant_no AND u.operation_no=a.operation_no
            WHERE a.tenant_no=#{tenant} AND a.instance_no=#{instance} AND a.deleted=0 AND u.deleted=0
            GROUP BY a.period_key,a.service_day
            """)
    List<Map<String,Object>> bucketGroups(@Param("tenant") String tenant,@Param("instance") String instance);
    @Select("""
            SELECT COUNT(*) FROM sv_lot l WHERE l.tenant_no=#{tenant} AND l.account_no=#{account} AND l.deleted=0
            AND (l.principal_minor<>COALESCE((SELECT SUM(e.principal_delta) FROM sv_entry e
                 WHERE e.tenant_no=l.tenant_no AND e.lot_no=l.lot_no AND e.deleted=0),0)
              OR l.bonus_minor<>COALESCE((SELECT SUM(e.bonus_delta) FROM sv_entry e
                 WHERE e.tenant_no=l.tenant_no AND e.lot_no=l.lot_no AND e.deleted=0),0)
              OR l.held_principal_minor<>COALESCE((SELECT SUM(a.principal_minor) FROM sv_hold_allocation a
                 JOIN ent_use_intent u ON u.tenant_no=a.tenant_no AND u.operation_no=a.operation_no
                 WHERE a.tenant_no=l.tenant_no AND a.lot_no=l.lot_no AND a.deleted=0
                 AND u.deleted=0 AND u.status='HELD' AND u.asset_kind='STORED_VALUE'),0)
              OR l.held_bonus_minor<>COALESCE((SELECT SUM(a.bonus_minor) FROM sv_hold_allocation a
                 JOIN ent_use_intent u ON u.tenant_no=a.tenant_no AND u.operation_no=a.operation_no
                 WHERE a.tenant_no=l.tenant_no AND a.lot_no=l.lot_no AND a.deleted=0
                 AND u.deleted=0 AND u.status='HELD' AND u.asset_kind='STORED_VALUE'),0))
            """)
    long lotDifferences(@Param("tenant") String tenant,@Param("account") String account);

    @Select("""
            SELECT COUNT(*) FROM sv_voucher v
            WHERE v.tenant_no=#{tenant} AND v.account_no=#{account} AND v.deleted=0
            AND (v.principal_delta<>COALESCE((SELECT SUM(e.principal_delta) FROM sv_entry e
                 WHERE e.tenant_no=v.tenant_no AND e.voucher_no=v.voucher_no AND e.deleted=0),0)
              OR v.bonus_delta<>COALESCE((SELECT SUM(e.bonus_delta) FROM sv_entry e
                 WHERE e.tenant_no=v.tenant_no AND e.voucher_no=v.voucher_no AND e.deleted=0),0)
              OR EXISTS(SELECT 1 FROM sv_entry e LEFT JOIN sv_lot l
                 ON l.tenant_no=e.tenant_no AND l.lot_no=e.lot_no AND l.deleted=0
                 WHERE e.tenant_no=v.tenant_no AND e.voucher_no=v.voucher_no AND e.deleted=0
                 AND (l.account_no IS NULL OR l.account_no<>v.account_no))
              OR v.principal_delta<>COALESCE((SELECT SUM(CASE WHEN p.side='DEBIT'
                 THEN p.amount_minor ELSE -p.amount_minor END) FROM sv_posting_line p
                 WHERE p.tenant_no=v.tenant_no AND p.voucher_no=v.voucher_no AND p.deleted=0
                 AND p.pool='PRINCIPAL' AND p.book='CUSTOMER_CLAIM'),0)
              OR -v.principal_delta<>COALESCE((SELECT SUM(CASE WHEN p.side='DEBIT'
                 THEN p.amount_minor ELSE -p.amount_minor END) FROM sv_posting_line p
                 WHERE p.tenant_no=v.tenant_no AND p.voucher_no=v.voucher_no AND p.deleted=0
                 AND p.pool='PRINCIPAL' AND p.book='MERCHANT_OBLIGATION'),0)
              OR v.bonus_delta<>COALESCE((SELECT SUM(CASE WHEN p.side='DEBIT'
                 THEN p.amount_minor ELSE -p.amount_minor END) FROM sv_posting_line p
                 WHERE p.tenant_no=v.tenant_no AND p.voucher_no=v.voucher_no AND p.deleted=0
                 AND p.pool='BONUS' AND p.book='CUSTOMER_CLAIM'),0)
              OR -v.bonus_delta<>COALESCE((SELECT SUM(CASE WHEN p.side='DEBIT'
                 THEN p.amount_minor ELSE -p.amount_minor END) FROM sv_posting_line p
                 WHERE p.tenant_no=v.tenant_no AND p.voucher_no=v.voucher_no AND p.deleted=0
                 AND p.pool='BONUS' AND p.book='MERCHANT_OBLIGATION'),0))
            """)
    long voucherDifferences(@Param("tenant") String tenant,@Param("account") String account);
}
