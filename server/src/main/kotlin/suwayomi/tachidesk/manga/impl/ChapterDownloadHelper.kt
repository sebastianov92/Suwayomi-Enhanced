package suwayomi.tachidesk.manga.impl

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ArchiveProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.FolderProvider
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.io.File
import java.io.InputStream

object ChapterDownloadHelper {
    fun getImage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
    ): Pair<InputStream, String> = provider(mangaId, chapterId).getImage().execute(index)

    fun getImageCount(
        mangaId: Int,
        chapterId: Int,
    ): Int = provider(mangaId, chapterId).getImageCount()

    fun delete(
        mangaId: Int,
        chapterId: Int,
    ): Boolean = provider(mangaId, chapterId).delete()

    /**
     * This function should never be called without calling [getChapterDownloadReady] beforehand.
     */
    suspend fun download(
        mangaId: Int,
        chapterId: Int,
        download: DownloadQueueItem,
        scope: CoroutineScope,
        step: suspend (DownloadQueueItem?, Boolean) -> Unit,
    ): Boolean = provider(mangaId, chapterId).download().execute(download, scope, step)

    // return the appropriate provider based on how the download was saved. For the logic is simple but will evolve when new types of downloads are available
    private fun provider(
        mangaId: Int,
        chapterId: Int,
    ): ChaptersFilesProvider<*> {
        val chapterFolder = File(getChapterDownloadPath(mangaId, chapterId))
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        if (cbzFile.exists()) return ArchiveProvider(mangaId, chapterId)
        if (!chapterFolder.exists() && serverConfig.downloadAsCbz.value) return ArchiveProvider(mangaId, chapterId)
        return FolderProvider(mangaId, chapterId)
    }

    fun getArchiveStreamWithSize(
        mangaId: Int,
        chapterId: Int,
    ): Pair<InputStream, Long> = provider(mangaId, chapterId).getAsArchiveStream()

    private fun getChapterWithCbzFileName(chapterId: Int): Pair<ChapterDataClass, String> =
        transaction {
            val row =
                (ChapterTable innerJoin MangaTable)
                    .select(ChapterTable.columns + MangaTable.columns)
                    .where { ChapterTable.id eq chapterId }
                    .firstOrNull() ?: throw IllegalArgumentException("ChapterId $chapterId not found")
            val chapter = ChapterTable.toDataClass(row)
            val override = MangaUserOverride.cachedOverride(chapter.mangaId)
            val mangaTitle =
                override
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                    ?: row[MangaTable.title]
            val mangaAuthor =
                (override?.author?.takeIf { it.isNotBlank() } ?: row[MangaTable.author])?.takeIf { it.isNotBlank() }

            // Match the on-disk download folder format used by DirName.getChapterDir:
            //   "[Author - ]{Title} ({Scanlator}) - {Chapter}"
            // and apply the user's scanlator alias mapping when one exists.
            // The author prefix is opt-in via serverConfig.opdsIncludeAuthorInEntry
            // so power users who want the full citation in the filename can have
            // it without forcing it on everyone else.
            val resolvedScanlator = ScanlatorAlias.resolve(chapter.scanlator)
            val authorPrefix =
                if (suwayomi.tachidesk.server.serverConfig.opdsIncludeAuthorInEntry.value && !mangaAuthor.isNullOrBlank()) {
                    "$mangaAuthor - "
                } else {
                    ""
                }
            val baseName =
                if (!resolvedScanlator.isNullOrBlank()) {
                    "$authorPrefix$mangaTitle ($resolvedScanlator) - ${chapter.name}"
                } else {
                    "$authorPrefix$mangaTitle - ${chapter.name}"
                }
            val fileName = "$baseName.cbz"

            Pair(chapter, fileName)
        }

    fun getCbzForDownload(
        chapterId: Int,
        markAsRead: Boolean?,
    ): Triple<InputStream, String, Long> {
        val (chapterData, fileName) = getChapterWithCbzFileName(chapterId)

        // Ensure the chapter is on disk before streaming. Lets OPDS
        // readers tap the CBZ acquisition link on a not-yet-downloaded
        // chapter and have the server fetch + serve it on the fly.
        ensureChapterOnDisk(chapterData)

        val cbzFile = provider(chapterData.mangaId, chapterData.id).getAsArchiveStream()

        if (markAsRead == true) {
            Chapter.modifyChapter(
                chapterData.mangaId,
                chapterData.index,
                isRead = true,
                isBookmarked = null,
                markPrevRead = null,
                lastPageRead = null,
            )
        }

        return Triple(cbzFile.first, fileName, cbzFile.second)
    }

    /**
     * Block (with timeout) until the chapter has been pulled to the
     * download directory. If the chapter isn't downloaded yet, the
     * function enqueues it and polls the ChapterTable until the row
     * flips `isDownloaded = true`.
     *
     * Throws if the chapter still isn't on disk after the timeout.
     */
    fun ensureChapterOnDiskById(chapterId: Int) {
        val chapter =
            transaction {
                val row =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.id eq chapterId }
                        .firstOrNull() ?: throw IllegalArgumentException("ChapterId $chapterId not found")
                ChapterTable.toDataClass(row)
            }
        ensureChapterOnDisk(chapter)
    }

    private fun ensureChapterOnDisk(chapter: ChapterDataClass) {
        val cbzPath = File(getChapterCbzPath(chapter.mangaId, chapter.id))
        val folderPath = File(getChapterDownloadPath(chapter.mangaId, chapter.id))
        val isOnDisk = {
            cbzPath.exists() ||
                (folderPath.exists() && (folderPath.listFiles()?.isNotEmpty() == true))
        }
        if (isOnDisk()) return

        suwayomi.tachidesk.manga.impl.download.DownloadManager
            .enqueueWithChapterIndex(chapter.mangaId, chapter.index)

        val timeoutMs = 5L * 60L * 1000L
        val pollIntervalMs = 1000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val downloaded =
                transaction {
                    ChapterTable
                        .select(ChapterTable.isDownloaded)
                        .where { ChapterTable.id eq chapter.id }
                        .firstOrNull()
                        ?.get(ChapterTable.isDownloaded) == true
                }
            if (downloaded || isOnDisk()) return
            Thread.sleep(pollIntervalMs)
        }
        throw IllegalStateException("Chapter $chapter.id failed to download within timeout")
    }

    fun getCbzMetadataForDownload(chapterId: Int): Pair<String, Long> { // fileName, fileSize
        val (chapterData, fileName) = getChapterWithCbzFileName(chapterId)

        val fileSize = provider(chapterData.mangaId, chapterData.id).getArchiveSize()

        return Pair(fileName, fileSize)
    }
}
