package net.ravenclaw.ravenclawspingequalizer.cryptography;

import net.ravenclaw.ravenclawspingequalizer.obfuscation.S;

public class ServerKey {
    // Public Key of the Server to verify signatures - retrieved from encrypted storage
    public static String getPublicKey() {
        return S.PUB_KEY();
    }
}
