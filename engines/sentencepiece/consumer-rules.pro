# SentencePiece ProGuard rules
# Keep all native methods and classes
-keep class com.mamba.picme.sentencepiece.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
