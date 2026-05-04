package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy

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
}
