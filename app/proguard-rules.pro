# Repackage
# Every renamed class MUST land under com.github.catvod.spider: the spider JAR is assembled by
# copying only smali/com/github/catvod/spider out of the R8 output. -flattenpackagehierarchy keeps
# distinct package names, and R8 then reuses kept package names (e.g. androidx.tracing) for our own
# classes, which silently drops them from the JAR -> NoClassDefFoundError at spider init.
-repackageclasses 'com.github.catvod.spider.merge'

# dontwarn
-dontwarn android.content.res.**
-dontwarn android.support.annotation.**

# Android runtime
-keeppackagenames androidx.annotation.**

# Gson
-keep class com.google.gson.** { *; }

# Spider
-keep class com.github.catvod.crawler.* { *; }
-keep class com.github.catvod.spider.* { public <methods>; }

# OkHttp
-dontwarn okhttp3.**
-keeppackagenames okio.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# QuickJS — YoutubeNsig reaches it by reflection, so names must survive.
-keeppackagenames com.whl.quickjs.**
-keep class com.whl.quickjs.** { *; }
