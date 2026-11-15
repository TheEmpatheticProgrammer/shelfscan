# kotlinx.serialization: keep the generated serializers for every @Serializable class in the app
# (API models in data.remote, plus the backup file, offline copies and scan queue in data).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.shelfscan.app.** { *** Companion; }
-keepclasseswithmembers class com.shelfscan.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.shelfscan.app.**$$serializer { *; }
-dontnote kotlinx.serialization.**

# Retrofit: keep the API interface and generic signatures (needed for suspend functions and List<T> bodies).
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface com.shelfscan.app.data.remote.ShelfApi
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
