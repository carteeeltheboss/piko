package app.morphe.extension.instagram.patches.batchDownload;

import java.util.LinkedHashMap;
import java.util.Map;

/** Screen-owned selection. Never stores positions or views. */
public final class SelectionManager<T> {
    private final LinkedHashMap<String, T> selected = new LinkedHashMap<>();

    public boolean toggle(String id, T item) {
        if (id == null || id.isEmpty() || "0".equals(id) || item == null) return false;
        if (selected.containsKey(id)) { selected.remove(id); return false; }
        selected.put(id, item);
        return true;
    }
    public boolean contains(String id) { return selected.containsKey(id); }
    public int size() { return selected.size(); }
    public void clear() { selected.clear(); }
    public Map<String, T> snapshot() { return new LinkedHashMap<>(selected); }
}
