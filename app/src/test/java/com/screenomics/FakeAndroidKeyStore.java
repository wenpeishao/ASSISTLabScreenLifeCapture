package com.screenomics;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * A stand-in for the AndroidKeyStore provider, for tests only.
 *
 * HttpSignatureInterceptor signs every request with a key it loads from
 * AndroidKeyStore, and the JVM Robolectric runs on has no such provider -- so
 * without this, no request the app makes can be tested at all: signing throws
 * and the call never reaches the wire.
 *
 * The keys here are ordinary in-memory RSA keys. That is the point: this proves
 * nothing about hardware key storage and is not meant to. It exists so the
 * request the app builds can be inspected.
 */
public final class FakeAndroidKeyStore {

    private static final Map<String, Key> KEYS = new HashMap<>();

    private FakeAndroidKeyStore() {}

    /** Make {@code alias} resolvable through KeyStore.getInstance("AndroidKeyStore"). */
    public static void install(String alias, PrivateKey key) {
        KEYS.put(alias, key);
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(new FakeProvider());
        }
    }

    public static final class FakeProvider extends Provider {
        public FakeProvider() {
            // The (String, double, String) form: compiled against android.jar,
            // whose Provider stub predates the string-version constructor.
            super("AndroidKeyStore", 1.0, "in-memory stand-in for tests");
            put("KeyStore.AndroidKeyStore", Spi.class.getName());
        }
    }

    public static final class Spi extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias, char[] password) { return KEYS.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return new Date(0); }
        @Override public void engineSetKeyEntry(String a, Key k, char[] p, Certificate[] c) { KEYS.put(a, k); }
        @Override public void engineSetKeyEntry(String a, byte[] k, Certificate[] c) { }
        @Override public void engineSetCertificateEntry(String a, Certificate c) { }
        @Override public void engineDeleteEntry(String alias) { KEYS.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return KEYS.containsKey(alias); }
        @Override public int engineSize() { return KEYS.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return KEYS.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(OutputStream stream, char[] password) { }
        @Override public void engineLoad(InputStream stream, char[] password) { }
    }
}
