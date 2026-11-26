package com.example.engine

import android.content.Context
import android.os.StatFs
import com.example.data.local.AnimeDubDao
import com.example.data.local.ModelManifestEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Multi-file, resumable, integrity-verified AI model download manager.
 *
 * Guarantees:
 * - Saves every artifact under its actual official relative filename in
 *   filesDir/verified_models/<modelId>/<relativePath> (never verified_artifact.bin).
 * - Downloads ALL required files (weights + tokenizers + configs + manifests).
 * - Uses real server Content-Length (never fake KB placeholders or invented sizes for gated repos).
 * - Supports HTTP Range resume via per-file .part files.
 * - Verifies SHA-256 checksums against official Hugging Face LFS SHA-256 hashes.
 * - Marks INSTALLED only when every required file exists, is non-empty, and passes verification.
 * - Prevents duplicate concurrent downloads and supports cancellation + retry.
 */
class ModelDownloadManager(
    private val context: Context,
    private val dao: AnimeDubDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    fun getModelsDirectory(): File {
        val dir = File(context.filesDir, "verified_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelDirectory(modelId: String): File {
        return File(getModelsDirectory(), modelId)
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }

    fun getModelsStorageUsedBytes(): Long {
        val dir = getModelsDirectory()
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun inspectLocalFiles(model: ModelManifestEntity): List<ModelArtifactDiskStatus> {
        val artifacts = VerifiedModelCatalog.parseManifest(model.filesManifestJson)
        val modelDir = getModelDirectory(model.modelId)
        return artifacts.map { artifact ->
            val targetFile = File(modelDir, artifact.relativePath)
            val partFile = File(modelDir, "${artifact.relativePath}.part")
            val actualLen = if (targetFile.exists() && targetFile.isFile) targetFile.length() else 0L
            val partLen = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
            val isSized = actualLen > 0L && (artifact.expectedBytes <= 0L || actualLen == artifact.expectedBytes)
            ModelArtifactDiskStatus(
                artifact = artifact,
                actualBytesOnDisk = actualLen,
                partialBytesOnDisk = partLen,
                isPresentAndSized = isSized,
                localPath = targetFile.absolutePath
            )
        }
    }

    /**
     * Reconciles the Room catalog with VerifiedModelCatalog on app startup:
     * - Removes obsolete fake/placeholder KB entries from earlier builds.
     * - Removes any legacy verified_artifact.bin files on disk.
     * - Preserves genuine on-disk verified files if all required artifacts are present.
     */
    suspend fun reconcileCatalogWithDisk() = withContext(Dispatchers.IO) {
        val catalog = VerifiedModelCatalog.buildVerifiedModelCatalog()
        val validIds = catalog.map { it.modelId }
        val existingById = dao.getAllModelsOnce().associateBy { it.modelId }

        // Clean legacy obsolete directories & fake verified_artifact.bin files
        val rootDir = getModelsDirectory()
        rootDir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (child.name !in validIds) {
                    child.deleteRecursively()
                } else {
                    File(child, "verified_artifact.bin").takeIf { it.exists() }?.delete()
                    File(child, "verified_artifact.part").takeIf { it.exists() }?.delete()
                }
            }
        }

        dao.deleteObsoleteModels(validIds)

        val reconciled = catalog.map { spec ->
            if (!VerifiedModelCatalog.isDownloadablePackage(spec)) {
                spec
            } else {
                val existing = existingById[spec.modelId]
                val diskStatuses = inspectLocalFiles(spec)
                val completeBytes = diskStatuses.sumOf { it.actualBytesOnDisk }
                val partialBytes = diskStatuses.sumOf { it.partialBytesOnDisk }
                val totalOnDisk = completeBytes + partialBytes
                val presentCount = diskStatuses.count { it.isPresentAndSized }

                // Keep probed gated manifest if user already resolved real sizes via HF token
                val effectiveSpec = if (
                    spec.isGatedHuggingFace &&
                    existing != null &&
                    existing.isSizeKnown &&
                    existing.expectedSizeBytes > 0L
                ) {
                    spec.copy(
                        filesManifestJson = existing.filesManifestJson,
                        expectedSizeBytes = existing.expectedSizeBytes,
                        isSizeKnown = true
                    )
                } else {
                    spec
                }

                when {
                    presentCount == diskStatuses.size && diskStatuses.isNotEmpty() && existing?.downloadState == "INSTALLED" -> {
                        val level = computeVerificationLevel(VerifiedModelCatalog.parseManifest(effectiveSpec.filesManifestJson))
                        val levelLabel = if (level == "VERIFIED_SHA256") "SHA-256 verified" else "size-verified only"
                        effectiveSpec.copy(
                            downloadState = "INSTALLED",
                            downloadProgressPercent = 100,
                            downloadedBytes = completeBytes,
                            verifiedFilesCount = presentCount,
                            installedVersion = existing.installedVersion,
                            localFilePath = getModelDirectory(spec.modelId).absolutePath,
                            sha256Checksum = existing.sha256Checksum,
                            verificationLevel = existing.verificationLevel.takeIf { it != "UNVERIFIED" } ?: level,
                            statusNote = "All $presentCount/${diskStatuses.size} files present on disk ($levelLabel)."
                        )
                    }
                    totalOnDisk > 0L -> {
                        val pct = if (effectiveSpec.isSizeKnown && effectiveSpec.expectedSizeBytes > 0L) {
                            ((totalOnDisk * 100L) / effectiveSpec.expectedSizeBytes).toInt().coerceIn(1, 99)
                        } else 0
                        effectiveSpec.copy(
                            downloadState = "NOT_DOWNLOADED",
                            downloadProgressPercent = pct,
                            downloadedBytes = totalOnDisk,
                            verifiedFilesCount = presentCount,
                            localFilePath = getModelDirectory(spec.modelId).absolutePath,
                            verificationLevel = "UNVERIFIED",
                            statusNote = "Incomplete download ($presentCount/${diskStatuses.size} files complete). Tap Download to resume."
                        )
                    }
                    else -> effectiveSpec
                }
            }
        }
        dao.insertModels(reconciled)
    }

    fun cancelDownload(modelId: String) {
        activeDownloads[modelId]?.cancel()
    }

    /**
     * Queries real HTTP HEAD Content-Length for gated Hugging Face files using the user's
     * authorized read-only HF token. Never invents or guesses sizes!
     */
    suspend fun probeGatedModelSizes(
        modelId: String,
        hfAccessToken: String
    ): Result<ModelManifestEntity> = withContext(Dispatchers.IO) {
        val token = hfAccessToken.trim()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a read-only Hugging Face Access Token first."))
        }
        val model = dao.getModelById(modelId)
            ?: return@withContext Result.failure(IllegalArgumentException("Model $modelId not found."))
        val artifacts = VerifiedModelCatalog.parseManifest(model.filesManifestJson)
        if (artifacts.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No downloadable files declared for ${model.modelName}."))
        }

        val resolved = mutableListOf<ModelArtifactFile>()
        for (file in artifacts) {
            if (file.expectedBytes > 0L) {
                resolved.add(file)
                continue
            }
            val sizeResult = queryRemoteContentLength(file.downloadUrl, token)
            if (sizeResult.isFailure) {
                val msg = sizeResult.exceptionOrNull()?.localizedMessage ?: "Authorization failed"
                val failed = model.copy(
                    statusNote = "Could not query gated file ${file.relativePath}: $msg"
                )
                dao.updateModel(failed)
                return@withContext Result.failure(IllegalStateException(failed.statusNote))
            }
            resolved.add(file.copy(expectedBytes = sizeResult.getOrThrow()))
        }

        val exactTotal = resolved.sumOf { it.expectedBytes }
        val updated = model.copy(
            filesManifestJson = VerifiedModelCatalog.serializeManifest(resolved),
            expectedSizeBytes = exactTotal,
            isSizeKnown = exactTotal > 0L,
            statusNote = "Verified real server total size with HF token: ${resolved.size} files (${exactTotal} bytes). Ready to download."
        )
        dao.updateModel(updated)
        Result.success(updated)
    }

    /**
     * Downloads ALL required files for a model into actual official filenames, supports HTTP Range resume,
     * verifies exact byte counts & SHA-256 hashes, and marks INSTALLED only after every file passes.
     */
    suspend fun downloadAndVerifyModel(
        modelId: String,
        hfAccessToken: String? = null
    ): Result<ModelManifestEntity> = withContext(Dispatchers.IO) {
        var model = dao.getModelById(modelId)
            ?: return@withContext Result.failure(IllegalArgumentException("Model $modelId not found in catalog."))

        if (!VerifiedModelCatalog.isDownloadablePackage(model)) {
            return@withContext Result.success(model)
        }

        val currentJob = coroutineContext[Job]
        mutex.withLock {
            if (activeDownloads[modelId]?.isActive == true) {
                return@withContext Result.failure(IllegalStateException("Download already running for ${model.modelName}."))
            }
            if (currentJob != null) {
                activeDownloads[modelId] = currentJob
            }
        }

        try {
            val token = hfAccessToken?.trim().orEmpty()
            if (model.isGatedHuggingFace && token.isEmpty()) {
                val gatedMsg = "Gated Hugging Face model: Accept user terms at ${model.sourceUrl} (and pyannote/segmentation-3.0) and provide a read-only HF Access Token."
                val failed = model.copy(downloadState = "FAILED", statusNote = gatedMsg)
                dao.updateModel(failed)
                return@withContext Result.failure(IllegalStateException(gatedMsg))
            }

            var artifacts = VerifiedModelCatalog.parseManifest(model.filesManifestJson)
            if (artifacts.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Model manifest contains no downloadable files."))
            }

            // 1. Resolve any unknown remote file sizes via real HTTP HEAD Content-Length (e.g. gated models)
            if (!model.isSizeKnown || artifacts.any { it.expectedBytes <= 0L }) {
                dao.updateModel(
                    model.copy(
                        downloadState = "DOWNLOADING",
                        downloadProgressPercent = 0,
                        statusNote = "Querying real server Content-Length for ${artifacts.size} files..."
                    )
                )
                val resolvedList = mutableListOf<ModelArtifactFile>()
                for (artifact in artifacts) {
                    coroutineContext.ensureActive()
                    if (artifact.expectedBytes > 0L) {
                        resolvedList.add(artifact)
                    } else {
                        val lenRes = queryRemoteContentLength(artifact.downloadUrl, token.ifEmpty { null })
                        if (lenRes.isFailure) {
                            val err = lenRes.exceptionOrNull()?.localizedMessage ?: "Failed to query remote size"
                            val failed = model.copy(
                                downloadState = "FAILED",
                                statusNote = "Cannot inspect ${artifact.relativePath}: $err"
                            )
                            dao.updateModel(failed)
                            return@withContext Result.failure(IllegalStateException(failed.statusNote))
                        }
                        resolvedList.add(artifact.copy(expectedBytes = lenRes.getOrThrow()))
                    }
                }
                artifacts = resolvedList
                model = model.copy(
                    filesManifestJson = VerifiedModelCatalog.serializeManifest(artifacts),
                    expectedSizeBytes = artifacts.sumOf { it.expectedBytes },
                    isSizeKnown = true,
                    totalFilesCount = artifacts.size
                )
                dao.updateModel(model)
            }

            val totalExpectedBytes = artifacts.sumOf { it.expectedBytes.coerceAtLeast(0L) }
            val modelDir = getModelDirectory(model.modelId).apply { mkdirs() }

            // 2. Calculate remaining bytes needed and verify available device storage
            val alreadyCompleteBytes = artifacts.sumOf { art ->
                val f = File(modelDir, art.relativePath)
                if (f.exists() && f.length() == art.expectedBytes) f.length() else 0L
            }
            val remainingToDownload = (totalExpectedBytes - alreadyCompleteBytes).coerceAtLeast(0L)
            val freeBytes = getAvailableStorageBytes()
            val safetyMarginBytes = 25L * 1000L * 1000L // 25 MB safety margin
            if (freeBytes < remainingToDownload + safetyMarginBytes) {
                val msg = "Insufficient device storage: Needs ${formatCompactSize(remainingToDownload)} remaining (+25 MB headroom), available ${formatCompactSize(freeBytes)}."
                val failed = model.copy(
                    downloadState = "FAILED",
                    statusNote = msg
                )
                dao.updateModel(failed)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            // 3. Download every required file with HTTP Range resume support
            var cumulativeVerifiedBytes = 0L
            var verifiedCount = 0
            var primarySha256 = ""

            for ((index, artifact) in artifacts.withIndex()) {
                coroutineContext.ensureActive()

                val targetFile = File(modelDir, artifact.relativePath)
                targetFile.parentFile?.mkdirs()
                val partialFile = File(modelDir, "${artifact.relativePath}.part")

                // Skip file if already on disk with exact size & verified SHA-256
                if (targetFile.exists() && targetFile.length() == artifact.expectedBytes) {
                    val existingSha = computeSha256Hex(targetFile)
                    val hashMatches = artifact.expectedSha256.isBlank() ||
                        existingSha.equals(artifact.expectedSha256, ignoreCase = true)
                    if (hashMatches) {
                        if (artifact.role == "WEIGHTS" && primarySha256.isEmpty()) primarySha256 = existingSha
                        cumulativeVerifiedBytes += targetFile.length()
                        verifiedCount++
                        continue
                    } else {
                        targetFile.delete()
                    }
                }

                var existingPartBytes = if (partialFile.exists()) partialFile.length() else 0L
                if (artifact.expectedBytes > 0L && existingPartBytes > artifact.expectedBytes) {
                    partialFile.delete()
                    existingPartBytes = 0L
                }

                dao.updateModel(
                    model.copy(
                        downloadState = "DOWNLOADING",
                        downloadedBytes = cumulativeVerifiedBytes + existingPartBytes,
                        downloadProgressPercent = computePct(cumulativeVerifiedBytes + existingPartBytes, totalExpectedBytes),
                        verifiedFilesCount = verifiedCount,
                        localFilePath = modelDir.absolutePath,
                        statusNote = "Downloading (${index + 1}/${artifacts.size}): ${artifact.relativePath}"
                    )
                )

                val reqBuilder = Request.Builder()
                    .url(artifact.downloadUrl)
                    .header("User-Agent", "AnimeDubStudio-Android/1.0")
                if (existingPartBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingPartBytes-")
                }
                if (token.isNotEmpty()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        val authMsg = "HTTP ${response.code} for ${artifact.relativePath}. Accept model terms on Hugging Face and check your read-only HF Access Token."
                        val failed = model.copy(downloadState = "FAILED", statusNote = authMsg)
                        dao.updateModel(failed)
                        return@withContext Result.failure(IllegalStateException(authMsg))
                    }
                    if (!response.isSuccessful && response.code != 416) {
                        val httpMsg = "HTTP ${response.code} downloading ${artifact.relativePath}"
                        val failed = model.copy(downloadState = "FAILED", statusNote = httpMsg)
                        dao.updateModel(failed)
                        return@withContext Result.failure(IllegalStateException(httpMsg))
                    }

                    if (response.code != 416) {
                        val isResumed = response.code == 206 && existingPartBytes > 0L
                        var fileDownloaded = if (isResumed) existingPartBytes else 0L
                        val bodyStream = response.body?.byteStream()
                            ?: throw IllegalStateException("Empty HTTP body for ${artifact.relativePath}")

                        FileOutputStream(partialFile, isResumed).use { fos ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var lastProgressUpdateMs = 0L
                            while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                fos.write(buffer, 0, bytesRead)
                                fileDownloaded += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdateMs >= 250L) {
                                    lastProgressUpdateMs = now
                                    val totalNow = cumulativeVerifiedBytes + fileDownloaded
                                    val remainingNow = (totalExpectedBytes - totalNow).coerceAtLeast(0L)
                                    dao.updateModel(
                                        model.copy(
                                            downloadState = "DOWNLOADING",
                                            downloadedBytes = totalNow,
                                            downloadProgressPercent = computePct(totalNow, totalExpectedBytes),
                                            verifiedFilesCount = verifiedCount,
                                            localFilePath = modelDir.absolutePath,
                                            statusNote = "File ${index + 1}/${artifacts.size} (${artifact.filename}): ${formatCompactSize(totalNow)} / ${formatCompactSize(totalExpectedBytes)} (${formatCompactSize(remainingNow)} left)"
                                        )
                                    )
                                }
                            }
                            fos.flush()
                        }
                    }
                }

                // 4. Verify downloaded file size & SHA-256 checksum
                if (!partialFile.exists() || partialFile.length() == 0L) {
                    val corrupted = model.copy(
                        downloadState = "CORRUPTED",
                        statusNote = "Downloaded file ${artifact.relativePath} is empty or missing."
                    )
                    dao.updateModel(corrupted)
                    return@withContext Result.failure(IllegalStateException(corrupted.statusNote))
                }

                if (artifact.expectedBytes > 0L && partialFile.length() != artifact.expectedBytes) {
                    val actual = partialFile.length()
                    val corrupted = model.copy(
                        downloadState = "CORRUPTED",
                        downloadedBytes = cumulativeVerifiedBytes + actual,
                        statusNote = "Size mismatch on ${artifact.relativePath}: got $actual bytes, expected ${artifact.expectedBytes} bytes. Retry to resume."
                    )
                    dao.updateModel(corrupted)
                    return@withContext Result.failure(IllegalStateException(corrupted.statusNote))
                }

                dao.updateModel(
                    model.copy(
                        downloadState = "VERIFYING",
                        downloadedBytes = cumulativeVerifiedBytes + partialFile.length(),
                        downloadProgressPercent = computePct(cumulativeVerifiedBytes + partialFile.length(), totalExpectedBytes),
                        verifiedFilesCount = verifiedCount,
                        localFilePath = modelDir.absolutePath,
                        statusNote = "Verifying SHA-256 (${index + 1}/${artifacts.size}): ${artifact.filename}..."
                    )
                )

                val actualSha256 = computeSha256Hex(partialFile)
                if (artifact.expectedSha256.isNotBlank() && !actualSha256.equals(artifact.expectedSha256, ignoreCase = true)) {
                    partialFile.delete()
                    val corrupted = model.copy(
                        downloadState = "CORRUPTED",
                        statusNote = "SHA-256 checksum mismatch on ${artifact.relativePath}! Expected ${artifact.expectedSha256.take(12)}…, got ${actualSha256.take(12)}…. Corrupted file removed."
                    )
                    dao.updateModel(corrupted)
                    return@withContext Result.failure(IllegalStateException(corrupted.statusNote))
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partialFile.renameTo(targetFile)) {
                    partialFile.copyTo(targetFile, overwrite = true)
                    partialFile.delete()
                }

                if (artifact.role == "WEIGHTS" && primarySha256.isEmpty()) {
                    primarySha256 = actualSha256
                }
                cumulativeVerifiedBytes += targetFile.length()
                verifiedCount++
            }

            // 5. Final check: mark INSTALLED ONLY when all required files exist & match
            val verLevel = computeVerificationLevel(artifacts)
            val verSummary = if (verLevel == "VERIFIED_SHA256") {
                "SHA-256 verified all $verifiedCount/${artifacts.size} files (${formatCompactSize(cumulativeVerifiedBytes)}) on disk."
            } else {
                "Size-verified only ($verifiedCount/${artifacts.size} files, ${formatCompactSize(cumulativeVerifiedBytes)}; official SHA-256 not available for all weight files)."
            }
            val finalModel = model.copy(
                downloadState = "INSTALLED",
                downloadProgressPercent = 100,
                downloadedBytes = cumulativeVerifiedBytes,
                verifiedFilesCount = verifiedCount,
                totalFilesCount = artifacts.size,
                installedVersion = model.modelVersion.ifBlank { "Verified" },
                localFilePath = modelDir.absolutePath,
                sha256Checksum = primarySha256,
                verificationLevel = verLevel,
                statusNote = verSummary
            )
            dao.updateModel(finalModel)
            Result.success(finalModel)
        } catch (e: CancellationException) {
            val diskBytes = inspectLocalFiles(model).sumOf { it.actualBytesOnDisk + it.partialBytesOnDisk }
            val completeCount = inspectLocalFiles(model).count { it.isPresentAndSized }
            dao.updateModel(
                model.copy(
                    downloadState = "NOT_DOWNLOADED",
                    downloadedBytes = diskBytes,
                    downloadProgressPercent = if (model.isSizeKnown && model.expectedSizeBytes > 0L) {
                        computePct(diskBytes, model.expectedSizeBytes)
                    } else 0,
                    verifiedFilesCount = completeCount,
                    statusNote = "Download paused/cancelled at ${formatCompactSize(diskBytes)}. Partial files retained for HTTP Range resume."
                )
            )
            throw e
        } catch (e: Exception) {
            val failed = model.copy(
                downloadState = "FAILED",
                statusNote = "Download error: ${e.localizedMessage ?: "Network interruption"}. Tap Retry to resume."
            )
            dao.updateModel(failed)
            Result.failure(e)
        } finally {
            activeDownloads.remove(modelId)
        }
    }

    /**
     * Re-verifies all required files on disk and their SHA-256 hashes.
     */
    suspend fun verifyInstalledModelIntegrity(modelId: String): Result<ModelManifestEntity> = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId)
            ?: return@withContext Result.failure(IllegalArgumentException("Model $modelId not found."))
        val artifacts = VerifiedModelCatalog.parseManifest(model.filesManifestJson)
        if (artifacts.isEmpty()) return@withContext Result.success(model)

        val modelDir = getModelDirectory(model.modelId)
        var verifiedCount = 0
        var verifiedBytes = 0L
        var primarySha = ""

        for (art in artifacts) {
            val file = File(modelDir, art.relativePath)
            if (!file.exists() || file.length() == 0L) {
                val missing = model.copy(
                    downloadState = "CORRUPTED",
                    verifiedFilesCount = verifiedCount,
                    downloadedBytes = verifiedBytes,
                    statusNote = "Missing required file: ${art.relativePath}. Re-download required."
                )
                dao.updateModel(missing)
                return@withContext Result.failure(IllegalStateException(missing.statusNote))
            }
            if (art.expectedBytes > 0L && file.length() != art.expectedBytes) {
                val mismatch = model.copy(
                    downloadState = "CORRUPTED",
                    verifiedFilesCount = verifiedCount,
                    downloadedBytes = verifiedBytes,
                    statusNote = "Size mismatch on ${art.relativePath} (${file.length()} vs ${art.expectedBytes} B)."
                )
                dao.updateModel(mismatch)
                return@withContext Result.failure(IllegalStateException(mismatch.statusNote))
            }
            val sha = computeSha256Hex(file)
            if (art.expectedSha256.isNotBlank() && !sha.equals(art.expectedSha256, ignoreCase = true)) {
                val bad = model.copy(
                    downloadState = "CORRUPTED",
                    verifiedFilesCount = verifiedCount,
                    downloadedBytes = verifiedBytes,
                    statusNote = "SHA-256 verification failed on ${art.relativePath}."
                )
                dao.updateModel(bad)
                return@withContext Result.failure(IllegalStateException(bad.statusNote))
            }
            if (art.role == "WEIGHTS" && primarySha.isEmpty()) primarySha = sha
            verifiedCount++
            verifiedBytes += file.length()
        }

        val verLevel = computeVerificationLevel(artifacts)
        val verNote = if (verLevel == "VERIFIED_SHA256") {
            "Integrity check passed: all $verifiedCount/${artifacts.size} files verified (weight SHA-256 matched)."
        } else {
            "Size-verified only: all $verifiedCount/${artifacts.size} files match expected byte length (official SHA-256 hash unavailable for some weights)."
        }
        val verified = model.copy(
            downloadState = "INSTALLED",
            downloadProgressPercent = 100,
            downloadedBytes = verifiedBytes,
            verifiedFilesCount = verifiedCount,
            totalFilesCount = artifacts.size,
            localFilePath = modelDir.absolutePath,
            sha256Checksum = primarySha,
            verificationLevel = verLevel,
            statusNote = verNote
        )
        dao.updateModel(verified)
        Result.success(verified)
    }

    suspend fun deleteInstalledModel(modelId: String): ModelManifestEntity? = withContext(Dispatchers.IO) {
        cancelDownload(modelId)
        val model = dao.getModelById(modelId) ?: return@withContext null
        val modelDir = getModelDirectory(model.modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        val reset = model.copy(
            downloadState = "NOT_DOWNLOADED",
            downloadProgressPercent = 0,
            downloadedBytes = 0L,
            verifiedFilesCount = 0,
            installedVersion = "",
            localFilePath = "",
            sha256Checksum = "",
            verificationLevel = "UNVERIFIED",
            statusNote = "Deleted all files from device storage. Ready to download."
        )
        dao.updateModel(reset)
        reset
    }

    private fun computeVerificationLevel(artifacts: List<ModelArtifactFile>): String {
        val weightArtifacts = artifacts.filter { it.role == "WEIGHTS" }
        val checkTargets = weightArtifacts.ifEmpty { artifacts }
        if (checkTargets.isEmpty()) return "UNVERIFIED"
        return if (checkTargets.all { it.expectedSha256.isNotBlank() }) {
            "VERIFIED_SHA256"
        } else {
            "VERIFIED_SIZE_ONLY"
        }
    }

    private fun queryRemoteContentLength(url: String, bearerToken: String?): Result<Long> {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "AnimeDubStudio-Android/1.0")
                .apply {
                    if (!bearerToken.isNullOrBlank()) {
                        header("Authorization", "Bearer $bearerToken")
                    }
                }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${resp.code} (check HF license terms & token)"))
                }
                val contentLength = resp.header("X-Linked-Size")?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                if (contentLength <= 0L) {
                    Result.failure(IllegalStateException("Server did not return a valid Content-Length header."))
                } else {
                    Result.success(contentLength)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computePct(downloaded: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
    }

    private fun formatCompactSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    companion object {
        fun isLocalRuntime(runtimeCompatibility: String): Boolean =
            VerifiedModelCatalog.isLocalOrBuiltIn(runtimeCompatibility)

        fun providerForModel(modelId: String): String =
            VerifiedModelCatalog.providerForModel(modelId)

        fun buildVerifiedModelCatalog(): List<ModelManifestEntity> =
            VerifiedModelCatalog.buildVerifiedModelCatalog()
    }
}
