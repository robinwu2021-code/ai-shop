-- Run as the database maintainer before deploying the resource occupation verifier.
-- Grant the application EXECUTE on this routine only. No trigger privileges are required.
CREATE PROCEDURE shop_resource_trigger_metadata(IN requested_scope VARCHAR(32))
SQL SECURITY DEFINER
READS SQL DATA
SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND requested_scope = 'RESOURCE_OCCUPATION'
  AND EVENT_OBJECT_TABLE IN (
      'mch_resource_occupation', 'mch_resource_occupation_unit',
      'mch_resource_occupation_segment', 'mch_resource_occupation_bucket',
      'mch_booking_owner', 'mch_resource_command', 'mch_booking_hold_unit', 'mch_resource_bucket'
  );
