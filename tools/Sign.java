// Signs an APK with APK Signature Scheme v2 (enough for Android 7 and newer; the app needs 10).
// Usage: java Sign <in.apk> <out.apk> <keystore.p12>, password in SCREENSHOT_MARKER_KEYSTORE_PASSWORD.
// Version: 2026-11-18
import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class Sign {
    public static void main(String[] a) throws Exception {
        String pass = System.getenv("SCREENSHOT_MARKER_KEYSTORE_PASSWORD");
        if (pass == null) throw new IllegalStateException("SCREENSHOT_MARKER_KEYSTORE_PASSWORD is not set");
        char[] pw = pass.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(a[2])) { ks.load(in, pw); }
        PrivateKey key = (PrivateKey) ks.getKey("marker", pw);
        X509Certificate cert = (X509Certificate) ks.getCertificate("marker");
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("marker", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(new File(a[0]))
                .setOutputApk(new File(a[1]))
                .setMinSdkVersion(29)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }
}
