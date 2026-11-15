package app.morphe.extension.instagram.patches.batchDownload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** One batch at a time. Cancellation finishes the active file and skips remaining work. */
public final class BatchDownloadQueue<T> {
    public interface Worker<T> { boolean download(T item, BooleanSupplier cancelled) throws Exception; }
    public interface Listener { void changed(int done, int failed, int total, String active, boolean running); }
    private final Executor executor;
    private final Worker<T> worker;
    private volatile boolean cancelled;
    private boolean running;
    private final LinkedHashMap<String, String> states = new LinkedHashMap<>();
    private Map<String, T> failures = new LinkedHashMap<>();

    public BatchDownloadQueue(Executor executor, Worker<T> worker) {
        this.executor = executor;
        this.worker = worker;
    }
    public synchronized boolean start(Map<String, T> items, Listener listener) {
        if (running || items.isEmpty()) return false;
        Map<String, T> batch = new LinkedHashMap<>(items);
        running = true;
        cancelled = false;
        failures.clear();
        states.clear();
        for (String id : batch.keySet()) states.put(id, "PENDING");
        try { executor.execute(() -> run(batch, listener)); }
        catch (RuntimeException e) { running = false; throw e; }
        return true;
    }
    public synchronized Map<String, String> states() { return new LinkedHashMap<>(states); }
    public synchronized void cancel() {
        cancelled = true;
        states.replaceAll((id, state) -> "PENDING".equals(state) ? "CANCELLED" : state);
    }
    public synchronized boolean isRunning() { return running; }
    public synchronized Map<String, T> failedItems() { return new LinkedHashMap<>(failures); }
    private void report(Listener listener, int done, int failed, int total, String active, boolean busy) {
        try { listener.changed(done, failed, total, active, busy); }
        catch (RuntimeException ignored) { /* UI observers cannot abort the worker. */ }
    }
    private void run(Map<String, T> items, Listener listener) {
        int done = 0, failed = 0;
        try {
            for (Map.Entry<String, T> entry : items.entrySet()) {
                synchronized (this) {
                    if (cancelled) break;
                    states.put(entry.getKey(), "ACTIVE");
                }
                report(listener, done, failed, items.size(), entry.getKey(), true);
                boolean success;
                try { success = worker.download(entry.getValue(), () -> cancelled); }
                catch (Exception | LinkageError e) { success = false; }
                if (!success) {
                    synchronized (this) { failures.put(entry.getKey(), entry.getValue()); }
                    failed++;
                }
                synchronized (this) { states.put(entry.getKey(), success ? "COMPLETED" : "FAILED"); }
                done++;
                report(listener, done, failed, items.size(), null, true);
            }
        } finally {
            synchronized (this) {
                running = false;
                states.replaceAll((id, state) -> "PENDING".equals(state) ? "CANCELLED" : state);
            }
            report(listener, done, failed, items.size(), null, false);
        }
    }
}
