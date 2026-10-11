package io.github.libxposed.api;

import java.lang.reflect.Executable;

public interface XposedInterface {
    int API_101 = 101;
    int LIB_API = API_101;
    int PRIORITY_DEFAULT = 50;

    interface Chain {
        Executable getExecutable();

        Object getThisObject();

        Object getArg(int index);

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface HookHandle {
        Executable getExecutable();

        void unhook();
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);

        HookBuilder setExceptionMode(ExceptionMode mode);

        HookHandle intercept(Hooker hooker);
    }

    int getApiVersion();

    String getFrameworkName();

    String getFrameworkVersion();

    long getFrameworkVersionCode();

    long getFrameworkProperties();

    HookBuilder hook(Executable origin);

    void log(int priority, String tag, String msg);

    void log(int priority, String tag, String msg, Throwable tr);
}
