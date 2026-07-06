# R Plan Beauty Engine - Consumer ProGuard Rules
# Keep all public APIs of the beauty engine
-keep public class com.mamba.picme.beauty.egl.** { public *; }
-keep public interface com.mamba.picme.beauty.api.** { *; }

# Keep BeautyLogProxy and BeautyLog interface for reflection binding
-keep class com.mamba.picme.beauty.log.BeautyLogProxy { public *; }
-keep interface com.mamba.picme.beauty.log.BeautyLog { *; }
-keep class com.mamba.picme.beauty.log.BeautyLogExtKt { *; }

# Keep app module Logger methods for reflection (called by BeautyLogProxy)
# Note: This rule must be in the app's proguard-rules.pro, not here.
# The app should add: -keep class com.mamba.picme.core.common.Logger { public *; }

# Keep JNI bridge classes for MNN face detection / embedding
# Native code in mnn_jni_bridge.cpp / mnn_face_embedder.cpp looks up these classes
# and their native methods by name; R8 must not obfuscate/remove them.
-keep class com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector {
    native <methods>;
}
-keep class com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceEmbedder {
    native <methods>;
}
-keep class com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceEmbedder$Companion {
    native <methods>;
}

# Keep FaceBox data class for JNI construction (called from mnn_jni_bridge.cpp)
# Native code looks up <init>(FFFFF[F)V via reflection; R8 must not remove/obfuscate it.
-keepclassmembers class com.mamba.picme.beauty.internal.facedetect.mnn.FaceBox {
    <init>(float, float, float, float, float, float[]);
}
-keep class com.mamba.picme.beauty.internal.facedetect.mnn.FaceBox { *; }

