# agent-native JNI ProGuard rules（自 :runtime-core 迁入，Phase 4 Task 12）
# Keep MNN-LLM JNI bridge class and its native methods
-keep class com.mamba.picme.agent.core.inference.local.llm.MnnLlmClient {
    native <methods>;
}

# Keep stream generation callback interface and onToken method
# Native code in llm_jni_bridge.cpp looks up onToken(Ljava/lang/String;Z)Z via reflection.
-keep interface com.mamba.picme.agent.core.inference.local.llm.StreamGenerateListener {
    boolean onToken(java.lang.String, boolean);
}
