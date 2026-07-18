package com.longdev.xiaoling.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException

class ApiFailureClassifierTest {
    @Test
    fun interruptedResponseStreamIsAConnectionFailure() {
        val failure = ApiFailureClassifier.fromNetwork(ProtocolException("unexpected end of stream"))

        assertEquals(FailureKind.CONNECTION, failure.kind)
    }

    @Test
    fun malformedHttpProtocolRemainsAResponseFailure() {
        val failure = ApiFailureClassifier.fromNetwork(ProtocolException("unexpected status line"))

        assertEquals(FailureKind.RESPONSE, failure.kind)
    }

    @Test
    fun unrecognizedIoFailureRemainsUnknown() {
        val failure = ApiFailureClassifier.fromNetwork(IOException("unrecognized I/O failure"))

        assertEquals(FailureKind.UNKNOWN, failure.kind)
    }
}
