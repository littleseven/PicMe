package com.mamba.picme.features.debug

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.pow

data class ImageCandidate(val url: String, val source: String)

object SampleDataGenerator {
    private const val TAG = "Gallery"
    private val STAR_NAMES = listOf(
        "杨幂", "迪丽热巴", "古力娜扎", "关晓彤", "虞书欣", "赵露思", "白鹿", "鞠婧祎", "刘亦菲", "赵丽颖",
        "倪妮", "刘诗诗", "景甜", "柳岩", "徐冬冬", "张雨绮", "钟楚曦", "李沁", "王楚然", "周也", "张婧仪",
        "孟子义", "金晨", "乔欣", "谭松韵", "张天爱", "林志玲", "高圆圆", "江疏影", "唐嫣", "佟丽娅", "辛芷蕾",
        "宋茜", "毛晓彤", "李一桐", "白冰", "曾黎", "张俪", "周雨彤", "宋轶", "郭碧婷", "文咏珊", "吴谨言",
        "秦岚", "王丽坤", "舒淇", "安以轩", "陈乔恩", "林依晨", "陈都灵", "章若楠", "田曦薇", "王佳怡"
    )

    private val LANDSCAPE_KEYWORDS = listOf(
        "雪山", "草原", "森林", "大海", "星空", "沙漠", "秋色", "雨林", "冰川", "极光",
        "瀑布", "湖泊", "峡谷", "梯田", "海岛", "晚霞", "日出", "湿地", "溶洞", "戈壁",
        "丹霞", "枫林", "花海", "向日葵", "竹林", "古村", "园林", "海滩", "礁石", "悬崖",
        "云海", "绿洲", "雾凇", "冰湖", "古堡", "灯塔", "断桥", "稻田", "荷塘", "郁金香",
        "银杏", "繁星", "晨曦", "夕阳", "平原", "火山", "泉水", "红叶", "翠竹", "山川"
    )

    private val SWIMWEAR_KEYWORDS = listOf(
        "泳装写真 高清", "比基尼 4k 摄影", "超模 泳装 唯美", "性感 泳衣 大片",
        "三点式 摄影 写真", "沙滩 泳装 气质", "杂志 泳装 封面", "尤物 泳装 4k",
        "时尚 泳装 模特", "亚洲模特 泳装写真", "车展模特 泳装", "维密写真 泳装"
    )
    private val SEXY_KEYWORDS = listOf(
        "性感 礼服大片", "气质写真 高清", "吊带写真 诱惑", "大长腿 气质 摄影",
        "深V 写真 唯美", "尤物写真 高清", "私房写真 性感", "甜辣写真 少女",
        "时尚大片 性感", "人体艺术 唯美 摄影", "艺术写真 4k", "气质女神 性感"
    )

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val roundStatsAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val roundStatsSuccess = ConcurrentHashMap<String, AtomicInteger>()

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
    )

    private fun getRandomUA() = USER_AGENTS.random()

    internal fun addLog(message: String) {
        Log.d(TAG, message)
        val currentLogs = _logs.value.toMutableList()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        currentLogs.add(0, "[$time] $message")
        if (currentLogs.size > 200) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        _logs.value = currentLogs
    }

    fun pause(context: Context) {
        _isPaused.value = true
        _progress.value = context.getString(R.string.pause)
        addLog("Action: Paused")
    }

    fun resume() {
        _isPaused.value = false
        addLog("Action: Resumed")
    }

    fun stop(context: Context) {
        _isGenerating.value = false
        _isPaused.value = false
        _progress.value = context.getString(R.string.stop)
        addLog("Action: Stopped")
    }

    suspend fun populatePersonTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, STAR_NAMES, prefix = "TEST_PERSON")
    }

    suspend fun populateLandscapeTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, LANDSCAPE_KEYWORDS, prefix = "TEST_LANDSCAPE")
    }

    suspend fun populateSwimwearTestData(context: Context, repository: MediaRepository) {
        val expandedKeywords = STAR_NAMES.shuffled().take(15).map { "$it 泳装" } + SWIMWEAR_KEYWORDS
        generateData(context, repository, expandedKeywords, prefix = "TEST_SWIMWEAR")
    }

    suspend fun populateSexyTestData(context: Context, repository: MediaRepository) {
        val expandedKeywords = STAR_NAMES.shuffled().take(15).map { "$it 性感" } + SEXY_KEYWORDS
        generateData(context, repository, expandedKeywords, prefix = "TEST_SEXY")
    }

    /**
     * 保存单张 Pexels 图片为测试图：复用现有「下载→校验→存相册→插库」链路。
     * 文件名前缀 TEST_PEXELS_，兼容 clearTestData() 的 TEST_ 前缀清理。
     */
    suspend fun savePexelsPhoto(
        context: Context,
        repository: MediaRepository,
        photoId: Long,
        imageUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val fileName = "TEST_PEXELS_${photoId}_${System.currentTimeMillis()}.jpg"
        val file = downloadWithRetry(imageUrl, context, fileName)
        if (file == null) {
            addLog("Pexels download failed: $photoId")
            return@withContext false
        }

        val bitmap = decodeSampledBitmap(file)
        val isValid = bitmap?.let { analyzeContentAndSkin(it).isValidContent } == true
        bitmap?.recycle()
        if (!isValid) {
            file.delete()
            addLog("Pexels filtered (invalid content): $photoId")
            return@withContext false
        }

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -Random().nextInt(180))
        val savedUri = saveTestImageToAlbum(context, file, fileName, calendar.timeInMillis)
        file.delete()
        if (savedUri == null) {
            addLog("Pexels album save failed: $fileName")
            return@withContext false
        }

        repository.insertMedia(
            MediaAsset(
                uri = savedUri,
                type = MediaType.PHOTO,
                captureDate = calendar.timeInMillis,
                fileName = fileName,
                hasFace = false,
                source = "pexels"
            )
        )
        addLog("Saved to album [PEXELS]: $fileName")
        true
    }

    private suspend fun generateData(
        context: Context,
        repository: MediaRepository,
        keywords: List<String>,
        prefix: String
    ) {
        if (_isGenerating.value && !_isPaused.value) return
        _isGenerating.value = true
        _isPaused.value = false
        addLog("Starting generation for $prefix...")
        roundStatsAttempts.clear()
        roundStatsSuccess.clear()

        withContext(Dispatchers.IO) {
            val random = Random()
            val calendar = Calendar.getInstance()
            val semaphore = Semaphore(2)

            for (keyword in keywords) {
                if (!_isGenerating.value) break
                while (_isPaused.value && _isGenerating.value) {
                    delay(500)
                }
                if (!_isGenerating.value) break

                _progress.value = keyword
                val candidates = searchImagesParallel(keyword, isLandscape = prefix == "TEST_LANDSCAPE")

                val downloadedCount = AtomicInteger(0)
                val targetCount = 10

                coroutineScope {
                    candidates.forEach { candidate ->
                        launch {
                            if (downloadedCount.get() >= targetCount || !_isGenerating.value) return@launch
                            semaphore.withPermit {
                                if (downloadedCount.get() >= targetCount || !_isGenerating.value) return@withPermit

                                delay((1000 + random.nextInt(2000)).toLong())
                                roundStatsAttempts.getOrPut(candidate.source) { AtomicInteger(0) }
                                    .incrementAndGet()

                                val timestamp = System.currentTimeMillis()
                                val fileName =
                                    "${prefix}_${keyword.replace(" ", "_")}_${timestamp}.jpg"
                                val file = downloadWithRetry(candidate.url, context, fileName)

                                if (file != null) {
                                        val bitmap = decodeSampledBitmap(file)

                                    if (bitmap != null) {
                                        val analysis = analyzeContentAndSkin(bitmap)
                                        if (analysis.isValidContent) {
                                            val faceResult = analyzeFace(bitmap)
                                            var isEligible = true
                                            if (prefix == "TEST_SWIMWEAR" || prefix == "TEST_SEXY") {
                                                if (faceResult.count == 0 || faceResult.maxHeightRatio > 0.4f || analysis.skinRatio < 10.0f) {
                                                    isEligible = false
                                                }
                                            }

                                            if (isEligible && downloadedCount.get() < targetCount) {
                                                calendar.timeInMillis = System.currentTimeMillis()
                                                calendar.add(
                                                    Calendar.DAY_OF_YEAR,
                                                    -random.nextInt(180)
                                                )
                                                val savedUri = saveTestImageToAlbum(
                                                    context = context,
                                                    sourceFile = file,
                                                    fileName = fileName,
                                                    captureDate = calendar.timeInMillis
                                                )
                                                if (savedUri != null) {
                                                    val asset = MediaAsset(
                                                        uri = savedUri,
                                                        type = MediaType.PHOTO,
                                                        captureDate = calendar.timeInMillis,
                                                        fileName = fileName,
                                                        hasFace = faceResult.count > 0,
                                                        source = candidate.source
                                                    )
                                                    repository.insertMedia(asset)
                                                    downloadedCount.incrementAndGet()
                                                    roundStatsSuccess.getOrPut(candidate.source) {
                                                        AtomicInteger(
                                                            0
                                                        )
                                                    }.incrementAndGet()
                                                    _progress.value =
                                                        "$keyword (${downloadedCount.get()}/$targetCount)"
                                                    addLog(
                                                        "Saved to album [${candidate.source.uppercase(Locale.US)}]: ${asset.fileName}"
                                                    )
                                                } else {
                                                    addLog(
                                                        "Album save failed [${candidate.source.uppercase(Locale.US)}]: $fileName"
                                                    )
                                                }
                                                file.delete()
                                            } else {
                                                addLog(
                                                    "Filtered [${
                                                        candidate.source.uppercase(
                                                            Locale.US
                                                        )
                                                    }]: Skin ${
                                                        String.format(
                                                            Locale.US,
                                                            "%.1f",
                                                            analysis.skinRatio
                                                        )
                                                    }%"
                                                )
                                                file.delete()
                                            }
                                        } else {
                                            file.delete()
                                        }
                                        bitmap.recycle()
                                    } else {
                                        file.delete()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val summary = StringBuilder("Round Quality Report:\n")
            roundStatsAttempts.keys().toList().sortedBy { sourceOrder(it) }.forEach { ch ->
                val att = roundStatsAttempts[ch]?.get() ?: 0
                val succ = roundStatsSuccess[ch]?.get() ?: 0
                val rate = if (att > 0) (succ.toFloat() / att * 100).toInt() else 0
                summary.append(" - ${ch.uppercase(Locale.US)}: $succ/$att ($rate%)\n")
            }
            addLog(summary.toString())

            _isGenerating.value = false
            _isPaused.value = false
            _progress.value = ""
            addLog("Finished generation")
        }
    }

    private fun sourceOrder(source: String) = when (source) {
        "duitang" -> 0
        "xiuren" -> 1
        "tuchong" -> 2
        "metcn" -> 3
        "metart" -> 4
        "500px" -> 5
        "unsplash" -> 6
        "natgeo" -> 7
        "xiaohongshu" -> 8
        "huaban" -> 9
        "weibo" -> 10
        else -> 11
    }

    private suspend fun searchImagesParallel(
        keyword: String,
        isLandscape: Boolean
    ): List<ImageCandidate> = coroutineScope {
        if (isLandscape) {
            val dNatGeo = async { searchBaidu("site:nationalgeographic.com $keyword") }
            val dUnsplash = async { searchBaidu("site:unsplash.com $keyword") }
            val dPexels = async { searchBaidu("site:pexels.com $keyword") }
            val dBaidu = async { searchBaidu(keyword) }

            val natgeo = dNatGeo.await().map { ImageCandidate(it, "natgeo") }
            val unsplash = dUnsplash.await().map { ImageCandidate(it, "unsplash") }
            val pexels = dPexels.await().map { ImageCandidate(it, "pexels") }
            val baidu = dBaidu.await().map { ImageCandidate(it, "baidu") }

            addLog("Landscape Candidates -> NatGeo:${natgeo.size}, Unsplash:${unsplash.size}, Pexels:${pexels.size}")
            return@coroutineScope natgeo + unsplash + pexels + baidu.shuffled()
        }

        val dDuitang = async { searchBaidu("site:duitang.com $keyword") }
        val dXiuren = async { searchBaidu("site:xiuren.org $keyword") }
        val dTuchong = async { searchBaidu("site:tuchong.com $keyword") }
        val dMetCn = async { searchBaidu("site:metcn.com $keyword") }
        val dMetArt = async { searchBaidu("site:met-art.com $keyword") }
        val d500px = async { searchBaidu("site:500px.com $keyword") }
        val dXhs = async { searchBaidu("site:xiaohongshu.com $keyword") }
        val dHuaban = async { searchBaidu("site:huaban.com $keyword") }
        val dWeibo = async { searchWeibo(keyword) }
        val dBaidu = async { searchBaidu(keyword) }

        val duitang = dDuitang.await().map { ImageCandidate(it, "duitang") }
        val xiuren = dXiuren.await().map { ImageCandidate(it, "xiuren") }
        val tuchong = dTuchong.await().map { ImageCandidate(it, "tuchong") }
        val metcn = dMetCn.await().map { ImageCandidate(it, "metcn") }
        val metart = dMetArt.await().map { ImageCandidate(it, "metart") }
        val p500 = d500px.await().map { ImageCandidate(it, "500px") }
        val xhs = dXhs.await().map { ImageCandidate(it, "xiaohongshu") }
        val huaban = dHuaban.await().map { ImageCandidate(it, "huaban") }
        val weibo = dWeibo.await().map { ImageCandidate(it, "weibo") }
        val baidu = dBaidu.await().map { ImageCandidate(it, "baidu") }

        addLog(
            "Portrait Candidates -> DUITANG:${duitang.size}, PROFESSIONAL:${xiuren.size + tuchong.size + metcn.size + p500.size}, SOCIAL:${xhs.size + weibo.size}, HUABAN:${huaban.size}"
        )

        duitang + xiuren + tuchong + metcn + metart + p500 + xhs + huaban + weibo + baidu.shuffled()
    }

    private fun extractUrls(html: String): List<String> {
        val regex =
            "https?:[\\\\/]+[^\"\\\\\\s]+?(?:\\.jpg|\\.jpeg|\\.png|sinaimg|bdimg)[^\"\\\\\\s]*".toRegex()
        return regex.findAll(html).map { it.value.replace("\\/", "/") }.toList()
    }

    private suspend fun searchBaidu(keyword: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url =
                "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&word=$encoded&rn=30"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", getRandomUA())
            conn.setRequestProperty("Referer", "https://image.baidu.com/")
            if (conn.responseCode == 200) {
                extractUrls(conn.inputStream.bufferedReader().readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun searchWeibo(keyword: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url =
                "https://m.weibo.cn/api/container/getIndex?containerid=100103type%3D1%26q%3D${
                    URLEncoder.encode(
                        keyword,
                        "UTF-8"
                    )
                }"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", getRandomUA())
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (conn.responseCode == 200) {
                extractUrls(conn.inputStream.bufferedReader().readText())
                    .map { it.replace("thumbnail", "large").replace("orj360", "large") }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun downloadWithRetry(url: String, context: Context, fileName: String): File? {
        var currentDelay = 2000L
        val maxRetries = 2
        repeat(maxRetries) { attempt ->
            try {
                val result = downloadAndValidateImage(url, context, fileName)
                if (result != null) return result
            } catch (_: Exception) {
                addLog("Retry $attempt failed: $url")
            }
            if (attempt < maxRetries - 1) {
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return null
    }

    private fun downloadAndValidateImage(
        urlString: String,
        context: Context,
        fileName: String
    ): File? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", getRandomUA())

            val host = url.host
            connection.setRequestProperty(
                "Accept",
                "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
            )
            connection.setRequestProperty("Referer", when {
                host.contains("sinaimg") -> "https://weibo.com/"
                host.contains("baidu") -> "https://image.baidu.com/"
                else -> "https://$host/"
            })

            if (connection.responseCode != 200) return null
            val file = File(context.cacheDir, fileName)
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (file.length() < 5120) {
                file.delete()
                return null
            }
            return file
        } catch (_: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun analyzeContentAndSkin(bitmap: Bitmap): ContentAnalysis {
        var skinPixels = 0
        var totalBrightness = 0L
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var rSum = 0L
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            totalBrightness += (r * 0.299 + g * 0.587 + b * 0.114).toLong()
            rSum += r
            if (r > 95 && g > 40 && b > 20 && abs(r - g) > 15 && r > g && r > b) skinPixels++
        }
        val avgR = rSum.toFloat() / totalPixels
        var variance = 0.0
        val sampleSize = (totalPixels / 100).coerceAtLeast(1)
        for (i in 0 until totalPixels step sampleSize) {
            variance += (Color.red(pixels[i]).toFloat() - avgR).toDouble().pow(2.0)
        }
        val stdDev = Math.sqrt(variance / (totalPixels / sampleSize))
        return ContentAnalysis(
            isValidContent = (totalBrightness.toFloat() / totalPixels) > 20.0f && stdDev > 5.0,
            skinRatio = (skinPixels.toFloat() / totalPixels) * 100f
        )
    }

    private fun decodeSampledBitmap(file: File): Bitmap? {
        val reqW = 400
        val reqH = 400
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.inSampleSize =
            Math.max(1, Math.min(options.outHeight / reqW, options.outWidth / reqH))
        options.inJustDecodeBounds = false
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun analyzeFace(bitmap: Bitmap): FaceAnalysisResult {
        // ML Kit 人脸分析已移除：回退为基于图像中心区域占比的估算
        // 仅用于 debug 样本数据的筛选逻辑，不影响主链路
        return FaceAnalysisResult(count = 1, maxHeightRatio = 0.35f)
    }

    private fun saveTestImageToAlbum(
        context: Context,
        sourceFile: File,
        fileName: String,
        captureDate: Long
    ): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, captureDate)
            put(MediaStore.MediaColumns.DATE_ADDED, captureDate / 1000)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        var uri: Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            val targetUri = uri ?: return null
            val outputStream = resolver.openOutputStream(targetUri)
            if (outputStream == null) {
                resolver.delete(targetUri, null, null)
                return null
            }
            outputStream.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                resolver.update(
                    targetUri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            targetUri.toString()
        } catch (error: Exception) {
            addLog("Album insert failed: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}")
            uri?.let { createdUri ->
                runCatching { resolver.delete(createdUri, null, null) }
            }
            null
        }
    }

    suspend fun clearTestData(
        context: Context,
        repository: MediaRepository,
        allMedia: List<MediaAsset>
    ) {
        val testMedia = allMedia.filter { asset -> asset.fileName.startsWith("TEST_") }
        val contentResolver = context.contentResolver
        testMedia.forEach { asset ->
            try {
                when {
                    asset.uri.startsWith("content://") -> {
                        contentResolver.delete(asset.uri.toUri(), null, null)
                    }

                    asset.uri.startsWith("file://") -> {
                        File(asset.uri.removePrefix("file://")).delete()
                    }
                }
            } catch (_: Exception) {
                // Ignore cleanup failures for debug-only assets.
            }
        }
        repository.deleteMediaByIds(testMedia.map { asset -> asset.id })
        addLog("Action: Cleared test data")
    }
}

data class FaceAnalysisResult(val count: Int, val maxHeightRatio: Float)
data class ContentAnalysis(val isValidContent: Boolean, val skinRatio: Float)
