package com.shinyhunter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hover texts that carry their own layout (tables) and must not be word-wrapped. Tracked by
 * identity, weakly, so a hover that's scrolled out of chat history is simply forgotten.
 */
public final class WideHovers {

    private static final Set<Object> WIDE = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private WideHovers() {
    }

    public static <T> T mark(T hover) {
        WIDE.add(hover);
        return hover;
    }

    public static boolean isWide(Object hover) {
        return hover != null && WIDE.contains(hover);
    }
}
