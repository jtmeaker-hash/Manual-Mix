# SetFlow ProGuard / R8 Rules

-keepclassmembers enum * { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepattributes *Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }

-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# ONNX Runtime (Beat This! beat/downbeat model): its native library finds its Java classes,
# constructors and fields by name over JNI (OnnxTensor, TensorInfo, OnnxJavaType, OrtException...).
-keep class ai.onnxruntime.** { *; }
