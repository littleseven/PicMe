package com.mamba.picme.features.gallery.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.mamba.picme.beauty.api.facedetect.FaceDetectionConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Paint
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

private const val TAG = "GalleryLandmark"

private class LandmarkDetectionSnapshot(
    val bigBeauty106Points: FloatArray?
)

// 与 FaceMakeupPass 中的腮红三角网格保持一致，便于对齐静态图左右脸区域。
private val BLUSH_TRIANGLE_INDICES = intArrayOf(
    2, 3, 78,
    3, 78, 44,
    3, 4, 44,
    4, 44, 45,
    4, 5, 45,
    5, 45, 46,
    5, 6, 46,
    29, 30, 79,
    79, 29, 44,
    28, 29, 44,
    44, 28, 45,
    27, 28, 45,
    45, 27, 46,
    26, 27, 46
)

class FaceLandmarkDetectionState(
    val imageWidth: Int,
    val imageHeight: Int,
    val bigBeauty106Points: FloatArray?,
    val isLoading: Boolean,
    val errorMessage: String?
)

@Composable
fun rememberFaceLandmarkDetection(
    imageUri: String,
    enabled: Boolean
): FaceLandmarkDetectionState {
    val context = LocalContext.current
    var imageWidth by remember(imageUri) { mutableIntStateOf(0) }
    var imageHeight by remember(imageUri) { mutableIntStateOf(0) }
    var bigBeauty106Points by remember(imageUri) { mutableStateOf<FloatArray?>(null) }
    var isLoading by remember(imageUri) { mutableStateOf(false) }
    var errorMessage by remember(imageUri) { mutableStateOf<String?>(null) }
    var detectionRequestId by remember { mutableIntStateOf(0) }

    var mediaPipeLandmarker by remember { mutableStateOf<FaceLandmarker?>(null) }

    DisposableEffect(Unit) {
        mediaPipeLandmarker = runCatching {
            createFaceLandmarker(context, Delegate.GPU)
        }.getOrElse { gpuError ->
            Logger.e(TAG, "Failed to init FaceLandmarker with GPU, fallback to CPU", gpuError)
            runCatching {
                createFaceLandmarker(context, Delegate.CPU)
            }.getOrElse { cpuError ->
                Logger.e(TAG, "Failed to init FaceLandmarker", cpuError)
                null
            }
        }

        onDispose {
            mediaPipeLandmarker?.close()
        }
    }

    LaunchedEffect(imageUri, enabled, mediaPipeLandmarker) {
        detectionRequestId += 1
        val requestId = detectionRequestId
        if (!enabled) {
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null
        bigBeauty106Points = null

        try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromUri(context, imageUri)
            } ?: throw IllegalStateException(context.getString(R.string.load_failed))

            imageWidth = bitmap.width
            imageHeight = bitmap.height

            val snapshot = try {
                withContext(Dispatchers.Default) {
                    LandmarkDetectionSnapshot(
                        bigBeauty106Points = mediaPipeLandmarker?.let { landmarker ->
                            detectMediaPipe106(bitmap, landmarker)
                        }
                    )
                }
            } finally {
                bitmap.recycle()
            }

            if (requestId != detectionRequestId) {
                return@LaunchedEffect
            }

            bigBeauty106Points = snapshot.bigBeauty106Points

            if (snapshot.bigBeauty106Points == null) {
                errorMessage = context.getString(R.string.landmark_no_face_detected)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (requestId == detectionRequestId) {
                errorMessage = error.message ?: context.getString(R.string.load_failed)
            }
            Logger.e(TAG, "Landmark detection failed", error)
        } finally {
            if (requestId == detectionRequestId) {
                isLoading = false
            }
        }
    }

    return FaceLandmarkDetectionState(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        bigBeauty106Points = bigBeauty106Points,
        isLoading = isLoading,
        errorMessage = errorMessage
    )
}

@Composable
fun FaceLandmarkCanvasOverlay(
    state: FaceLandmarkDetectionState,
    modifier: Modifier = Modifier
) {
    if (state.imageWidth <= 0 || state.imageHeight <= 0) {
        return
    }

    val drawParams = remember(state.imageWidth, state.imageHeight) {
        object {
            val imageAspect = state.imageWidth.toFloat() / state.imageHeight.toFloat()
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val canvasAspect = canvasWidth / canvasHeight
        val imageAspect = drawParams.imageAspect

        val drawWidth: Float
        val drawHeight: Float
        val drawLeft: Float
        val drawTop: Float

        if (imageAspect > canvasAspect) {
            drawWidth = canvasWidth
            drawHeight = canvasWidth / imageAspect
            drawLeft = 0f
            drawTop = (canvasHeight - drawHeight) / 2f
        } else {
            drawHeight = canvasHeight
            drawWidth = canvasHeight * imageAspect
            drawLeft = (canvasWidth - drawWidth) / 2f
            drawTop = 0f
        }

        fun toCanvasPoint(normX: Float, normY: Float): Offset {
            return Offset(
                x = drawLeft + normX * drawWidth,
                y = drawTop + normY * drawHeight
            )
        }

        fun drawBlushTriangleMesh(points106: FloatArray, color: Color) {
            val pointCount = points106.size / 2
            val fillColor = color.copy(alpha = 0.14f)
            val strokeColor = color.copy(alpha = 0.75f)

            for (index in BLUSH_TRIANGLE_INDICES.indices step 3) {
                val first = BLUSH_TRIANGLE_INDICES[index]
                val second = BLUSH_TRIANGLE_INDICES[index + 1]
                val third = BLUSH_TRIANGLE_INDICES[index + 2]
                if (first >= pointCount || second >= pointCount || third >= pointCount) {
                    continue
                }

                val p0 = toCanvasPoint(points106[first * 2], points106[first * 2 + 1])
                val p1 = toCanvasPoint(points106[second * 2], points106[second * 2 + 1])
                val p2 = toCanvasPoint(points106[third * 2], points106[third * 2 + 1])
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                }

                drawPath(path = path, color = fillColor)
                drawPath(path = path, color = strokeColor, style = Stroke(width = 1.5f))
            }
        }

        if (state.bigBeauty106Points != null) {
            val blueColor = Color(0xFF4488FF)
            drawBlushTriangleMesh(state.bigBeauty106Points, blueColor)
            for (index in 0 until state.bigBeauty106Points.size / 2) {
                val x = state.bigBeauty106Points[index * 2]
                val y = state.bigBeauty106Points[index * 2 + 1]
                val canvasPoint = toCanvasPoint(x, y)
                drawCircle(color = blueColor, radius = 6f, center = canvasPoint)
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        color = "#4488FF".toColorInt()
                        textSize = 18f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        index.toString(),
                        canvasPoint.x,
                        canvasPoint.y - 8f,
                        paint
                    )
                }
            }
        }


    }
}

private fun createFaceLandmarker(context: Context, delegate: Delegate): FaceLandmarker {
    val baseOptions = BaseOptions.builder()
        .setDelegate(delegate)
        .setModelAssetPath("mediapipe/face_landmarker.task")
        .build()
    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(baseOptions)
        .setMinFaceDetectionConfidence(0.5f)
        .setMinTrackingConfidence(0.5f)
        .setMinFacePresenceConfidence(0.5f)
        .setNumFaces(1)
        .setOutputFaceBlendshapes(false)
        .setRunningMode(RunningMode.IMAGE)
        .build()
    return FaceLandmarker.createFromOptions(context, options)
}

private fun decodeSampledBitmapFromUri(context: Context, imageUri: String, maxDimension: Int = 2048): Bitmap? {
    val uri = imageUri.toUri()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, bounds)
    }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, decodeOptions)
    } ?: return null

    return normalizeBitmapOrientation(context, uri, decodedBitmap)
}

private fun normalizeBitmapOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        ExifInterface(inputStream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            transform.postRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            transform.postScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.postRotate(90f)
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            transform.postRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.postRotate(270f)
            transform.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            transform.postRotate(270f)
        }
    }

    if (transform.isIdentity) {
        return bitmap
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    }.onSuccess { transformedBitmap ->
        if (transformedBitmap !== bitmap) {
            bitmap.recycle()
        }
    }.getOrElse { error ->
        Logger.w(TAG, "Failed to normalize bitmap orientation, using original", error)
        bitmap
    }
}

private fun detectMediaPipe106(bitmap: Bitmap, landmarker: FaceLandmarker): FloatArray? {
    return try {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)
        if (result.faceLandmarks().isEmpty()) {
            Log.d(TAG, "No face detected by MediaPipe")
            return null
        }
        convert468To106ForDebug(result.faceLandmarks()[0])
    } catch (error: Exception) {
        Logger.e(TAG, "MediaPipe detection failed", error)
        null
    }
}

private fun convert468To106ForDebug(
    landmarks: List<NormalizedLandmark>
): FloatArray {
    val result = FloatArray(FaceDetectionConstants.POINT_COUNT * 2)

    fun getMpPoint(index: Int): Pair<Float, Float>? {
        if (index >= landmarks.size) return null
        return Pair(landmarks[index].x(), landmarks[index].y())
    }

    fun setPoint(index: Int, point: Pair<Float, Float>?) {
        if (point == null) return
        result[index * 2] = point.first.coerceIn(0f, 1f)
        result[index * 2 + 1] = point.second.coerceIn(0f, 1f)
    }

    val leftContourBasePoints = listOf(127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152)
        .mapNotNull(::getMpPoint)
    val rightContourBasePoints = listOf(152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356)
        .mapNotNull(::getMpPoint)

    for (index in 0..16) {
        val t = index.toFloat() / 16f
        val position = t * (leftContourBasePoints.size - 1)
        val baseIndex = position.toInt().coerceIn(0, leftContourBasePoints.size - 2)
        val fraction = position - baseIndex
        val p1 = leftContourBasePoints[baseIndex]
        val p2 = leftContourBasePoints[baseIndex + 1]
        setPoint(
            index,
            Pair(
                p1.first + (p2.first - p1.first) * fraction,
                p1.second + (p2.second - p1.second) * fraction
            )
        )
    }

    for (index in 1..16) {
        val t = index.toFloat() / 16f
        val position = t * (rightContourBasePoints.size - 1)
        val baseIndex = position.toInt().coerceIn(0, rightContourBasePoints.size - 2)
        val fraction = position - baseIndex
        val p1 = rightContourBasePoints[baseIndex]
        val p2 = rightContourBasePoints[baseIndex + 1]
        setPoint(
            16 + index,
            Pair(
                p1.first + (p2.first - p1.first) * fraction,
                p1.second + (p2.second - p1.second) * fraction
            )
        )
    }

    val nonContourMapping = intArrayOf(
        70, 63, 105, 66, 107,
        336, 296, 334, 293, 300,
        168,
        197, 5, 4,
        98, 241, 2, 461, 327,
        226, 30, 56, 133, 26, 110,
        362, 286, 260, 446, 339, 256,
        53, 52, 65, 55,
        285, 295, 282, 283,
        27, 23, 473,
        257, 253, 468,
        193, 417,
        198, 420, 49, 279,
        61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,
        78, 81, 13, 311, 308, 178, 14, 402,
        473, 468
    )

    for (index in 0 until FaceDetectionConstants.NON_CONTOUR_POINT_COUNT) {
        val mpIndex = nonContourMapping[index]
        if (mpIndex < landmarks.size) {
            val landmark = landmarks[mpIndex]
            result[(33 + index) * 2] = landmark.x().coerceIn(0f, 1f)
            result[(33 + index) * 2 + 1] = landmark.y().coerceIn(0f, 1f)
        }
    }

    return result
}



