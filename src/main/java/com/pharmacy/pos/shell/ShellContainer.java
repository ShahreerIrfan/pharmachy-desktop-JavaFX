package com.pharmacy.pos.shell;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ShellContainer - Lightweight Dependency Injection & Service Registry.
 * Architecture Decision: ADR-0321 (P321).
 */
public class ShellContainer {

    private static final ShellContainer INSTANCE = new ShellContainer();
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();

    private ShellContainer() {}

    public static ShellContainer getInstance() {
        return INSTANCE;
    }

    public <T> void registerSingleton(Class<T> type, T instance) {
        if (type == null || instance == null) {
            throw new IllegalArgumentException("Type and instance must not be null.");
        }
        singletons.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T resolve(Class<T> type) {
        if (type == null) return null;
        Object instance = singletons.get(type);
        if (instance == null) {
            throw new IllegalStateException("No registered instance for type: " + type.getName());
        }
        return (T) instance;
    }

    public boolean has(Class<?> type) {
        return type != null && singletons.containsKey(type);
    }

    public void reset() {
        singletons.clear();
    }
}
