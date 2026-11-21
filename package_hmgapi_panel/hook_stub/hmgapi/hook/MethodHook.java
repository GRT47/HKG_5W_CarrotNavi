package hmgapi.hook;

import java.lang.reflect.Member;

/** Compile-only stub matching HMG-API's MethodHook API (provided at runtime by hmgapi-hook.jar). */
public abstract class MethodHook {
    protected void beforeHookedMethod(Param param) throws Throwable {}

    protected void afterHookedMethod(Param param) throws Throwable {}

    public static final class Param {
        public final Member method = null;
        public final Object thisObject = null;
        public final Object[] args = null;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {}

        public Throwable getThrowable() {
            return null;
        }

        public boolean hasThrowable() {
            return false;
        }

        public void setThrowable(Throwable throwable) {}
    }
}
