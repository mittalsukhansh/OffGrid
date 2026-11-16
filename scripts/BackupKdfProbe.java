import com.example.offgrid.IdentityBackup;
import java.time.Instant;
import java.util.Arrays;

/** Measures production backup API on this JVM; does not represent Android-device performance. */
public final class BackupKdfProbe {
    public static void main(String[] args) throws Exception {
        byte[] plain = new byte[256];
        char[] phrase = "Public benchmark-only phrase".toCharArray();
        for (int i = -1; i < 3; i++) {
            long start = System.nanoTime();
            byte[] encrypted = IdentityBackup.encrypt(plain, phrase);
            long middle = System.nanoTime();
            byte[] decoded = IdentityBackup.decrypt(encrypted, phrase);
            long end = System.nanoTime();
            if (!Arrays.equals(plain, decoded)) throw new AssertionError("Round-trip failed");
            if (i == -1) {
                System.out.println("{\"scope\":\"Host JVM only; not Android or radio performance\",\"capturedUtc\":\"" + Instant.now() + "\",\"javaVersion\":\"" + System.getProperty("java.version") + "\",\"format\":\"OGB1\",\"N\":32768,\"r\":8,\"p\":1,\"derivedKeyBytes\":32,\"coreMemoryBytes\":33554432,\"samples\":[");
            } else {
                System.out.print((i == 0 ? "" : ",") + "{\"encryptMs\":" + (middle - start) / 1_000_000 + ",\"decryptMs\":" + (end - middle) / 1_000_000 + "}");
            }
        }
        System.out.println("]}");
        Arrays.fill(phrase, '\0');
    }
}
