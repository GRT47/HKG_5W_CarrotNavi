package hmgapi.hook;

import java.lang.reflect.Method;

/** Compile-only stub matching HMG-API's Hooks helpers (provided at runtime by hmgapi-hook.jar). */
public final class Hooks {
    private Hooks() {}

    public static Class<?> findClass(String name, ClassLoader loader) {
        return null;
    }

    public static Method findAndHookMethod(Class<?> cls, String name, Object... parameterTypesAndHook) {
        return null;
    }

    public static Method findAndHookMethod(String className, ClassLoader loader, String name,
            Object... parameterTypesAndHook) {
        return null;
    }

    public static Object getObjectField(Object obj, String name) {
        return null;
    }

    public static Object callMethod(Object obj, String name, Object... args) {
        return null;
    }
}
