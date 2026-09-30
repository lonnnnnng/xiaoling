package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class GitHubSkillDocument(
    val content: String,
    val sourceUrl: String,
    val sourceRef: String,
    val sha256: String,
)

data class GitHubSkillLocation(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String,
)

data class GitHubSkillRepositoryTarget(
    val owner: String,
    val repo: String,
    val ref: String,
    val prefix: String,
)

data class GitHubSkillCandidate(
    val sourceUrl: String,
    val sourceRef: String,
    val path: String,
)

class GitHubSkillSelectionRequired(
    val candidates: List<GitHubSkillCandidate>,
) : IOException("GitHub 仓库发现多个 Skill，请选择要导入的文件")

class GitHubSkillImporter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun download(url: String): String = downloadDocument(url).content

    suspend fun discover(url: String): List<GitHubSkillCandidate> = withContext(Dispatchers.IO) {
        val repository = GitHubSkillUrlPolicy.repositoryTarget(url)
        if (repository == null) {
            val normalized = GitHubSkillUrlPolicy.normalize(url)
            return@withContext listOfNotNull(pinToCommit(normalized)?.let(::candidateFor))
        }
        val commit = resolveCommit(repository)
        val apiUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repo)
            .addPathSegment("git")
            .addPathSegment("trees")
            .addPathSegment(commit)
            .addQueryParameter("recursive", "1")
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "XiaoLingSkillImporter/0.1")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext emptyList()
            if (!response.isSuccessful) throw IOException("GitHub Skill 目录发现失败：HTTP ${response.code}")
            // long: 递归 Tree API 可能在响应末尾才给出 truncated；超限时改为逐棵子树读取，避免把不完整目录当成完整候选集。
            val body = response.body?.source()?.let { readLimited(it, MAX_TREE_RESPONSE_BYTES) } ?: ByteArray(0)
            if (body.size > MAX_TREE_RESPONSE_BYTES) return@withContext discoverViaSubtrees(repository, commit)
            val payload = org.json.JSONObject(body.toString(Charsets.UTF_8))
            if (payload.optBoolean("truncated", false)) return@withContext discoverViaSubtrees(repository, commit)
            val tree = payload.optJSONArray("tree") ?: return@withContext emptyList()
            candidatesFromTree(repository, commit, tree)
        }
    }

    private fun candidatesFromTree(
        repository: GitHubSkillRepositoryTarget,
        commit: String,
        tree: JSONArray,
    ): List<GitHubSkillCandidate> {
        val prefix = repository.prefix.trim('/').let { if (it.isBlank()) "" else "$it/" }
        val candidates = buildList {
            for (index in 0 until tree.length()) {
                val entry = tree.optJSONObject(index) ?: continue
                if (entry.optString("type") != "blob") continue
                val path = entry.optString("path").trim()
                if (!path.startsWith(prefix) || path.split('/').any { it == ".." || it.isBlank() }) continue
                if (!isSkillPath(path)) continue
                val normalized = GitHubSkillUrlPolicy.rawUrl(repository.owner, repository.repo, commit, path)
                add(GitHubSkillCandidate(normalized, commit, path))
            }
        }.distinctBy(GitHubSkillCandidate::sourceUrl).sortedBy(GitHubSkillCandidate::path)
        require(candidates.size <= MAX_DISCOVERED_CANDIDATES) { "GitHub 仓库包含过多 Skill，无法一次导入" }
        return candidates
    }

    private fun discoverViaSubtrees(
        repository: GitHubSkillRepositoryTarget,
        commit: String,
    ): List<GitHubSkillCandidate> {
        val root = repository.prefix.trim('/')
        require(root.isBlank() || root.split('/').none { it == ".." || it.isBlank() }) { "GitHub Skill 目录路径无效" }
        val pendingTrees = ArrayDeque<Pair<String, String>>().apply { add("" to commit) }
        val visitedTrees = mutableSetOf<String>()
        val candidates = linkedMapOf<String, GitHubSkillCandidate>()
        var visitedEntries = 0

        while (pendingTrees.isNotEmpty()) {
            val (directory, treeSha) = pendingTrees.removeFirst()
            require(visitedTrees.size < MAX_SUBTREES) { "GitHub 仓库子目录过多，无法安全发现 Skill" }
            if (!visitedTrees.add(directory)) continue
            val entries = fetchNonRecursiveTree(repository, treeSha)
            for (index in 0 until entries.length()) {
                require(++visitedEntries <= MAX_TREE_ENTRIES) { "GitHub 仓库目录条目过多，无法安全发现 Skill" }
                val entry = entries.optJSONObject(index) ?: continue
                val name = entry.optString("path").trim()
                if (name.isBlank() || name.contains('/') || name == "..") continue
                val path = if (directory.isBlank()) name else "$directory/$name"
                when (entry.optString("type")) {
                    "tree" -> {
                        // long: 指定子目录导入时只遍历目标的祖先与后代，避免无关目录消耗请求预算。
                        if (root.isBlank() || root == path || root.startsWith("$path/") || path.startsWith("$root/")) {
                            val childSha = entry.optString("sha")
                            require(childSha.matches(COMMIT_SHA)) { "GitHub 子树 SHA 无效" }
                            pendingTrees.addLast(path to childSha)
                        }
                    }
                    "blob" -> if ((root.isBlank() || path.startsWith("$root/")) && isSkillPath(path)) {
                        val normalized = GitHubSkillUrlPolicy.rawUrl(repository.owner, repository.repo, commit, path)
                        candidates.putIfAbsent(normalized, GitHubSkillCandidate(normalized, commit, path))
                        require(candidates.size <= MAX_DISCOVERED_CANDIDATES) { "GitHub 仓库包含过多 Skill，无法一次导入" }
                    }
                }
            }
        }
        return candidates.values.sortedBy(GitHubSkillCandidate::path)
    }

    private fun fetchNonRecursiveTree(
        repository: GitHubSkillRepositoryTarget,
        treeSha: String,
    ): JSONArray {
        val apiUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repo)
            .addPathSegment("git")
            .addPathSegment("trees")
            .addPathSegment(treeSha)
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "XiaoLingSkillImporter/0.1")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub Skill 子树发现失败：HTTP ${response.code}")
            val body = response.body?.source()?.let { readLimited(it, MAX_TREE_RESPONSE_BYTES) } ?: ByteArray(0)
            require(body.size <= MAX_TREE_RESPONSE_BYTES) { "GitHub 子树响应超过大小限制" }
            val payload = org.json.JSONObject(body.toString(Charsets.UTF_8))
            require(!payload.optBoolean("truncated", false)) { "GitHub 子树目录过大，无法安全完整发现 Skill" }
            payload.optJSONArray("tree") ?: throw IOException("GitHub 子树响应缺少目录")
        }
    }

    private fun isSkillPath(path: String): Boolean =
        path == "SKILL.json" || path == "SKILL.md" || path.endsWith("/SKILL.json") || path.endsWith("/SKILL.md")

    suspend fun downloadDocument(url: String): GitHubSkillDocument = withContext(Dispatchers.IO) {
        val repository = GitHubSkillUrlPolicy.repositoryTarget(url)
        if (repository != null) {
            val candidates = discover(url)
            require(candidates.isNotEmpty()) { "GitHub 仓库中没有找到 SKILL.json 或 SKILL.md" }
            if (candidates.size > 1) throw GitHubSkillSelectionRequired(candidates)
            return@withContext downloadDocument(candidates.single().sourceUrl)
        }
        val candidates = GitHubSkillUrlPolicy.candidates(url)
        for (normalized in candidates) {
            val pinnedUrl = pinToCommit(normalized) ?: continue
            val request = Request.Builder()
                .url(pinnedUrl)
                .header("Accept", "application/json, text/plain, text/markdown")
                .header("User-Agent", "XiaoLingSkillImporter/0.1")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use
                if (!response.isSuccessful) throw IOException("GitHub Skill 下载失败：HTTP ${response.code}")
                val bytes = response.body?.source()?.let { readLimited(it, AgentSkillDocumentCodec.MAX_DOCUMENT_BYTES) }
                    ?: ByteArray(0)
                require(bytes.size <= AgentSkillDocumentCodec.MAX_DOCUMENT_BYTES) {
                    "GitHub Skill 文件不能超过 64 KiB"
                }
                return@withContext GitHubSkillDocument(
                    content = bytes.toString(Charsets.UTF_8),
                    sourceUrl = pinnedUrl,
                    sourceRef = GitHubSkillUrlPolicy.sourceRef(pinnedUrl),
                    sha256 = sha256(bytes),
                )
            }
        }
        throw IOException("GitHub 仓库中没有找到 SKILL.json 或 SKILL.md")
    }

    private fun pinToCommit(normalized: String): String? {
        val location = GitHubSkillUrlPolicy.location(normalized)
        if (location.ref.matches(COMMIT_SHA)) return normalized
        val apiUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(location.owner)
            .addPathSegment(location.repo)
            .addPathSegment("commits")
            .addQueryParameter("path", location.path)
            .addQueryParameter("sha", location.ref)
            .addQueryParameter("per_page", "1")
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "XiaoLingSkillImporter/0.1")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub Skill commit 解析失败：HTTP ${response.code}")
            val body = response.body?.source()?.let { readLimited(it, MAX_API_RESPONSE_BYTES) } ?: ByteArray(0)
            require(body.size <= MAX_API_RESPONSE_BYTES) { "GitHub API 响应超过大小限制" }
            val commits = JSONArray(body.toString(Charsets.UTF_8))
            val sha = commits.optJSONObject(0)?.optString("sha")?.trim().orEmpty()
            if (!sha.matches(COMMIT_SHA)) return null
            return GitHubSkillUrlPolicy.withRef(normalized, sha)
        }
    }

    private fun resolveCommit(repository: GitHubSkillRepositoryTarget): String {
        if (repository.ref.matches(COMMIT_SHA)) return repository.ref
        val apiUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repo)
            .addPathSegment("commits")
            .addQueryParameter("sha", repository.ref)
            .addQueryParameter("per_page", "1")
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "XiaoLingSkillImporter/0.1")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub Skill commit 解析失败：HTTP ${response.code}")
            val body = response.body?.source()?.let { readLimited(it, MAX_API_RESPONSE_BYTES) } ?: ByteArray(0)
            require(body.size <= MAX_API_RESPONSE_BYTES) { "GitHub API 响应超过大小限制" }
            val sha = JSONArray(body.toString(Charsets.UTF_8)).optJSONObject(0)?.optString("sha").orEmpty().trim()
            require(sha.matches(COMMIT_SHA)) { "GitHub Skill commit 解析结果无效" }
            sha
        }
    }

    private fun candidateFor(normalized: String): GitHubSkillCandidate {
        val location = GitHubSkillUrlPolicy.location(normalized)
        return GitHubSkillCandidate(normalized, location.ref, location.path)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val COMMIT_SHA = Regex("[0-9a-fA-F]{40}")
        const val MAX_API_RESPONSE_BYTES = 128 * 1024
        const val MAX_TREE_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_SUBTREES = 1_000
        const val MAX_TREE_ENTRIES = 10_000
        const val MAX_DISCOVERED_CANDIDATES = 50
    }
}

private fun readLimited(source: BufferedSource, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (output.size() <= maxBytes) {
        val count = source.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object GitHubSkillUrlPolicy {
    fun candidates(raw: String): List<String> {
        val first = normalize(raw)
        return if (first.endsWith("/SKILL.json")) listOf(first, first.removeSuffix("SKILL.json") + "SKILL.md") else listOf(first)
    }

    fun normalize(raw: String): String {
        val value = raw.trim()
        require(value.length in 1..2_048) { "GitHub Skill 地址长度无效" }
        val parsed = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入 HTTPS GitHub Skill 地址")
        require(parsed.scheme == "https") { "GitHub Skill 地址必须使用 HTTPS" }
        if (parsed.host == "raw.githubusercontent.com") {
            require(parsed.encodedPath.endsWith("/SKILL.json") || parsed.encodedPath.endsWith("/SKILL.md")) {
                "raw GitHub 地址必须指向 SKILL.json 或 SKILL.md"
            }
            return parsed.toString()
        }
        require(parsed.host == "github.com") { "请输入 github.com 或 raw.githubusercontent.com 地址" }
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        require(segments.size >= 2) { "GitHub 仓库地址缺少 owner/repo" }
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "GitHub 仓库地址包含非法名称"
        }
        val filePath = when {
            segments.size == 2 -> "SKILL.json"
            segments[2] == "blob" && segments.size >= 5 -> segments.drop(4).joinToString("/")
                .ifBlank { "SKILL.json" }
            segments[2] == "tree" && segments.size >= 4 -> segments.drop(4).joinToString("/").let { path ->
                if (path.isBlank()) "SKILL.json" else path.trimEnd('/') + "/SKILL.json"
            }
            else -> "SKILL.json"
        }
        val ref = when {
            segments.size >= 4 && (segments[2] == "blob" || segments[2] == "tree") -> segments[3]
            else -> "HEAD"
        }
        require(filePath.endsWith("SKILL.json") || filePath.endsWith("SKILL.md")) {
            "GitHub Skill 文件必须命名为 SKILL.json 或 SKILL.md"
        }
        require(filePath.split('/').none { it == ".." || it.isBlank() }) { "GitHub Skill 路径无效" }
        return "https://raw.githubusercontent.com/$owner/$repo/$ref/$filePath"
    }

    fun repositoryTarget(raw: String): GitHubSkillRepositoryTarget? {
        val value = raw.trim()
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != "github.com") return null
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "GitHub 仓库地址包含非法名称"
        }
        return when {
            segments.size == 2 -> GitHubSkillRepositoryTarget(owner, repo, "HEAD", "")
            segments[2] == "tree" && segments.size >= 4 -> GitHubSkillRepositoryTarget(
                owner = owner,
                repo = repo,
                ref = segments[3],
                prefix = segments.drop(4).joinToString("/").trim('/'),
            )
            else -> null
        }
    }

    fun sourceRef(normalized: String): String {
        return location(normalized).ref
    }

    fun location(normalized: String): GitHubSkillLocation {
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("GitHub Skill 来源地址无效")
        require(parsed.host == "raw.githubusercontent.com") { "GitHub Skill 来源必须是 raw.githubusercontent.com" }
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        require(segments.size >= 4) { "GitHub Skill 来源缺少 owner、repo、ref 或文件路径" }
        val owner = segments[0]
        val repo = segments[1]
        val ref = segments[2]
        val path = segments.drop(3).joinToString("/")
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "GitHub Skill 来源仓库名称无效"
        }
        require(ref.isNotBlank() && (path.endsWith("SKILL.json") || path.endsWith("SKILL.md"))) {
            "GitHub Skill 来源文件无效"
        }
        require(path.split('/').none { it == ".." || it.isBlank() }) { "GitHub Skill 来源路径无效" }
        return GitHubSkillLocation(owner, repo, ref, path)
    }

    fun withRef(normalized: String, ref: String): String {
        val location = location(normalized)
        return okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("raw.githubusercontent.com")
            .addPathSegment(location.owner)
            .addPathSegment(location.repo)
            .addPathSegment(ref)
            .apply { location.path.split('/').forEach(::addPathSegment) }
            .build()
            .toString()
    }

    fun rawUrl(owner: String, repo: String, ref: String, path: String): String =
        okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("raw.githubusercontent.com")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment(ref)
            .apply { path.split('/').forEach(::addPathSegment) }
            .build()
            .toString()
}
