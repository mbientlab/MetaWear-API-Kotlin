# R8 keep rules for the MetaWear app release build.

# --- Room ---------------------------------------------------------------
# Entities / DAOs are reached via generated code and reflection-free, but the
# @Database subclass and its generated _Impl are located by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Nordic Kotlin BLE library + Android DFU library --------------------
# The DFU library instantiates the app's DfuBaseService subclass by name and
# talks to it over broadcasts; the BLE library uses reflection-free coroutines
# but ships some optional pieces R8 warns about.
-keep class com.mbientlab.metawear.firmware.MetaWearDfuService { *; }
-keep class no.nordicsemi.android.dfu.** { *; }
-dontwarn no.nordicsemi.android.**

# --- kotlinx.coroutines / datetime ---------------------------------------
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.datetime.**

# --- App: keep the persisted-record codec's data classes stable ------------
# LogSessionRecord / RememberedDevice round-trip through hand-rolled string
# codecs (no reflection), so nothing here needs keeping — listed for clarity.

# --- General ---------------------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
