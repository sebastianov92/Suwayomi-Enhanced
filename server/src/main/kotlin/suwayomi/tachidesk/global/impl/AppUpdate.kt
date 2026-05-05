package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.ts.config.ApplicationRootDir
import java.io.File
import kotlin.system.exitProcess

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class UpdateDataClass(
    /** [channel] mirrors [suwayomi.tachidesk.server.BuildConfig.BUILD_TYPE] */
    val channel: String,
    val tag: String,
    val url: String,
)

object AppUpdate {
    private const val LATEST_STABLE_CHANNEL_URL = "https://api.github.com/repos/sebastianov92/Suwayomi-Enhanced/releases/latest"
    private const val LATEST_PREVIEW_CHANNEL_URL = "https://api.github.com/repos/sebastianov92/Suwayomi-Enhanced/releases/latest"

    private val json: Json by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private suspend fun fetchLatest(
        channel: String,
        url: String,
    ): UpdateDataClass? =
        runCatching {
            val body =
                network.client
                    .newCall(GET(url))
                    .await()
                    .body
                    .string()
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
            val html = obj["html_url"]?.jsonPrimitive?.content ?: return@runCatching null
            UpdateDataClass(channel, tag, html)
        }.getOrNull()

    suspend fun checkUpdate(): List<UpdateDataClass> =
        listOfNotNull(
            fetchLatest("Stable", LATEST_STABLE_CHANNEL_URL),
            fetchLatest("Preview", LATEST_PREVIEW_CHANNEL_URL),
        )

    private val logger = LoggerFactory.getLogger(AppUpdate::class.java)

    /**
     * Downloads the latest fork release jar into ${dataRoot}/server-update.jar.
     * Returns (tag, jarBytesWritten). The Docker entrypoint moves the
     * file into /opt/suwayomi/server.jar on next boot.
     */
    suspend fun downloadLatestJarToDataRoot(): Pair<String, Long> {
        val body =
            network.client
                .newCall(GET(LATEST_PREVIEW_CHANNEL_URL))
                .await()
                .body
                .string()
        val obj = json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content
            ?: throw Exception("No tag_name in latest release response")
        val assets = obj["assets"]?.let { it as? JsonArray } ?: obj["assets"]?.jsonArray
            ?: throw Exception("No assets in latest release response")
        val jarAsset =
            assets.firstOrNull {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                name.startsWith("Suwayomi-Server-") && name.endsWith(".jar")
            } ?: throw Exception("No Suwayomi-Server-*.jar asset in $tag")
        val downloadUrl =
            jarAsset.jsonObject["browser_download_url"]?.jsonPrimitive?.content
                ?: throw Exception("Asset has no browser_download_url")

        val target = File(ApplicationRootDir, "server-update.jar")
        val tmp = File(ApplicationRootDir, "server-update.jar.part")
        tmp.parentFile.mkdirs()

        network.client
            .newCall(GET(downloadUrl))
            .await()
            .body
            .byteStream()
            .use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }

        // Atomic swap so a half-written jar can never be promoted.
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        logger.info("downloadLatestJarToDataRoot: queued $tag at ${target.absolutePath} (${target.length()} bytes)")
        return tag to target.length()
    }

    /**
     * Schedule a JVM exit after a short delay so the GraphQL response
     * has time to flush back to the client. Docker `restart: unless-stopped`
     * brings us back up; the entrypoint applies the queued jar.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun scheduleRestart() {
        GlobalScope.launch {
            delay(2_000)
            logger.warn("scheduleRestart: exiting JVM to apply queued server upgrade")
            exitProcess(0)
        }
    }
}
