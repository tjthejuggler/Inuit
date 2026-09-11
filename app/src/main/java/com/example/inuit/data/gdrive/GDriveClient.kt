package com.example.inuit.data.gdrive

import com.example.inuit.data.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.io.IOException

class GDriveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One Google Doc sitting in Drive root. */
data class DriveDoc(
    val id: String,
    val name: String,
    val createdTime: String
)

/** The OAuth token bundle the user pastes from their auth flow. */
data class GDriveCredentials(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String
) {
    val configured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && refreshToken.isNotBlank()
}

/** Parsed response of a sync run — surfaced in Settings for transparency. */
data class GDriveSyncResult(
    val scanned: Int = 0,
    val ingested: Int = 0,
    val moved: Int = 0,
    val failed: Int = 0,
    val note: String? = null
) {
    override fun toString(): String =
        "scanned=$scanned ingested=$ingested moved=$moved failed=$failed" +
            (note?.let { " — $it" } ?: "")
}

/**
 * Minimal Google Drive v3 REST client — no external SDK. Only what the
 * Gemini-doc sync needs:
 *  - [refreshAccessToken] — OAuth2 refresh-token → access token,
 *  - [listRootDocuments] — Google Docs that are DIRECT children of root,
 *  - [exportAsText] — the doc's content as plain text,
 *  - [move] — addParent of the archive folder (+ removeParent root),
 *  - [trash] — move to Drive trash (after successful ingestion).
 *
 * Access tokens are cached in memory until 60 s before expiry.
 */
class GDriveClient(private val creds: GDriveCredentials) {

    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0

    // ── OAuth ─────────────────────────────────────────────────────────────

    /** Exchanges the refresh token for a fresh access token. */
    suspend fun refreshAccessToken(): String = withContext(Dispatchers.IO) {
        cachedToken?.takeIf { System.currentTimeMillis() < tokenExpiresAt }?.let { return@withContext it }
        val form = buildString {
            append("client_id=").append(enc(creds.clientId))
            append("&client_secret=").append(enc(creds.clientSecret))
            append("&refresh_token=").append(enc(creds.refreshToken))
            append("&grant_type=refresh_token")
        }
        val res = Http.post(
            url = TOKEN_URL,
            headers = emptyMap(),
            body = form,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 30_000,
            totalTimeoutMs = 60_000,
            contentType = "application/x-www-form-urlencoded"
        )
        if (res.code !in 200..299) {
            throw GDriveException(tokenErrorHint(res.code, res.body))
        }
        val json = JSONObject(res.body)
        val token = json.optString("access_token").ifBlank {
            throw GDriveException("token refresh response had no access_token")
        }
        val expiresInSeconds = json.optLong("expires_in", 3600L)
        cachedToken = token
        tokenExpiresAt = System.currentTimeMillis() + (expiresInSeconds - 60) * 1000
        token
    }

    // ── Drive operations ──────────────────────────────────────────────────

    /**
     * Lists Google Docs that are DIRECT children of Drive root, newest first.
     * Sub-folders are never descended into — by design the sync only ever
     * touches loose documents in root.
     */
    suspend fun listRootDocuments(pageSize: Int = 50): List<DriveDoc> {
        val q = "'root' in parents and mimeType = 'application/vnd.google-apps.document' and trashed = false"
        val url = BASE_URL + "files" +
            "?q=" + enc(q) +
            "&fields=" + enc("nextPageToken, files(id,name,createdTime)") +
            "&orderBy=" + enc("createdTime desc") +
            "&pageSize=$pageSize"
        val res = authorizedGet(url)
        if (res.code !in 200..299) {
            throw GDriveException("list root failed: HTTP ${res.code} — ${res.body.take(300)}")
        }
        val json = JSONObject(res.body)
        val arr = json.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DriveDoc(
                id = o.optString("id"),
                name = o.optString("name"),
                createdTime = o.optString("createdTime")
            )
        }.filter { it.id.isNotBlank() }
    }

    /**
     * Exports the Google Doc as plain text. Docs Editors files have no
     * binary content to download (alt=media returns 403) — they must go
     * through the dedicated /export endpoint with a Docs-compatible MIME.
     */
    suspend fun exportAsText(docId: String): String {
        val url = BASE_URL + "files/" + enc(docId) + "/export?mimeType=" + enc(MIME_TEXT)
        val res = authorizedGet(url, accept = MIME_TEXT)
        if (res.code !in 200..299) {
            throw GDriveException("export failed: HTTP ${res.code} — ${res.body.take(300)}")
        }
        return res.body
    }

    /**
     * Moves the file out of root into the archive folder (adds the folder
     * parent, removes root). Idempotent enough: re-moving is harmless.
     */
    suspend fun moveToFolder(docId: String, folderId: String) {
        // Need the current parents to remove root afterwards.
        val getUrl = BASE_URL + "files/" + enc(docId) + "?fields=" + enc("parents")
        val cur = authorizedGet(getUrl)
        if (cur.code !in 200..299) {
            throw GDriveException("read parents failed: HTTP ${cur.code} — ${cur.body.take(300)}")
        }
        val parents = JSONObject(cur.body).optJSONArray("parents")
        val removeParam = parents?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                .joinToString(",") { enc(it) }
        } ?: ""
        val url = BASE_URL + "files/" + enc(docId) +
            "?addParents=" + enc(folderId) +
            (if (removeParam.isNotBlank()) "&removeParents=$removeParam" else "")
        val res = Http.patch(
            url = url,
            headers = authHeaders(),
            body = "{}",
            totalTimeoutMs = 60_000
        )
        if (res.code !in 200..299) {
            throw GDriveException("move failed: HTTP ${res.code} — ${res.body.take(300)}")
        }
    }

    /**
     * Finds (or creates) the archive folder by name in root and returns its
     * id. Created with the exact name the user asked for: inuit_auto_move.
     */
    suspend fun ensureFolder(name: String): String {
        val q = "'root' in parents and mimeType = 'application/vnd.google-apps.folder' " +
            "and name = '${name.replace("'", "\\'")}' and trashed = false"
        val listUrl = BASE_URL + "files" +
            "?q=" + enc(q) +
            "&fields=" + enc("files(id,name)") +
            "&pageSize=5"
        val list = authorizedGet(listUrl)
        if (list.code in 200..299) {
            val arr = JSONObject(list.body).optJSONArray("files")
            if (arr != null && arr.length() > 0) {
                return arr.optJSONObject(0)?.optString("id").orEmpty()
            }
        }
        // Create it.
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
        val res = Http.post(
            url = BASE_URL + "files" + "?fields=" + enc("id"),
            headers = authHeaders(),
            body = body,
            totalTimeoutMs = 60_000
        )
        if (res.code !in 200..299) {
            throw GDriveException("folder create failed: HTTP ${res.code} — ${res.body.take(300)}")
        }
        val id = JSONObject(res.body).optString("id").orEmpty()
        if (id.isBlank()) throw GDriveException("folder create returned no id")
        return id
    }

    /** Moves the file to Drive trash (recoverable for ~30 days). */
    suspend fun trash(docId: String) {
        val res = Http.patch(
            url = BASE_URL + "files/" + enc(docId),
            headers = authHeaders(),
            body = JSONObject().put("trashed", true).toString(),
            totalTimeoutMs = 60_000
        )
        if (res.code !in 200..299) {
            throw GDriveException("trash failed: HTTP ${res.code} — ${res.body.take(300)}")
        }
    }

    /** Quick credential sanity check for the Settings "Test" button. */
    suspend fun testAbout(): String {
        val res = authorizedGet(BASE_URL + "about" + "?fields=" + enc("user,storageQuota"))
        if (res.code !in 200..299) {
            throw GDriveException(driveErrorHint(res.code, res.body))
        }
        val json = JSONObject(res.body)
        val user = json.optJSONObject("user")
        val email = user?.optString("emailAddress").orEmpty()
        val display = user?.optString("displayName").orEmpty()
        return display.ifBlank { email }.ifBlank { "connected" }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private suspend fun authorizedGet(url: String, accept: String = "application/json") =
        Http.get(url = url, headers = authHeaders() + mapOf("Accept" to accept))

    private suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${refreshAccessToken()}")

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /**
     * Turns a token-endpoint failure into an actionable message. The two
     * classic misconfigurations:
     *  - invalid_client (HTTP 401): the client ID/secret do NOT belong to
     *    the same Google Cloud client that minted the refresh token.
     *  - invalid_grant (HTTP 400): token expired/revoked, or minted without
     *    offline access / under a different account or scope.
     */
    private fun tokenErrorHint(code: Int, body: String): String {
        val err = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        val hint = when {
            err == "invalid_client" || (code == 401 && err.isBlank()) ->
                "The client ID and secret do not match the client that issued this " +
                "refresh token. Re-mint the token in the OAuth Playground with " +
                "\"Use your own OAuth credentials\" checked, and paste THAT client's " +
                "ID and secret here."
            err == "invalid_grant" ->
                "The refresh token is expired, revoked, or was issued for a different " +
                "account/scope. Re-authorize in the OAuth Playground and paste the new token."
            else -> "Check all three values for typos or stray spaces."
        }
        return "token refresh failed: HTTP $code ${err.ifBlank { body.take(120) }} — $hint"
    }

    /** Turns a Drive API failure into an actionable message. */
    private fun driveErrorHint(code: Int, body: String): String {
        val err = runCatching { JSONObject(body).optString("error").let {
            if (it.isBlank()) it else JSONObject(it).optString("message")
        } }.getOrNull().orEmpty()
        val hint = when (code) {
            401 -> "The access token was rejected. Press Test again — if it persists, " +
                "the refresh token was likely minted without the Drive scope " +
                "(https://www.googleapis.com/auth/drive). Re-authorize with that scope."
            403 -> "Access denied. Check the Drive API is enabled and your account is " +
                "added as a test user on the OAuth consent screen."
            else -> "Check credentials and connection."
        }
        return "Drive request failed: HTTP $code ${err.take(120)} — $hint"
    }

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/drive/v3/"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val MIME_TEXT = "text/plain"

        /** The archive folder for non-Gemini documents, per user request. */
        const val ARCHIVE_FOLDER_NAME = "inuit_auto_move"

        /** Human-readable setup instructions shown in the Settings UI. */
        const val SETUP_HELP: String =
            "How to get these three values (one-time, ~5 min):\n" +
            "1. console.cloud.google.com → create/select a project.\n" +
            "2. APIs & Services → Library → enable \"Google Drive API\".\n" +
            "3. OAuth consent screen → External → add your Google account as a test user.\n" +
            "4. Credentials → Create credentials → OAuth client ID → " +
            "Application type: \"Web application\". Under \"Authorized redirect URIs\" add:\n" +
            "    https://developers.google.com/oauthplayground\n" +
            "   (Desktop type does NOT accept this URI anymore — Web application is required.)\n" +
            "5. Open developers.google.com/oauthplayground. Click the gear (top right):\n" +
            "    check \"Use your own OAuth credentials\" and paste your client ID + secret. " +
            "Set Access type to Offline and Force approval prompt to On.\n" +
            "6. In Step 1, enter scope https://www.googleapis.com/auth/drive, " +
            "authorize, then Exchange authorization code for tokens.\n" +
            "7. Copy the refresh_token plus your client ID/secret below and Save."
    }
}
