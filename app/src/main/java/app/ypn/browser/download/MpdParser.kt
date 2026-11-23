package app.ypn.browser.download

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL
import kotlin.math.ceil

/**
 * One downloadable piece of a segmented stream: a URL plus, for
 * the DASH addressing modes that carve pieces out of one file
 * (SegmentBase, ranged SegmentList entries), the byte range to
 * ask for. A null range is a plain whole-resource GET — every
 * HLS segment and most DASH segments are one of those.
 */
data class SegmentRef(val url: String, val range: String? = null)

/** One Representation of a DASH manifest, segments resolved. */
data class DashRepresentation(
    val id: String?,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val segments: List<SegmentRef>,
    /** SegmentBase / bare-BaseURL form: one whole media file. */
    val singleFile: Boolean,
    val drm: Boolean
)

/** A parsed DASH manifest: video and audio kept apart, as sent. */
class DashManifest(
    val isLive: Boolean,
    val durationSec: Double,
    val video: List<DashRepresentation>,
    val audio: List<DashRepresentation>
) {
    val hasSeparateAudio: Boolean get() = audio.isNotEmpty()
}

/**
 * v1.6: a small MPEG-DASH (MPD) reader, written for the shapes
 * real sites actually serve:
 *
 *  - SegmentTemplate addressing, both duration-based ($Number$)
 *    and SegmentTimeline-based ($Time$), with the standard
 *    $-substitutions ($RepresentationID$, $Bandwidth$, and the
 *    %0Nd width forms)
 *  - SegmentList addressing (with per-segment media ranges and a
 *    ranged Initialization)
 *  - SegmentBase addressing (one media file; an Initialization
 *    range splits it into init + media range requests)
 *  - A Representation with no segment addressing at all: its
 *    BaseURL is the whole file
 *
 * BaseURL elements resolve down the MPD → Period → AdaptationSet
 * → Representation chain against the manifest's own URL. The
 * first Period that carries representations wins (multi-period
 * manifests are a live/DRM shape this reader does not join).
 * ContentProtection anywhere in a set marks its representations
 * as DRM — the caller refuses those honestly.
 *
 * Pure text-in / model-out (XmlPullParser is the only platform
 * API), so the media sheet and the download engine read a
 * manifest exactly the same way. Throws IllegalArgumentException
 * with a plain message on anything that is not a usable MPD.
 */
object MpdParser {

    fun parse(text: String, manifestUrl: String): DashManifest {
        val root = parseXml(text)
        if (root.tag != "MPD") {
            throw IllegalArgumentException("Not a DASH manifest")
        }
        val isLive = root.attrs["type"]?.equals("dynamic", true) == true
        val mpdDuration = parseIsoDuration(root.attrs["mediaPresentationDuration"]) ?: 0.0
        val mpdBase = baseOf(root, manifestUrl)

        val video = mutableListOf<DashRepresentation>()
        val audio = mutableListOf<DashRepresentation>()
        for (period in root.childrenNamed("Period")) {
            val periodBase = baseOf(period, mpdBase)
            val durationSec = parseIsoDuration(period.attrs["duration"]) ?: mpdDuration
            val periodVideo = mutableListOf<DashRepresentation>()
            val periodAudio = mutableListOf<DashRepresentation>()
            for (set in period.childrenNamed("AdaptationSet")) {
                val setBase = baseOf(set, periodBase)
                val reps = set.childrenNamed("Representation")
                if (reps.isEmpty()) continue
                val kind = adaptationKind(set, reps) ?: continue
                val setDrm = set.hasDescendant("ContentProtection")
                val setMime = set.attrs["mimeType"]
                    ?: reps.firstOrNull()?.attrs?.get("mimeType")
                for (rep in reps) {
                    val built = buildRepresentation(
                        rep = rep,
                        set = set,
                        repBase = baseOf(rep, setBase),
                        setMime = setMime,
                        durationSec = durationSec,
                        drm = setDrm || rep.hasDescendant("ContentProtection")
                    ) ?: continue
                    if (kind == Kind.VIDEO) periodVideo += built else periodAudio += built
                }
            }
            if (periodVideo.isNotEmpty() || periodAudio.isNotEmpty()) {
                video += periodVideo
                audio += periodAudio
                return DashManifest(isLive, durationSec, video, audio)
            }
        }
        return DashManifest(isLive, mpdDuration, video, audio)
    }

    private enum class Kind { VIDEO, AUDIO }

    /** Video or audio, from the set's own labels, else its codecs. */
    private fun adaptationKind(set: Node, reps: List<Node>): Kind? {
        val contentType = set.attrs["contentType"]?.lowercase()
        val mime = (set.attrs["mimeType"]
            ?: reps.firstOrNull()?.attrs?.get("mimeType") ?: "").lowercase()
        when {
            contentType == "video" || mime.startsWith("video/") -> return Kind.VIDEO
            contentType == "audio" || mime.startsWith("audio/") -> return Kind.AUDIO
        }
        val codecs = reps.mapNotNull { it.attrs["codecs"] }.joinToString(",").lowercase()
        return when {
            listOf("avc", "hev", "hvc", "av01", "vp09", "vp9", "vp08", "vp8")
                .any { codecs.contains(it) } -> Kind.VIDEO
            listOf("mp4a", "ac-3", "ec-3", "opus", "vorbis", "flac")
                .any { codecs.contains(it) } -> Kind.AUDIO
            else -> null
        }
    }

    private fun buildRepresentation(
        rep: Node,
        set: Node,
        repBase: String,
        setMime: String?,
        durationSec: Double,
        drm: Boolean
    ): DashRepresentation? {
        val template = rep.firstChild("SegmentTemplate")
            ?: set.firstChild("SegmentTemplate")
        val segList = rep.firstChild("SegmentList")
            ?: set.firstChild("SegmentList")
        val segBase = rep.firstChild("SegmentBase")
            ?: set.firstChild("SegmentBase")
        var singleFile = false
        val segments = when {
            template != null ->
                expandTemplate(rep, template, repBase, durationSec)
            segList != null -> fromSegmentList(segList, repBase)
            segBase != null -> {
                singleFile = true
                fromSegmentBase(segBase, repBase)
            }
            else -> {
                // No segment addressing at all: the BaseURL is
                // the whole media file (progressive download).
                singleFile = true
                listOf(SegmentRef(repBase))
            }
        }
        if (segments.isEmpty()) return null
        return DashRepresentation(
            id = rep.attrs["id"],
            bandwidth = rep.attrs["bandwidth"]?.toLongOrNull() ?: 0L,
            width = rep.attrs["width"]?.toIntOrNull() ?: 0,
            height = rep.attrs["height"]?.toIntOrNull() ?: 0,
            mimeType = rep.attrs["mimeType"] ?: setMime,
            segments = segments,
            singleFile = singleFile,
            drm = drm
        )
    }

    private fun expandTemplate(
        rep: Node,
        template: Node,
        repBase: String,
        durationSec: Double
    ): List<SegmentRef> {
        val media = template.attrs["media"] ?: return emptyList()
        val timescale = template.attrs["timescale"]?.toDoubleOrNull() ?: 1.0
        val startNumber = template.attrs["startNumber"]?.toIntOrNull() ?: 1
        val out = mutableListOf<SegmentRef>()
        template.attrs["initialization"]?.let { init ->
            out += SegmentRef(resolve(repBase, substitute(init, rep, null, null)))
        }
        val timeline = template.firstChild("SegmentTimeline")
        if (timeline != null) {
            var time = timeline.childrenNamed("S").firstOrNull()
                ?.attrs?.get("t")?.toLongOrNull() ?: 0L
            var number = startNumber
            for (s in timeline.childrenNamed("S")) {
                s.attrs["t"]?.toLongOrNull()?.let { time = it }
                val step = s.attrs["d"]?.toLongOrNull() ?: continue
                var repeats = s.attrs["r"]?.toIntOrNull() ?: 0
                if (repeats < 0) {
                    // r="-1": repeat until the period ends.
                    val totalTime = (durationSec * timescale).toLong()
                    repeats = if (step > 0 && totalTime > time) {
                        (((totalTime - time) + step - 1) / step - 1)
                            .toInt().coerceAtLeast(0)
                    } else {
                        0
                    }
                }
                repeat(repeats + 1) {
                    out += SegmentRef(
                        resolve(repBase, substitute(media, rep, number, time))
                    )
                    time += step
                    number++
                }
            }
            return out
        }
        val segDuration = template.attrs["duration"]?.toDoubleOrNull()
        if (segDuration == null || segDuration <= 0 || durationSec <= 0) {
            return out
        }
        val count = ceil(durationSec * timescale / segDuration).toInt()
        for (i in 0 until count) {
            val time = (i * segDuration).toLong()
            out += SegmentRef(
                resolve(repBase, substitute(media, rep, startNumber + i, time))
            )
        }
        return out
    }

    private fun fromSegmentList(segList: Node, repBase: String): List<SegmentRef> {
        val out = mutableListOf<SegmentRef>()
        segList.firstChild("Initialization")?.let { init ->
            init.attrs["sourceURL"]?.let { source ->
                out += SegmentRef(resolve(repBase, source), init.attrs["range"])
            }
        }
        for (seg in segList.childrenNamed("SegmentURL")) {
            seg.attrs["media"]?.let { media ->
                out += SegmentRef(resolve(repBase, media), seg.attrs["mediaRange"])
            }
        }
        return out
    }

    private fun fromSegmentBase(segBase: Node, repBase: String): List<SegmentRef> {
        val initRange = segBase.firstChild("Initialization")?.attrs?.get("range")
        val initEnd = initRange?.substringAfterLast('-')?.toLongOrNull()
        return if (initRange != null && initEnd != null) {
            listOf(
                SegmentRef(repBase, initRange),
                SegmentRef(repBase, "${initEnd + 1}-")
            )
        } else {
            listOf(SegmentRef(repBase))
        }
    }

    /** $…$ substitution; "$$" is the escaped literal dollar. */
    private fun substitute(
        template: String,
        rep: Node,
        number: Int?,
        time: Long?
    ): String {
        var out = template.replace("$$", "\u0001")
        out = out.replace("\$RepresentationID\$", rep.attrs["id"] ?: "")
        out = out.replace("\$Bandwidth\$", rep.attrs["bandwidth"] ?: "0")
        out = Regex("""\$Number%(0\d+)d\$""").replace(out) { match ->
            String.format("%0${match.groupValues[1]}d", number ?: 0)
        }
        out = out.replace("\$Number\$", (number ?: 0).toString())
        out = Regex("""\$Time%(0\d+)d\$""").replace(out) { match ->
            String.format("%0${match.groupValues[1]}d", time ?: 0L)
        }
        out = out.replace("\$Time\$", (time ?: 0L).toString())
        return out.replace("\u0001", "\$")
    }

    /** "PT1H2M3.5S" / "P1DT2H" → seconds. Null when unparseable. */
    private fun parseIsoDuration(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val match = Regex(
            """^P(?:(\d+(?:\.\d+)?)D)?""" +
                """(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?""" +
                """(?:(\d+(?:\.\d+)?)S)?)?$"""
        ).find(value.trim()) ?: return null
        fun part(index: Int) = match.groupValues[index].toDoubleOrNull() ?: 0.0
        return part(1) * 86_400 + part(2) * 3_600 + part(3) * 60 + part(4)
    }

    private fun resolve(base: String, relative: String): String =
        try {
            URL(URL(base), relative).toString()
        } catch (e: Exception) {
            relative
        }

    /** A level's effective base: its first BaseURL child, resolved. */
    private fun baseOf(node: Node, parentBase: String): String {
        val own = node.childrenNamed("BaseURL").firstOrNull()
            ?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return parentBase
        return resolve(parentBase, own)
    }

    // ---------- A tiny DOM over XmlPullParser ----------

    private class Node(val tag: String, val attrs: Map<String, String>) {
        val children = mutableListOf<Node>()
        var text: String = ""

        fun childrenNamed(tag: String): List<Node> = children.filter { it.tag == tag }

        fun firstChild(tag: String): Node? = children.firstOrNull { it.tag == tag }

        fun hasDescendant(tag: String): Boolean =
            children.any { it.tag == tag || it.hasDescendant(tag) }
    }

    private fun parseXml(text: String): Node {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        try {
            parser.setInput(text.reader())
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a DASH manifest")
        }
        var root: Node? = null
        val stack = ArrayDeque<Node>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                    val node = Node(parser.name ?: "", attrs)
                    if (stack.isEmpty()) {
                        if (root == null) root = node
                    } else {
                        stack.last().children += node
                    }
                    stack.addLast(node)
                }
                XmlPullParser.TEXT ->
                    stack.lastOrNull()?.let { it.text += parser.text ?: "" }
                XmlPullParser.END_TAG ->
                    if (stack.isNotEmpty()) stack.removeLast()
            }
            event = parser.next()
        }
        return root ?: throw IllegalArgumentException("Not a DASH manifest")
    }
}
