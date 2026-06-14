package com.bun.miitmdid.interfaces;

// Minimal stand-in for the real MSA SDK IdSupplier interface.
public interface IdSupplier {
    boolean isSupported();
    boolean isLimited();
    String getOAID();
    String getVAID();
    String getAAID();
}
