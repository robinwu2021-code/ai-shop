package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.logistics.entity.LgsKd100SubAttempt;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.Kd100SubAttemptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/** Reserves one of four monthly /poll attempts before making an external request. */
@Component
public class Kd100AttemptStore {
    private static final int MONTHLY_LIMIT = 4;
    private final Kd100SubAttemptMapper mapper;

    public Kd100AttemptStore(Kd100SubAttemptMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<LgsKd100SubAttempt> reserveAttempt(String accountRef, String companyCode,
            String waybillNo, String monthKey, String shipmentNo, String identityKey) {
        for (int slot = 1; slot <= MONTHLY_LIMIT; slot++) {
            Long used = mapper.selectCount(Wrappers.<LgsKd100SubAttempt>query()
                    .eq("account_ref", accountRef).eq("company_code", companyCode)
                    .eq("waybill_no", waybillNo).eq("month_key", monthKey));
            if (used != null && used >= MONTHLY_LIMIT) return Optional.empty();
            LgsKd100SubAttempt attempt = new LgsKd100SubAttempt();
            attempt.setAccountRef(accountRef);
            attempt.setCompanyCode(companyCode);
            attempt.setWaybillNo(waybillNo);
            attempt.setMonthKey(monthKey);
            attempt.setAttemptNo((used == null ? 0 : used.intValue()) + 1);
            attempt.setShipmentNo(shipmentNo);
            attempt.setIdentityKey(identityKey);
            attempt.setResult("RESERVED");
            attempt.setCreatedAt(LocalDateTime.now());
            attempt.setUpdatedAt(attempt.getCreatedAt());
            try {
                mapper.insert(attempt);
                return Optional.of(attempt);
            } catch (DuplicateKeyException occupied) {
                // Another request reserved this slot; count again and try the next one.
            }
        }
        return Optional.empty();
    }

    public void markResult(long attemptId, String result, String resultCode, String callbackId) {
        mapper.update(null, Wrappers.<LgsKd100SubAttempt>update().eq("id", attemptId)
                .set("result", result).set("result_code", resultCode)
                .set("callback_id", callbackId).set("updated_at", LocalDateTime.now()));
    }

    public void markTerminal(long attemptId, long terminatedAt) {
        mapper.update(null, Wrappers.<LgsKd100SubAttempt>update().eq("id", attemptId)
                .set("terminated_at", terminatedAt).set("updated_at", LocalDateTime.now()));
    }
}
