package com.cleartune.app.metadata

import org.junit.Assert.*
import org.junit.Test

class MusicTagCoverReviewTest {
    @Test fun confirmationOnlyAppliesToTheImageTheUserSawAndSelected() {
        val actual = "data:image/png;base64,cGljdHVyZQ=="
        val url = "https://example.com/selected.jpg"
        val review = MusicTagCoverReview(actual, url, true)
        assertTrue(coverReviewApproves(review, actual, url))
        assertFalse(coverReviewApproves(null, actual, url))
        assertFalse(coverReviewApproves(review, "data:image/png;base64,Y2hhbmdlZA==", url))
        assertFalse(coverReviewApproves(review, actual, "https://example.com/other.jpg"))
        assertFalse(coverReviewApproves(MusicTagCoverReview("data:image/png;base64,", url, true), "data:image/png;base64,", url))
    }
}
