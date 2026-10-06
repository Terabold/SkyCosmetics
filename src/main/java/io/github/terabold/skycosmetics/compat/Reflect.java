package io.github.terabold.skycosmetics.compat;

import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reading other mods' objects by name, for {@link OtherLooks}. Lookups are cached per class; anything missing
 * or failing comes back as null (or {@link Missing}), so a renamed field in a mod update hides one value instead
 * of throwing.
 */
final class Reflect {
    /** A field or method this class doesn't have. */
    static final class Missing extends RuntimeException {
        Missing(String what) {
            super(what, null, false, false);
        }
    }

    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    private Reflect() {}

    /** A public no-argument method's result, or null when {@code target} has none by that name. */
    static Object call(Object target, String method) {
        if (target == null) return null;
        Method m = method(target.getClass(), method);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }

    /** A public method of {@code c} (or a superclass) by name and parameter types; null if there is none. */
    static Method method(Class<?> c, String name, Class<?>... types) {
        Object m = CACHE.computeIfAbsent(c.getName() + "#" + name + Arrays.toString(types), k -> {
            try {
                return c.getMethod(name, types);
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                return NONE;
            }
        });
        return m == NONE ? null : (Method) m;
    }

    /** {@link #method} that must exist: a missing one throws {@link Missing}. */
    static Method requireMethod(Class<?> c, String name, Class<?>... types) {
        Method m = method(c, name, types);
        if (m == null) throw new Missing(c.getName() + "#" + name);
        return m;
    }

    /** The first of {@code names} that {@code target}'s class (or a superclass) declares, read even if private. */
    static Object field(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Field f = field(target.getClass(), name);
            if (f == null) continue;
            try {
                return f.get(target);
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** A declared field of {@code c} or a superclass, made accessible; null if there is none. */
    static Field field(Class<?> c, String name) {
        Object f = CACHE.computeIfAbsent(c.getName() + "." + name, k -> {
            for (Class<?> k2 = c; k2 != null && k2 != Object.class; k2 = k2.getSuperclass()) {
                try {
                    Field found = k2.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException ignored) {
                    // the superclass may have it
                } catch (RuntimeException | LinkageError e) {
                    return NONE;
                }
            }
            return NONE;
        });
        return f == NONE ? null : (Field) f;
    }

    /** {@link #field} that must exist: a missing one throws {@link Missing}. */
    static Field require(Class<?> c, String name) {
        Field f = field(c, name);
        if (f == null) throw new Missing(c.getName() + "." + name);
        return f;
    }

    /** "minecraft:netherite_boots" as "Netherite Boots". */
    static String words(Object id) {
        String s = id instanceof Identifier i ? i.getPath() : String.valueOf(id);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder b = new StringBuilder();
        for (String w : s.split("[_ ]+")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** "#RRGGBB". */
    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }
}
