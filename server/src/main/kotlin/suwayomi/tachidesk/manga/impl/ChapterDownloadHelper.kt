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

        // Treat as ready only when:
        //   1. ChapterTable.isDownloaded == true (downloader marked it).
        //   2. There's actually something on disk (a CBZ with bytes, or a
        //      folder with at least one image file).
        // Both checks are needed because a stale isDownloaded flag from
        // a previous partial run can race the actual files. Without the
        // disk check, ensureChapterOnDisk would early-return and the
        // provider would throw "CBZ file not found" / "Invalid folder".
        val isPhysicallyOnDisk = {
            (cbzPath.exists() && cbzPath.length() > 0L) ||
                (folderPath.exists() && folderPath.listFiles()?.any { it.isFile } == true)
        }
        val isFlagged = {
            transaction {
                ChapterTable
                    .select(ChapterTable.isDownloaded)
                    .where { ChapterTable.id eq chapter.id }
                    .firstOrNull()
                    ?.get(ChapterTable.isDownloaded) == true
            }
        }
        val isReady = { isFlagged() && isPhysicallyOnDisk() }
        if (isReady()) return

        // Retry the enqueue-and-poll loop a few times. Sources behind
        // rate limiting / Cloudflare regularly bounce back with 400 or
        // 429 on a single attempt; a fresh enqueue picks up where the
        // failed one left off and usually succeeds. Three attempts at
        // 90 s each keeps the worst case bounded but still gives slow
        // sources room to finish.
        val attempts = 3
        val perAttemptMs = 90L * 1000L
        val pollIntervalMs = 1000L
        repeat(attempts) { _ ->
            suwayomi.tachidesk.manga.impl.download.DownloadManager
                .enqueueWithChapterIndex(chapter.mangaId, chapter.index)

            val deadline = System.currentTimeMillis() + perAttemptMs
            while (System.currentTimeMillis() < deadline) {
                if (isReady()) return
                Thread.sleep(pollIntervalMs)
            }
        }
        throw IllegalStateException(
            "Chapter ${chapter.id} did not finish downloading after ${attempts * perAttemptMs / 1000}s",
        )
    }

    fun getCbzMetadataForDownload(chapterId: Int): Pair<String, Long> { // fileName, fileSize
        val (chapterData, fileName) = getChapterWithCbzFileName(chapterId)

        val fileSize = provider(chapterData.mangaId, chapterData.id).getArchiveSize()

        return Pair(fileName, fileSize)
    }
}
