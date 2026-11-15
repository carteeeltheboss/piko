package app.morphe.extension.crimera.downloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Process-local diagnostics. Contains labels and sanitized errors, never authenticated URLs. */
public final class DownloadQueueMonitor {
    public enum State { PENDING, ACTIVE, COMPLETED, FAILED, CANCELLED, SKIPPED }
    public static final class Entry {
        public final long id, created;
        public final String label;
        public State state = State.PENDING;
        public long bytes, total = -1, updated, started, finished;
        public String detail = "Waiting for earlier requests";
        private Entry(long id, String label) {
            this.id = id; this.label = label; created = updated = System.currentTimeMillis();
        }
        private Entry(Entry source) {
            id = source.id; label = source.label; created = source.created; state = source.state;
            bytes = source.bytes; total = source.total; updated = source.updated;
            started = source.started; finished = source.finished; detail = source.detail;
        }
    }
    private static final LinkedHashMap<Long, Entry> ENTRIES = new LinkedHashMap<>();
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();
    private static long next;
    private DownloadQueueMonitor() { }
    public static synchronized Entry pending(String label) {
        Entry entry = new Entry(++next, label); ENTRIES.put(entry.id, entry); trim(); return entry;
    }
    public static synchronized boolean begin(Entry entry) {
        if (entry.state == State.CANCELLED) return false;
        entry.state = State.ACTIVE; entry.started = entry.updated = System.currentTimeMillis();
        entry.detail = "Connecting / preparing destination"; CURRENT.set(entry); return true;
    }
    public static synchronized void progress(long bytes, long total) {
        Entry entry = CURRENT.get(); if (entry == null) return;
        entry.bytes = bytes; entry.total = total; entry.updated = System.currentTimeMillis();
        entry.detail = "Receiving media";
    }
    public static synchronized void detail(String text) {
        Entry entry = CURRENT.get(); if (entry != null) entry.detail = text;
    }
    public static synchronized void error(Throwable error) {
        Entry entry = CURRENT.get(); if (entry == null) return;
        String message = error.getMessage();
        if (message == null) message = "No additional detail";
        message = message.replaceAll("https?://[^\\s]+", "[URL]");
        if (message.length() > 240) message = message.substring(0, 240);
        entry.detail = error.getClass().getSimpleName() + ": " + message;
    }
    public static synchronized void finish(Entry entry, boolean success) {
        entry.state = success ? ("Already exists (skip policy)".equals(entry.detail) ? State.SKIPPED : State.COMPLETED) : State.FAILED;
        if (success && entry.state == State.COMPLETED) entry.detail = "Saved successfully";
        entry.finished = entry.updated = System.currentTimeMillis(); CURRENT.remove(); trim();
    }
    public static synchronized List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES.values()) result.add(new Entry(entry));
        return result;
    }
    public static synchronized void cancelPending() {
        for (Entry entry : ENTRIES.values()) if (entry.state == State.PENDING) {
            entry.state = State.CANCELLED; entry.detail = "Cancelled before starting";
            entry.finished = entry.updated = System.currentTimeMillis();
        }
        trim();
    }
    public static synchronized void clearFinished() {
        ENTRIES.values().removeIf(entry -> entry.state != State.PENDING && entry.state != State.ACTIVE);
    }
    private static void trim() {
        int finished = 0;
        for (Entry entry : ENTRIES.values()) if (entry.state != State.PENDING && entry.state != State.ACTIVE) finished++;
        java.util.Iterator<Entry> iterator = ENTRIES.values().iterator();
        while (finished > 200 && iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.state != State.PENDING && entry.state != State.ACTIVE) { iterator.remove(); finished--; }
        }
    }
}
