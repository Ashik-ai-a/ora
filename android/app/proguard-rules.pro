# kotlinx.serialization (protocol messages in :core)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.airbox.core.** { *** Companion; }
-keepclasseswithmembers class app.airbox.core.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.airbox.core.**$$serializer { *; }
# BouncyCastle is only used by the TLS transfer path
-dontwarn org.bouncycastle.**
