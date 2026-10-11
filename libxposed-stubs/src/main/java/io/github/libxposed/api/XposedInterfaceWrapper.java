package io.github.libxposed.api;

import java.lang.reflect.Executable;

public class XposedInterfaceWrapper implements XposedInterface {
    private XposedInterface base;

    public final void attachFramework(XposedInterface base) {
        this.base = base;
    }

    @Override
    public int getApiVersion() {
        return base.getApiVersion();
    }

    @Override
    public String getFrameworkName() {
        return base.getFrameworkName();
    }

    @Override
    public String getFrameworkVersion() {
        return base.getFrameworkVersion();
    }

    @Override
    public long getFrameworkVersionCode() {
        return base.getFrameworkVersionCode();
    }

    @Override
    public long getFrameworkProperties() {
        return base.getFrameworkProperties();
    }

    @Override
    public HookBuilder hook(Executable origin) {
        return base.hook(origin);
    }

    @Override
    public void log(int priority, String tag, String msg) {
        base.log(priority, tag, msg);
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        base.log(priority, tag, msg, tr);
    }
}
