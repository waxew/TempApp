package ir.alanrang.privateapp;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCrypto {
    public static final String FORMAT = "ALR_PRIVATE_BACKUP_V1";
    private static final int ITERATIONS = 210000;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private BackupCrypto() {}

    private static byte[] random(int count) { byte[] out = new byte[count]; RNG.nextBytes(out); return out; }

    static byte[] pbkdf2Sha256(char[] password, byte[] salt, int iterations, int length) throws Exception {
        if (iterations < 1 || length < 1) throw new IllegalArgumentException("bad kdf params");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new String(password).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] result = new byte[length];
        int offset = 0;
        for (int block = 1; block <= blocks; block++) {
            mac.reset();
            mac.update(salt);
            mac.update(ByteBuffer.allocate(4).putInt(block).array());
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                mac.reset();
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            int copy = Math.min(hLen, length - offset);
            System.arraycopy(t, 0, result, offset, copy);
            offset += copy;
        }
        return result;
    }

    public static String encryptPortable(String plaintext, String password) throws Exception {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("BACKUP_PASSWORD_TOO_SHORT");
        byte[] salt = random(16), iv = random(12);
        byte[] key = pbkdf2Sha256(password.toCharArray(), salt, ITERATIONS, KEY_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal((plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("kdf", "PBKDF2-HMAC-SHA256");
        o.put("iterations", ITERATIONS);
        o.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        o.put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return o.toString();
    }

    public static String decryptPortable(String envelope, String password) throws Exception {
        JSONObject o = new JSONObject(envelope);
        if (!FORMAT.equals(o.optString("format"))) throw new IllegalArgumentException("BACKUP_FORMAT_UNSUPPORTED");
        int iterations = o.optInt("iterations", 0);
        if (iterations < 100000 || iterations > 1000000) throw new IllegalArgumentException("BACKUP_KDF_INVALID");
        byte[] salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(o.getString("ciphertext"), Base64.NO_WRAP);
        if (salt.length < 8 || iv.length != 12 || encrypted.length < 16) throw new IllegalArgumentException("BACKUP_ENVELOPE_INVALID");
        byte[] key = pbkdf2Sha256((password == null ? "" : password).toCharArray(), salt, iterations, KEY_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
