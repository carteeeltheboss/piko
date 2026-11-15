import app.morphe.extension.crimera.downloader.DownloadQueueMonitor;
import java.net.SocketTimeoutException;

public final class DownloadQueueMonitorTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        DownloadQueueMonitor.clearFinished();
        var active = DownloadQueueMonitor.pending("video.mp4");
        var pending = DownloadQueueMonitor.pending("photo.jpg");
        check(DownloadQueueMonitor.snapshot().size() == 2);
        check(DownloadQueueMonitor.begin(active));
        DownloadQueueMonitor.progress(512, 1024);
        var copy = DownloadQueueMonitor.snapshot().get(0);
        check(copy.bytes == 512 && copy.total == 1024 && copy.state == DownloadQueueMonitor.State.ACTIVE);
        copy.bytes = 0;
        check(DownloadQueueMonitor.snapshot().get(0).bytes == 512);
        DownloadQueueMonitor.cancelPending();
        check(!DownloadQueueMonitor.begin(pending));
        check(DownloadQueueMonitor.snapshot().get(0).state == DownloadQueueMonitor.State.ACTIVE);
        DownloadQueueMonitor.error(new SocketTimeoutException("Read timed out https://example.test/media?token=secret"));
        DownloadQueueMonitor.finish(active, false);
        var failure = DownloadQueueMonitor.snapshot().get(0);
        check(failure.state == DownloadQueueMonitor.State.FAILED);
        check(failure.detail.contains("SocketTimeoutException") && !failure.detail.contains("secret"));
        DownloadQueueMonitor.clearFinished(); check(DownloadQueueMonitor.snapshot().isEmpty());
        var skip = DownloadQueueMonitor.pending("existing.jpg"); DownloadQueueMonitor.begin(skip);
        DownloadQueueMonitor.detail("Already exists (skip policy)"); DownloadQueueMonitor.finish(skip, true);
        check(DownloadQueueMonitor.snapshot().get(0).state == DownloadQueueMonitor.State.SKIPPED);
        var waiting = DownloadQueueMonitor.pending("keep-pending");
        for (int i = 0; i < 220; i++) {
            var entry = DownloadQueueMonitor.pending("file"+i); DownloadQueueMonitor.begin(entry); DownloadQueueMonitor.finish(entry, true);
        }
        check(DownloadQueueMonitor.snapshot().size() == 201);
        DownloadQueueMonitor.clearFinished();
        check(DownloadQueueMonitor.snapshot().size() == 1 && DownloadQueueMonitor.snapshot().get(0).id == waiting.id);
        DownloadQueueMonitor.cancelPending(); DownloadQueueMonitor.clearFinished();
        System.out.println("PASS: queue lifecycle, progress, snapshot isolation, pending-only cancellation, timeout redaction, skip policy, bounded history and pending retention");
    }
}
