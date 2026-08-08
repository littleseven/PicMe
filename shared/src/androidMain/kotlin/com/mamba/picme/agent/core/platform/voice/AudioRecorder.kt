package com.mamba.picme.agent.core.platform.voice

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.mamba.picme.agent.core.platform.logging.Logger
import java.io.ByteArrayOutputStream

/**
 * 音频录制工具类
 *
 * 配置：16kHz、单声道、16bit PCM
 *
 * 参考 MnnLlmChat AsrService 的音频优化：
 * - 使用 VOICE_COMMUNICATION 音源启用硬件 AEC
 * - 启用 AcousticEchoCanceler（回声消除）
 * - 启用 NoiseSuppressor（噪声抑制）
 *
 * 耳机模式适配（2026-06）：
 * - 蓝牙耳机/有线耳机：使用 MIC 音源，关闭 AEC（耳机协议自带），降低输入增益
 * - 内置麦克风：保持 VOICE_COMMUNICATION + AEC/NS
 */
class AudioRecorder(private val context: Context? = null) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecorder"

        // 蓝牙耳机麦克风增益通常较高，适当降低避免削波
        const val GAIN_BLUETOOTH_SCO = 0.7f
        // 有线耳机麦克风增益接近内置麦，微调即可
        const val GAIN_WIRED_HEADSET = 0.9f
        // 内置麦克风默认增益
        const val GAIN_BUILTIN_MIC = 1.0f
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize = 0
    private var isRecording = false
    private var inputGain = GAIN_BUILTIN_MIC

    /**
     * 当前检测到的音频输入设备类型
     */
    val currentInputDevice: InputAudioDevice
        get() = detectInputDevice()

    /**
     * 启动录音
     *
     * 参考 AsrService.initMicrophone():
     * - 使用 VOICE_COMMUNICATION 替代 MIC，启用硬件级回声消除
     * - 动态创建 AEC 和 NS 音频效果器
     *
     * 耳机模式：根据当前连接设备自动选择最优音源和效果器配置
     */
    @Suppress("ReturnCount")
    fun start(): Boolean {
        if (isRecording) {
            Logger.d(TAG, "Already recording")
            return true
        }

        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(SAMPLE_RATE * 2) // 至少 1 秒缓冲区

        val device = detectInputDevice()
        inputGain = device.gain

        // 蓝牙耳机需要先启动 SCO，否则 AudioRecord 可能无法从蓝牙耳麦采集
        if (device is InputAudioDevice.BluetoothSco) {
            startBluetoothSco()
        }

        try {
            // 耳机模式下使用 MIC 音源；内置麦克风使用 VOICE_COMMUNICATION 启用 AEC
            val audioSource = when (device) {
                is InputAudioDevice.BluetoothSco,
                is InputAudioDevice.WiredHeadset -> MediaRecorder.AudioSource.MIC

                is InputAudioDevice.BuiltInMic -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord initialization failed")
                return false
            }

            // 仅在非耳机设备上启用 AEC（蓝牙耳机/有线耳机通常自带回声消除）
            val enableAec = device is InputAudioDevice.BuiltInMic
            if (enableAec && AcousticEchoCanceler.isAvailable()) {
                val echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                echoCanceler?.enabled = true
                Logger.i(TAG, "AcousticEchoCanceler enabled")
            } else if (enableAec) {
                Logger.w(TAG, "AcousticEchoCanceler not available")
            } else {
                Logger.d(TAG, "AcousticEchoCanceler skipped for ${device.label}")
            }

            // 噪声抑制对所有输入设备都有益，但蓝牙耳机上可能已有 DSP 处理，按需启用
            val enableNs = device !is InputAudioDevice.BluetoothSco
            if (enableNs && NoiseSuppressor.isAvailable()) {
                val noiseSuppressor = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                noiseSuppressor?.enabled = true
                Logger.i(TAG, "NoiseSuppressor enabled")
            } else if (enableNs) {
                Logger.w(TAG, "NoiseSuppressor not available")
            } else {
                Logger.d(TAG, "NoiseSuppressor skipped for ${device.label}")
            }

            audioRecord?.startRecording()
            isRecording = true
            Logger.i(
                TAG,
                "Recording started (source=${audioSourceName(audioSource)}, " +
                    "device=${device.label}, gain=${device.gain})"
            )
            return true
        } catch (securityException: SecurityException) {
            Logger.e(TAG, "Failed to start recording", securityException)
            return false
        } catch (illegalStateException: IllegalStateException) {
            Logger.e(TAG, "Failed to start recording", illegalStateException)
            return false
        }
    }

    /**
     * 读取一帧音频数据
     *
     * 注意：如果启用了输入增益缩放，返回的数据已经过增益处理
     *
     * @return 音频数据，如果未录音返回空数组
     */
    fun read(): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        if (!isRecording) return ByteArray(0)

        val buffer = ByteArray(bufferSize)
        val readSize = record.read(buffer, 0, buffer.size)
        return if (readSize > 0) {
            applyGain(buffer.copyOf(readSize))
        } else {
            ByteArray(0)
        }
    }

    /**
     * 读取 ShortArray 音频数据（用于流式 ASR）
     *
     * 参考 AsrService.processSamples() 直接读取 ShortArray 避免转换开销
     * 注意：返回的 ShortArray 已经过增益处理
     *
     * @param buffer 目标缓冲区
     * @param offset 偏移量
     * @param size 读取大小
     * @return 实际读取的样本数
     */
    fun readShortArray(buffer: ShortArray, offset: Int, size: Int): Int {
        val record = audioRecord ?: return 0
        if (!isRecording) return 0
        val readSize = record.read(buffer, offset, size)
        if (readSize > 0 && inputGain != 1.0f) {
            applyGainToShortArray(buffer, offset, readSize)
        }
        return readSize
    }

    /**
     * 读取指定时长的音频片段
     *
     * @param maxDurationMs 最大录制时长（毫秒）
     * @param silenceTimeoutMs 静音超时（毫秒），检测到持续静音则提前结束
     * @return 音频数据
     */
    fun readSegment(maxDurationMs: Int, silenceTimeoutMs: Int = 800): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        if (!isRecording) return ByteArray(0)

        val maxBytes = (SAMPLE_RATE * 2 * maxDurationMs / 1000)
        val output = ByteArrayOutputStream(maxBytes)
        val vadDetector = VadDetector(thresholdDb = 40f, minSpeechMs = 100)

        val startTime = System.currentTimeMillis()
        var lastSpeechTime = startTime
        val buffer = ByteArray(bufferSize)

        while (isRecording) {
            val readSize = record.read(buffer, 0, buffer.size)
            if (readSize > 0) {
                val chunk = applyGain(buffer.copyOf(readSize))
                output.write(chunk)

                val isSpeech = vadDetector.process(chunk)
                val now = System.currentTimeMillis()
                if (isSpeech) {
                    lastSpeechTime = now
                }

                val elapsed = now - startTime
                val silenceDuration = now - lastSpeechTime

                if (elapsed >= maxDurationMs || silenceDuration >= silenceTimeoutMs) {
                    Logger.d(TAG, "Segment ended: elapsed=${elapsed}ms, silence=${silenceDuration}ms")
                    break
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * 停止录音
     */
    fun stop() {
        isRecording = false
        inputGain = GAIN_BUILTIN_MIC
        try {
            // 仅当 AudioRecord 处于录制状态时才 stop，避免 IllegalStateException
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error stopping recorder", e)
        }
        audioRecord = null
        stopBluetoothSco()
        Logger.d(TAG, "Recording stopped")
    }

    /**
     * 启动蓝牙 SCO 音频连接
     *
     * 蓝牙耳机麦克风必须通过 SCO 通道才能用于语音输入。
     * 使用旧版 API 以兼容 minSdk=24；Android 11+ 的 CommunicationDevice API
     * 在低版本上不可用。
     */
    @Suppress("DEPRECATION")
    private fun startBluetoothSco() {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return

        try {
            if (!audioManager.isBluetoothScoOn) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                Logger.i(TAG, "Bluetooth SCO started")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to start Bluetooth SCO", e)
        }
    }

    /**
     * 停止蓝牙 SCO 音频连接
     */
    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return

        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
                Logger.i(TAG, "Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to stop Bluetooth SCO", e)
        }
    }

    /**
     * 检测当前优先使用的音频输入设备
     *
     * 优先级：蓝牙耳机 > 有线耳机 > 内置麦克风
     *
     * 注意：部分蓝牙耳机在 SCO 未启动前不会出现在 getDevices() 列表中，
     * 因此额外检查蓝牙连接状态 / isBluetoothScoOn / isBluetoothA2dpOn 作为兜底。
     */
    private fun detectInputDevice(): InputAudioDevice {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return InputAudioDevice.BuiltInMic

        val devices = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } else {
            null
        }

        // 检查蓝牙 headset 是否已连接（不依赖 SCO 是否启动）
        val isBluetoothHeadsetConnected = isBluetoothHeadsetConnected()

        Logger.d(TAG, "detectInputDevice: devices=${devices?.size ?: 0}, " +
            "isBluetoothScoOn=${audioManager.isBluetoothScoOn}, " +
            "isBluetoothA2dpOn=${audioManager.isBluetoothA2dpOn}, " +
            "isBluetoothHeadsetConnected=$isBluetoothHeadsetConnected")

        devices?.forEach {
            Logger.d(TAG, "  device: type=${it.type}, name=${it.productName}, isSource=${it.isSource}")
        }

        if (devices.isNullOrEmpty()) {
            // 兜底 1：蓝牙 headset 已连接（A2DP 或 SCO 任一激活）
            @Suppress("DEPRECATION")
            if (isBluetoothHeadsetConnected || audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) {
                Logger.d(TAG, "No input devices but bluetooth headset connected, assuming BluetoothSCO")
                return InputAudioDevice.BluetoothSco(name = "")
            }
            return InputAudioDevice.BuiltInMic
        }

        // 优先检测蓝牙耳机（SCO 协议，支持双向语音）
        val bluetoothSco = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (bluetoothSco != null) {
            Logger.d(TAG, "Found Bluetooth SCO device: ${bluetoothSco.productName}")
            return InputAudioDevice.BluetoothSco(name = bluetoothSco.productName?.toString() ?: "")
        }

        // 其次检测有线耳机（带麦克风）
        val wiredHeadset = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wiredHeadset != null) {
            Logger.d(TAG, "Found wired headset: ${wiredHeadset.productName}")
            return InputAudioDevice.WiredHeadset(name = wiredHeadset.productName?.toString() ?: "")
        }

        // 兜底 2：getDevices() 中没有 SCO 设备，但蓝牙 headset 已连接
        @Suppress("DEPRECATION")
        if (isBluetoothHeadsetConnected || audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) {
            Logger.d(TAG, "Bluetooth headset connected but no SCO device in getDevices(), assuming BluetoothSCO")
            return InputAudioDevice.BluetoothSco(name = "")
        }

        Logger.d(TAG, "No headset found, using built-in mic")
        return InputAudioDevice.BuiltInMic
    }

    /**
     * 检查是否有蓝牙耳机（Headset profile）已连接
     * 不依赖 SCO 是否启动，只要蓝牙配对并连接即返回 true
     */
    private fun isBluetoothHeadsetConnected(): Boolean {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                return false
            }
            val a2dpConnected = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            val headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            Logger.d(TAG, "Bluetooth profile state: A2DP=$a2dpConnected, HEADSET=$headsetState")
            a2dpConnected || headsetState == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            Logger.w(TAG, "Missing BLUETOOTH_CONNECT permission, cannot check headset state")
            false
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to check bluetooth headset state", e)
            false
        }
    }

    /**
     * 对 PCM16 字节数据应用输入增益
     */
    private fun applyGain(pcmData: ByteArray): ByteArray {
        if (inputGain == 1.0f) return pcmData

        for (i in 0 until pcmData.size - 1 step 2) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            var sample = (high shl 8) or low

            // 符号扩展
            if (sample and 0x8000 != 0) {
                sample -= 0x10000
            }

            val amplified = (sample * inputGain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            pcmData[i] = (amplified and 0xFF).toByte()
            pcmData[i + 1] = ((amplified shr 8) and 0xFF).toByte()
        }
        return pcmData
    }

    /**
     * 对 ShortArray 应用输入增益（原地修改）
     */
    private fun applyGainToShortArray(buffer: ShortArray, offset: Int, size: Int) {
        if (inputGain == 1.0f) return

        for (i in offset until offset + size) {
            val amplified = (buffer[i] * inputGain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = amplified.toShort()
        }
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> "UNKNOWN($source)"
    }
}

/**
 * 音频输入设备类型（密封类，显式枚举所有支持设备）
 */
sealed class InputAudioDevice(
    val label: String,
    val gain: Float
) {
    data class BluetoothSco(val name: String = "") : InputAudioDevice(
        label = if (name.isBlank()) "BluetoothSCO" else "BluetoothSCO($name)",
        gain = AudioRecorder.GAIN_BLUETOOTH_SCO
    )

    data class WiredHeadset(val name: String = "") : InputAudioDevice(
        label = if (name.isBlank()) "WiredHeadset" else "WiredHeadset($name)",
        gain = AudioRecorder.GAIN_WIRED_HEADSET
    )

    data object BuiltInMic : InputAudioDevice(
        label = "BuiltInMic",
        gain = AudioRecorder.GAIN_BUILTIN_MIC
    )
}
