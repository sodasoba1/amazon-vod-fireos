package com.scriptgod.fireos.avod.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Adds only the descriptive-audio role marker needed by Media3. The Amazon MPD remains the
 * authority for SegmentList, SegmentBase, BaseURL, index ranges and segment timing.
 */
object MpdTimingCorrector {
    private const val TAG = "MpdTimingCorrector"

    suspend fun correctMpd(mpdUrl: String, httpClient: OkHttpClient): String? = try {
        val original = downloadString(mpdUrl, httpClient)
        val enhanced = enhanceMpdXml(original)
        if (enhanced == original) null else enhanced
    } catch (e: Exception) {
        Log.w(TAG, "MPD enhancement skipped; using original manifest", e)
        null
    }

    internal fun enhanceMpdXml(xml: String): String {
        val doc = parseXml(xml)
        if (!injectDescriptiveAudioRoles(doc)) return xml
        return serializeXml(doc)
    }

    private fun injectDescriptiveAudioRoles(doc: Document): Boolean {
        val sets = doc.getElementsByTagName("AdaptationSet")
        var changed = false
        for (i in 0 until sets.length) {
            val set = sets.item(i) as? Element ?: continue
            if (!set.getAttribute("audioTrackId").contains("_descriptive", ignoreCase = true)) continue
            val children = set.childNodes
            val exists = (0 until children.length).any { index ->
                val child = children.item(index)
                child is Element && child.localName == "Role" && child.getAttribute("value") == "description"
            }
            if (exists) continue
            val role = doc.createElementNS(set.namespaceURI, "Role")
            role.setAttribute("schemeIdUri", "urn:mpeg:dash:role:2011")
            role.setAttribute("value", "description")
            val first = set.firstChild
            if (first == null) set.appendChild(role) else set.insertBefore(role, first)
            changed = true
        }
        return changed
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun serializeXml(doc: Document): String {
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
        val transformer = factory.newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun downloadString(url: String, client: OkHttpClient): String {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP " + response.code + " for manifest")
            response.body?.string() ?: throw RuntimeException("Empty manifest response")
        }
    }
}
