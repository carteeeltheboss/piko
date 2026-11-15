package app.morphe.extension.instagram.patches.batchDownload;

import com.instagram.common.session.UserSession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Version-452 native loaded-data readers. Never issues requests or paginates. */
public final class LoadedPosts {
    private LoadedPosts() { }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name); return method.invoke(target);
    }
    public static List<Object> saved(Object fragment, UserSession session) throws Exception {
        Object adapter = field(fragment, "A04"); // Saved fragment's 0CzQ.
        Object source = field(adapter, "A0F");   // Native 0DAr loaded-model provider.
        return models((Iterator<?>) call(source, "A04"), session);
    }
    public static String profileId(Object fragment) throws Exception {
        Object user = call(fragment, "A0s");
        return (String) call(user, "getId");
    }
    public static List<Object> profile(android.view.View root, UserSession session) throws Exception {
        List<Object> loadedModels = new ArrayList<>();
        collectAdapters(root, Class.forName("X.04Mo"), loadedModels);
        return models(loadedModels.iterator(), session);
    }
    private static void collectAdapters(android.view.View view, Class<?> adapterType, List<Object> models) throws Exception {
        // getAdapter is the stable RecyclerView API. A0i is 452's native 04Mo model getter.
        if (isRecyclerView(view.getClass())) {
            Object adapter = call(view, "getAdapter");
            if (adapter != null && adapterType.isInstance(adapter)) {
                int count = (Integer) call(adapter, "getItemCount");
                Method getItem = adapterType.getMethod("A0i", int.class);
                for (int i = 0; i < count; i++) models.add(getItem.invoke(adapter, i));
                return; // Nested carousels inside media holders are not profile posts.
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectAdapters(group.getChildAt(i), adapterType, models);
        }
    }
    private static boolean isRecyclerView(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) return true;
        }
        return false;
    }
    private static List<Object> models(Iterator<?> iterator, UserSession session) throws Exception {
        List<Object> result = new ArrayList<>();
        // The native Saved renderer itself checks 0fgo then calls CkC on these
        // loaded grid models. CMJ is layout metadata, not a media reference.
        Class<?> mediaModel = Class.forName("X.0fgo");
        Method mediaGetter = mediaModel.getMethod("CkC");
        while (iterator.hasNext()) {
            Object model = iterator.next();
            if (!mediaModel.isInstance(model)) continue;
            try {
                Object media = mediaGetter.invoke(model);
                if (media != null) result.add(media);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Unavailable models do not prevent selecting other loaded posts.
            }
        }
        return result;
    }
}
