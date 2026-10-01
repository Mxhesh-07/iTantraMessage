# ProGuard / R8 rules for iTantra Message.

# Release builds run R8 with the default optimise file plus this one. Nothing here is
# required for correctness unless it is commented as such -- it exists mostly to keep
# R8 from stripping things that are only reached reflectively, which is a build-time
# surprise rather than a compile error.

# ---- Room -----------------------------------------------------------------------
# Room generates an implementation of each @Database / @Dao and looks it up by name at
# runtime. R8 cannot see those references from the generated code alone, so without
# these the release APK builds cleanly and then throws at first query.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin coroutines ----------------------------------------------------------
# Coroutines use ServiceLoader at runtime to find the dispatcher factories.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Compose --------------------------------------------------------------------
# Compose's own runtime ships consumer rules; these are only for the debugging bits,
# which are absent from a release build anyway.
-dontwarn androidx.compose.**

# ---- Our own reflection use -----------------------------------------------------
# The device-identity code derives a stable ID from the Keystore and reads it back by
# alias. Keystore access is by string, so the alias constant must survive shrinking.
-keepclassmembers class in.isro.sih26173.itantramessage.data.crypto.EncryptionManager {
    public static ** Companion;
}

# ---- Line numbers for readable crash reports ------------------------------------
# A release crash should be traceable to a source line in a bug report. The source file
# name is deliberately kept but the original file name (which leaks build paths) is not.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
