# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class com.aitrader.data.model.** { *; }
-keep,includedescriptorclasses class com.aitrader.**$$serializer { *; }
-keepclassmembers class com.aitrader.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.aitrader.data.remote.EngineApi { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
