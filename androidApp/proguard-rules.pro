# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class cz.faltyn.elfall.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class cz.faltyn.elfall.**$$serializer { *; }
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
# secp256k1 JNI
-keep class fr.acinq.secp256k1.** { *; }
# zxing
-keep class com.journeyapps.barcodescanner.** { *; }

# libsodium přes JNA (lazysodium) – JNA používá reflexi
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**

# UniFFI vazby Rust jádra (JNA reflexe)
-keep class uniffi.elfall_core.** { *; }
