package ai.neargo.shop.pay.entitlement.policy;

/**
 * 权益次数的纯规则；实际预占/确认必须在pay事务内以条件更新或锁持久化。
 *
 * <p>此处只接受某个实例/原服务日的快照，不选择服务日、不执行状态机，也不替代数据库并发控制。
 */
public final class UsageQuota {
    private UsageQuota() {
    }

    /** 剩余次数含已预占部分，提交时同时减少剩余和预占。 */
    public record Count(int remaining, int reserved) {
        public Count {
            if (remaining < 0 || reserved < 0 || reserved > remaining) {
                throw new IllegalArgumentException("Invalid count quota snapshot");
            }
        }

        /** 只能预占尚未冻结的次数。 */
        public Count reserve(int units) {
            requirePositive(units);
            requireCapacity(units <= remaining - reserved);
            return new Count(remaining, reserved + units);
        }

        /** 只提交原操作已分配的预占，调用方负责按操作幂等。 */
        public Count commit(int units) {
            requirePositive(units);
            requireCapacity(units <= reserved);
            return new Count(remaining - units, reserved - units);
        }

        /** 释放不改变可消费总余次。 */
        public Count release(int units) {
            requirePositive(units);
            requireCapacity(units <= reserved);
            return new Count(remaining, reserved - units);
        }
    }

    /** 每日额度必须持久绑定原periodKey和serviceDay，不能跨日转移。 */
    public record Daily(int limit, int reserved, int used) {
        public Daily {
            if (limit <= 0 || reserved < 0 || used < 0 || (long) reserved + used > limit) {
                throw new IllegalArgumentException("Invalid daily quota snapshot");
            }
        }

        /** 预占同样占用当日额度，未用部分不结转。 */
        public Daily reserve(int units) {
            requirePositive(units);
            requireCapacity(units <= limit - used - reserved);
            return new Daily(limit, reserved + units, used);
        }

        /** 当日使用由冻结转已用。 */
        public Daily commit(int units) {
            requirePositive(units);
            requireCapacity(units <= reserved);
            return new Daily(limit, reserved - units, used + units);
        }

        /** 释放必须作用在原服务日快照。 */
        public Daily release(int units) {
            requirePositive(units);
            requireCapacity(units <= reserved);
            return new Daily(limit, reserved - units, used);
        }
    }

    private static void requirePositive(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Units must be positive");
        }
    }

    private static void requireCapacity(boolean available) {
        if (!available) {
            throw new IllegalStateException("Insufficient quota or held units");
        }
    }
}
