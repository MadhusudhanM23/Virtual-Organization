package io.vorg.optimization;

import com.google.ortools.Loader;
import com.google.ortools.init.OrToolsVersion;

/**
 * Loads the OR-Tools native library once per JVM and names the solver. {@link Loader#loadNativeLibraries()} unpacks the
 * per-OS native jar ({@code ortools-win32-x86-64}, {@code ortools-linux-x86-64}, ...) into a temp directory and loads
 * it; it is synchronized and idempotent. A failure surfaces as an {@link UnsatisfiedLinkError} (ADR-62 packaging risk).
 */
public final class Solvers {

    private static volatile boolean loaded;

    private Solvers() {}

    /** Loads the native library if this JVM has not done so yet. */
    public static void ensureLoaded() {
        if (!loaded) {
            synchronized (Solvers.class) {
                if (!loaded) {
                    Loader.loadNativeLibraries();
                    loaded = true;
                }
            }
        }
    }

    /** The OR-Tools version reported by the native library, e.g. {@code 9.15.6755}. */
    public static String orToolsVersion() {
        ensureLoaded();
        return OrToolsVersion.getVersionString();
    }

    /** {@code feasibility.solver}: {@code "or-tools-cp-sat <version>"}. */
    public static String solverName() {
        return "or-tools-cp-sat " + orToolsVersion();
    }
}
