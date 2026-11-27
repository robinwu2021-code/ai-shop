-- Independent check on either side of a cutover. A healthy tenant returns no rows.
SET @tenant = COALESCE(@tenant, 'MAIN');
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

WITH accounts AS (
    SELECT account_no,account_seq,principal_minor,bonus_minor,held_principal_minor,held_bonus_minor
    FROM sv_account WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0
), lots AS (
    SELECT account_no,SUM(principal_minor) AS principal,SUM(bonus_minor) AS bonus,
           SUM(held_principal_minor) AS held_principal,SUM(held_bonus_minor) AS held_bonus
    FROM sv_lot WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 GROUP BY account_no
), vouchers AS (
    SELECT voucher_no,account_no,account_seq,principal_delta,bonus_delta,after_principal_minor,after_bonus_minor FROM sv_voucher
    WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0
), sequence_check AS (
    SELECT v.*, ROW_NUMBER() OVER ledger AS expected_seq,
           LAG(after_principal_minor,1,0) OVER ledger AS prior_principal,
           LAG(after_bonus_minor,1,0) OVER ledger AS prior_bonus
    FROM vouchers v WINDOW ledger AS (PARTITION BY account_no ORDER BY account_seq)
), entries AS (
    SELECT voucher_no,SUM(principal_delta) AS principal,SUM(bonus_delta) AS bonus
    FROM sv_entry WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 GROUP BY voucher_no
), pools AS (
    SELECT voucher_no,'PRINCIPAL' AS pool,principal_delta AS delta FROM vouchers
    UNION ALL
    SELECT voucher_no,'BONUS',bonus_delta FROM vouchers
), books AS (
    SELECT voucher_no,pool,
           SUM(CASE WHEN book='CUSTOMER_CLAIM' THEN IF(side='DEBIT',amount_minor,-amount_minor) ELSE 0 END) AS claim,
           SUM(CASE WHEN book='MERCHANT_OBLIGATION' THEN IF(side='CREDIT',amount_minor,-amount_minor) ELSE 0 END) AS obligation
    FROM sv_posting_line WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 GROUP BY voucher_no,pool
)
SELECT 'ACCOUNT_LOTS' AS difference,a.account_no AS business_no,'' AS pool
FROM accounts a LEFT JOIN lots l USING(account_no)
WHERE a.principal_minor<>COALESCE(l.principal,0) OR a.bonus_minor<>COALESCE(l.bonus,0)
   OR a.held_principal_minor<>COALESCE(l.held_principal,0) OR a.held_bonus_minor<>COALESCE(l.held_bonus,0)
UNION ALL
SELECT 'LOT_WITHOUT_ACCOUNT',l.account_no,'' FROM lots l LEFT JOIN accounts a USING(account_no)
WHERE a.account_no IS NULL
UNION ALL
SELECT 'VOUCHER_SEQUENCE',v.voucher_no,'' FROM sequence_check v
WHERE v.account_seq<>v.expected_seq OR v.after_principal_minor<>v.prior_principal+v.principal_delta
   OR v.after_bonus_minor<>v.prior_bonus+v.bonus_delta
UNION ALL
SELECT 'VOUCHER_WITHOUT_ACCOUNT',v.voucher_no,'' FROM vouchers v LEFT JOIN accounts a USING(account_no)
WHERE a.account_no IS NULL OR v.account_seq>a.account_seq
UNION ALL
SELECT 'ACCOUNT_CHECKPOINT',a.account_no,'' FROM accounts a LEFT JOIN vouchers v USING(account_no,account_seq)
WHERE (a.account_seq<>0 AND v.voucher_no IS NULL)
   OR a.principal_minor<>COALESCE(v.after_principal_minor,0) OR a.bonus_minor<>COALESCE(v.after_bonus_minor,0)
UNION ALL
SELECT 'LOT_ENTRY',v.voucher_no,'' FROM vouchers v LEFT JOIN entries e USING(voucher_no)
WHERE e.voucher_no IS NULL OR e.principal<>v.principal_delta OR e.bonus<>v.bonus_delta
UNION ALL
SELECT 'BOOK_DELTA',p.voucher_no,p.pool FROM pools p LEFT JOIN books b USING(voucher_no,pool)
WHERE COALESCE(b.claim,0)<>p.delta OR COALESCE(b.obligation,0)<>p.delta
UNION ALL
SELECT 'ENTRY_WITHOUT_VOUCHER',e.voucher_no,'' FROM entries e LEFT JOIN vouchers v USING(voucher_no)
WHERE v.voucher_no IS NULL
UNION ALL
SELECT 'POSTING_WITHOUT_VOUCHER',b.voucher_no,b.pool FROM books b LEFT JOIN vouchers v USING(voucher_no)
WHERE v.voucher_no IS NULL
ORDER BY 1,2,3;
COMMIT;
