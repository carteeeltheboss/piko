package app.morphe.extension.instagram.patches.batchDownload;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import app.morphe.extension.crimera.downloader.DownloadQueueMonitor;
import app.morphe.extension.crimera.downloader.MediaDownloader;

/** Live process-local queue; callbacks are removed as soon as the dialog closes. */
public final class DownloadQueueDialog {
    private DownloadQueueDialog() { }
    public static void show(Context context) {
        MediaDownloader.enableInstagramQueue();
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView summary = new TextView(context); summary.setTextSize(15); content.addView(summary);
        ScrollView scroll = new ScrollView(context);
        TextView rows = new TextView(context); rows.setTextSize(13); rows.setTextIsSelectable(true);
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (360 * context.getResources().getDisplayMetrics().density)));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Download Queue").setView(content)
                .setPositiveButton("Close", null)
                .setNegativeButton("Cancel pending", null)
                .setNeutralButton("Clear file history", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            public void run() {
                if (!dialog.isShowing()) return;
                List<DownloadQueueMonitor.Entry> files = DownloadQueueMonitor.snapshot();
                files.sort((a, b) -> {
                    int pa = a.state == DownloadQueueMonitor.State.ACTIVE ? 0 : a.state == DownloadQueueMonitor.State.PENDING ? 1 : 2;
                    int pb = b.state == DownloadQueueMonitor.State.ACTIVE ? 0 : b.state == DownloadQueueMonitor.State.PENDING ? 1 : 2;
                    return pa != pb ? Integer.compare(pa, pb) : pa == 1 ? Long.compare(a.id, b.id) : Long.compare(b.id, a.id);
                });
                int pending = 0, active = 0, failed = 0, complete = 0;
                StringBuilder text = new StringBuilder();
                Map<String, String> posts = BatchDownloadManager.queueStates();
                if (!files.isEmpty()) text.append("FILES (manual and batch)\n");
                for (DownloadQueueMonitor.Entry file : files) {
                    switch (file.state) {
                        case PENDING: pending++; break;
                        case ACTIVE: active++; break;
                        case FAILED: failed++; break;
                        case COMPLETED: case SKIPPED: complete++; break;
                        default: break;
                    }
                    text.append('#').append(file.id).append(' ').append(file.state).append("  ").append(file.label).append('\n');
                    if (file.bytes > 0 || file.state == DownloadQueueMonitor.State.ACTIVE) {
                        text.append(String.format(Locale.US, "%.2f MB", file.bytes / 1048576.0));
                        if (file.total > 0) text.append(String.format(Locale.US, " / %.2f MB (%d%%)", file.total / 1048576.0, file.bytes * 100 / file.total));
                        if (file.state == DownloadQueueMonitor.State.ACTIVE) text.append("; last update ").append((System.currentTimeMillis()-file.updated)/1000).append("s ago");
                        text.append('\n');
                    }
                    text.append(file.detail).append("\n\n");
                }
                if (!posts.isEmpty()) {
                    text.append("LATEST BATCH POSTS\n");
                    for (Map.Entry<String, String> post : posts.entrySet()) text.append(post.getValue()).append("  ").append(post.getKey()).append('\n');
                }
                summary.setText("Files: " + pending + " pending · " + active + " active · " + complete + " completed · " + failed + " failed\nOne file at a time. Active files finish when pending work is cancelled.\n");
                rows.setText(text.length() == 0 ? "No downloads recorded in this app session." : text.toString());
                handler.postDelayed(this, 1000);
            }
        };
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refresh));
        content.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(android.view.View v) { }
            public void onViewDetachedFromWindow(android.view.View v) { handler.removeCallbacks(refresh); }
        });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> BatchDownloadManager.cancelDownloads());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> DownloadQueueMonitor.clearFinished());
            handler.post(refresh);
        });
        dialog.show();
    }
}
