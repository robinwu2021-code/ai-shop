-- Message recovery audits never re-run a financial command.
ALTER TABLE pay_ent_exception_action DROP CHECK ck_exception_action_kind;
ALTER TABLE pay_ent_exception_action
 ADD CONSTRAINT ck_exception_action_kind CHECK (operation_kind IN ('RETRY_GRANT','QUERY_SOURCE','RETRY_EVENT','QUERY_EVENT'));
