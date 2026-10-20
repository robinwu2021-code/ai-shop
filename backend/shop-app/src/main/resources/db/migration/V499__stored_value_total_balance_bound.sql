-- Both component balances and their public total must fit the signed-long amount contract.
ALTER TABLE sv_account ADD CONSTRAINT ck_sv_account_total_balance
    CHECK (principal_minor <= 9223372036854775807 - bonus_minor);
