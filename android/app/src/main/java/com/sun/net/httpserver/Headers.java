package com.sun.net.httpserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** HTTP headers, names without regard to case (as in the JDK: "content-type" and "Content-Type" are one). */
public class Headers implements Map<String, List<String>> {

    private final Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private static String normalize(Object key) {
        String k = String.valueOf(key);
        return k.isEmpty() ? k : Character.toUpperCase(k.charAt(0)) + k.substring(1).toLowerCase(Locale.ROOT);
    }

    public String getFirst(String key) {
        List<String> l = map.get(key);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    public void add(String key, String value) {
        map.computeIfAbsent(normalize(key), k -> new ArrayList<>()).add(value);
    }

    public void set(String key, String value) {
        List<String> l = new ArrayList<>();
        l.add(value);
        map.remove(key);
        map.put(normalize(key), l);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    @Override
    public List<String> get(Object key) {
        return key instanceof String ? map.get(key) : null;
    }

    @Override
    public List<String> put(String key, List<String> value) {
        List<String> old = map.remove(key);
        map.put(normalize(key), value);
        return old;
    }

    @Override
    public List<String> remove(Object key) {
        return key instanceof String ? map.remove(key) : null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> m) {
        for (Map.Entry<? extends String, ? extends List<String>> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Set<String> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<List<String>> values() {
        return map.values();
    }

    @Override
    public Set<Map.Entry<String, List<String>>> entrySet() {
        return map.entrySet();
    }
}
