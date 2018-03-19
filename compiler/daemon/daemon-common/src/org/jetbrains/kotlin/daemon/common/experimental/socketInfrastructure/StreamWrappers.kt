package org.jetbrains.kotlin.daemon.common.experimental.socketInfrastructure

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.experimental.io.ByteBuffer
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.io.ByteWriteChannel
import kotlinx.coroutines.experimental.launch
import kotlinx.io.core.readBytes
import org.jetbrains.kotlin.daemon.common.CompileService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.logging.Logger

class ByteReadChannelWrapper(private val readChannel: ByteReadChannel, private val log: Logger) {

    private suspend fun readBytes(length: Int) =
        readChannel.readPacket(length).readBytes()

    private suspend fun getObject(length: Int) =
        if (length >= 0) {
            ObjectInputStream(
                ByteArrayInputStream(readBytes(length))
            ).use {
                it.readObject()
            }
        } else { // optimize for long strings!
            String(
                ByteArrayInputStream(
                    readBytes(-length)
                ).readBytes()
            )
        }.also {
            log.info("object : ${if (it is CompileService.CallResult<*> && it.isGood) it.get() else it}")
        }

    private suspend fun getLength(): Int {
        log.info("length : ")
        val packet = readBytes(4)
        log.info("length : ${packet.toList()}")
        val (b1, b2, b3, b4) = packet.map(Byte::toInt)
        return (0xFF and b1 shl 24 or (0xFF and b2 shl 16) or
                (0xFF and b3 shl 8) or (0xFF and b4)).also { log.info("   $it") }
    }

    suspend fun nextObject() = getObject(getLength())

}


class ByteWriteChannelWrapper(private val writeChannel: ByteWriteChannel, private val log: Logger) {

    private suspend fun printBytes(bytes: ByteArray) {
        bytes.forEach { writeChannel.writeByte(it) }
        writeChannel.flush()
    }

    private suspend fun printObjectImpl(obj: Any?) =
        ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { objOut ->
                objOut.writeObject(obj)
                objOut.flush()
                val bytes = bos.toByteArray()
                printLength(bytes.size)
                printBytes(bytes)
            }
        }
            .also {
                log.info("sent object : $obj")
            }

    private suspend fun printString(s: String) {
        printLength(-s.length)
        printBytes(s.toByteArray())
    }


    private suspend fun printLength(length: Int) = printBytes(
        ByteBuffer
            .allocate(4)
            .putInt(length)
            .array()
            .also {
                log.info("printLength $length")
            }
    )

    suspend fun writeObject(obj: Any?) {
        launch {
            if (obj is String) printString(obj)
            else printObjectImpl(obj)
        }
    }
}

fun ByteReadChannel.toWrapper(log: Logger) = ByteReadChannelWrapper(this, log)
fun ByteWriteChannel.toWrapper(log: Logger) = ByteWriteChannelWrapper(this, log)

fun Socket.openAndWrapReadChannel(log: Logger) = this.openReadChannel().toWrapper(log)
fun Socket.openAndWrapWriteChannel(log: Logger) = this.openWriteChannel().toWrapper(log)

data class IOPair(val input: ByteReadChannelWrapper, val output: ByteWriteChannelWrapper)

fun Socket.openIO(log: Logger) = IOPair(this.openAndWrapReadChannel(log), this.openAndWrapWriteChannel(log))