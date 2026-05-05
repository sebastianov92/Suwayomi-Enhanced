package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.UpdateState.DOWNLOADING
import suwayomi.tachidesk.graphql.types.UpdateState.ERROR
import suwayomi.tachidesk.graphql.types.UpdateState.IDLE
import suwayomi.tachidesk.graphql.types.WebUIFlavor
import suwayomi.tachidesk.graphql.types.WebUIUpdateStatus
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.WebInterfaceManager
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class InfoMutation {
    data class WebUIUpdateInput(
        val clientMutationId: String? = null,
    )

    data class WebUIUpdatePayload(
        val clientMutationId: String?,
        val updateStatus: WebUIUpdateStatus,
    )

    @RequireAuth
    fun updateWebUI(input: WebUIUpdateInput): CompletableFuture<DataFetcherResult<WebUIUpdatePayload?>> {
        return future {
            asDataFetcherResult {
                withTimeout(30.seconds) {
                    if (WebInterfaceManager.status.value.state === DOWNLOADING) {
                        return@withTimeout WebUIUpdatePayload(input.clientMutationId, WebInterfaceManager.status.value)
                    }

                    val flavor = WebUIFlavor.current

                    val (version, updateAvailable) = WebInterfaceManager.isUpdateAvailable(flavor)

                    if (!updateAvailable) {
                        val didUpdateCheckFail = version.isEmpty()

                        return@withTimeout WebUIUpdatePayload(
                            input.clientMutationId,
                            WebInterfaceManager.getStatus(version, if (didUpdateCheckFail) ERROR else IDLE),
                        )
                    }
                    try {
                        WebInterfaceManager.startDownloadInScope(flavor, version)
                    } catch (e: Exception) {
                        // ignore since we use the status anyway
                    }

                    WebUIUpdatePayload(
                        input.clientMutationId,
                        updateStatus = WebInterfaceManager.status.first { it.state == DOWNLOADING },
                    )
                }
            }
        }
    }

    @RequireAuth
    fun resetWebUIUpdateStatus(): CompletableFuture<DataFetcherResult<WebUIUpdateStatus?>> =
        future {
            asDataFetcherResult {
                withTimeout(30.seconds) {
                    val isUpdateFinished = WebInterfaceManager.status.value.state != DOWNLOADING
                    if (!isUpdateFinished) {
                        throw Exception("Status reset is not allowed during status \"$DOWNLOADING\"")
                    }

                    WebInterfaceManager.resetStatus()

                    WebInterfaceManager.status.first { it.state == IDLE }
                }
            }
        }

    data class ServerUpdatePayload(
        val clientMutationId: String?,
        val tag: String,
        val downloaded: Boolean,
    )

    /**
     * Pulls the latest jar from the fork's GitHub releases, drops it
     * at /data/server-update.jar, then exits the JVM. The Docker
     * entrypoint detects the queued jar on next boot, swaps it into
     * /opt/suwayomi/server.jar, and the container's restart policy
     * brings the upgraded server back up.
     *
     * Only applicable when the server runs inside the Suwayomi-Enhanced
     * Docker image — bare-jar deployments don't have the entrypoint
     * swap and would still need to relaunch manually.
     */
    @RequireAuth
    fun triggerServerUpdate(input: WebUIUpdateInput): CompletableFuture<DataFetcherResult<ServerUpdatePayload?>> =
        future {
            asDataFetcherResult {
                val (tag, _) =
                    suwayomi.tachidesk.global.impl.AppUpdate.downloadLatestJarToDataRoot()
                ServerUpdatePayload(input.clientMutationId, tag, downloaded = true)
                    .also {
                        // Schedule shutdown after the response goes out so the
                        // mutation completes cleanly. Docker restart policy
                        // brings us back up with the swapped jar.
                        suwayomi.tachidesk.global.impl.AppUpdate.scheduleRestart()
                    }
            }
        }
}
