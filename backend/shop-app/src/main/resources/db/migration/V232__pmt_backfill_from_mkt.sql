-- 把存量券搬进新模型（P4）。**只搬一次，且可重跑**：
-- 两条都带 NOT EXISTS，重跑不会翻倍 —— 回退再前滚是这次改造里很可能发生的一步。
--
-- 老模型的三列如何落到新模型（口径一字不改，CouponModelCompatTest 守着）：
--   type=DISCOUNT → benefit_mode=PERCENT，benefit_value=discount_rate（万分比）
--   type=FULL_CUT → benefit_mode=CASH，   benefit_value=face_minor
--   threshold_minor → min_amount_minor；其余维度取新模型的默认值（等于「不限」）
--
-- ⚠️ scope_type 一律 ALL：老券的 scope_desc「仅限粮油类」**从来只是文案**，
-- 校验只看 entity_no。把它当规则搬过去会让存量券突然变严，用户手上的券会莫名其妙用不了。
-- 文案原样留在 scope_desc 里，运营端把「文案与规则不符」标出来，由人去认领。
INSERT INTO pmt_coupon (coupon_no, entity_no, funder, title, benefit_mode, benefit_value,
                        benefit_cap_minor, min_amount_minor, scope_type, scope_desc,
                        validity_mode, start_at, end_at, issue_mode, redeem_mode, times_total,
                        total_count, received_count, per_user_limit, budget_minor, status,
                        archived_at, tenant_no, created_at, updated_at, version, deleted)
SELECT c.coupon_no, c.entity_no, COALESCE(c.funder, 'MERCHANT'), c.title,
       CASE WHEN c.type = 'DISCOUNT' THEN 'PERCENT' ELSE 'CASH' END,
       CASE WHEN c.type = 'DISCOUNT' THEN COALESCE(c.discount_rate, 0)
            ELSE COALESCE(c.face_minor, 0) END,
       c.max_discount_minor, c.threshold_minor, 'ALL', c.scope_desc,
       'ABSOLUTE', c.start_at, c.end_at, 'CENTER', 'ORDER', 1,
       c.total_count, COALESCE(c.received_count, 0), COALESCE(c.per_user_limit, 1),
       c.budget_minor, COALESCE(c.status, 'ACTIVE'),
       c.archived_at, COALESCE(c.tenant_no, 'MAIN'), c.created_at, c.updated_at, 0, 0
FROM mkt_coupon c
WHERE c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM pmt_coupon p WHERE p.coupon_no = c.coupon_no);

-- 用户手上的券。**expire_at 取模板的 end_at**：老模型里用户券没有自己的有效期，
-- 到期与否一直是拿模板的 end_at 判的，这里把当时那个判断固化下来，语义不变。
INSERT INTO pmt_user_coupon (user_coupon_no, coupon_no, user_no, entity_no, status, times_used,
                             order_no, used_at, received_at, expire_at,
                             tenant_no, created_at, updated_at, version, deleted)
SELECT u.user_coupon_no, u.coupon_no, u.user_no, c.entity_no, COALESCE(u.status, 'UNUSED'),
       CASE WHEN u.status = 'USED' THEN 1 ELSE 0 END,
       u.order_no, u.used_at, COALESCE(u.received_at, 0), COALESCE(c.end_at, 0),
       COALESCE(u.tenant_no, 'MAIN'), u.created_at, u.updated_at, 0, 0
FROM mkt_user_coupon u
JOIN mkt_coupon c ON c.coupon_no = u.coupon_no AND c.deleted = 0
WHERE u.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM pmt_user_coupon p WHERE p.user_coupon_no = u.user_coupon_no);
