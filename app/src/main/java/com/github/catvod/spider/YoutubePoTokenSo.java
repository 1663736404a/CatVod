package com.github.catvod.spider;

import android.content.Context;
import android.os.Build;
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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Offline GVS poToken minter backed by Morphe's libpot.so.
 *
 * <p>Replaces the previous WebView route entirely: no network call, no JS engine, no
 * {@code esm.sh} module graph. Startup cost is one {@code dlopen} instead of a WebView plus
 * three YouTube round trips, which is what made the old path unusable on low-end TV boxes.
 *
 * <h3>Where the library comes from</h3>
 * The spider ships as {@code custom_spider.jar}, and a JAR gets no {@code nativeLibraryDir}
 * from the platform, so {@code System.loadLibrary("pot")} can never resolve. The library is
 * therefore carried inside this JAR under {@code lib/<abi>/libpot.so}, located at runtime by
 * finding the JAR itself, extracted into the host's private storage and opened with
 * {@link System#load(String)}. Nothing has to be placed on the device by hand.
 *
 * <p>{@code ext.pot_so_path} still overrides everything, which is useful for testing a
 * different build of the library without rebuilding the JAR.
 */
final class YoutubePoTokenSo {

    private static final String SO_NAME = "libpot.so";
    /** GetByteArrayRegion in the library is capped at 0x300 bytes. */
    private static final int MAX_CHALLENGE_BYTES = 0x300;
    /** Ships libpot.so per ABI; used only as an additional library source when installed. */
    private static final String HELPER_PACKAGE = "app.morphe.pot.helper";
    /** ABIs carried in this JAR, in the order they are worth trying. */
    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    /** dlopen is process-wide, so the outcome is cached for the whole process. */
    private static volatile boolean loaded;
    private static volatile boolean attempted;
    private static volatile String loadedFrom;
    /** Resolved once; finding the JAR involves reflection and a directory scan. */
    private static volatile String jarPath;

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
            // A library already mapped into this process is enough, whoever loaded it.
            if (probe()) {
                loaded = true;
                loadedFrom = "already-loaded";
                return true;
            }
            for (File candidate : candidates()) {
                if (candidate == null || !candidate.isFile() || candidate.length() == 0) continue;
                File target = stagedIfNeeded(candidate);
                if (target == null) continue;
                if (open(target, candidate.getAbsolutePath())) return true;
            }
            File extracted = extractFromJar();
            if (extracted != null && open(extracted, extracted.getAbsolutePath())) return true;
            SpiderDebug.log("PoTokenSo: 无法加载 " + SO_NAME
                    + "（JAR 内未找到匹配 ABI，也可在 ext 配置 pot_so_path）");
            return false;
        }
    }

    /** @return true when the library is mapped and its symbol binds. */
    private boolean open(File file, String origin) {
        try {
            System.load(file.getAbsolutePath());
            loaded = true;
            loadedFrom = origin;
            SpiderDebug.log("PoTokenSo: 已加载 " + origin);
            return true;
        } catch (Throwable error) {
            // Loading the same soname twice in one process is reported as an error even though
            // the library is usable, so treat a bound symbol as success.
            String message = String.valueOf(error.getMessage());
            if (message.contains("already opened") && probe()) {
                loaded = true;
                loadedFrom = origin + " (already opened)";
                SpiderDebug.log("PoTokenSo: 已加载（进程内已存在）" + origin);
                return true;
            }
            // Usually a foreign ABI; the caller keeps trying the remaining candidates.
            SpiderDebug.log("PoTokenSo: 加载失败 " + origin + " " + error);
            return false;
        }
    }

    /**
     * True when the native method is already bound in this process.
     *
     * <p>Deliberately does not call the minter: that would run native code with a challenge
     * this method did not construct. Only the JNI binding is checked, which is what
     * distinguishes "the soname is mapped" from "the symbol is missing".
     */
    private static boolean probe() {
        try {
            java.lang.reflect.Method method = PoTokenServiceImpl.class
                    .getDeclaredMethod("mintMorpheIntegrityTokens", byte[].class);
            // Resolving a native method's implementation is what throws when it is unbound.
            method.setAccessible(true);
            return java.lang.reflect.Modifier.isNative(method.getModifiers()) && bound(method);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Probes the binding with a zero-length challenge, which the minter treats as empty input
     * and rejects without doing work. An {@link UnsatisfiedLinkError} means no binding exists.
     */
    private static boolean bound(java.lang.reflect.Method method) {
        try {
            method.invoke(null, (Object) new byte[0]);
            return true;
        } catch (java.lang.reflect.InvocationTargetException error) {
            // The native code ran and threw, so the symbol is bound.
            return !(error.getCause() instanceof UnsatisfiedLinkError);
        } catch (Throwable error) {
            return !(error instanceof UnsatisfiedLinkError);
        }
    }

    /** Library files that may exist outside this JAR, in priority order. */
    private List<File> candidates() {
        List<File> files = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isEmpty()) {
            File configured = new File(configuredPath);
            // Accept either the file itself or a directory holding it.
            files.add(configured.isDirectory() ? new File(configured, SO_NAME) : configured);
        }
        if (context != null) {
            // Not the staged copy under catvod_pot: that path is per-ClassLoader now and is
            // handled by extractFromJar, which knows the right file name.
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
            if (info.nativeLibraryDir != null) files.add(new File(info.nativeLibraryDir, SO_NAME));
        } catch (Throwable ignored) {
            // Helper not installed; the JAR remains the source.
        }
        return files;
    }

    /**
     * Copies a library into private storage when it is not already somewhere the host may map
     * as executable.
     */
    private File stagedIfNeeded(File source) {
        if (context == null) return source;
        String path = source.getAbsolutePath();
        // Anything already carrying this loader's file name is ready to open as-is.
        if (source.getName().equals(libraryFileName())) return source;
        // A helper's nativeLibraryDir is already extracted and executable.
        if (path.contains("/" + HELPER_PACKAGE + "-") || path.contains("/" + HELPER_PACKAGE + "/")) {
            return source;
        }
        return stage(source);
    }

    private File stage(File source) {
        try {
            File dir = privateDir();
            if (dir == null) return null;
            File target = new File(dir, libraryFileName());
            // Re-copy whenever the source changed, so replacing the file takes effect.
            if (target.isFile() && target.length() == source.length()
                    && target.lastModified() >= source.lastModified()) {
                return target;
            }
            try (InputStream in = new java.io.FileInputStream(source)) {
                write(in, target);
            }
            return target;
        } catch (Throwable error) {
            SpiderDebug.log("PoTokenSo: 复制 so 失败 " + error);
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* extraction from this JAR                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Extracts {@code lib/<abi>/libpot.so} out of this JAR.
     *
     * <p>The device ABI is tried first; the remaining ones follow, because an emulator or a
     * 32-bit host process can report an ABI list this JAR does not carry verbatim.
     */
    private File extractFromJar() {
        if (context == null) return null;
        String jar = jar();
        if (jar == null) {
            SpiderDebug.log("PoTokenSo: 未能定位自身 JAR");
            return null;
        }
        File jarFile = new File(jar);
        if (!jarFile.isFile()) return null;
        File dir = privateDir();
        if (dir == null) return null;
        String name = libraryFileName();
        pruneStaleLibraries(dir, name);
        File target = new File(dir, name);
        // Reuse an extraction that is newer than the JAR it came from.
        if (target.isFile() && target.length() > 0 && target.lastModified() >= jarFile.lastModified()) {
            return target;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            for (String abi : abis()) {
                ZipEntry entry = zip.getEntry("lib/" + abi + "/" + SO_NAME);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    write(in, target);
                }
                SpiderDebug.log("PoTokenSo: 已从 JAR 解压 lib/" + abi + "/" + SO_NAME);
                return target;
            }
            SpiderDebug.log("PoTokenSo: JAR 内无 lib/<abi>/" + SO_NAME);
            return null;
        } catch (Throwable error) {
            SpiderDebug.log("PoTokenSo: 解压失败 " + error);
            return null;
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignored) { }
        }
    }

    /** Device ABIs first, then the rest of the ABIs this JAR carries. */
    private static List<String> abis() {
        Set<String> out = new LinkedHashSet<>();
        try {
            String[] supported = Build.SUPPORTED_ABIS;
            if (supported != null) for (String abi : supported) if (abi != null) out.add(abi);
        } catch (Throwable ignored) {
            // Fall back to the packaged order.
        }
        for (String abi : ABIS) out.add(abi);
        return new ArrayList<>(out);
    }

    private File privateDir() {
        File dir = new File(context.getFilesDir(), "catvod_pot");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
        return dir;
    }

    /**
     * Per-ClassLoader library file name.
     *
     * <p>A native library can only be opened by one ClassLoader per process. The host builds a new
     * {@code CspDexClassLoader} whenever it reloads the spider JAR, and the previous loader stays
     * alive holding the old handle, so opening the same path again fails with
     * "already opened by ClassLoader ...". The new loader can never bind the symbol, because the
     * binding belongs to the old one.
     *
     * <p>Giving each loader its own copy sidesteps the conflict: a distinct path is a distinct
     * library as far as the runtime is concerned. The identity hash is stable for the lifetime of
     * the loader, which is exactly the scope that matters here.
     */
    private String libraryFileName() {
        ClassLoader loader = YoutubePoTokenSo.class.getClassLoader();
        String tag = loader == null ? "boot" : Integer.toHexString(System.identityHashCode(loader));
        return "libpot_" + tag + ".so";
    }

    /** Removes copies left by loaders that are gone, so the directory does not grow. */
    private void pruneStaleLibraries(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith("libpot_") || !name.endsWith(".so")) continue;
            if (name.equals(keep)) continue;
            // Only reap old ones: a sibling loader in this process may still be using a recent copy.
            if (file.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    private static void write(InputStream in, File target) throws java.io.IOException {
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        // Replace atomically, so a half-written library is never opened.
        if (target.exists()) //noinspection ResultOfMethodCallIgnored
            target.delete();
        if (!temp.renameTo(target)) throw new java.io.IOException("rename failed: " + temp);
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, true);
    }

    /* ------------------------------------------------------------------ */
    /* locating this JAR                                                  */
    /* ------------------------------------------------------------------ */

    private String jar() {
        String cached = jarPath;
        if (cached != null) return cached.isEmpty() ? null : cached;
        String found = fromProtectionDomain();
        if (found == null) found = fromClassLoader();
        if (found == null) found = fromCommonPaths();
        jarPath = found == null ? "" : found;
        if (found != null) SpiderDebug.log("PoTokenSo: JAR 位置 " + found);
        return found;
    }

    /** The most direct route: the loader records where the code came from. */
    private String fromProtectionDomain() {
        try {
            ProtectionDomain domain = YoutubePoTokenSo.class.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            java.net.URL location = source == null ? null : source.getLocation();
            if (location == null) return null;
            String path = location.getPath();
            if (path == null) return null;
            int bang = path.indexOf('!');
            if (bang > 0) path = path.substring(0, bang);
            if (path.startsWith("file:")) path = path.substring(5);
            return usable(new File(path)) ? path : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads the DexPathList of the loader that defined this class.
     *
     * <p>Hosts load the spider through their own DexClassLoader, so the JAR path is reachable
     * through {@code pathList.dexElements[i].path/zip/dexFile}. Field names differ across API
     * levels, hence the reflective walk rather than a fixed path.
     */
    private String fromClassLoader() {
        try {
            ClassLoader loader = YoutubePoTokenSo.class.getClassLoader();
            while (loader != null) {
                String found = fromLoader(loader);
                if (found != null) return found;
                loader = loader.getParent();
            }
        } catch (Throwable ignored) {
            // Fall through to the directory scan.
        }
        return null;
    }

    private String fromLoader(ClassLoader loader) {
        try {
            Field pathListField = field(loader.getClass(), "pathList");
            if (pathListField == null) return null;
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return null;
            Field elementsField = field(pathList.getClass(), "dexElements");
            if (elementsField == null) return null;
            elementsField.setAccessible(true);
            Object elements = elementsField.get(pathList);
            if (!(elements instanceof Object[])) return null;
            for (Object element : (Object[]) elements) {
                String found = fromElement(element);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
            // Not a PathClassLoader, or the fields moved; the caller tries the parent.
        }
        return null;
    }

    private String fromElement(Object element) {
        if (element == null) return null;
        for (String name : new String[]{"path", "zip", "dexFile"}) {
            try {
                Field f = field(element.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object value = f.get(element);
                if (value == null) continue;
                String path = null;
                if (value instanceof File) {
                    path = ((File) value).getAbsolutePath();
                } else {
                    // DexFile keeps the source name behind a getter-like field.
                    Field nameField = field(value.getClass(), "mFileName");
                    if (nameField != null) {
                        nameField.setAccessible(true);
                        Object fileName = nameField.get(value);
                        if (fileName != null) path = String.valueOf(fileName);
                    }
                }
                if (path != null && usable(new File(path))) return path;
            } catch (Throwable ignored) {
                // Try the next field.
            }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException error) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** Last resort: scan the directories a host keeps downloaded spider JARs in. */
    private String fromCommonPaths() {
        if (context == null) return null;
        List<File> roots = new ArrayList<>();
        roots.add(context.getFilesDir());
        roots.add(context.getCacheDir());
        File external = context.getExternalFilesDir(null);
        if (external != null) roots.add(external);
        File externalCache = context.getExternalCacheDir();
        if (externalCache != null) roots.add(externalCache);
        for (File root : roots) {
            String found = scan(root, 0);
            if (found != null) return found;
        }
        return null;
    }

    /** Bounded depth-first scan for a JAR that carries the library. */
    private String scan(File dir, int depth) {
        if (dir == null || depth > 2 || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        List<File> dirs = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(child);
                continue;
            }
            String name = child.getName().toLowerCase(java.util.Locale.ROOT);
            if ((name.endsWith(".jar") || name.endsWith(".dex")) && usable(child)) {
                return child.getAbsolutePath();
            }
        }
        for (File child : dirs) {
            String found = scan(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** True when the archive actually contains the library, so a wrong JAR is not picked. */
    private static boolean usable(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith("/" + SO_NAME)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignored) { }
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
     * The payload handed to the native minter.
     *
     * <p>Disassembling {@code mintMorpheIntegrityTokens} (va 0x2174) shows the library does not
     * expect a Challenge protobuf from the caller. It builds that structure itself: a 224-byte
     * blob at va 0x10b8 is copied onto the stack before anything else, a second 32-byte blob at
     * va 0x1402 follows, and only then is the caller's array read with {@code GetByteArrayRegion}
     * and copied into one field of that structure. The read is capped at 0x300 bytes
     * ({@code cmp w0, #0x300}), so the argument is a bounded payload, not a message.
     *
     * <p>Wrapping the identifier in a length-delimited field, as an earlier version did, therefore
     * fed {@code pb_decode} two extra bytes of framing it never asked for. The binding identifier
     * is passed raw.
     */
    private static byte[] buildChallenge(String visitorData) {
        byte[] identifier = visitorData.getBytes(StandardCharsets.UTF_8);
        // The library truncates anything past 768 bytes; keep the call honest about that.
        if (identifier.length <= MAX_CHALLENGE_BYTES) return identifier;
        byte[] capped = new byte[MAX_CHALLENGE_BYTES];
        System.arraycopy(identifier, 0, capped, 0, MAX_CHALLENGE_BYTES);
        return capped;
    }

}
