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

public class MainActivity extends Activity {

    private static final String TAG = "OAIDTEST";
    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tv = new TextView(this);
        tv.setTextSize(20f);
        tv.setPadding(48, 96, 48, 48);
        tv.setGravity(Gravity.CENTER);
        tv.setText("Requesting OAID...");
        setContentView(tv);

        final IIdentifierListener listener = new IIdentifierListener() {
            @Override
            public void OnSupport(final IdSupplier supplier) {
                final String oaid = (supplier != null) ? supplier.getOAID() : null;
                final boolean supported = (supplier != null) && supplier.isSupported();
                Log.i(TAG, "OnSupport called -> OAID=" + oaid + " supported=" + supported);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (oaid != null && oaid.length() > 0) {
                            tv.setTextColor(Color.parseColor("#1B5E20"));
                            tv.setText("OAID obtained:\n\n" + oaid);
                        } else {
                            tv.setTextColor(Color.RED);
                            tv.setText("OnSupport called but OAID empty");
                        }
                    }
                });
            }
        };

        int code = MdidSdkHelper.InitSdk(this, false, listener);
        Log.i(TAG, "InitSdk returned code=" + code);

        // If nothing called the listener shortly after, report failure on screen.
        tv.postDelayed(new Runnable() {
            @Override public void run() {
                CharSequence t = tv.getText();
                if (t != null && t.toString().startsWith("Requesting")) {
                    tv.setTextColor(Color.RED);
                    tv.setText("Failed to get OAID\n(InitSdk code=" + code + ", no callback)\n\n"
                            + "Enable the OAID Provider module for this app.");
                }
            }
        }, 1500);
    }
}
