# Firebase Firestore
-keep class com.google.firebase.firestore.** { *; }

# Firebase Auth
-keep class com.google.firebase.auth.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# Supabase & Ktor
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.json.** { *; }
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature

# Project Models
-keep class com.tech.spendwise.models.** { *; }

# Maintain line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean