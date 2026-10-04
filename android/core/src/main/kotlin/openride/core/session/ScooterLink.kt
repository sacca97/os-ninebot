package openride.core.session

import kotlinx.coroutines.flow.Flow

/** The only thing the session knows about Bluetooth. */
interface ScooterLink {
    /** Raw notification chunks from 6e400003. Completes when the link drops. */
    val incoming: Flow<ByteArray>

    /** Largest chunk to put in one write (MTU - 3). */
    val maxChunk: Int get() = 20

    /** One write-without-response to 6e400002. */
    suspend fun write(chunk: ByteArray)

    suspend fun close()
}
