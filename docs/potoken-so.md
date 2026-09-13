# 离线 PoToken（libpot.so）

`sabr-so-offline` 分支用 Morphe `libpot.so` 在本地生成 GVS poToken，取代 `sabr-micro` 的
WebView/BotGuard 方案。没有网络请求，没有 JS 引擎，起播不再等 `esm.sh` 模块图和
`GenerateIT` 往返。

**开箱即用**：`libpot.so` 已随 `custom_spider.jar` 一起分发，无需手动放置任何文件。

## so 怎么进的 JAR

JAR 本身就是 ZIP，按 Android 惯例放 `lib/<abi>/libpot.so` 即可：

```
custom_spider.jar
├── classes.dex
├── lib/arm64-v8a/libpot.so
├── lib/armeabi-v7a/libpot.so
├── lib/x86/libpot.so
└── lib/x86_64/libpot.so
```

源文件在 `jar/spider.jar/unknown/lib/<abi>/`，并在 `jar/spider.jar/apktool.yml` 的
`unknownFiles` 中登记，`apktool b` 才会原样打回。`jar/checkJar.ps1` 会校验重建后的 JAR
仍带着这些条目且都是合法 ELF。

## 为什么不能用 System.loadLibrary

平台只为已安装的 APK 建立 `nativeLibraryDir`，爬虫是宿主用 `DexClassLoader` 加载的一个
JAR，没有这个目录，`System.loadLibrary("pot")` 永远解析不到。所以流程是：定位 JAR 自身 →
`ZipFile` 取出对应 ABI 的 so → 写入宿主私有目录 → `System.load(绝对路径)`。

## 加载顺序

`YoutubePoTokenSo.load()`，命中即停：

1. 进程内已加载（`probe()` 检查 JNI 绑定）
2. `ext.pot_so_path` —— 手动指定的 so，便于换库测试
3. `<files>/catvod_pot/libpot.so`、`<外部files>/libpot.so`
4. 已安装 `app.morphe.pot.helper` 的 `nativeLibraryDir`
5. **从当前 JAR 解压**（默认路径）

JAR 自定位依次尝试 `ProtectionDomain.getCodeSource()`、反射读 `DexPathList.dexElements`
的 `path/zip/dexFile`、扫描 `filesDir`/`cacheDir`/外部目录（深度 2）。候选 JAR 必须真的
含有 `lib/**/libpot.so` 才会被采用，避免误取宿主其它 JAR。

ABI 选择以 `Build.SUPPORTED_ABIS` 为准，其后补上 JAR 内携带的四个 ABI 作为兜底。
解压走临时文件 + `renameTo`，不会出现写一半就被加载的情况；JAR 比已解压 so 新时自动重解。

同一 soname 在一个进程里重复 `dlopen` 会报 `already opened`，此时若 JNI 符号已绑定即视为
成功，不再重复解压。

## 配置

无需配置。仅在需要替换 so 时使用：

```json
{
  "pot_so_path": "/sdcard/libpot.so",
  "po_token": ""
}
```

`po_token` 优先于 so，填了固定 token 就直接用，便于对照排查。

## 日志

- `PoTokenSo: 已从 JAR 解压 lib/<abi>/libpot.so` —— 默认路径生效
- `PoTokenSo: 已加载 <路径>` / `已加载（进程内已存在）` —— so 打开成功
- `PoTokenSo: JAR 位置 <路径>` —— 自定位结果
- `PoTokenSo 成功: len=<长度> so=<来源>` —— mint 成功
- `PoTokenSo: 未能定位自身 JAR` —— 三种定位都失败，可用 `pot_so_path` 兜底
- `PoTokenSo: JAR 内无 lib/<abi>/libpot.so` —— 设备 ABI 不在打包范围内
- `PoTokenSo 失败: mint 输出不完整` —— so 已加载但 challenge 不被接受，见下

## challenge 的已知限制

PotHelper 里喂给 so 的字节来自 GMS PoTokens 服务，公开源码无法确认其确切结构
（`libpot.so` 用 nanopb 解 `Challenge_msg`，导出符号只有
`Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens`）。
本分支按 field 1 传 `visitorData` 作为绑定标识。

若日志停在 `mint 输出不完整`，或 SABR 报 `missing-visitor-bound-potoken`，说明需要真实
challenge。抓一次真实调用后把字节写回 `YoutubePoTokenSo.buildChallenge`：

```
frida -U -n com.google.android.youtube -e '
Interceptor.attach(Module.findExportByName("libpot.so",
  "Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens"),
  { onEnter(args) { /* dump args[2] 指向的 jbyteArray */ } });'
```

静态路径：`Ghidra` 打开 `libpot.so`，从 `0x2174` 的导出函数跟 `Challenge_field_info`
反推字段号，再用 protobuf 手拼。

## JNI 类名约束

`libpot.so` 没有 `JNI_OnLoad`，符号由类的完整限定名推导，因此桥接类必须保持
`app.morphe.pot.helper.potokens.PoTokenServiceImpl`。相关约束分布在三处：
`build.gradle::prepareSpiderJar` 打包 `smali/app/morphe`、`proguard-rules.pro` 用
`-keeppackagenames app.morphe.**` 防止重打包、`checkJar.ps1` 放行该类位于 catvod 树之外。

## 许可证

`libpot.so` 由 Morphe 以二进制形式分发，其 README 声明该二进制不可与 GPLv3 等强 copyleft
代码链接或共同分发。本分支将其打入 JAR 一并分发，采用此分支前请自行确认许可证兼容性；
如需避免共同分发，改回外部放置即可（删除 `jar/spider.jar/unknown/lib`、`apktool.yml` 中
的登记项与 `checkJar.ps1` 的对应校验，然后用 `pot_so_path` 指定）。
