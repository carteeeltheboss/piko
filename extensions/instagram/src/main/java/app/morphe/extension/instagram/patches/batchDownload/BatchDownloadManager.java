package app.morphe.extension.instagram.patches.batchDownload;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import app.morphe.extension.crimera.downloader.MediaDownloader;
import app.morphe.extension.crimera.downloader.StorageUtils;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import com.instagram.common.session.UserSession;

/** Hooks run on the UI thread. Native scrolling and pagination remain untouched. */
public final class BatchDownloadManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, Screen> SCREENS = new WeakHashMap<>();
    private static final Map<View, Cell> CELLS = new WeakHashMap<>();
    private static final Map<View, Object> BOUND = new WeakHashMap<>();
    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        public void onViewAttachedToWindow(View view) { render(view, BOUND.get(view)); }
        public void onViewDetachedFromWindow(View view) {
            Cell cell = CELLS.remove(view);
            if (cell != null) view.getOverlay().remove(cell);
        }
    };
    private static BatchDownloadQueue<MediaData> queue;

    private static final class Screen {
        final SelectionManager<MediaData> selection = new SelectionManager<>();
        final WeakReference<View> root;
        final UserSession session;
        final LinearLayout bar;
        final Button mode, download, cancel, retry;
        final TextView count;
        boolean selecting;
        Screen(View root, UserSession session, LinearLayout bar, Button mode, Button download,
               Button cancel, Button retry, TextView count) {
            this.root = new WeakReference<>(root); this.session = session; this.bar = bar;
            this.mode = mode; this.download = download; this.cancel = cancel; this.retry = retry; this.count = count;
        }
    }
    private static final class Cell extends android.graphics.drawable.Drawable {
        final WeakReference<View> view;
        final WeakReference<Screen> screen;
        final String id;
        final android.graphics.Paint paint = new android.graphics.Paint(3);
        Cell(View view, Screen screen, String id) {
            this.view = new WeakReference<>(view); this.screen = new WeakReference<>(screen); this.id = id;
        }
        void update() {
            View target = view.get();
            if (target != null) setBounds(0, 0, target.getWidth(), target.getHeight());
            invalidateSelf();
        }
        public void draw(android.graphics.Canvas canvas) {
            Screen owner = screen.get();
            if (owner == null || !owner.selecting) return;
            View target = view.get(); if (target == null) return;
            float density = target.getResources().getDisplayMetrics().density;
            float x = getBounds().right - 18 * density, y = 18 * density, radius = 10 * density;
            boolean checked = owner.selection.contains(id);
            paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(checked ? 0xFF3366FF : 0xAA000000);
            canvas.drawCircle(x, y, radius, paint);
            paint.setColor(Color.WHITE); paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
            canvas.drawCircle(x, y, radius, paint);
            if (checked) {
                canvas.drawLine(x-5*density, y, x-1*density, y+4*density, paint);
                canvas.drawLine(x-1*density, y+4*density, x+6*density, y-5*density, paint);
            }
        }
        public void setAlpha(int alpha) { }
        public void setColorFilter(android.graphics.ColorFilter filter) { }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private static Button button(Context context, String text) {
        Button b = new Button(context); b.setText(text); b.setTextSize(12); b.setMinWidth(0); return b;
    }
    public static void attach(View root, UserSession session) {
        try {
            if (!(root instanceof ViewGroup) || session == null || SCREENS.containsKey(root)) return;
            Context context = root.getContext();
            LinearLayout bar = new LinearLayout(context); bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL); bar.setBackgroundColor(0xEE202020);
            Button mode = button(context, "BD"), download = button(context, "Download"), cancel = button(context, "Cancel"), retry = button(context, "Retry");
            TextView count = new TextView(context); count.setTextColor(Color.WHITE); count.setTextSize(12);
            bar.addView(mode); bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
            bar.addView(download); bar.addView(cancel); bar.addView(retry);
            View host = root;
            while (!(host instanceof FrameLayout) && host.getParent() instanceof View) {
                host = (View) host.getParent();
            }
            if (!(host instanceof FrameLayout)) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            ((FrameLayout) host).addView(bar, params);
            Screen screen = new Screen(root, session, bar, mode, download, cancel, retry, count);
            SCREENS.put(root, screen);
            if (queue == null) {
                MediaDownloader downloader = new MediaDownloader(context.getApplicationContext());
                Map<MediaData, java.util.Set<String>> completed = new WeakHashMap<>();
                queue = new BatchDownloadQueue<>(Executors.newSingleThreadExecutor(),
                        (post, cancelled) -> DownloadUtils.downloadCompletePost(downloader, post, cancelled,
                                completed.computeIfAbsent(post, ignored -> new java.util.HashSet<>())));
            }
            mode.setOnClickListener(v -> {
                screen.selecting = !screen.selecting;
                if (!screen.selecting) screen.selection.clear();
                refresh(screen);
            });
            download.setOnClickListener(v -> start(screen, screen.selection.snapshot()));
            cancel.setOnClickListener(v -> queue.cancel());
            retry.setOnClickListener(v -> start(screen, queue.failedItems()));
            refresh(screen);
            for (Map.Entry<View, Object> entry : BOUND.entrySet()) render(entry.getKey(), entry.getValue());
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Saved BD attach failed", e); }
    }
    public static void detach(View root) {
        Screen screen = SCREENS.remove(root);
        if (screen == null) return;
        screen.selection.clear(); screen.selecting = false;
        if (screen.bar.getParent() instanceof ViewGroup) ((ViewGroup) screen.bar.getParent()).removeView(screen.bar);
        for (Cell cell : CELLS.values()) if (cell.screen.get() == screen) cell.update();
        // An explicitly started batch continues using application context after navigation.
    }
    private static void refresh(Screen screen) {
        screen.mode.setText(screen.selecting ? "Done" : "BD");
        screen.count.setText(screen.selection.size() + " selected");
        screen.download.setVisibility(screen.selecting ? View.VISIBLE : View.GONE);
        screen.download.setEnabled(screen.selection.size() > 0 && !queue.isRunning());
        screen.cancel.setVisibility(queue.isRunning() ? View.VISIBLE : View.GONE);
        screen.retry.setVisibility(!queue.isRunning() && !queue.failedItems().isEmpty() ? View.VISIBLE : View.GONE);
        for (Cell cell : CELLS.values()) if (cell.screen.get() == screen) cell.update();
    }
    private static void start(Screen screen, Map<String, MediaData> items) {
        if (!StorageUtils.checkStoragePermissions()) { StorageUtils.allowStorageAccess(); return; }
        if (!Utils.isNetworkConnected()) { Utils.showToastShort("No internet connection"); return; }
        WeakReference<Screen> weak = new WeakReference<>(screen);
        if (queue.start(items, (done, failed, total, active, running) -> MAIN.post(() -> {
            Screen owner = weak.get();
            if (owner != null && SCREENS.containsValue(owner)) {
                refresh(owner);
                owner.count.setText(done + " / " + total + " · " + failed + " failed");
            }
            if (!running) Utils.showToastLong("BD: " + done + " / " + total + ", " + failed + " failed");
        }))) {
            screen.selection.clear(); refresh(screen);
        }
    }
    /** Called on every native thumbnail bind, with its complete parent Media object. */
    public static void bind(View view, Object media) {
        if (view == null) return;
        if (!BOUND.containsKey(view)) {
            view.addOnAttachStateChangeListener(ATTACH);
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                Cell cell = CELLS.get(v); if (cell != null) cell.update();
            });
        }
        BOUND.put(view, media);
        render(view, media);
        view.post(() -> { if (BOUND.get(view) == media) render(view, media); });
    }
    private static Screen owner(View view) {
        for (View node = view; node != null;) {
            Screen screen = SCREENS.get(node); if (screen != null) return screen;
            ViewParent parent = node.getParent(); node = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
    /** Native Saved-grid click callback; returning true suppresses opening the post. */
    public static boolean select(View view, Object media) {
        try {
            Screen screen = owner(view);
            if (screen == null || !screen.selecting) return false;
            if (media != null) {
                MediaData post = new MediaData(media, screen.session);
                screen.selection.toggle(post.getPostID(), post); refresh(screen);
            }
            return true;
        } catch (Exception | LinkageError e) {
            Logger.printException(() -> "Saved BD selection failed", e); return true;
        }
    }
    private static void render(View view, Object media) {
        try {
            Screen screen = owner(view);
            Cell previous = CELLS.remove(view);
            if (previous != null) view.getOverlay().remove(previous);
            if (screen == null || media == null) return;
            MediaData post = new MediaData(media, screen.session);
            String id = post.getPostID(); if ("0".equals(id)) return;
            Cell cell = new Cell(view, screen, id); CELLS.put(view, cell);
            view.getOverlay().add(cell); cell.update();
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Saved BD bind failed", e); }
    }
}
