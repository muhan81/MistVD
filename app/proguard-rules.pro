# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== GSON serialized data classes =====
# R8 renames fields, but GSON uses reflection to match JSON keys to field names.
# Without these rules, deserialization returns null for non-null Kotlin fields → NPE.

# Locked folder system
-keep class com.nkls.nekovideo.components.helpers.LockedFileEntry { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedSubfolderEntry { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFolderManifest { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFolderRegistryEntry { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFoldersRegistry { *; }

# Pinned folders (Gson via PinnedFoldersStore) — faltava regra; sem ela o R8
# renomeava a classe/campos (mapping: PinnedFolderEntry -> i1, path -> a) e um
# upgrade de versão poderia perder os pins salvos.
-keep class com.nkls.nekovideo.components.helpers.PinnedFolderEntry { *; }

# ffmpeg-kit - lossless video cutting
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Folder scanner cache
-keep class com.nkls.nekovideo.services.SerializableFolderInfo { *; }
-keep class com.nkls.nekovideo.services.SerializableVideoInfo { *; }
-keep class com.nkls.nekovideo.services.FolderInfo { *; }
-keep class com.nkls.nekovideo.services.VideoInfo { *; }

# Beauty presets (第 6 轮) —— 方案以 JSON 存在 SharedPreferences 里，靠 Gson 读写。
# 这类字段被重命名会让已保存的方案读不出来（PinnedFolderEntry 就是这么丢的），
# 所以连同嵌套 DTO 一起 keep，并保留 SerializedName 注解。
-keep class com.nkls.nekovideo.components.helpers.BeautyPreset { *; }
-keep class com.nkls.nekovideo.components.helpers.BeautyPresetStore$PresetDto { *; }
-keep class com.nkls.nekovideo.components.helpers.BeautyPresetStore$BackupPayload { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}