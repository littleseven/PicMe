package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.model.MediaEntity

@Database(
    entities = [
        MediaEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        PersonEntity::class,
        FaceEmbeddingEntity::class,
        PhotoEditRecipeEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        OcrWordEntity::class,
        OcrWordOccurrence::class,
        LocationHierarchyEntity::class,
        MediaLocationEntity::class,
        TagScanTaskEntity::class,
        MediaFeedbackEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun tagDao(): TagDao
    abstract fun tagScanTaskDao(): TagScanTaskDao
    abstract fun ocrWordDao(): OcrWordDao
    abstract fun personDao(): PersonDao
    abstract fun locationDao(): LocationDao
    abstract fun photoEditRecipeDao(): PhotoEditRecipeDao
    abstract fun mediaFeedbackDao(): MediaFeedbackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picme_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration 2 → 3：新增 tag_scan_tasks 表，媒体表增加 lastTagScanAt / lastTagScanPasses
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 新增 tag_scan_tasks 表
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_scan_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        `pass` TEXT NOT NULL,
                        `tagCategories` TEXT,
                        `status` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `scheduledAt` INTEGER,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        `errorMessage` TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_status_priority_scheduledAt` ON `tag_scan_tasks` (`status`, `priority`, `scheduledAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_mediaId_pass_status` ON `tag_scan_tasks` (`mediaId`, `pass`, `status`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_sessionId_status` ON `tag_scan_tasks` (`sessionId`, `status`)"
                )

                // 媒体表新增字段
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanAt` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanPasses` TEXT"
                )
            }
        }
        /**
         * Migration 3 → 4：新增 media_assets.semantic_embedding 字段
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `semanticEmbedding` TEXT"
                )
            }
        }

        /**
         * Migration 4 → 5：空迁移（修复设备上数据库版本已升到5的问题）
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 无 schema 变更，仅同步版本号
            }
        }

        /**
         * Migration 5 → 6：新增 media_assets.mlKitLabels 字段
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `mlKitLabels` TEXT"
                )
            }
        }

        /**
         * Migration 6 → 7：新增 media_assets.mlKitLabelsZh 字段
         * 存储 ML Kit 英文标签对应的中文翻译，使中文搜索可直接命中 ML Kit 标签
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `mlKitLabelsZh` TEXT"
                )
            }
        }

        /**
         * Migration 7 → 8：性能优化
         * 添加 captureDate/hasFace 索引（清理旧命名 + 创建 Room 标准命名）
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 清理可能因上次 migration 回退残留的旧命名索引
                // SQLite DDL 不参与事务回退，需要显式清理
                try {
                    database.execSQL("DROP INDEX IF EXISTS `idx_media_capture_date`")
                } catch (_: Exception) { }
                try {
                    database.execSQL("DROP INDEX IF EXISTS `idx_media_has_face`")
                } catch (_: Exception) { }

                // 使用 Room 命名约定创建索引: index_<tableName>_<columnName>
                // 名称必须与 @Entity(indices = [...]) 声明一致
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_assets_captureDate` ON `media_assets`(`captureDate`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_assets_hasFace` ON `media_assets`(`hasFace`)"
                )
            }
        }

        /**
         * Migration 8 → 9：新增 photo_edit_recipes 表，保存编辑配方以实现非破坏性编辑
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_edit_recipes` (
                        `outputUri` TEXT PRIMARY KEY NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `recipeJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 9 → 10：新增 media_feedback 表，保存用户对搜索结果的点赞/点踩反馈
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_feedback` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `feedback_type` TEXT NOT NULL,
                        `query_text` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_feedback_lookup` ON `media_feedback` (`media_id`, `query_text`, `feedback_type`)"
                )
            }
        }

        /**
         * Migration 10 → 11：清理 ML Kit 图像标注遗留数据
         *
         * ML Kit Image Labeler 已移除，不再生成新标签。本次 migration：
         * 1. 清空 media_assets.mlKitLabels / mlKitLabelsZh
         * 2. 清空旧 ML Kit 写入的 labels（JSON 数组格式，Qwen 标签为 JSON 对象）
         * 3. 清空规范化标签表 tags / media_tag_cross_ref（旧 ML Kit 与废弃 ImageTagIndexingWorker 数据）
         */
        /**
         * Migration 11 → 12：新增 media_assets.labelsEn / labelsZh（英文打标 + 双字段汉化，见 spec §3.2）。
         *
         * 只 ADD COLUMN（全版本 SQLite 安全）；labels / mlKit* 死列暂不动（懒回填，见 spec §4）。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `labelsEn` TEXT")
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `labelsZh` TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 清空 ML Kit 专属列
                database.execSQL("UPDATE `media_assets` SET `mlKitLabels` = NULL, `mlKitLabelsZh` = NULL")

                // 清空旧 ML Kit 写入的 labels（JSON 数组格式）
                // tagger 当前写入的是 JSON 对象，以 '{' 开头，不受影响
                database.execSQL("UPDATE `media_assets` SET `labels` = NULL WHERE `labels` LIKE '[%]'")

                // 清空规范化标签表（旧 ML Kit / 废弃 ImageTagIndexingWorker 数据）
                database.execSQL("DELETE FROM `media_tag_cross_ref`")
                database.execSQL("DELETE FROM `tags`")
            }
        }
    }
}
