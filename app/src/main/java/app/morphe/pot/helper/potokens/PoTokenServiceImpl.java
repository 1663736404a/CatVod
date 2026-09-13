package app.morphe.pot.helper.potokens;

/**
 * Native bridge for libpot.so.
 *
 * <p>The JNI symbol exported by the library is
 * {@code Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens},
 * and JNI resolves it from the fully qualified class name. The library exports no
 * {@code JNI_OnLoad}, so there is no {@code RegisterNatives} path either: this package and
 * class name are part of the ABI and must not be renamed, repackaged or obfuscated.
 * See {@code proguard-rules.pro} and {@code build.gradle::prepareSpiderJar}.
 *
 * @param data the challenge protobuf understood by the native minter
 * @return {@code [0]} raw Tink key, {@code [1]} payload to encrypt, {@code [2]} token data
 */
public final class PoTokenServiceImpl {
    private PoTokenServiceImpl() {}

    public static native byte[][][] mintMorpheIntegrityTokens(byte[] data);
}
