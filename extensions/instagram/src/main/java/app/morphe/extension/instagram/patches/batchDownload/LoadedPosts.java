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
    private static List<Object> models(Iterator<?> iterator, UserSession session) throws Exception {
        List<Object> result = new ArrayList<>();
        Object resolver = Class.forName("X.01zd").getMethod("A00", UserSession.class).invoke(null, session);
        Class<?> gridModel = Class.forName("X.0D0U");
        Class<?> mediaReference = Class.forName("X.022I");
        Method resolve = mediaReference.getMethod("A00", Class.forName("X.0EAU"));
        while (iterator.hasNext()) {
            Object model = iterator.next();
            if (!gridModel.isInstance(model)) continue; // Skip headers/footers and loading rows.
            Object reference = call(model, "CMJ");
            Object media = resolve.invoke(reference, resolver);
            if (media != null) result.add(media);
        }
        return result;
    }
}
