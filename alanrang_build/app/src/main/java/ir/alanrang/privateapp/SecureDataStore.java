package ir.alanrang.privateapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureDataStore {
    private static final String PREFS = "alanrang_private_secure_store_v1";
    private static final String KEY_ALIAS = "alanrang_private_core_aes_v1";
    private static final String PREFIX = "gcm1:";
    private final SharedPreferences prefs;

    public SecureDataStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    public synchronized boolean save(String name, String value) {
        try {
            if (name == null || name.trim().isEmpty()) return false;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] aad = ("AlanRangPrivate|" + name).getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            String packed = PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            if (!prefs.edit().putString(name, packed).commit()) return false;
            return (value == null ? "" : value).equals(load(name));
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String load(String name) {
        try {
            if (name == null || name.trim().isEmpty()) return "";
            String packed = prefs.getString(name, "");
            if (packed == null || !packed.startsWith(PREFIX)) return "";
            String[] parts = packed.split(":", 3);
            if (parts.length != 3) return "";
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(("AlanRangPrivate|" + name).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized boolean remove(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        return prefs.edit().remove(name).commit() && !prefs.contains(name);
    }
}
