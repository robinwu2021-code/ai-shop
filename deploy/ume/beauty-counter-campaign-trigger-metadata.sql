-- Install on the application schema with a maintenance identity that can read the trigger definitions.
CREATE PROCEDURE shop_counter_campaign_trigger_metadata(IN requested_scope VARCHAR(32))
SQL SECURITY DEFINER
READS SQL DATA
SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND requested_scope = 'COUNTER_CAMPAIGN'
  AND EVENT_OBJECT_TABLE IN ('mkt_campaign', 'pmt_activity', 'pmt_activity_version',
      'pmt_activity_goods', 'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods');
