-- MySQL filters INFORMATION_SCHEMA trigger rows using the session context even
-- through a DEFINER view. This no-argument routine changes that context only for
-- a read of the two retention tables. DATABASE() is the routine's defining schema,
-- including when a caller uses another default schema and qualifies this CALL.
CREATE PROCEDURE inv_execution_retention_trigger_metadata()
    READS SQL DATA
    SQL SECURITY DEFINER
SELECT TRIGGER_SCHEMA, TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE,
       ACTION_TIMING, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND EVENT_OBJECT_TABLE IN ('inv_execution_retention_claim', 'inv_execution_retention');
