-- Run as the DB maintainer against the application schema before enabling embedded beauty.
-- The application's only additional grant is EXECUTE on this read-only routine.
CREATE PROCEDURE shop_runtime_trigger_metadata(IN requested_scope VARCHAR(32))
SQL SECURITY DEFINER
READS SQL DATA
SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND (
      (requested_scope = 'SERVICE_EXECUTION' AND EVENT_OBJECT_TABLE IN
          ('ord_service_execution', 'ord_service_execution_unit', 'ord_service_execution_action'))
      OR (requested_scope = 'STOCK_RETENTION' AND EVENT_OBJECT_TABLE = 'prd_execution_retention')
      OR (requested_scope = 'BEAUTY_BASE' AND LEFT(EVENT_OBJECT_TABLE, 4) = 'bty_'
          AND EVENT_OBJECT_TABLE NOT IN ('bty_service_execution_fence', 'bty_service_execution_stock_intent'))
      OR (requested_scope = 'BEAUTY_EXECUTION' AND EVENT_OBJECT_TABLE = 'bty_service_execution_fence')
      OR (requested_scope = 'BEAUTY_STOCK' AND EVENT_OBJECT_TABLE = 'bty_service_execution_stock_intent')
  );
