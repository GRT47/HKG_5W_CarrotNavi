package hmgapi.hook;

/** Compile-only stub matching HMG-API's ModEntry interface (provided at runtime by hmgapi-hook.jar). */
public abstract class ModEntry {
    public void zygoteInit(ModContext mod) throws Throwable {}

    public void appLoaded(ModContext mod, LoadedApp app) throws Throwable {}

    public void onSettingsChanged(ModContext mod) throws Throwable {}

    public void onEnabled(ModContext mod, boolean enabled) throws Throwable {}
}
