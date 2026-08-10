import onnxruntime_objc

// MARK: - ORTSharedEnv

/// Process-level shared ONNX Runtime environment.
///
/// The ORT documentation states: "There should be one Environment per process.
/// Creating multiple environments is not recommended and may cause issues."
///
/// Prior to this singleton, both `MobileClipEncoder` and `Florence2Tagger`
/// each created their own `ORTEnv`. When Pass 1 loaded MobileClipEncoder
/// (env #1) and Pass 3 later loaded Florence2Tagger (env #2), the two
/// environments conflicted, causing a SIGSEGV/SIGABRT during Florence-2 vision
/// encoder `session.run()`.
///
/// This enum provides a single lazily-initialized `ORTEnv` that both consumers
/// (and any future ORT session) reuse, eliminating the multi-env conflict.
enum ORTSharedEnv {

    /// The single process-wide ORT environment.
    ///
    /// Initialized lazily (thread-safe via Swift's `static let` dispatch_once
    /// semantics) on first access. Uses `.warning` log level to match the
    /// Android `OrtEnvironment.getEnvironment()` default.
    static let env: ORTEnv = {
        do {
            return try ORTEnv(loggingLevel: .warning)
        } catch {
            // ORTEnv creation failing is extraordinary (trivial allocation).
            // If it does fail, surface immediately rather than propagating nil.
            fatalError("ORTSharedEnv: failed to create ORTEnv: \(error)")
        }
    }()
}
