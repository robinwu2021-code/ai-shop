-- Run against each quiesced Pay / member-marketing ledger on MySQL, using the same tenant.
-- Defaults to MAIN. Set @tenant before sourcing this file to narrow to another tenant.
SET @tenant = COALESCE(@tenant, 'MAIN');
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT @tenant AS requested_tenant, COUNT(*) AS accounts FROM sv_account WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0;
SELECT account_no,user_no,entity_no,currency,principal_minor,bonus_minor,held_principal_minor,held_bonus_minor,account_seq,status
FROM sv_account WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY account_no;
SELECT recharge_no,request_id,reservation_no,user_no,entity_no,store_no,status,currency,principal_minor,bonus_minor,
       payload_hash,receipt_digest,SHA2(CAST(source_snapshot AS CHAR),256) AS source_hash,
       SHA2(CAST(received_decision_snapshot AS CHAR),256) AS decision_hash
FROM sv_recharge WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY recharge_no;
SELECT lot_no,account_no,recharge_no,activity_version_no,credited_at,principal_minor,bonus_minor,held_principal_minor,held_bonus_minor,
       SHA2(CAST(scope_snapshot AS CHAR),256) AS scope_hash
FROM sv_lot WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY lot_no;
SELECT operation_no,user_no,entity_no,store_no,status,source_key,active_source_key,settled_source_key,source_decision_no,usage_no,expires_at,payload_hash,
       SHA2(CAST(source_snapshot AS CHAR),256) AS source_hash,
       SHA2(CAST(command_snapshot AS CHAR),256) AS command_hash
FROM ent_use_intent WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 AND asset_kind='STORED_VALUE' ORDER BY operation_no;
SELECT usage_no,operation_no,user_no,status,entity_no,store_no,source_order_no,currency,amount_minor,occurred_at,
       SHA2(CAST(items_snapshot AS CHAR),256) AS items_hash
FROM ent_usage WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 AND asset_kind='STORED_VALUE' ORDER BY usage_no;
SELECT operation_no,lot_no,principal_minor,bonus_minor,SHA2(CAST(items_allocation AS CHAR),256) AS allocation_hash
FROM sv_hold_allocation WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY operation_no,lot_no;
SELECT account_no,account_seq,voucher_no,biz_type,biz_no,payload_hash,usage_no,origin_voucher_no,
       source_decision_no,after_sale_no,principal_delta,bonus_delta,after_principal_minor,after_bonus_minor
FROM sv_voucher WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY account_no,account_seq;
SELECT voucher_no,lot_no,principal_delta,bonus_delta FROM sv_entry
WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY voucher_no,lot_no;
SELECT voucher_no,line_no,book,pool,side,amount_minor FROM sv_posting_line
WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY voucher_no,line_no;
SELECT record_no,voucher_no,account_no,user_no,entity_no,account_seq,store_no,store_name,type,business_no,title,currency,
       signed_amount_minor,balance_after_minor,occurred_at
FROM sv_statement WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY occurred_at,record_no;
SELECT clearing_no,original_clearing_no,issuer_no,issuer_version_no,release_no,recharge_store_no,redemption_store_no,usage_no,allocation_no,lot_no,payer_entity_no,payee_entity_no,
       currency,entry_type,principal_used_minor,bonus_used_minor,payable_minor,issuer_borne_minor,
       decision_no,source_event_key,content_hash
FROM sv_clearing_entry WHERE tenant_no=CAST(@tenant AS BINARY) AND deleted=0 ORDER BY clearing_no;
COMMIT;
