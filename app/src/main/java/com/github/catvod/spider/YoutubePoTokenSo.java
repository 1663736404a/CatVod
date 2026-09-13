package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;
import android.util.Pair;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.spider.pot.IntegrityToken;
import com.github.catvod.spider.pot.KeySet;
import com.github.catvod.spider.pot.PoTokenResult;
import app.morphe.pot.helper.potokens.PoTokenServiceImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Offline GVS poToken minter backed by Morphe's libpot.so.
 *
 * <p>Replaces the previous WebView route entirely: no network call, no JS engine, no
 * {@code esm.sh} module graph. Startup cost is one {@code dlopen} instead of a WebView
 * plus three YouTube round trips, which is the whole point on low-end TV boxes.
 *
 * <h3>Why the library is loaded by path</h3>
 * The spider ships as {@code custom_spider.jar}, which is a bare dex container: the build
 * copies only {@code smali/com/github/catvod/spider} out of the R8 output (see
 * {@code build.gradle::prepareSpiderJar}) and {@code checkJar.ps1} rejects anything else.
 * There is no {@code lib/<abi>/} inside a JAR and no {@code nativeLibraryDir} for it, so
 * {@code System.loadLibrary("pot")} can never resolve. The library is therefore located at
 * runtime, copied into the host's private storage and opened with
 * {@link System#load(String)}.
 *
 * <p>Resolution order, first hit wins:
 * <ol>
 *   <li>{@code ext.pot_so_path} — absolute path in the site config.</li>
 *   <li>{@code <files>/catvod_pot/libpot.so} and the usual sideload directories.</li>
 *   <li>An installed PoToken Helper package, whose {@code nativeLibraryDir} already holds a
 *       per-ABI {@code libpot.so}.</li>
 * </ol>
 */
final class YoutubePoTokenSo {

    /** Package that ships libpot.so per ABI; used only as a library source. */
    private static final String HELPER_PACKAGE = "app.morphe.pot.helper";
    private static final String SO_NAME = "libpot.so";

    /** dlopen is process-wide, so the outcome is cached for the whole process. */
    private static volatile boolean loaded;
    private static volatile boolean attempted;
    private static volatile String loadedFrom;

    private final YoutubePoToken configured;
    private final Context context;
    private final String configuredPath;

    YoutubePoTokenSo(Context context, YoutubePoToken configured, String configuredPath) {
        this.context = context == null ? null : context.getApplicationContext();
        this.configured = configured;
        this.configuredPath = configuredPath;
    }

    /** Mints the token for one visitor binding: websafe base64 poToken, or null. */
    String token(String visitorData) {
        // A token pinned in the config still wins, so an existing setup keeps working.
        if (configured != null) {
            String pinned = configured.get(YoutubePlayer.CLIENT, visitorData);
            if (pinned != null && !pinned.isEmpty()) {
                SpiderDebug.log("PoTokenSo: using po_token from config");
                return pinned;
            }
        }
        if (visitorData == null || visitorData.isEmpty()) return null;
        if (!load()) return null;
        try {
            byte[][][] minted = PoTokenServiceImpl.mintMorpheIntegrityTokens(buildChallenge(visitorData));
            if (minted == null || minted.length < 3) {
                SpiderDebug.log("PoTokenSo 失败: mint 返回 " + (minted == null ? "null" : minted.length + " 组"));
                return null;
            }
            byte[] rawKey = at(minted, 0);
            byte[] data = at(minted, 1);
            byte[] tokenData = at(minted, 2);
            if (rawKey == null || data == null || tokenData == null) {
                SpiderDebug.log("PoTokenSo 失败: mint 输出不完整"
                        + " rawKey=" + (rawKey == null ? "null" : rawKey.length)
                        + " data=" + (data == null ? "null" : data.length)
                        + " tokenData=" + (tokenData == null ? "null" : tokenData.length));
                return null;
            }
            byte[] encrypted = encrypt(rawKey, data);
            if (encrypted == null || encrypted.length == 0) return null;
            byte[] proto = new PoTokenResult(new IntegrityToken(encrypted, tokenData)).toByteArray();
            if (proto == null || proto.length == 0) {
                SpiderDebug.log("PoTokenSo 失败: 序列化为空");
                return null;
            }
            // YTSabr.buildStreamerContext b64url-decodes this again for the protobuf field.
            String token = Base64.encodeToString(proto, Base64.URL_SAFE | Base64.NO_WRAP);
            if (token == null || token.isEmpty()) return null;
            SpiderDebug.log("PoTokenSo 成功: len=" + token.length() + " so=" + loadedFrom);
            return token;
        } catch (UnsatisfiedLinkError error) {
            // The symbol is missing, so no later call can succeed either.
            loaded = false;
            SpiderDebug.log("PoTokenSo 失败: 符号缺失 " + error);
            return null;
        } catch (Throwable error) {
            SpiderDebug.log("PoTokenSo 失败: " + error);
            return null;
        }
    }

    private static byte[] at(byte[][][] minted, int index) {
        byte[][] group = index < minted.length ? minted[index] : null;
        if (group == null || group.length == 0) return null;
        byte[] value = group[0];
        return value == null || value.length == 0 ? null : value;
    }

    /* ------------------------------------------------------------------ */
    /* native library loading                                             */
    /* ------------------------------------------------------------------ */

    private boolean load() {
        if (attempted) return loaded;
        synchronized (YoutubePoTokenSo.class) {
            if (attempted) return loaded;
            attempted = true;
            for (File candidate : candidates()) {
                if (candidate == null || !candidate.isFile() || candidate.length() == 0) continue;
                File target = candidate;
                // dlopen needs the file on a path the host process may map as executable.
                if (!isPrivate(candidate)) {
                    target = stage(candidate);
                    if (target == null) continue;
                }
                try {
                    System.load(target.getAbsolutePath());
                    loaded = true;
                    loadedFrom = candidate.getAbsolutePath();
                    SpiderDebug.log("PoTokenSo: 已加载 " + loadedFrom);
                    return true;
                } catch (Throwable error) {
                    // Usually a foreign ABI; keep trying the remaining candidates.
                    SpiderDebug.log("PoTokenSo: 加载失败 " + candidate + " " + error);
                }
            }
            SpiderDebug.log("PoTokenSo: 未找到 " + SO_NAME + "，请在 ext 配置 pot_so_path 或安装 " + HELPER_PACKAGE);
            return false;
        }
    }

    /** Every place the library may legitimately come from, in priority order. */
    private List<File> candidates() {
        List<File> files = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isEmpty()) {
            File configured = new File(configuredPath);
            // Accept either the file itself or a directory holding it.
            files.add(configured.isDirectory() ? new File(configured, SO_NAME) : configured);
        }
        if (context != null) {
            files.add(new File(context.getFilesDir(), "catvod_pot/" + SO_NAME));
            files.add(new File(context.getFilesDir(), SO_NAME));
            File external = context.getExternalFilesDir(null);
            if (external != null) files.add(new File(external, SO_NAME));
            files.addAll(helperLibraries());
        }
        return files;
    }

    /** libpot.so as installed by the helper package, if it is present. */
    private List<File> helperLibraries() {
        List<File> files = new ArrayList<>();
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(HELPER_PACKAGE, 0);
            if (info.nativeLibraryDir != null) {
                files.add(new File(info.nativeLibraryDir, SO_NAME));
            }
        } catch (Throwable ignored) {
            // Helper not installed; the configured paths remain the only source.
        }
        return files;
    }

    private boolean isPrivate(File file) {
        if (context == null) return false;
        String path = file.getAbsolutePath();
        File dir = context.getFilesDir();
        if (dir != null && path.startsWith(dir.getAbsolutePath())) return true;
        // A helper's nativeLibraryDir is already extracted and executable.
        return loadedFromHelper(path);
    }

    private boolean loadedFromHelper(String path) {
        return path.contains("/" + HELPER_PACKAGE + "-") || path.contains("/" + HELPER_PACKAGE + "/");
    }

    /** Copies the library into private storage so it can be mapped executable. */
    private File stage(File source) {
        if (context == null) return null;
        try {
            File dir = new File(context.getFilesDir(), "catvod_pot");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File target = new File(dir, SO_NAME);
            // Re-copy whenever the source changed, so replacing the file takes effect.
            if (target.isFile() && target.length() == source.length()
                    && target.lastModified() >= source.lastModified()) {
                return target;
            }
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            //noinspection ResultOfMethodCallIgnored
            target.setReadable(true, true);
            return target;
        } catch (Throwable error) {
            SpiderDebug.log("PoTokenSo: 复制 so 失败 " + error);
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* crypto                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Wraps the minted payload exactly as PotHelper does: AES-GCM under the Tink key the
     * native side returns, with the key id prefixed when present.
     */
    private static byte[] encrypt(byte[] rawKey, byte[] data) {
        try {
            Pair<Integer, SecretKeySpec> keyPair = KeySet.parseFrom(rawKey).getKeyPair();
            Integer keyId = keyPair.first;
            SecretKeySpec key = keyPair.second;
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            byte[] result = new byte[data.length + 28];
            System.arraycopy(iv, 0, result, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            cipher.doFinal(data, 0, data.length, result, 12);
            if (keyId != null) {
                byte[] output = new byte[result.length + 5];
                output[0] = 1;
                byte[] identifier = ByteBuffer.allocate(5).put(output[0]).putInt(keyId).array();
                System.arraycopy(identifier, 0, output, 0, identifier.length);
                System.arraycopy(result, 0, output, identifier.length, result.length);
                result = output;
            }
            return result;
        } catch (Throwable error) {
            SpiderDebug.log("PoTokenSo 失败: 加密 " + error);
            return null;
        }
    }

    /**
     * Builds the challenge the native minter decodes.
     *
     * <p>In PotHelper these bytes arrive from the GMS PoTokens service, so their exact shape is
     * not established from public source. The library decodes them with nanopb into its
     * {@code Challenge} message, and the visitor binding is what the GVS token must be tied to,
     * so the identifier is passed as field 1. If a build turns out to need the original proto,
     * capture one real call and return those bytes here instead:
     *
     * <pre>
     * frida -U -n com.google.android.youtube -e '
     *   Interceptor.attach(Module.findExportByName("libpot.so",
     *     "Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens"),
     *     { onEnter(a) { /* dump the jbyteArray at a[2] */ } });'
     * </pre>
     */
    private static byte[] buildChallenge(String visitorData) {
        byte[] identifier = visitorData.getBytes(StandardCharsets.UTF_8);
        byte[] header = tag(1, identifier.length);
        byte[] out = new byte[header.length + identifier.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(identifier, 0, out, header.length, identifier.length);
        return out;
    }

    /** Length-delimited protobuf tag plus length, both varint encoded. */
    private static byte[] tag(int field, int length) {
        byte[] key = varint((field << 3) | 2);
        byte[] size = varint(length);
        byte[] out = new byte[key.length + size.length];
        System.arraycopy(key, 0, out, 0, key.length);
        System.arraycopy(size, 0, out, key.length, size.length);
        return out;
    }

    private static byte[] varint(long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(10);
        long remaining = value;
        while (true) {
            int b = (int) (remaining & 0x7f);
            remaining >>>= 7;
            if (remaining != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                break;
            }
        }
        return out.toByteArray();
    }

}
