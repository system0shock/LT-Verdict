package io.ltverdict.integrations.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class ConfluencePublisherTest {
    @Test
    fun `publisher is not configured until an exact strategy is supplied`() {
        val publisher = ConfluencePublisher(null)

        val result = publisher.publish(ConfluencePublishRequest("Report", "<h1>report</h1>".encodeToByteArray()))

        assertEquals(ConfluencePublishStatus.NOT_CONFIGURED, result.status)
        assertNull(result.pageUrl)
        assertNull(result.failureCode)
    }

    @Test
    fun `publisher is fail soft and does not expose transport failure text`() {
        val publisher = ConfluencePublisher { throw IllegalStateException("secret-token") }

        val result = publisher.publish(ConfluencePublishRequest("Report", "<h1>report</h1>".encodeToByteArray()))

        assertEquals(ConfluencePublishStatus.FAILED, result.status)
        assertEquals("CONFLUENCE_PUBLISH_FAILED", result.failureCode)
        assertFalse(result.toString().contains("secret-token"))
    }

    @Test
    fun `publisher accepts only credential-free HTTP page URLs`() {
        val published = ConfluencePublisher { URI("https://confluence.example/pages/42") }
        val unsafe = ConfluencePublisher { URI("https://user:secret@confluence.example/pages/42") }
        val queryCredential = ConfluencePublisher { URI("https://confluence.example/pages/42?token=secret") }
        val request = ConfluencePublishRequest("Report", "<h1>report</h1>".encodeToByteArray())

        assertEquals(ConfluencePublishStatus.PUBLISHED, published.publish(request).status)
        assertEquals(ConfluencePublishStatus.FAILED, unsafe.publish(request).status)
        assertEquals(ConfluencePublishStatus.FAILED, queryCredential.publish(request).status)
    }
}
