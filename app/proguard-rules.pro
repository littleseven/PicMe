# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Logger class for reflection binding from beauty-engine module
-keep class com.mamba.picme.core.common.Logger { public *; }

# ONNX Runtime: 保留所有 ONNX Runtime Java 类，防止 R8 裁剪导致 SIGSEGV
# 参考：https://github.com/microsoft/onnxruntime/issues/17847
-keep class ai.onnxruntime.** { *; }

# R8: javax.lang.model 仅在编译期注解处理时需要，运行时不存在
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
# ML Kit: 防止 R8 裁剪/混淆通过反射访问的内部注册表，避免 release 构建下
# MultiFlavorDetectorCreator 等类出现 NPE（Attempt to read from field 'HashMap ...' on null）
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# TAG 数据备份/还原：Moshi 通过反射/Kotlin 元数据解析，R8 会裁剪字段导致列表为 null
-keep class com.mamba.picme.domain.backup.model.** { *; }
-keepclassmembers class com.mamba.picme.domain.backup.model.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations, AnnotationDefault

# Feishu/Lark OAPI SDK: FeishuChannelHandler 通过反射访问 ws.Client 的 protected 成员
# (autoReconnect 字段 / disconnect() 方法 / executor 线程池) 做断连清理——SDK 未公开 stop API。
# 这些 protected 成员会被 R8 混淆导致 NoSuchFieldException/NoSuchMethodException，需保留原名。
# 验证：release 包 logcat 不再出现 PoLang:FeishuHandler NoSuchFieldException autoReconnect。
-keep class com.lark.oapi.ws.Client { *; }
