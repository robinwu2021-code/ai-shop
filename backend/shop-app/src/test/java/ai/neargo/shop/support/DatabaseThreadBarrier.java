package ai.neargo.shop.support;

import java.util.Arrays;

/** Observes real JDBC execution beneath a business frame with H2, MariaDB, or MySQL drivers. */
public final class DatabaseThreadBarrier {
    private DatabaseThreadBarrier() { }

    public static boolean executing(Thread thread, String type, String method) {
        if (thread == null) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        return Arrays.stream(stack).anyMatch(f -> f.getClassName().endsWith("." + type)
                && f.getMethodName().equals(method))
                && Arrays.stream(stack).anyMatch(f -> (f.getClassName().startsWith("org.h2.")
                || f.getClassName().startsWith("org.mariadb.") || f.getClassName().startsWith("com.mysql.cj."))
                        && f.getMethodName().startsWith("execute"));
    }

    public static boolean updating(Thread thread, String type, String method) {
        return executing(thread, type, method) && Arrays.stream(thread.getStackTrace())
                .anyMatch(f -> f.getClassName().endsWith(".PreparedStatementHandler")
                        && f.getMethodName().equals("update"));
    }

    public static boolean originalRestore(Thread thread) {
        return executing(thread, "OriginalReservationSource", "lock") || posting(thread);
    }

    public static boolean posting(Thread thread) {
        return updating(thread, "StockPostingServiceImpl", "post");
    }
}
