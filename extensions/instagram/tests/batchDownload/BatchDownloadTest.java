import app.morphe.extension.instagram.patches.batchDownload.SelectionManager;
import app.morphe.extension.instagram.patches.batchDownload.BatchDownloadQueue;
import java.util.*;
import java.util.concurrent.Executor;

public final class BatchDownloadTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class Deferred implements Executor {
        Runnable task;
        public void execute(Runnable task) { this.task = task; }
        void run() { task.run(); }
    }
    public static void main(String[] args) {
        SelectionManager<String> selection = new SelectionManager<>();
        check(!selection.toggle("0", "invalid"));
        check(selection.toggle("123", "post"));
        // A recycled cell and a new page must consult the stable ID, not its new position.
        check(selection.contains("123")); check(!selection.contains("456"));
        check(selection.toggle("456", "new page"));
        Map<String, String> snapshot = selection.snapshot(); selection.clear();
        check(snapshot.size() == 2 && selection.size() == 0);
        check(selection.toggle("123", "rebound post"));
        check(!selection.toggle("123", "rebound post")); check(selection.size() == 0);
        SelectionManager<String> profile = new SelectionManager<>();
        selection.select("offscreen", "loaded but never bound");
        selection.select("offscreen", "same post pinned twice");
        selection.select("new-page", "loaded after scrolling");
        selection.select("0", "unavailable");
        check(selection.size() == 2 && profile.size() == 0);
        check(!selection.toggle("offscreen", "manual deselect"));
        check(selection.size() == 1); selection.clear(); check(selection.size() == 0);
        Deferred executor = new Deferred(); List<String> calls = new ArrayList<>();
        BatchDownloadQueue<String> queue = new BatchDownloadQueue<>(executor, (item, cancelled) -> {
            calls.add(item); if (item.equals("bad")) throw new IllegalStateException("Unavailable"); return true;
        });
        Map<String, String> posts = new LinkedHashMap<>();
        posts.put("1", "photo"); posts.put("1", "photo"); posts.put("2", "bad"); posts.put("3", "mixed carousel");
        List<String> progress = new ArrayList<>();
        check(queue.start(posts, (done, failed, total, active, running) -> progress.add(done+":"+failed+":"+running)));
        check(queue.states().size() == 3 && queue.states().values().stream().allMatch("PENDING"::equals));
        check(!queue.start(posts, (a,b,c,d,e) -> {}));
        executor.run(); check(calls.equals(Arrays.asList("photo", "bad", "mixed carousel")));
        check("FAILED".equals(queue.states().get("2")) && "COMPLETED".equals(queue.states().get("3")));
        check(queue.failedItems().size() == 1 && queue.failedItems().containsKey("2"));
        check(progress.get(progress.size()-1).equals("3:1:false")); check(!queue.isRunning());
        calls.clear(); check(queue.start(posts, (a,b,c,d,e) -> { throw new RuntimeException(); }));
        queue.cancel(); executor.run(); check(calls.isEmpty() && !queue.isRunning());
        check(queue.states().values().stream().allMatch("CANCELLED"::equals));
        final BatchDownloadQueue<String>[] holder = new BatchDownloadQueue[1];
        calls.clear(); holder[0] = new BatchDownloadQueue<>(executor, (item, cancelled) -> {
            calls.add(item); holder[0].cancel(); return true;
        });
        check(holder[0].start(posts, (a,b,c,d,e) -> {})); executor.run();
        check(calls.equals(Collections.singletonList("photo"))); check(!holder[0].isRunning());
        System.out.println("PASS: stable selection, rebind/page IDs, snapshots, deduplication, failure isolation, progress, cancellation, observer isolation");
    }
}
