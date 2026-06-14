package com.oaidfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Vendor-agnostic OAID provider for AOSP/LineageOS.
 *
 * Instead of faking one vendor SDK, this hooks the FRAMEWORK layer that every
 * manufacturer's OAID path funnels through, so it works no matter which vendor
 * service an app talks to (and survives R8/relocation, since it keys off
 * framework calls + preserved intent/package strings, not library class names):
 *
 *  1) PackageManager.getPackageInfo  -> pretend the vendor OAID service package
 *     is installed, so each impl's supported()/pre-checks pass.
 *  2) Context.bindService           -> intercept binds to any known vendor OAID
 *     service and deliver a fake IBinder.
 *  3) Fake IBinder                  -> queryLocalInterface returns a dynamic
 *     Proxy of whatever AIDL interface the app asks for (returns our OAID for
 *     every String getter); if the interface class was relocated and can't be
 *     loaded by descriptor, onTransact answers the remote call with our OAID.
 *  4) Context.unbindService         -> swallow unbind for our fake connections.
 *  5) ContentResolver.query/.call   -> vendors that expose OAID via a provider
 *     (Meizu / Vivo / Nubia).
 *  6) MSA SDK MdidSdkHelper.InitSdk -> the com.bun.miitmdid unified SDK path.
 *
 * (No Class.forName hook: the Xiaomi com.android.id.impl.IdProviderImpl reflection
 * path can only be intercepted by hooking the caller-sensitive 1-arg
 * Class.forName(String), which corrupts the implicit ClassLoader and crashes apps
 * that rely on it, e.g. androidx.startup. Xiaomi devices have that class natively
 * anyway, so it is intentionally not faked.)
 */
public class OaidModule implements IXposedHookLoadPackage {

    public static final String OAID = "a0f4dafe-b794-4478-9383-e391bb03e918";
    private static final String TAG = "[OAID] ";

    // Vendor OAID service packages (for getPackageInfo + bindService matching).
    private static final Set<String> VENDOR_PKGS = new HashSet<>(Arrays.asList(
            "com.heytap.openid",                 // OPPO / OnePlus / realme (HeyTap)
            "com.coloros.mcs",                   // ColorOS legacy stdid host
            "com.oplus.stdid",                   // OPPO stdid
            "com.samsung.android.deviceidservice",
            "com.asus.msa.SupplementaryDID",     // ASUS
            "com.zui.deviceidservice",           // Lenovo / Motorola / ZUI
            "com.coolpad.deviceidsupport",       // Coolpad
            "com.android.creator",               // Freeme
            "com.qiku.id",                       // 360 / Qiku
            "com.huawei.hwid",                   // Huawei OPENIDS service host
            "com.huawei.hms",
            "com.mdid.msa"                        // MSA service host
    ));

    // Vendor service intent actions.
    private static final Set<String> VENDOR_ACTIONS = new HashSet<>(Arrays.asList(
            "action.com.heytap.openid.OPEN_ID_SERVICE",
            "action.com.oplus.stdid.ID_SERVICE",
            "com.asus.msa.action.ACCESS_DID",
            "android.service.action.msa",
            "qiku.service.action.id",
            "com.uodis.opendevice.OPENIDS_SERVICE",
            "com.bun.msa.action.start.service",
            "com.bun.msa.action.bindto.service"
    ));

    // Content provider URI prefixes -> cursor-with-"value" vendors.
    private static final String URI_MEIZU = "content://com.meizu.flyme.openidsdk";
    private static final String URI_VIVO  = "content://com.vivo.vms.IdProvider/IdentifierId";
    private static final String URI_NUBIA = "content://cn.nubia.identity";

    // ServiceConnections we faked, so unbindService doesn't crash with
    // "Service not registered".
    private static final Set<Object> FAKED_CONNS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        try {
            hookPackageManager(cl);
            hookBindService(cl);
            hookContentResolver(cl);
            hookMsaSdk(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init error in " + lpparam.packageName + ": " + t);
        }
    }

    // ---- 1) getPackageInfo: make vendor OAID packages look installed ----------
    private void hookPackageManager(ClassLoader cl) {
        Class<?> apm = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManager", cl);
        if (apm == null) return;
        XposedBridge.hookAllMethods(apm, "getPackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) return;
                Object[] a = param.args;
                if (a == null || a.length == 0 || !(a[0] instanceof String)) return;
                String pkg = (String) a[0];
                if (!VENDOR_PKGS.contains(pkg)) return;
                PackageInfo pi = new PackageInfo();
                pi.packageName = pkg;
                pi.versionName = "1.0";
                pi.versionCode = 1;
                try {
                    pi.signatures = new Signature[]{ new Signature("00") };
                } catch (Throwable ignored) { }
                param.setResult(pi); // clears the NameNotFoundException
            }
        });
    }

    // ---- 2/4) bindService + unbindService faker -------------------------------
    private void hookBindService(final ClassLoader cl) {
        Class<?> ctxImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", cl);
        if (ctxImpl == null) return;

        XC_MethodHook bindHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] a = param.args;
                if (a == null) return;
                Intent intent = null;
                ServiceConnection conn = null;
                for (Object o : a) {
                    if (intent == null && o instanceof Intent) intent = (Intent) o;
                    if (conn == null && o instanceof ServiceConnection) conn = (ServiceConnection) o;
                }
                if (intent == null || conn == null || !isVendorOaidService(intent)) return;

                final ComponentName cn = resolveComponent(intent);
                final ServiceConnection fconn = conn;
                final IBinder binder = new FakeOaidBinder(cl);
                FAKED_CONNS.add(conn);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try { fconn.onServiceConnected(cn, binder); }
                        catch (Throwable t) { XposedBridge.log(TAG + "onServiceConnected failed: " + t); }
                    }
                });
                XposedBridge.log(TAG + "faked vendor OAID service " + cn);
                param.setResult(Boolean.TRUE);
            }
        };
        XposedBridge.hookAllMethods(ctxImpl, "bindService", bindHook);
        XposedBridge.hookAllMethods(ctxImpl, "bindServiceAsUser", bindHook);

        XposedBridge.hookAllMethods(ctxImpl, "unbindService", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] a = param.args;
                if (a != null && a.length > 0 && FAKED_CONNS.contains(a[0])) {
                    FAKED_CONNS.remove(a[0]);
                    param.setResult(null); // skip the real (would throw "not registered")
                }
            }
        });
    }

    private boolean isVendorOaidService(Intent intent) {
        try {
            String action = intent.getAction();
            if (action != null && VENDOR_ACTIONS.contains(action)) return true;
            ComponentName c = intent.getComponent();
            if (c != null && VENDOR_PKGS.contains(c.getPackageName())) return true;
            String p = intent.getPackage();
            if (p != null && VENDOR_PKGS.contains(p)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private ComponentName resolveComponent(Intent intent) {
        ComponentName c = intent.getComponent();
        if (c != null) return c;
        String p = intent.getPackage();
        if (p == null) p = "com.fake.oaid";
        return new ComponentName(p, p + ".OAIDService");
    }

    // Fake binder: serves any vendor AIDL OAID interface.
    private static final class FakeOaidBinder extends Binder {
        private final ClassLoader cl;
        FakeOaidBinder(ClassLoader cl) { this.cl = cl; }

        // In-process path (works when the AIDL interface class == descriptor name).
        @Override
        public IInterface queryLocalInterface(String descriptor) {
            if (descriptor == null) return null;
            try {
                Class<?> itf = Class.forName(descriptor, false, cl);
                if (itf.isInterface() && IInterface.class.isAssignableFrom(itf)) {
                    return (IInterface) Proxy.newProxyInstance(
                            itf.getClassLoader() != null ? itf.getClassLoader() : cl,
                            new Class<?>[]{ itf }, new OaidInvocationHandler(this));
                }
            } catch (Throwable ignored) { }
            return null; // -> caller falls back to a remote Stub.Proxy -> onTransact
        }

        // Remote path (relocated/shaded interfaces): every OAID getter returns a
        // String, so answering with writeString(OAID) satisfies the proxy.
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION || code == DUMP_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeString(OAID);
            }
            return true;
        }
    }

    private static final class OaidInvocationHandler implements InvocationHandler {
        private final IBinder binder;
        OaidInvocationHandler(IBinder binder) { this.binder = binder; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            Class<?> rt = method.getReturnType();
            if ("asBinder".equals(n)) return binder;
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(n)) return "FakeOaid";
                if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                if ("equals".equals(n)) return proxy == (args != null ? args[0] : null);
            }
            if (rt == String.class) return OAID;        // getOAID/getSerID/getId/getUDID/...
            if (rt == boolean.class || rt == Boolean.class) {
                return !n.toLowerCase().contains("limit"); // isSupport(ed)=true, isLimited=false
            }
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt.isPrimitive()) return 0;
            return null;
        }
    }

    // ---- 5) ContentResolver provider vendors (Meizu / Vivo / Nubia) -----------
    private void hookContentResolver(ClassLoader cl) {
        Class<?> cr = XposedHelpers.findClassIfExists("android.content.ContentResolver", cl);
        if (cr == null) return;

        XposedBridge.hookAllMethods(cr, "query", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] a = param.args;
                if (a == null || a.length == 0 || !(a[0] instanceof Uri)) return;
                String uri = a[0].toString();
                if (uri.startsWith(URI_MEIZU) || uri.startsWith(URI_VIVO)) {
                    MatrixCursor mc = new MatrixCursor(new String[]{"value"});
                    mc.addRow(new Object[]{ OAID });
                    param.setResult(mc);
                    XposedBridge.log(TAG + "faked provider OAID for " + uri);
                }
            }
        });

        XposedBridge.hookAllMethods(cr, "call", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] a = param.args;
                String uri = null;
                if (a != null) {
                    for (Object o : a) {
                        if (o instanceof Uri) { uri = o.toString(); break; }
                        if (o instanceof String && ((String) o).startsWith("content://")) { uri = (String) o; break; }
                    }
                }
                if (uri != null && uri.startsWith(URI_NUBIA)) {
                    Bundle b = new Bundle();
                    b.putInt("code", 0);
                    b.putString("id", OAID);
                    b.putString("message", "");
                    param.setResult(b);
                    XposedBridge.log(TAG + "faked nubia provider OAID");
                }
            }
        });
    }

    // ---- 6) MSA unified SDK (com.bun.miitmdid) --------------------------------
    private void hookMsaSdk(final ClassLoader cl, final String pkgName) {
        Class<?> helper = XposedHelpers.findClassIfExists("com.bun.miitmdid.core.MdidSdkHelper", cl);
        if (helper == null) return;
        XposedBridge.log(TAG + "MSA SDK detected in " + pkgName + ", hooking InitSdk");
        XposedBridge.hookAllMethods(helper, "InitSdk", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object[] args = param.args;
                    if (args == null) return;
                    Object listener = null;
                    Method onSupport = null;
                    for (Object a : args) {
                        if (a == null) continue;
                        Method m = findOnSupport(a.getClass());
                        if (m != null) { listener = a; onSupport = m; break; }
                    }
                    if (listener == null) return;
                    Class<?>[] pts = onSupport.getParameterTypes();
                    Class<?> supplierItf = null;
                    for (Class<?> p : pts) {
                        if (p == boolean.class || p == Boolean.class) continue;
                        supplierItf = p;
                    }
                    if (supplierItf == null || !supplierItf.isInterface()) return;
                    Object supplier = makeMsaSupplier(cl, supplierItf);
                    onSupport.setAccessible(true);
                    if (pts.length == 1) {
                        onSupport.invoke(listener, supplier);
                    } else if (pts.length == 2) {
                        Object[] call = new Object[2];
                        for (int i = 0; i < 2; i++) {
                            call[i] = (pts[i] == boolean.class || pts[i] == Boolean.class) ? Boolean.TRUE : supplier;
                        }
                        onSupport.invoke(listener, call);
                    } else {
                        return;
                    }
                    param.setResult(1008614);
                    XposedBridge.log(TAG + "delivered OAID via MSA to " + pkgName);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "MSA hook failed: " + t);
                }
            }
        });
    }

    private static Method findOnSupport(Class<?> c) {
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("OnSupport") && m.getParameterTypes().length >= 1) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object makeMsaSupplier(ClassLoader appCl, Class<?> supplierItf) {
        ClassLoader defCl = supplierItf.getClassLoader();
        final ClassLoader loader = (defCl != null) ? defCl : appCl;
        return Proxy.newProxyInstance(loader, new Class<?>[]{ supplierItf }, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] a) {
                String n = method.getName();
                Class<?> rt = method.getReturnType();
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(n)) return "OaidSupplier{" + OAID + "}";
                    if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                    if ("equals".equals(n)) return proxy == (a != null ? a[0] : null);
                }
                switch (n) {
                    case "getOAID": return OAID;
                    case "isSupported": return Boolean.TRUE;
                    case "isLimited": return Boolean.FALSE;
                    case "getVAID":
                    case "getAAID": return "";
                    case "shutDown": return null;
                    default: break;
                }
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == String.class) return "";
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt.isPrimitive()) return 0;
                return null;
            }
        });
    }
}
