package com.crisisconnect.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Generic collection helpers (Unit 2 - Generics & type safety).
 * Every method is generic, so it works for Resources, Requests, Allocations ...
 */
public final class CollectionUtils {

    private CollectionUtils() { }

    /** Counts items per key: e.g. number of requests per UrgencyLevel. */
    public static <T, K> Map<K, Integer> countBy(Collection<T> items, Function<? super T, ? extends K> keyFn) {
        Map<K, Integer> counts = new HashMap<>();
        for (T item : items) {
            counts.merge(keyFn.apply(item), 1, Integer::sum);
        }
        return counts;
    }

    /** Sums an int property per key: e.g. available units per ResourceType. */
    public static <T, K> Map<K, Integer> sumBy(Collection<T> items, Function<? super T, ? extends K> keyFn,
                                               ToIntFunction<? super T> valueFn) {
        Map<K, Integer> sums = new HashMap<>();
        for (T item : items) {
            sums.merge(keyFn.apply(item), valueFn.applyAsInt(item), Integer::sum);
        }
        return sums;
    }

    /** Removes matching elements using an explicit Iterator (safe removal while iterating). */
    public static <T> int removeIf(Collection<T> items, Predicate<? super T> condition) {
        int removed = 0;
        Iterator<T> it = items.iterator();
        while (it.hasNext()) {
            if (condition.test(it.next())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Keeps only the last {@code max} entries - used for the live activity feed (LinkedList as a queue). */
    public static <T> void pushBounded(LinkedList<T> list, T item, int max) {
        list.addFirst(item);
        while (list.size() > max) list.removeLast();
    }

    /** Bounded-generic max: works for any Comparable type. */
    public static <T extends Comparable<? super T>> T maxOf(List<T> items) {
        if (items == null || items.isEmpty()) return null;
        T best = items.get(0);
        for (T t : items) if (t.compareTo(best) > 0) best = t;
        return best;
    }
}
