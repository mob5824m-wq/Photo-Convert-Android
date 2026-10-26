package com.pixelnas.photosaver.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Push alert webhook client supporting ntfy.sh, Discord, Slack, and generic JSON endpoints.
 */
object WebhookNotifier {

    fun sendAlert(webhookUrl: String, title: String, message: String, eventType: String = "info"): Pair<Boolean, String> {
        val clean = webhookUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return false to "Webhook URL must start with http:// or https://"
        }
        val lower = clean.lowercase()
        return try {
            val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("User-Agent", "PixelPhotoNAS-Android/1.1")
            }
            val payloadBytes: ByteArray = when {
                lower.contains("discord.com/api/webhooks") || lower.contains("discordapp.com/api/webhooks") -> {
                    conn.setRequestProperty("Content-Type", "application/json")
                    JSONObject().put("content", "**$title**\n$message").toString().toByteArray(Charsets.UTF_8)
                }
                lower.contains("hooks.slack.com") -> {
                    conn.setRequestProperty("Content-Type", "application/json")
                    JSONObject().put("text", "*$title*\n$message").toString().toByteArray(Charsets.UTF_8)
                }
                lower.contains("ntfy.sh/") || lower.contains("/ntfy/") -> {
                    conn.setRequestProperty("Title", title.take(120))
                    conn.setRequestProperty("Tags", "camera,nas")
                    message.toByteArray(Charsets.UTF_8)
                }
                else -> {
                    conn.setRequestProperty("Content-Type", "application/json")
                    JSONObject()
                        .put("app", "Pixel Photo NAS")
                        .put("event", eventType)
                        .put("title", title)
                        .put("message", message)
                        .put("text", "[$title] $message")
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                }
            }
            conn.outputStream.use { it.write(payloadBytes) }
            val code = conn.responseCode
            conn.disconnect()
            (code in 200..299) to "HTTP $code"
        } catch (e: Exception) {
            false to (e.message ?: "Webhook delivery failed")
        }
    }
}
