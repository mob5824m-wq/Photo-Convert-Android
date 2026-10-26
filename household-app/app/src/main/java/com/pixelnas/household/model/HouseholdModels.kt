package com.pixelnas.household.model

enum class SmbAuthMode {
    GUEST,
    CREDENTIALS
}

data class SmbConnectionConfig(
    val host: String = "",
    val port: Int = 445,
    val shareName: String = "PixelNAS",
    val baseSubfolder: String = "Incoming",
    val targetProfileFolder: String = "StorageSaver",
    val memberName: String = "",
    val routeToMemberSubfolder: Boolean = false,
    val authMode: SmbAuthMode = SmbAuthMode.GUEST,
    val username: String = "pixel",
    val password: String = "",
    val workgroup: String = "WORKGROUP",
    val autoCameraSyncWifiOnly: Boolean = false,
    val syncChargingOnly: Boolean = false,
    val trustedSubnetPrefix: String = "",
    val syncVideosToo: Boolean = true
) {
    val cleanShareName: String
        get() = shareName.trim().trim('/', '\\').ifBlank { "PixelNAS" }

    /**
     * Resolves the relative destination directory inside the connected Samba share.
     * Examples:
     *  - Share = "PixelNAS", baseSubfolder = "Incoming", profile = "StorageSaver" -> "Incoming\StorageSaver"
     *  - Share = "Incoming", baseSubfolder = "", profile = "Family"               -> "Family"
     *  - With routeToMemberSubfolder = true, memberName = "Mom"                   -> "Incoming\StorageSaver\Mom"
     */
    fun destinationRelativeDir(overrideProfile: String? = null): String {
        val segments = ArrayList<String>()
        val base = baseSubfolder.trim().trim('/', '\\')
        if (base.isNotEmpty() && !base.equals(cleanShareName, ignoreCase = true)) {
            segments.addAll(base.replace('/', '\\').split('\\').filter { it.isNotBlank() })
        }
        val chosenProfile = (overrideProfile ?: targetProfileFolder).trim().trim('/', '\\')
        if (chosenProfile.isNotEmpty()) {
            segments.add(sanitizeSegment(chosenProfile))
        }
        val cleanMember = sanitizeSegment(memberName)
        if (routeToMemberSubfolder && cleanMember.isNotEmpty() &&
            !cleanMember.equals(chosenProfile, ignoreCase = true)
        ) {
            segments.add(cleanMember)
        }
        return segments.joinToString("\\")
    }

    val displayUnc: String
        get() {
            val hostLabel = host.ifBlank { "<nas-ip>" }
            val dest = destinationRelativeDir()
            return if (dest.isEmpty()) {
                "\\\\$hostLabel\\$cleanShareName"
            } else {
                "\\\\$hostLabel\\$cleanShareName\\$dest"
            }
        }

    val displaySmbUrl: String
        get() {
            val hostLabel = host.ifBlank { "<nas-ip>" }
            val portPart = if (port == 445) "" else ":$port"
            val dest = destinationRelativeDir().replace('\\', '/')
            return if (dest.isEmpty()) {
                "smb://$hostLabel$portPart/$cleanShareName/"
            } else {
                "smb://$hostLabel$portPart/$cleanShareName/$dest/"
            }
        }

    companion object {
        fun sanitizeSegment(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9._ -]"), "").trim('.', ' ', '/', '\\')

        /**
         * Parses a 1-tap `pixelnas://pair?host=...&port=...&share=...&auth=...` QR / deep-link URI
         * using pure JVM query parsing so it works identically in Android runtime & JVM unit tests.
         */
        fun parsePairingUri(
            raw: String,
            existing: SmbConnectionConfig = SmbConnectionConfig()
        ): SmbConnectionConfig? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("pixelnas://pair", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?', "")
            if (query.isBlank()) return null
            val params = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isBlank()) continue
                val k = java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8").lowercase()
                val v = java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                params[k] = v
            }
            val host = params["host"]?.trim().orEmpty()
            if (host.isBlank()) return null
            val port = params["port"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 4445
            val share = params["share"]?.trim()?.ifBlank { "PixelNAS" } ?: "PixelNAS"
            val authMode = if (params["auth"].equals("credentials", ignoreCase = true)) {
                SmbAuthMode.CREDENTIALS
            } else {
                SmbAuthMode.GUEST
            }
            val user = params["user"]?.trim()?.ifBlank { existing.username } ?: existing.username
            val workgroup = params["workgroup"]?.trim()?.ifBlank { "WORKGROUP" } ?: "WORKGROUP"
            return existing.copy(
                host = host,
                port = port,
                shareName = share,
                authMode = authMode,
                username = user,
                workgroup = workgroup
            )
        }
    }
}

data class SmbShareSpace(
    val activePort: Int,
    val totalBytes: Long,
    val freeBytes: Long,
    val topFolders: List<String>
)

data class SmbRemoteEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
) {
    val isPreviewableImage: Boolean
        get() {
            if (isDirectory) return false
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
        }
}

data class DiscoveredSmbHost(
    val label: String,
    val host: String,
    val port: Int,
    val suggestedShare: String = "PixelNAS",
    val source: String = "Samba SMB2/3"
)

data class UploadLedgerEntry(
    val filename: String,
    val remoteUncPath: String,
    val sizeBytes: Long,
    val profileFolder: String,
    val uploadedAtMillis: Long,
    val success: Boolean = true,
    val message: String = ""
)

data class TransferBatchState(
    val active: Boolean = false,
    val currentFilename: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val bytesSentCurrentFile: Long = 0L,
    val totalBytesCurrentFile: Long = 0L,
    val statusMessage: String = "Ready — connected via Samba SMB2/3"
)
