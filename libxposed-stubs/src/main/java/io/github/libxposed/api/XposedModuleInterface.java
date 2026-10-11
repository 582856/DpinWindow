package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        boolean isSystemServer();

        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();

        ApplicationInfo getApplicationInfo();

        boolean isFirstPackage();

        ClassLoader getDefaultClassLoader();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        ClassLoader getClassLoader();
    }

    interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }

    default void onModuleLoaded(ModuleLoadedParam param) {
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }

    default void onPackageReady(PackageReadyParam param) {
    }

    default void onSystemServerStarting(SystemServerStartingParam param) {
    }
}
