import re

path = '/Users/guoshuai/AndroidStudioProjects/polang/androidApp/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt'
with open(path, 'r') as f:
    content = f.read()

# 1. Fix faceRoiToJson
old1 = '    /** 将 Stage 1 结果序列化为 JSON（用于 DB 持久化） */\n    private fun faceRoiToJson(result: Stage1Result): String {\n        return """{"hasFace":${result.hasFace},"faceCount":${result.faceCount},"isSelfie":${result.isSelfie},"isGroupPhoto":${result.isGroupPhoto}}"""\n    }'
new1 = '''    /**
     * 将 Stage 1 结果序列化为 JSON（用于 DB 持久化）
     *
     * 无人脸时返回 null，避免 caller 误将 hasFace 标记为 true。
     */
    private fun faceRoiToJson(result: Stage1Result): String? {
        if (!result.hasFace || result.faceCount == 0) {
            return null
        }
        return """{"hasFace":${result.hasFace},"faceCount":${result.faceCount},"isSelfie":${result.isSelfie},"isGroupPhoto":${result.isGroupPhoto}}"""
    }

    /** 判断 embedding 是否为无效的零向量 */
    private fun isZeroVector(embedding: FloatArray): Boolean {
        return embedding.all { it == 0f }
    }'''

if old1 in content:
    content = content.replace(old1, new1)
    print('Replaced faceRoiToJson')
else:
    print('WARNING: faceRoiToJson pattern not found')

# 2. Fix stage1WithEmbeddings
old2 = '            // 提取每张人脸的 512 维 embedding\n            val embeddings = mutableListOf<FloatArray>()\n            for (roi in stage1Result.roiRects) {\n                val feature = faceClusterEngine.extractFeature(faceBitmap, roi)\n                embeddings.add(feature)\n            }\n\n            Log.d(TAG, "[Pass 1] Extracted ${embeddings.size} embeddings for mediaId=$mediaId")\n            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings)'
new2 = '''            // 提取每张人脸的 512 维 embedding，过滤零向量
            val embeddings = mutableListOf<FloatArray>()
            for (roi in stage1Result.roiRects) {
                val feature = faceClusterEngine.extractFeature(faceBitmap, roi)
                if (!isZeroVector(feature)) {
                    embeddings.add(feature)
                } else {
                    Log.w(TAG, "[Pass 1] Zero vector embedding skipped for mediaId=$mediaId, roi=$roi")
                }
            }

            Log.d(TAG, "[Pass 1] Extracted ${embeddings.size} valid embeddings for mediaId=$mediaId")
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings)'''

if old2 in content:
    content = content.replace(old2, new2)
    print('Replaced stage1WithEmbeddings')
else:
    print('WARNING: stage1WithEmbeddings pattern not found')

# 3. Fix stage2FaceCluster
old3 = '''    private suspend fun stage2FaceCluster(
        bitmap: Bitmap,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val embeddings = mutableListOf<FaceEmbeddingOutput>()

        for (roi in stage1Result.roiRects) {
            val feature = faceClusterEngine.extractFeature(bitmap, roi)

            val matchedPersonId = faceClusterEngine.matchCluster(feature)

            val personId: Long = if (matchedPersonId != null) {
                faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                matchedPersonId
            } else {
                faceClusterEngine.createCluster(feature, mediaId)
            }

            embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
        }

        return Stage2Result(
            faceEmbeddings = embeddings,
            personIds = embeddings.mapNotNull { it.personId }
        )
    }'''
new3 = '''    private suspend fun stage2FaceCluster(
        bitmap: Bitmap,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val embeddings = mutableListOf<FaceEmbeddingOutput>()

        for (roi in stage1Result.roiRects) {
            val feature = faceClusterEngine.extractFeature(bitmap, roi)

            // 过滤零向量，避免误聚类
            if (isZeroVector(feature)) {
                Log.w(TAG, "[Stage 2] Zero vector embedding skipped for mediaId=$mediaId, roi=$roi")
                continue
            }

            val matchedPersonId = faceClusterEngine.matchCluster(feature)

            val personId: Long = if (matchedPersonId != null) {
                faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                matchedPersonId
            } else {
                faceClusterEngine.createCluster(feature, mediaId)
            }

            embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
        }

        return Stage2Result(
            faceEmbeddings = embeddings,
            personIds = embeddings.mapNotNull { it.personId }
        )
    }'''

if old3 in content:
    content = content.replace(old3, new3)
    print('Replaced stage2FaceCluster')
else:
    print('WARNING: stage2FaceCluster pattern not found')

with open(path, 'w') as f:
    f.write(content)

print('Done')
