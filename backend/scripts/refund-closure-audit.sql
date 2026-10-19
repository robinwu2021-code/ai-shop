-- Read-only MySQL 8+ evidence export after V476/V477, NOT a repair script.
-- Replace the tenant and id cursor for each authorized batch; export the last id and continue.
-- A missing legacy execution/scope/bill is UNKNOWN evidence, not proof of an invalid refund.
-- refunded_minor/refunded_qty retain their historical business meaning, NOT actual cash/goods closure.
WITH execution_facts AS (
    SELECT after_sale_no, execution_no, closure_version, closure_status, status AS financial_state,
           CASE WHEN status='SUCCEEDED' THEN amount_minor ELSE NULL END AS confirmed_cash_minor,
           resource_status
      FROM ord_refund_execution WHERE deleted=0 AND tenant_no='MAIN'
    UNION ALL
    SELECT e.after_sale_no, CONCAT('PRF-',e.after_sale_no), e.closure_version,
           CASE WHEN e.closure_version=1 THEN 'LEGACY'
                WHEN e.state='COMMITTED' AND a.status='REFUNDED' THEN 'DONE' ELSE 'PENDING' END,
           e.state,
           CASE WHEN e.state IN ('CASH_CONFIRMED','COMMITTED') THEN e.net_cash_minor ELSE NULL END,
           'MANAGED_SEPARATELY'
      FROM ord_points_refund_execution e
      JOIN ord_after_sale a ON a.after_sale_no=e.after_sale_no AND a.deleted=0
     WHERE e.deleted=0 AND e.tenant_no='MAIN'
), quantities AS (
    SELECT sub_order_no, COUNT(*) AS item_rows, SUM(qty) AS ordered_qty,
           SUM(refunded_qty) AS historical_refunded_qty,
           SUM(CASE WHEN refunded_qty IS NULL OR refunded_qty<0 OR refunded_qty>qty THEN 1 ELSE 0 END) AS suspect_rows
      FROM ord_item WHERE deleted=0 AND tenant_no='MAIN' GROUP BY sub_order_no
), obligations AS (
    SELECT execution_no,
           SUM(CASE WHEN kind='COUPON' THEN 1 ELSE 0 END) AS benefit_rows,
           SUM(CASE WHEN kind='COUPON' AND state NOT IN ('SUCCEEDED','NOT_REQUIRED') THEN 1 ELSE 0 END) AS benefit_pending,
           SUM(CASE WHEN kind='STOCK' AND state NOT IN ('SUCCEEDED','NOT_REQUIRED') THEN 1 ELSE 0 END) AS stock_pending
      FROM ord_refund_obligation GROUP BY execution_no
), bills AS (
    SELECT sub_order_no, COUNT(*) AS bill_rows, GROUP_CONCAT(DISTINCT status ORDER BY status) AS bill_states
      FROM stl_bill WHERE deleted=0 AND tenant_no='MAIN' GROUP BY sub_order_no
)
SELECT a.id AS next_cursor, a.after_sale_no, a.sub_order_no, a.status AS after_sale_status,
       s.status AS order_status, s.refunded_minor AS historical_business_refunded_minor,
       e.execution_no, e.closure_version, e.closure_status, e.financial_state, e.confirmed_cash_minor,
       e.resource_status, o.benefit_rows, o.benefit_pending, o.stock_pending,
       q.item_rows, q.ordered_qty, q.historical_refunded_qty, q.suspect_rows,
       CASE WHEN c.order_no IS NULL THEN 'UNKNOWN' ELSE 'FROZEN' END AS coupon_scope_evidence,
       b.bill_rows, b.bill_states,
       CASE WHEN e.execution_no IS NULL THEN 'LEGACY_EXECUTION_EVIDENCE_REQUIRED'
            WHEN q.suspect_rows>0 THEN 'QUANTITY_REQUIRES_REVIEW'
            WHEN a.status='REFUNDED' AND e.closure_version=2
                 AND (e.confirmed_cash_minor IS NULL OR e.closure_status<>'DONE' OR COALESCE(o.benefit_rows,0)=0 OR o.benefit_pending>0)
                 THEN 'TERMINAL_PROOF_REQUIRES_REVIEW'
            WHEN e.closure_status='PENDING' THEN 'CLOSURE_PENDING'
            WHEN e.closure_version=1 THEN 'LEGACY_PROTOCOL'
            ELSE 'NO_LOCAL_CLOSURE_DIFFERENCE' END AS review_reason
  FROM ord_after_sale a
  JOIN ord_sub_order s ON s.sub_order_no=a.sub_order_no AND s.deleted=0
  LEFT JOIN execution_facts e ON e.after_sale_no=a.after_sale_no
  LEFT JOIN quantities q ON q.sub_order_no=a.sub_order_no
  LEFT JOIN obligations o ON o.execution_no=e.execution_no
  LEFT JOIN ord_refund_coupon_scope c ON c.order_no=a.order_no
  LEFT JOIN bills b ON b.sub_order_no=a.sub_order_no
 WHERE a.deleted=0 AND a.tenant_no='MAIN' AND a.id>0
 ORDER BY a.id LIMIT 1000;
