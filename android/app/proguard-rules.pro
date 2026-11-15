# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.shelfscan.app.data.remote.** { *** Companion; }
-keepclasseswithmembers class com.shelfscan.app.data.remote.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.shelfscan.app.data.remote.**$$serializer { *; }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface com.shelfscan.app.data.remote.ShelfApi
