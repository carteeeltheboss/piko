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
    private static final Map<View, WeakReference<Cell>> CELLS = new WeakHashMap<>();
    private static final Map<View, Object> BOUND = new WeakHashMap<>();
    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        public void onViewAttachedToWindow(View view) { render(view, BOUND.get(view)); }
        public void onViewDetachedFromWindow(View view) {
            WeakReference<Cell> ref = CELLS.remove(view);
            Cell cell = ref == null ? null : ref.get();
            if (cell != null) view.getOverlay().remove(cell);
        }
    };
    private static BatchDownloadQueue<MediaData> queue;
    private static WeakReference<Screen> queueOrigin = new WeakReference<>(null);
    private static String queueProfileId;

    public static Map<String, String> queueStates() {
        return queue == null ? java.util.Collections.emptyMap() : queue.states();
    }
    public static void cancelDownloads() {
        if (queue != null) queue.cancel();
        app.morphe.extension.crimera.downloader.DownloadQueueMonitor.cancelPending();
    }
    private static final class Screen {
        final SelectionManager<MediaData> selection = new SelectionManager<>();
        final WeakReference<View> root;
        final UserSession session;
        final LinearLayout bar;
        final Button mode, download, cancel, retry;
        Button all, none;
        WeakReference<Object> source;
        int discovery;
        boolean profile;
        String profileId;
        LinearLayout panel;
        WeakReference<Button> toolbarButton = new WeakReference<>(null);
        android.view.ViewTreeObserver.OnGlobalLayoutListener profileLayout;
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
    public static void attach(View root, UserSession session, Object source) {
        attachScreen(root, session, source, false);
    }
    public static void attachProfile(View root, UserSession session, Object fragment) {
        attachScreen(root, session, fragment, true);
    }
    private static void attachScreen(View root, UserSession session, Object source, boolean profile) {
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
            if (!(host instanceof FrameLayout) && !profile) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            LinearLayout panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
            panel.addView(bar);
            LinearLayout actions = new LinearLayout(context);
            Button all = button(context, "Select All Loaded"), none = button(context, "Deselect All");
            actions.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(none, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(actions); actions.setTag("batchSelectionActions");
            if (host instanceof FrameLayout) ((FrameLayout) host).addView(panel, params);
            Screen screen = new Screen(root, session, bar, mode, download, cancel, retry, count);
            screen.panel = panel;
            screen.source = new WeakReference<>(source); screen.profile = profile; screen.all = all; screen.none = none;
            SCREENS.put(root, screen);
            all.setOnClickListener(v -> selectLoaded(screen));
            none.setOnClickListener(v -> { screen.discovery++; screen.selection.clear(); refresh(screen); });
            if (queue == null) {
                MediaDownloader downloader = new MediaDownloader(context.getApplicationContext());
                Map<MediaData, java.util.Set<String>> completed = new WeakHashMap<>();
                queue = new BatchDownloadQueue<>(Executors.newSingleThreadExecutor(),
                        (post, cancelled) -> DownloadUtils.downloadCompletePost(downloader, post, cancelled,
                                completed.computeIfAbsent(post, ignored -> new java.util.HashSet<>())));
            }
            mode.setOnClickListener(v -> {
                checkProfile(screen);
                screen.selecting = !screen.selecting;
                if (!screen.selecting) { screen.discovery++; screen.selection.clear(); }
                refresh(screen);
            });
            download.setOnClickListener(v -> { checkProfile(screen); start(screen, screen.selection.snapshot()); });
            cancel.setOnClickListener(v -> queue.cancel());
            retry.setOnClickListener(v -> {
                checkProfile(screen);
                if (queueOrigin.get() == screen && java.util.Objects.equals(queueProfileId, screen.profileId)) {
                    start(screen, queue.failedItems());
                }
            });
            refresh(screen);
            if (profile) {
                screen.profileLayout = () -> mountProfile(screen);
                root.getViewTreeObserver().addOnGlobalLayoutListener(screen.profileLayout);
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View v) { mountProfile(screen); }
                    public void onViewDetachedFromWindow(View v) { screen.panel.setVisibility(View.GONE); }
                });
                root.post(() -> mountProfile(screen));
            }
            for (Map.Entry<View, Object> entry : BOUND.entrySet()) render(entry.getKey(), entry.getValue());
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Saved BD attach failed", e); }
    }
    public static void detach(View root) {
        Screen screen = SCREENS.remove(root);
        if (screen == null) return;
        screen.discovery++; screen.selection.clear(); screen.selecting = false;
        Button toolbar = screen.toolbarButton.get();
        if (toolbar != null && toolbar.getParent() instanceof ViewGroup) ((ViewGroup) toolbar.getParent()).removeView(toolbar);
        if (screen.profileLayout != null && root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(screen.profileLayout);
        View panel = (View) screen.bar.getParent();
        if (panel != null && panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        for (WeakReference<Cell> ref : CELLS.values()) {
            Cell cell = ref.get(); if (cell != null && cell.screen.get() == screen) cell.update();
        }
        // An explicitly started batch continues using application context after navigation.
    }
    private static void refresh(Screen screen) {
        screen.mode.setText(screen.selecting ? "Done" : "BD");
        if (screen.profile) {
            View root = screen.root.get();
            screen.panel.setVisibility(screen.selecting && root != null && root.isShown() ? View.VISIBLE : View.GONE);
            Button toolbar = screen.toolbarButton.get();
            if (toolbar != null) toolbar.setText(screen.selecting ? "Done" : "BD");
        }
        screen.all.setVisibility(screen.selecting ? View.VISIBLE : View.GONE);
        screen.none.setVisibility(screen.selecting ? View.VISIBLE : View.GONE);
        screen.count.setText(screen.selection.size() + " selected");
        screen.download.setVisibility(screen.selecting ? View.VISIBLE : View.GONE);
        screen.download.setEnabled(screen.selection.size() > 0 && !queue.isRunning());
        screen.cancel.setVisibility(queue.isRunning() ? View.VISIBLE : View.GONE);
        screen.retry.setVisibility(!queue.isRunning() && !queue.failedItems().isEmpty()
                && queueOrigin.get() == screen && java.util.Objects.equals(queueProfileId, screen.profileId)
                ? View.VISIBLE : View.GONE);
        for (WeakReference<Cell> ref : CELLS.values()) {
            Cell cell = ref.get(); if (cell != null && cell.screen.get() == screen) cell.update();
        }
    }
    private static void selectLoaded(Screen screen) {
        Object source = screen.source.get();
        if (source == null) return;
        try {
            checkProfile(screen);
            java.util.List<Object> loaded = screen.profile
                    ? LoadedPosts.profile(screen.root.get(), screen.session) : LoadedPosts.saved(source, screen.session);
            int generation = ++screen.discovery;
            addLoaded(screen, loaded, 0, generation);
        } catch (Exception | LinkageError e) {
            Logger.printException(() -> "BD could not read loaded posts", e);
            Utils.showToastShort("Could not read loaded posts; individual selection is still available");
        }
    }
    private static void addLoaded(Screen screen, java.util.List<Object> loaded, int offset, int generation) {
        if (screen.discovery != generation || !screen.selecting || !SCREENS.containsValue(screen)) return;
        int end = Math.min(offset + 40, loaded.size());
        for (int i = offset; i < end; i++) {
            MediaData post = new MediaData(loaded.get(i), screen.session);
            screen.selection.select(post.getPostID(), post);
        }
        refresh(screen);
        if (end < loaded.size()) {
            View root = screen.root.get();
            if (root != null) root.postOnAnimation(() -> addLoaded(screen, loaded, end, generation));
        } else Utils.showToastShort(screen.selection.size() + " selected from loaded posts; scroll and repeat for more");
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
            queueOrigin = weak; queueProfileId = screen.profileId;
            screen.selection.clear(); refresh(screen);
        }
    }
    /** Called on every native thumbnail bind, with its complete parent Media object. */
    public static void bind(View view, Object media) {
        if (view == null) return;
        if (!BOUND.containsKey(view)) {
            view.addOnAttachStateChangeListener(ATTACH);
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                WeakReference<Cell> ref = CELLS.get(v);
                Cell cell = ref == null ? null : ref.get(); if (cell != null) cell.update();
            });
        }
        BOUND.put(view, media);
        render(view, media);
        view.post(() -> { if (BOUND.get(view) == media) render(view, media); });
    }
    private static Screen owner(View view) {
        for (View node = view; node != null;) {
            Screen screen = SCREENS.get(node); if (screen != null) { checkProfile(screen); return screen; }
            ViewParent parent = node.getParent(); node = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
    /** Called after native configureActionBar as well as profile-view creation. */
    public static void profileToolbar(Object fragment) {
        try {
            View root = (View) fragment.getClass().getMethod("getView").invoke(fragment);
            if (root == null) return;
            UserSession session = (UserSession) fragment.getClass().getMethod("getSession").invoke(fragment);
            attachProfile(root, session, fragment);
            Screen screen = SCREENS.get(root);
            if (screen != null) root.post(() -> mountProfile(screen));
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Profile BD toolbar hook failed", e); }
    }
    private static void mountProfile(Screen screen) {
        View root = screen.root.get();
        if (root == null || !SCREENS.containsValue(screen) || !root.isAttachedToWindow()) return;
        try {
            if (screen.panel.getParent() == null) {
                View host = root;
                while (!(host instanceof FrameLayout) && host.getParent() instanceof View) host = (View) host.getParent();
                if (host instanceof FrameLayout) ((FrameLayout) host).addView(screen.panel,
                        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            }
            screen.panel.setVisibility(screen.selecting && root.isShown() ? View.VISIBLE : View.GONE);
            Object source = screen.source.get();
            if (source == null) return;
            java.lang.reflect.Field field = source.getClass().getDeclaredField("A0K");
            field.setAccessible(true);
            Object bar = field.get(source);
            if (!(bar instanceof View)) return;
            int id = app.morphe.extension.shared.ResourceUtils.getIdentifier(root.getContext(), app.morphe.extension.shared.ResourceType.ID, "right_action_bar_buttons");
            View container = id == 0 ? null : ((View) bar).findViewById(id);
            if (!(container instanceof LinearLayout)) return;
            LinearLayout buttons = (LinearLayout) container;
            buttons.setVisibility(View.VISIBLE);
            Button button = buttons.findViewWithTag("piko_profile_batch_download");
            if (button == null) {
                button = button(root.getContext(), "BD");
                button.setTag("piko_profile_batch_download");
                button.setContentDescription("Profile Batch Download");
                button.setTextColor(0xFF0095F6); button.setBackgroundColor(Color.TRANSPARENT);
                button.setPadding(0, 0, 0, 0); button.setMinimumWidth(0); button.setMinimumHeight(0);
                int size = (int) (48 * root.getResources().getDisplayMetrics().density);
                buttons.addView(button, Math.max(0, buttons.getChildCount()-1), new LinearLayout.LayoutParams(size, size));
                button.setOnClickListener(v -> screen.mode.performClick());
                button.setOnLongClickListener(v -> { DownloadQueueDialog.show(v.getContext()); return true; });
            }
            screen.toolbarButton = new WeakReference<>(button);
            String text = screen.selecting ? "Done" : "BD";
            if (!text.contentEquals(button.getText())) button.setText(text);
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Profile BD toolbar attachment failed", e); }
    }
    private static void checkProfile(Screen screen) {
        if (!screen.profile) return;
        Object fragment = screen.source.get();
        if (fragment == null) return;
        try {
            String id = LoadedPosts.profileId(fragment);
            if (screen.profileId != null && !screen.profileId.equals(id)) {
                screen.discovery++; screen.selection.clear();
                screen.selecting = false;
                refresh(screen);
            }
            screen.profileId = id;
        } catch (Exception | LinkageError ignored) {
            // Profile user data can be absent while an empty/private profile is loading.
        }
    }
    /** Wrap the native listener on every rebind, preserving navigation outside Profile BD. */
    public static View.OnClickListener profileClick(View view, Object media, View.OnClickListener original) {
        return clicked -> {
            Screen screen = owner(view);
            if (screen != null && screen.profile && screen.selecting && select(view, media)) return;
            if (original != null) original.onClick(clicked);
        };
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
            WeakReference<Cell> ref = CELLS.remove(view);
            Cell previous = ref == null ? null : ref.get();
            if (previous != null) view.getOverlay().remove(previous);
            if (screen == null || media == null) return;
            MediaData post = new MediaData(media, screen.session);
            String id = post.getPostID(); if ("0".equals(id)) return;
            Cell cell = new Cell(view, screen, id); CELLS.put(view, new WeakReference<>(cell));
            view.getOverlay().add(cell); cell.update();
        } catch (Exception | LinkageError e) { Logger.printException(() -> "Saved BD bind failed", e); }
    }
}
