package net.ravenclaw.ravenclawspingequalizer.cryptography.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoUtils;
import net.ravenclaw.ravenclawspingequalizer.cryptography.ModPrivateKey;

public class CryptoHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoHandler.class);
    final String MOD_HASH_STRING;

    public CryptoHandler() {
        byte[] modHash = CryptoUtils.calculateModHash();
        this.MOD_HASH_STRING = CryptoUtils.bytesToHex(modHash);
        
        if (MOD_HASH_STRING == null || MOD_HASH_STRING.isEmpty()) {
            throw new IllegalStateException("Failed to calculate mod hash");
        }

        if (ModPrivateKey.getPrivateKey().equals("DEV_ONLY_PRIVATE_KEY_1234567890")) {
            LOGGER.info("Warning: Using default development private key. This should be replaced in production builds.");
        }
    }

    public void init() {
        LOGGER.info("CryptoHandler initialized with Mod Hash: {}", MOD_HASH_STRING);
        LOGGER.info("Signed mod hash using private key: {}", CryptoUtils.bytesToHex(CryptoUtils.signPayload(MOD_HASH_STRING.getBytes(), ModPrivateKey.getPrivateKey())));
    }

    public String getModHashString() {
        return MOD_HASH_STRING;
    }
}
