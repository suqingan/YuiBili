package com.yuilittle.bili

/** Builds the local static MPD used by Media3 for Bilibili DASH tracks. */
object BiliDashManifest {
    fun build(stream: BiliPlayUrl.DashStream): String {
        require(stream.isDash) { "DASH stream is required" }
        require(stream.videoTracks.isNotEmpty() || stream.audioTracks.isNotEmpty()) {
            "DASH audio or video track is required"
        }
        require(stream.durationMs > 0L) { "DASH duration must be positive" }
        val duration = stream.durationMs
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:dvb=\"urn:dvb:dash:profile:dvb-dash:2014\" " +
                    "type=\"static\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                    "mediaPresentationDuration=\"${duration.toIsoDuration()}\" " +
                    "minBufferTime=\"PT1.500S\">\n"
            )
            append("  <Period start=\"PT0S\">\n")
            if (stream.videoTracks.isNotEmpty()) {
                val videoMime = stream.videoTracks.first().mimeType.ifBlank { "video/mp4" }
                append("    <AdaptationSet contentType=\"video\" mimeType=\"${videoMime.escapeXml()}\">\n")
                stream.videoTracks.forEach { appendVideo(it) }
                append("    </AdaptationSet>\n")
            }
            if (stream.audioTracks.isNotEmpty()) {
                val audioMime = stream.audioTracks.first().mimeType.ifBlank { "audio/mp4" }
                append("    <AdaptationSet contentType=\"audio\" mimeType=\"${audioMime.escapeXml()}\" lang=\"und\">\n")
                stream.audioTracks.forEach { appendAudio(it) }
                append("    </AdaptationSet>\n")
            }
            append("  </Period>\n</MPD>\n")
        }
    }

    private fun StringBuilder.appendVideo(track: BiliPlayUrl.DashVideoTrack) {
        val urls = (listOf(track.url) + track.backups).filter(String::isNotBlank).distinct().take(3)
        require(urls.isNotEmpty()) { "Video track has no playable URL" }
        val mime = track.mimeType.ifBlank { "video/mp4" }
        val codecs = track.codecs.ifBlank { "avc1" }
        val bandwidth = track.bandwidth.takeIf { it > 0 } ?: 1_000_000
        append("      <Representation id=\"${track.id}\" bandwidth=\"$bandwidth\"")
        append(" mimeType=\"${mime.escapeXml()}\"")
        append(" codecs=\"${codecs.escapeXml()}\"")
        if (track.width > 0) append(" width=\"${track.width}\"")
        if (track.height > 0) append(" height=\"${track.height}\"")
        if (track.frameRate.isNotBlank()) append(" frameRate=\"${track.frameRate.escapeXml()}\"")
        append(">\n")
        appendBaseUrls(urls, priorityOffset = 0)
        appendSegmentBase(track.initializationRange, track.indexRange)
        append("      </Representation>\n")
    }

    private fun StringBuilder.appendAudio(track: BiliPlayUrl.DashAudioTrack) {
        val urls = (listOf(track.url) + track.backups).filter(String::isNotBlank).distinct().take(3)
        require(urls.isNotEmpty()) { "Audio track has no playable URL" }
        val mime = track.mimeType.ifBlank { "audio/mp4" }
        val codecs = track.codecs.ifBlank { "mp4a.40.2" }
        val bandwidth = track.bandwidth.takeIf { it > 0 } ?: 128_000
        append("      <Representation id=\"${track.id}\" bandwidth=\"$bandwidth\"")
        append(" mimeType=\"${mime.escapeXml()}\"")
        append(" codecs=\"${codecs.escapeXml()}\">\n")
        // Use distinct priority space from video tracks so BaseUrlExclusionList
        // excluding priority N on one track does not automatically exclude the other track.
        appendBaseUrls(urls, priorityOffset = 100)
        appendSegmentBase(track.initializationRange, track.indexRange)
        append("      </Representation>\n")
    }

    private fun StringBuilder.appendBaseUrls(urls: List<String>, priorityOffset: Int) {
        // Distinct priorities make Media3 count the URLs as separate fallback locations,
        // while keeping video/audio priorities disjoint avoids cross-track exclusion.
        urls.forEachIndexed { index, url ->
            append("        <BaseURL dvb:priority=\"${priorityOffset + index + 1}\">${url.escapeXml()}</BaseURL>\n")
        }
    }

    private fun StringBuilder.appendSegmentBase(initialization: String?, index: String?) {
        val initRange = MediaTransportPolicy.parseByteRangeSpec(initialization)
        val indexRange = MediaTransportPolicy.parseByteRangeSpec(index)
        require(initialization.isNullOrBlank() || initRange != null) { "Invalid DASH initialization range: $initialization" }
        require(index.isNullOrBlank() || indexRange != null) { "Invalid DASH index range: $index" }
        if (initRange == null && indexRange == null) return
        append("        <SegmentBase")
        if (indexRange != null) append(" indexRange=\"${indexRange.first}-${indexRange.last}\"")
        append(">\n")
        if (initRange != null) {
            append("          <Initialization range=\"${initRange.first}-${initRange.last}\"/>\n")
        }
        append("        </SegmentBase>\n")
    }

    private fun Long.toIsoDuration(): String = "PT${this / 1000}.${(this % 1000).toString().padStart(3, '0')}S"

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
