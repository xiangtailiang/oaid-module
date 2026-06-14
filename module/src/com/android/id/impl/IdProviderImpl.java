package com.android.id.impl;

import android.content.Context;

import com.oaidfix.OaidModule;

/**
 * Stand-in for MIUI's framework class {@code com.android.id.impl.IdProviderImpl}.
 *
 * On a real Xiaomi/Redmi ROM this class lives in the system framework and apps
 * read the OAID reflectively via {@code IdProviderImpl#getOAID(Context)}. On
 * AOSP/LineageOS it doesn't exist, so {@code Class.forName(...)} throws.
 * {@link OaidModule} hooks {@code Class.forName} to hand back THIS class when the
 * real lookup fails, and reflection then calls these methods across class loaders.
 */
public class IdProviderImpl {

    public IdProviderImpl() {
    }

    public String getOAID(Context context) {
        return OaidModule.OAID;
    }

    public String getOAID() {
        return OaidModule.OAID;
    }

    public String getUDID(Context context) {
        return OaidModule.OAID;
    }

    public String getDefaultUDID(Context context) {
        return OaidModule.OAID;
    }

    public String getVAID(Context context) {
        return "";
    }

    public String getAAID(Context context) {
        return "";
    }

    public boolean isSupported() {
        return true;
    }
}
