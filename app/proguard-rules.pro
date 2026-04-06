# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep TensorFlow Lite classes
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Keep Ktor classes
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }

# SLF4J
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# LiteRT-LM
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Gson (used by LiteRT-LM)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Markdown rendering
-keep class dev.jeziellago.compose.markdowntext.** { *; }
-dontwarn dev.jeziellago.compose.markdowntext.**
