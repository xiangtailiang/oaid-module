package com.bun.miitmdid.core;

import android.content.Context;
import android.util.Log;

import com.bun.miitmdid.interfaces.IIdentifierListener;

// Minimal stand-in for the real MSA SDK helper.
// Emulates AOSP/LineageOS behaviour: no vendor OAID service, so init fails
// with INIT_ERROR_DEVICE_NOSUPPORT and the listener is never called.
public class MdidSdkHelper {

    public static final int INIT_ERROR_DEVICE_NOSUPPORT = 1008612;

    public static int InitSdk(Context context, boolean isCDID, IIdentifierListener listener) {
        Log.i("OAIDTEST", "real MdidSdkHelper.InitSdk running (no vendor OAID) -> returning "
                + INIT_ERROR_DEVICE_NOSUPPORT + ", listener NOT called");
        return INIT_ERROR_DEVICE_NOSUPPORT;
    }
}
