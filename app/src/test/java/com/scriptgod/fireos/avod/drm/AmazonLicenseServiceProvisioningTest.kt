package com.scriptgod.fireos.avod.drm

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AmazonLicenseServiceProvisioningTest {
    @Test
    fun signedRequestIsAddedToProvisioningUrlAndExistingParametersArePreserved() {
        val url = AmazonLicenseService.buildProvisioningUrl(
            "https://www.googleapis.com/certificateprovisioning/v1/devicecertificates/create?key=test-key",
            "signed+request/with=padding"
        ).toHttpUrl()

        assertEquals("test-key", url.queryParameter("key"))
        assertEquals("signed+request/with=padding", url.queryParameter("signedRequest"))
        assertNotNull(url.queryParameter("signedRequest"))
    }
}
