package hmgapi.hook;

/** Compile-only stub matching HMG-API's ModContext API (provided at runtime by hmgapi-hook.jar). */
public final class ModContext {
    public final String id = "";
    public final String version = "";

    public boolean enabled() {
        return false;
    }

    public boolean settingBoolean(String key, boolean fallback) {
        return fallback;
    }

    public void log(String message) {}

    public void error(String message, Throwable t) {}
}
