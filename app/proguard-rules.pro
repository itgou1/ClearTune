# Retrofit and kotlinx.serialization inspect generic signatures and annotations.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, AnnotationDefault

# Retain Retrofit service method metadata while allowing implementations and models to shrink.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# kotlinx.serialization serializers are referenced by generated code.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers,allowoptimization,allowobfuscation class <1> {
    static <1>$$serializer Companion;
}
