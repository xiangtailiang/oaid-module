package com.oaidtest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import com.bun.miitmdid.core.MdidSdkHelper;
import com.bun.miitmdid.interfaces.IIdentifierListener;
import com.bun.miitmdid.interfaces.IdSupplier;

/**
 * Exercises the MSA unified SDK (com.bun.miitmdid) OAID path so the module can be
 * verified on any device. On a real device the OPPO/bindService and other vendor
 * paths are covered by the Android_CN_OAID demo.
 */
public class MainActivity extends Activity {

    private static final String TAG = "OAIDTEST";
    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new TextView(this);
        tv.setTextSize(20f);
        tv.setPadding(48, 120, 48, 48);
        tv.setGravity(Gravity.CENTER);
        tv.setText("Requesting OAID via MSA SDK...");
        setContentView(tv);

        final IIdentifierListener listener = new IIdentifierListener() {
            @Override
            public void OnSupport(IdSupplier supplier) {
                final String oaid = (supplier != null) ? supplier.getOAID() : null;
                Log.i(TAG, "MSA SDK OAID=" + oaid);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (oaid != null && oaid.length() > 0) {
                            tv.setTextColor(Color.parseColor("#1B5E20"));
                            tv.setText("MSA SDK OAID:\n\n" + oaid);
                        } else {
                            tv.setTextColor(Color.parseColor("#B71C1C"));
                            tv.setText("OnSupport called but OAID empty");
                        }
                    }
                });
            }
        };
        int code = MdidSdkHelper.InitSdk(this, false, listener);
        Log.i(TAG, "InitSdk code=" + code);

        tv.postDelayed(new Runnable() {
            @Override public void run() {
                CharSequence t = tv.getText();
                if (t != null && t.toString().startsWith("Requesting")) {
                    tv.setTextColor(Color.parseColor("#B71C1C"));
                    tv.setText("Failed to get OAID\n(InitSdk code=" + code + ", no callback)\n\n"
                            + "Enable the OAID Provider module for this app.");
                }
            }
        }, 1500);
    }
}
