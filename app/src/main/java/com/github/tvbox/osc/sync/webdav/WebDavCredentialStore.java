package com.github.tvbox.osc.sync.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.github.tvbox.osc.base.App;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class WebDavCredentialStore {
    private static final String STORE = "webdav_secure_credentials";
    private static final String ALIAS = "tvbox.webdav.credentials.v1";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";

    public static final class Credentials {
        public final String username;
        public final String password;

        private Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    public void save(String username, String password) throws GeneralSecurityException {
        preferences().edit()
                .putString(USERNAME, encrypt(username == null ? "" : username))
                .putString(PASSWORD, encrypt(password == null ? "" : password))
                .apply();
    }

    public Credentials load() throws GeneralSecurityException {
        String username = preferences().getString(USERNAME, null);
        String password = preferences().getString(PASSWORD, null);
        if (username == null || password == null) return null;
        return new Credentials(decrypt(username), decrypt(password));
    }

    public void clear() {
        preferences().edit().clear().apply();
    }

    private SharedPreferences preferences() {
        return App.getInstance().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private String encrypt(String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer output = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        output.putInt(iv.length).put(iv).put(encrypted);
        return Base64.encodeToString(output.array(), Base64.NO_WRAP);
    }

    private String decrypt(String value) throws GeneralSecurityException {
        byte[] stored = Base64.decode(value, Base64.NO_WRAP);
        ByteBuffer input = ByteBuffer.wrap(stored);
        int ivLength = input.getInt();
        if (ivLength < 12 || ivLength > 16 || input.remaining() <= ivLength) {
            throw new GeneralSecurityException("Invalid encrypted WebDAV credential");
        }
        byte[] iv = new byte[ivLength];
        input.get(iv);
        byte[] encrypted = new byte[input.remaining()];
        input.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
        java.security.Key existing = keyStore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
