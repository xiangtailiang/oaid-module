package com.oaidtest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import com.bun.miitmdid.core.MdidSdkHelper;
import com.bun.miitmdid.interfaces.IIdentifierListener;
import com.bun.miitmdid.interfaces.IdSupplier;

import java.lang.reflect.Method;

/**
 * Exercises two OAID read paths so the module can be verified on any device:
 *   - the MSA unified SDK (com.bun.miitmdid)
 *   - Xiaomi/Redmi reflection on com.android.id.impl.IdProviderImpl
 */
public class MainActivity extends Activity {

    private static final String TAG = "OAIDTEST";
    private TextView tv;
    private String msaResult = "MSA SDK: (pending)";
    private String xiaomiResult = "Xiaomi reflect: (pending)";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new TextView(this);
        tv.setTextSize(18f);
        tv.setPadding(48, 120, 48, 48);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        // ---- MSA SDK path ----
        final IIdentifierListener listener = new IIdentifierListener() {
            @Override
            public void OnSupport(IdSupplier supplier) {
                String oaid = (supplier != null) ? supplier.getOAID() : null;
                msaResult = (oaid != null && oaid.length() > 0) ? "MSA SDK:\n" + oaid : "MSA SDK: empty";
                Log.i(TAG, msaResult.replace("\n", " "));
                render();
            }
        };
        int code = MdidSdkHelper.InitSdk(this, false, listener);
        Log.i(TAG, "InitSdk code=" + code);
        if (msaResult.contains("pending")) msaResult = "MSA SDK: failed (code=" + code + ")";

        // ---- Xiaomi reflection path ----
        xiaomiResult = testXiaomi(this);
        Log.i(TAG, xiaomiResult.replace("\n", " "));
        render();
    }

    private static String testXiaomi(Context ctx) {
        try {
            Class<?> c = Class.forName("com.android.id.impl.IdProviderImpl");
            Object impl = c.newInstance();
            Method m = c.getMethod("getOAID", Context.class);
            String oaid = (String) m.invoke(impl, ctx);
            return "Xiaomi reflect:\n" + oaid;
        } catch (Throwable t) {
            return "Xiaomi reflect: failed\n(" + t.getClass().getSimpleName() + ")";
        }
    }

    private void render() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean ok = hasId(msaResult) && hasId(xiaomiResult);
                tv.setTextColor(ok ? Color.parseColor("#1B5E20") : Color.parseColor("#B71C1C"));
                tv.setText(msaResult + "\n\n────────\n\n" + xiaomiResult);
            }
        });
    }

    private static boolean hasId(String s) {
        return s != null && s.matches("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*");
    }
}
