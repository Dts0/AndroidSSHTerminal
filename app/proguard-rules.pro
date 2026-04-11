# Add project specific ProGuard rules here.

# JSch
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Tink / annotations used by androidx.security
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
