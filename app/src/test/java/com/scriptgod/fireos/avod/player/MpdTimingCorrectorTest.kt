package com.scriptgod.fireos.avod.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpdTimingCorrectorTest {
    @Test fun descriptiveRoleIsAddedWithoutChangingSegmentAddressing() {
        val mpd = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period><AdaptationSet audioTrackId="en_descriptive">
                <SegmentList duration="2000"><SegmentURL media="audio.m4s"/></SegmentList>
              </AdaptationSet></Period>
            </MPD>""".trimIndent()
        val result = MpdTimingCorrector.enhanceMpdXml(mpd)
        assertTrue(result.contains("value=\"description\""))
        assertTrue(result.contains("SegmentList"))
        assertTrue(result.contains("SegmentURL"))
        assertFalse(result.contains("SegmentBase"))
    }

    @Test fun nonDescriptiveManifestIsReturnedUnchanged() {
        val mpd = """<MPD><Period><AdaptationSet audioTrackId="en">
            <SegmentList duration="2000"><SegmentURL media="audio.m4s"/></SegmentList>
        </AdaptationSet></Period></MPD>"""
        assertTrue(MpdTimingCorrector.enhanceMpdXml(mpd) === mpd)
    }
}
