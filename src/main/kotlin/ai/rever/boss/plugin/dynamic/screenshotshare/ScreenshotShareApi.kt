package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Thin wrapper around [PluginContext.supabaseDataProvider] for the RPCs added in
 * BossConsole/supabase/migrations/20260824000000_screenshot_shares.sql.
 *
 * NOTE: `SupabaseDataProvider.rpc`'s exact parameter type lives in boss-plugin-api,
 * which isn't checked out in this workspace (it's a git submodule pointing at a
 * private repo). This assumes `rpc(function: String, parameters: String): Result<String>`
 * per docs/plugin-api.md's summary ("rpc(function, parameters): Result<String>") --
 * verify against the real interface and adjust only this file if it differs.
 */
class ScreenshotShareApi(private val context: PluginContext) {

    private suspend fun rpc(function: String, params: JsonObject): Result<JsonObject> {
        val provider = context.supabaseDataProvider
            ?: return Result.failure(IllegalStateException("Supabase is not available to this plugin"))
        return provider.rpc(function, params.toString())
            .mapCatching { raw -> Json.parseToJsonElement(raw).jsonObject }
            .recoverCatching { throw IllegalStateException(describeFailure(function, it), it) }
    }

    /**
     * Rewrites a schema-cache miss into something the person looking at the
     * dialog can act on.
     *
     * A plugin release and a database migration ship separately, so a plugin can
     * legitimately be newer than the schema it is calling. PostgREST reports
     * that as `Could not find the function public.x(...) in the schema cache`,
     * which is precise but unreadable in a send dialog and tells the reader
     * nothing about what to do. Everything else is passed through untouched --
     * the server's own errors ("Recipient does not share an organisation with
     * you") are already the better message.
     */
    private fun describeFailure(function: String, cause: Throwable): String {
        val message = cause.message ?: return "$function failed"
        val schemaMiss = "Could not find the function" in message || "schema cache" in message
        return if (schemaMiss) {
            "This BOSS database is missing an update that $function needs - " +
                "ask an admin to apply the latest screenshot_shares migration"
        } else {
            message
        }
    }

    private fun requireSuccess(obj: JsonObject): JsonObject {
        val ok = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Request failed")
        return obj
    }

    /** Required string field, e.g. `o.str("user_id")` for `o["user_id"]!!.jsonPrimitive.content`. */
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolOrFalse(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    /**
     * Adds an OPTIONAL rpc argument only when it carries a value.
     *
     * PostgREST resolves a function by the SET of argument names supplied, so
     * `put(key, null)` is emphatically not the same as omitting the key: a name
     * the deployed function does not declare makes the call match nothing and
     * come back as `Could not find the function public.x(...) in the schema
     * cache`, while omitting a parameter that has a DEFAULT always resolves.
     *
     * Sending nulls is what broke every send when p_password was added ahead of
     * its migration. Omitting them is both the fix and the more correct call:
     * "no value" is exactly what a SQL DEFAULT is for.
     */
    private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    suspend fun listShareableRecipients(query: String? = null): Result<List<Recipient>> =
        rpc(
            "list_shareable_recipients",
            buildJsonObject {
                putIfPresent("p_query", query)
                // The server clamps this to 200. Asking for 50 silently hid people:
                // a caller in orgs of 135 + 80 has ~215 reachable co-members, so the
                // page has to be as large as allowed AND the query has to be pushed
                // server-side -- 200 alone still does not cover everyone.
                put("p_limit", RECIPIENT_PAGE_SIZE)
            },
        ).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                Recipient(
                    userId = o.str("user_id"),
                    email = o.str("email"),
                    displayName = o.str("display_name"),
                    orgId = o.str("org_id"),
                    orgName = o.str("org_name"),
                )
            }
        }

    suspend fun shareScreenshot(
        recipientId: String,
        imageBase64: String,
        mimeType: String,
        width: Int,
        height: Int,
        note: String?,
        password: String?,
    ): Result<String> =
        rpc(
            "share_screenshot",
            buildJsonObject {
                put("p_recipient_id", recipientId)
                put("p_image_base64", imageBase64)
                put("p_mime_type", mimeType)
                put("p_width", width)
                put("p_height", height)
                putIfPresent("p_note", note)
                putIfPresent("p_password", password)
            },
        ).mapCatching { obj ->
            requireSuccess(obj).strOrNull("share_id") ?: error("Missing share_id in response")
        }

    suspend fun listReceived(onlyUnread: Boolean = false): Result<List<ReceivedScreenshot>> =
        rpc(
            "list_received_screenshots",
            buildJsonObject {
                put("p_only_unread", onlyUnread)
                put("p_limit", 50)
            },
        ).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                ReceivedScreenshot(
                    id = o.str("id"),
                    senderId = o.str("sender_id"),
                    senderEmail = o.str("sender_email"),
                    note = o.strOrNull("note"),
                    width = o.intOrNull("width"),
                    height = o.intOrNull("height"),
                    createdAt = o.str("created_at"),
                    readAt = o.strOrNull("read_at"),
                    expiresAt = o.strOrNull("expires_at"),
                    hasPassword = o.boolOrFalse("has_password"),
                )
            }
        }

    suspend fun listSent(): Result<List<SentScreenshot>> =
        rpc("list_sent_screenshots", buildJsonObject { put("p_limit", 50) }).mapCatching { obj ->
            requireSuccess(obj)["data"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                SentScreenshot(
                    id = o.str("id"),
                    recipientId = o.str("recipient_id"),
                    recipientEmail = o.str("recipient_email"),
                    note = o.strOrNull("note"),
                    width = o.intOrNull("width"),
                    height = o.intOrNull("height"),
                    createdAt = o.str("created_at"),
                    readAt = o.strOrNull("read_at"),
                    expiresAt = o.strOrNull("expires_at"),
                    hasPassword = o.boolOrFalse("has_password"),
                )
            }
        }

    /**
     * Deletes a share. For the sender this is a recall -- the recipient loses
     * access immediately, read or not; for the recipient it's a dismissal.
     */
    suspend fun deleteShare(shareId: String): Result<Unit> =
        rpc("delete_screenshot_share", buildJsonObject { put("p_share_id", shareId) })
            .mapCatching { requireSuccess(it); Unit }

    /** Marks the share read server-side as a side effect, once a [ImageFetchResult.Success] is
     * returned. The password-prompt states are legitimate outcomes, not [Result.failure]s --
     * only "password_required"/"invalid_password"/"locked" are intercepted here; any other
     * server error (e.g. "Screenshot not found") still surfaces as a failed [Result]. */
    suspend fun getImage(shareId: String, password: String? = null): Result<ImageFetchResult> =
        rpc(
            "get_screenshot_image",
            buildJsonObject {
                put("p_share_id", shareId)
                putIfPresent("p_password", password)
            },
        ).mapCatching { obj ->
            if (obj.boolOrFalse("success")) {
                ImageFetchResult.Success(obj.str("image_base64"), obj.str("mime_type"))
            } else {
                when (obj.strOrNull("error")) {
                    "password_required" -> ImageFetchResult.PasswordRequired
                    "invalid_password" -> ImageFetchResult.InvalidPassword(obj.intOrNull("attempts_remaining") ?: 0)
                    "locked" -> ImageFetchResult.Locked
                    else -> error(obj.strOrNull("error") ?: "Request failed")
                }
            }
        }
}
