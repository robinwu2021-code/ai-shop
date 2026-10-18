package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard;
import ai.neargo.shop.pay.points.model.AccountSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.store.LifecycleStore;

import org.springframework.stereotype.Service;

@Service
public class AccountExpiryGuardImpl implements AccountExpiryGuard {
    private final LifecycleStore store;

    public AccountExpiryGuardImpl(LifecycleStore store) {
        this.store = store;
    }

    @Override
    public boolean overdue(AccountSnapshot account, long now) {
        if (account.points(Bucket.AVAILABLE) == 0
                || account.expireAt() == null
                || account.expireAt() > now) {
            return false;
        }
        return ExpiryPolicy.extend(
                        account.expireAt(),
                        account.lastActiveAt(),
                        now,
                        store.pauses(
                                account.typeCode(),
                                account.holderNo(),
                                account.lastActiveAt(),
                                now))
                <= now;
    }
}
