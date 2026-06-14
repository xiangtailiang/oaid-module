package de.robv.android.xposed;

import java.util.Set;

public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Set hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
