# ---- TubePilot release shrinking rules ----

# Keep generic signatures and annotations used by reflection-based JSON (Jackson) and Retrofit.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Anthropic Java SDK: Jackson databind + Kotlin reflection over its model classes.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn io.swagger.**
-dontwarn com.github.victools.**
-dontwarn io.opentelemetry.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.standardwebhooks.**
-dontwarn org.jetbrains.annotations.**

# MediaPipe LLM Inference (JNI + protobuf lite).
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**

# kotlinx.serialization: keep generated serializers of app models (library rules cover the rest).
-keepclassmembers class com.tubepilot.** { *** Companion; }
-keepclasseswithmembers class com.tubepilot.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.tubepilot.**$$serializer { *; }

# Retrofit service interfaces are created by reflection.
-keep,allowobfuscation interface com.tubepilot.youtube.api.**
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Only referenced by the Anthropic SDK's class-based structured-output helper, which the app does
# not use (it is absent from Android's runtime).
-dontwarn java.lang.reflect.AnnotatedType
