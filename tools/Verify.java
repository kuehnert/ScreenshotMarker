// Checks an APK's signature the way Android does. Usage: java Verify <app.apk>. Version: 2026-11-18
import com.android.apksig.ApkVerifier;

import java.io.File;

public class Verify {
    public static void main(String[] a) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[0])).build().verify();
        for (Object e : r.getErrors()) System.out.println("Fehler: " + e);
        if (!r.isVerified()) System.exit(1);
        System.out.println("Signatur geprüft (v2: " + r.isVerifiedUsingV2Scheme() + ")");
    }
}
