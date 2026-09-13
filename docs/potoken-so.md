# 离线 PoToken（libpot.so）

`sabr-so-offline` 分支用 Morphe `libpot.so` 在本地生成 GVS poToken，取代 `sabr-micro` 的
WebView/BotGuard 方案。没有网络请求，没有 JS 引擎，起播不再等 `esm.sh` 模块图和
`GenerateIT` 往返。

## 为什么 so 不在 JAR 里

`custom_spider.jar` 是纯 dex 容器：`build.gradle::prepareSpiderJar` 只把 R8 产物里的
`smali/com/github/catvod/spider`（外加 JNI 桥接类 `smali/app/morphe`）打包，`checkJar.ps1`
会拒绝其它内容。JAR 没有 `lib/<abi>/`，宿主也不会为它建立 `nativeLibraryDir`，所以
`System.loadLibrary("pot")` 永远解析不到。库改为运行时定位、复制到宿主私有目录后用
`System.load(绝对路径)` 打开。

## 放置 libpot.so

按优先级，命中即用：

1. `ext.pot_so_path` —— 配置里写绝对路径（文件或所在目录都行）
2. `<宿主files>/catvod_pot/libpot.so`、`<宿主files>/libpot.so`、`<外部files>/libpot.so`
3. 已安装 `app.morphe.pot.helper`，直接用它的 `nativeLibraryDir/libpot.so`

so 必须与宿主进程 ABI 一致（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`），来源：

```
unzip -j PotHelper-<版本>.apk "lib/arm64-v8a/libpot.so" -d /sdcard/
```

## 配置示例

```json
{
  "pot_so_path": "/sdcard/libpot.so",
  "po_token": ""
}
```

`po_token` 仍然优先于 so：填了固定 token 就直接用，便于对照排查。

## 日志

打开宿主的爬虫日志，关键行：

- `PoTokenSo: 已加载 <路径>` —— so 打开成功
- `PoTokenSo 成功: len=<长度> so=<路径>` —— 本次 mint 成功
- `PoTokenSo: 未找到 libpot.so...` —— 三个来源都没命中
- `PoTokenSo: 加载失败 <路径> ...` —— 多为 ABI 不匹配
- `PoTokenSo 失败: mint 输出不完整` —— so 已加载但 challenge 不被接受，见下

## challenge 的已知限制

PotHelper 里喂给 so 的字节来自 GMS PoTokens 服务，公开源码无法确认其确切结构
（`libpot.so` 用 nanopb 解 `Challenge_msg`，导出符号只有
`Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens`）。
本分支按 field 1 传 `visitorData` 作为绑定标识。

若日志停在 `mint 输出不完整` 或 SABR 报 `missing-visitor-bound-potoken`，说明需要真实
challenge。抓一次真实调用后把字节写回 `YoutubePoTokenSo.buildChallenge`：

```
frida -U -n com.google.android.youtube -e '
Interceptor.attach(Module.findExportByName("libpot.so",
  "Java_app_morphe_pot_helper_potokens_PoTokenServiceImpl_mintMorpheIntegrityTokens"),
  { onEnter(args) { /* dump args[2] 指向的 jbyteArray */ } });'
```

静态路径：`Ghidra` 打开 `libpot.so`，从 `0x2174` 的导出函数跟 `Challenge_field_info`
反推字段号，再用 protobuf 手拼。

## 许可证提醒

`libpot.so` 由 Morphe 以二进制形式分发，其 README 声明不可与 GPLv3 等强 copyleft 代码
链接分发。因此本仓库不包含该二进制，仅在运行时加载由使用者自行放置的文件。
