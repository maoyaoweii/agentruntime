package com.agentruntime.dsh.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only encrypted credentials leave memory; the encryption key is owned by Android Keystore. */
public final class CredentialVault {
    public interface KeyAccess { SecretKey get() throws Exception; }
    private static final String ALIAS="dsh_mobile_api_key_v1";
    private final SharedPreferences preferences;
    private final KeyAccess key;

    public CredentialVault(Context context) {
        this(context.getSharedPreferences("dsh_credentials",Context.MODE_PRIVATE),CredentialVault::androidKey);
    }
    public CredentialVault(SharedPreferences preferences,KeyAccess key) { this.preferences=preferences; this.key=key; }
    public boolean hasKey() { return preferences.contains("ciphertext"); }
    SharedPreferences preferences() { return preferences; }

    public synchronized void save(String value) throws Exception {
        SharedPreferences.Editor editor=preferences.edit();
        put(editor,value);
        if(!editor.commit()) throw new java.io.IOException("Credential storage unavailable");
    }

    synchronized void put(SharedPreferences.Editor editor,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key.get());
        byte[] encrypted=cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        editor.putString("ciphertext",Base64.getEncoder().encodeToString(encrypted))
                .putString("iv",Base64.getEncoder().encodeToString(cipher.getIV()));
    }

    public synchronized String load() throws Exception {
        if(!hasKey()) return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key.get(),new GCMParameterSpec(128,Base64.getDecoder().decode(preferences.getString("iv",""))));
        byte[] plain=cipher.doFinal(Base64.getDecoder().decode(preferences.getString("ciphertext","")));
        return new String(plain,java.nio.charset.StandardCharsets.UTF_8);
    }

    public synchronized void clear() {
        if(!preferences.edit().remove("ciphertext").remove("iv").commit()) throw new IllegalStateException("Credential storage unavailable");
    }

    private static synchronized SecretKey androidKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
