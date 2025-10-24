package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;

public class HttpSignatureInterceptor implements Interceptor {
    private static final String TAG = "SCREENOMICS_SIGN";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mindpulse_client_key"; // must match RegisterActivity

    private static final String HDR_DATE = "Date";
    private static final String HDR_DIGEST = "Digest";
    private static final String HDR_SIGNATURE = "Signature";
    private static final String HDR_NONCE = "X-Request-Nonce";
    private static final String HDR_TS = "X-Request-Timestamp";
    private static final String HDR_REQ_ID = "X-Request-Id";

    private final Context appContext;

    public HttpSignatureInterceptor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // Need participant id for keyId=...; if missing, proceed unsigned (pre-enrollment).
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        String pptId = prefs.getString("ppt_id", null);
        if (pptId == null || pptId.isEmpty()) {
            return chain.proceed(original);
        }

        // ----- Prepare headers (reuse if already present) -----
        String dateHeader = headerOrDefault(original, HDR_DATE, httpDateNow());

        byte[] body = peekBodyBytes(original);
        String computedDigest = "SHA-256=" + base64(sha256(body));
        String digestHeader = headerOrDefault(original, HDR_DIGEST, computedDigest);

        // Anti-replay headers (required by server)
        String nonce = headerOrDefault(original, HDR_NONCE, UUID.randomUUID().toString());
        String tsIso = headerOrDefault(original, HDR_TS, iso8601ZuluNow());
        // Optional request id for tracing
        String reqId = headerOrDefault(original, HDR_REQ_ID, "and-" + UUID.randomUUID());

        // ----- Canonical string to sign -----
        String pathWithQuery = original.url().encodedPath();
        if (original.url().encodedQuery() != null) {
            pathWithQuery += "?" + original.url().encodedQuery();
        }

        // Order must match the "headers=" list below
        String canonical =
                "(request-target): " + original.method().toLowerCase(Locale.US) + " " + pathWithQuery + "\n" +
                        "date: " + dateHeader + "\n" +
                        "digest: " + digestHeader + "\n" +
                        "x-request-nonce: " + nonce + "\n" +
                        "x-request-timestamp: " + tsIso;

        // ----- Sign (PSS preferred, PKCS#1 v1.5 fallback) -----
        String signatureB64;
        try {
            PrivateKey key = loadPrivateKey();
            byte[] sigBytes = trySignPssThenPkcs1(key, canonical.getBytes(StandardCharsets.UTF_8));
            if (sigBytes == null) {
                throw new IOException("Both RSA-PSS and PKCS#1 signing attempts failed");
            }
            signatureB64 = base64(sigBytes);
        } catch (Exception e) {
            throw new IOException("Failed to sign request", e);
        }

        // ----- HTTP Signature header -----
        String signatureHeader =
                "keyId=\"" + pptId + "\"," +
                        "algorithm=\"rsa-sha256\"," +
                        "headers=\"(request-target) date digest x-request-nonce x-request-timestamp\"," +
                        "signature=\"" + signatureB64 + "\"";

        // Helpful debug logs (compare with server)
        Log.d(TAG, "CANONICAL:\n" + canonical);
        Log.d(TAG, "HEADERS: Date=" + dateHeader + " Digest=" + digestHeader +
                " " + HDR_NONCE + "=" + nonce + " " + HDR_TS + "=" + tsIso);
        Log.d(TAG, "SIGNATURE: " + signatureHeader);

        // ----- Build signed request (preserve any existing values) -----
        Request.Builder nb = original.newBuilder()
                .header(HDR_DATE, dateHeader)
                .header(HDR_DIGEST, digestHeader)
                .header(HDR_SIGNATURE, signatureHeader)
                .header(HDR_NONCE, nonce)
                .header(HDR_TS, tsIso)
                .header(HDR_REQ_ID, reqId);

        Request signed = nb.build();
        return chain.proceed(signed);
    }

    /** Try RSA-PSS (two provider names), then PKCS#1 v1.5. Returns null if all fail. */
    private static byte[] trySignPssThenPkcs1(PrivateKey key, byte[] data) {
        // 1) Preferred PSS: "SHA256withRSA/PSS"
        byte[] sig = signWithPss(key, data, "SHA256withRSA/PSS");
        if (sig != null) {
            Log.d(TAG, "Using SHA256withRSA/PSS");
            return sig;
        }
        // 2) Some devices only expose PSS as "RSASSA-PSS"
        sig = signWithPss(key, data, "RSASSA-PSS");
        if (sig != null) {
            Log.d(TAG, "Using RSASSA-PSS (legacy name)");
            return sig;
        }
        // 3) Fallback to PKCS#1 v1.5
        sig = signWithAlg(key, data, "SHA256withRSA");
        if (sig != null) {
            Log.w(TAG, "Falling back to SHA256withRSA (PKCS#1 v1.5)");
            return sig;
        }
        Log.e(TAG, "All signing algorithms failed (PSS and PKCS#1)");
        return null;
    }

    /** Attempt PSS with the given algorithm name and SHA-256 parameters. */
    private static byte[] signWithPss(PrivateKey key, byte[] data, String alg) {
        try {
            Signature s = Signature.getInstance(alg);
            // SHA-256 digest, MGF1(SHA-256), 32-byte salt (matches server), trailerField=1
            PSSParameterSpec p = new PSSParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
            s.setParameter(p);
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (InvalidKeyException e) {
            Log.w(TAG, "PSS initSign failed (key not allowed for PSS?) alg=" + alg + " : " + e.getMessage());
            return null;
        } catch (InvalidAlgorithmParameterException e) {
            Log.w(TAG, "PSS params unsupported, alg=" + alg + " : " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "PSS signing failed, alg=" + alg + " : " + e.getMessage());
            return null;
        }
    }

    /** Attempt generic RSA signature with the given algorithm name (e.g., SHA256withRSA). */
    private static byte[] signWithAlg(PrivateKey key, byte[] data, String alg) {
        try {
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (InvalidKeyException e) {
            Log.w(TAG, "PKCS#1 initSign failed (key not allowed for PKCS1?) : " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "PKCS#1 signing failed: " + e.getMessage());
            return null;
        }
    }

    private static String headerOrDefault(Request req, String name, String fallback) {
        String v = req.header(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static byte[] peekBodyBytes(Request req) throws IOException {
        if (req.body() == null) return new byte[0];
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        return buf.readByteArray();
    }

    private static byte[] sha256(byte[] in) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(in); }
        catch (Exception e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static String base64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String httpDateNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    /** RFC 3339 / ISO-8601 UTC like 2025-10-24T04:11:14Z */
    private static String iso8601ZuluNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static PrivateKey loadPrivateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return (PrivateKey) ks.getKey(KEY_ALIAS, null);
    }
}
