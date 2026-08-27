# JSch (com.github.mwiede fork) uses reflection to instantiate crypto implementations
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.jzlib.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**

# EdDSA provider loaded reflectively by JSch for ssh-ed25519 keys
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# BouncyCastle is optional at runtime for JSch; it is not bundled
-dontwarn org.bouncycastle.**

# kotlinx.serialization generated serializers
-keepclassmembers class com.lalatendu.poweroffremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.lalatendu.poweroffremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
