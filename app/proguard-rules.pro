# Retrofit and kotlinx.serialization inspect generic signatures and annotations.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, AnnotationDefault

# JSch instantiates algorithm implementations by their configured class names.
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
# Optional desktop agent / Kerberos integrations are never enabled on Android.
# MusicTagConnection explicitly selects password or publickey authentication.
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**

# Retain Retrofit service method metadata while allowing implementations and models to shrink.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# kotlinx.serialization serializers are referenced by generated code.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers,allowoptimization,allowobfuscation class <1> {
    static <1>$$serializer Companion;
}
