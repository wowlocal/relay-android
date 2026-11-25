package org.telegram.relay.calls;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The call controllers' thread: Android's main thread; plugin requests on a small background pool. */
public final class RelayCallScheduler implements org.telegram.relay.mattermost.calls.RelayCallScheduler {
    public static final RelayCallScheduler INSTANCE = new RelayCallScheduler();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "RelayCalls-io");
        t.setDaemon(true);
        return t;
    });

    private RelayCallScheduler() {
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public Cancellable schedule(long delayMs, Runnable action) {
        Runnable r = action::run;
        main.postDelayed(r, Math.max(0, delayMs));
        return () -> main.removeCallbacks(r);
    }

    @Override
    public void post(Runnable action) {
        main.post(action);
    }

    @Override
    public void io(Runnable task) {
        io.execute(task);
    }

    public static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
