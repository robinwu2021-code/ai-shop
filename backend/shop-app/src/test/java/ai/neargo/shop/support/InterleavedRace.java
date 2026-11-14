package ai.neargo.shop.support;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把一次「先查后改」的竞态<b>确定地</b>摆出来：主线程停在业务方法里的某一点，
 * 另一个线程把同一件事完整做一遍，主线程再接着做。
 *
 * <p>为什么不用一堆线程赌时序：赌赢了是绿、赌输了也是绿，修复前那条用例根本红不起来。
 * 这里另一个线程要么<b>做完</b>（修复前：没有锁挡它），要么<b>被行锁挡住</b>（修复后），
 * 两种都等得到 —— 挡住时主线程马上放行、提交，H2 的锁等待不会超时。
 */
public final class InterleavedRace {

    private InterleavedRace() {
    }

    /** 当前线程的调用栈里有没有这一帧 —— 只在被测方法里触发，别的路径上同一个钩子不动 */
    public static boolean inFrame(String classSuffix, String method) {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(f -> f.getClassName().endsWith(classSuffix) && f.getMethodName().equals(method));
    }

    /**
     * 在另一个线程里跑 {@code other}，直到它跑完或者停在数据库锁上。
     *
     * @return 另一个线程（调用方在主线程提交之后 join 它，拿结果）
     */
    public static Racer runAlongside(Runnable other) {
        Racer r = new Racer(other);
        r.thread.start();
        long deadline = System.currentTimeMillis() + 5_000;
        int parked = 0;
        while (r.thread.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.State st = r.thread.getState();
            boolean waiting = st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING
                    || st == Thread.State.BLOCKED;
            boolean inDb = Arrays.stream(r.thread.getStackTrace())
                    .anyMatch(f -> f.getClassName().startsWith("org.h2.")
                            || f.getClassName().startsWith("org.mariadb.")
                            || f.getClassName().startsWith("com.mysql.cj."));
            parked = waiting && inDb ? parked + 1 : 0;
            if (parked >= 3) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return r;
    }

    public static final class Racer {
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Racer(Runnable body) {
            this.thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "interleaved-race");
        }

        /** 等它结束；返回它抛的异常（没抛就是 null） */
        public Throwable join() throws InterruptedException {
            thread.join(10_000);
            return failure.get();
        }
    }
}
