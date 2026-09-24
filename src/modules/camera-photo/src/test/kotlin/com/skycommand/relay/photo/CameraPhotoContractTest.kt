package com.skycommand.relay.photo

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import com.skycommand.relay.photo.executor.DjiPhotoPort
import com.skycommand.relay.photo.executor.PhotoDjiCompletion
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import com.skycommand.relay.photo.executor.PhotoReadable
import com.skycommand.relay.photo.media.PhotoMediaClock
import com.skycommand.relay.photo.media.PhotoMediaCancellation
import com.skycommand.relay.photo.media.PhotoMediaPublisher
import com.skycommand.relay.photo.media.PhotoMediaWriter
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.MediaResultFrame
import com.skycommand.relay.protocol.RelayFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraPhotoContractTest {
    @Test
    fun captureSuccessStoresIdentityAndRejectsASecondCommandUntilFinished() {
        val port = Port()
        val photo = photo(port)
        val duplicate = Completion()

        photo.commandHandler().handle(capture(), Completion())
        photo.commandHandler().handle(fetch(), duplicate)

        assertEquals(listOf<PhotoHardwareRequest>(PhotoHardwareRequest.Capture), port.requests)
        assertEquals(listOf("reject:Photo operation was rejected"), duplicate.events)

        port.captured(PhotoCaptureIdentity("shot.jpg", 3))
        val fetched = Completion()
        photo.commandHandler().handle(fetch(), fetched)
        assertEquals(PhotoHardwareRequest.Download(PhotoCaptureIdentity("shot.jpg", 3)), port.requests.last())
    }

    @Test
    fun fetchWithoutACaptureIsRejectedBeforeDjiAndKeepsIdentityAfterTransferAbort() {
        val port = Port()
        val photo = photo(port)
        val missing = Completion()
        photo.commandHandler().handle(fetch(), missing)
        assertEquals(emptyList<PhotoHardwareRequest>(), port.requests)
        assertEquals(listOf("reject:Photo operation was rejected"), missing.events)

        val captured = Completion()
        photo.commandHandler().handle(capture(), captured)
        port.captured(PhotoCaptureIdentity("shot.jpg", 1))
        assertTrue(captured.events.first().startsWith("ok:"))

        photo.commandHandler().handle(fetch(), Completion())
        val bytes = byteArrayOf(1, 2, 3)
        port.delivered(PhotoLocalFile("shot.jpg", bytes.size.toLong(), PhotoMediaPublisher.sha256Hex(bytes), PhotoReadable { bytes }))
        photo.abortTransfer()

        val retry = Completion()
        photo.commandHandler().handle(fetch(), retry)
        assertEquals(PhotoHardwareRequest.Download(PhotoCaptureIdentity("shot.jpg", 1)), port.requests.last())
    }

    @Test
    fun exposesTheRealDjiRejectionAsAStructuredCommandResult() {
        val port = Port()
        val photo = photo(port)
        val completion = Completion()
        val failure = PhotoDjiFailure.fromDjiError("CAMERA_BUSY", "The camera is busy")

        photo.commandHandler().handle(capture(), completion)
        port.fail(failure)

        assertEquals(listOf("reject:Photo action was rejected"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("photo"),
                    "outcome" to JsonString("ACTION_REJECTED"),
                    "errorCode" to JsonString("CAMERA_BUSY"),
                    "errorDescription" to JsonString("The camera is busy"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun fetchSucceedsOnlyAfterMediaResultAndThenClearsIdentity() {
        val port = Port()
        val writer = RecordingWriter()
        val photo = photo(port, writer)
        photo.commandHandler().handle(capture(), Completion())
        port.captured(PhotoCaptureIdentity("shot.jpg", 1))

        val delivered = Completion()
        photo.commandHandler().handle(fetch(), delivered)
        val bytes = byteArrayOf(4, 5, 6)
        port.delivered(PhotoLocalFile("shot.jpg", bytes.size.toLong(), PhotoMediaPublisher.sha256Hex(bytes), PhotoReadable { bytes }))
        photo.acceptMediaResult(MediaResultFrame("photo-1", true, "stored"))

        assertTrue(delivered.events.first().startsWith("ok:Photo delivered"))
        val missing = Completion()
        photo.commandHandler().handle(fetch(), missing)
        assertEquals(listOf("reject:Photo operation was rejected"), missing.events)
        assertTrue(writer.frames.isNotEmpty())
    }

    @Test
    fun keepsTheCameraMediaGateClosedUntilAnUnconfirmedPhotoOperationReleasesHardware() {
        val port = Port()
        val scheduler = Scheduler()
        var cameraMediaReady = true
        val photo = photo(
            port = port,
            scheduler = scheduler,
            onCameraMediaBusy = { cameraMediaReady = false },
            onCameraMediaReleased = { cameraMediaReady = true },
        )

        photo.commandHandler().handle(capture(), Completion())
        assertFalse(cameraMediaReady)

        scheduler.fire()
        assertFalse(cameraMediaReady)

        port.releaseHardware()
        assertTrue(cameraMediaReady)
    }

    private fun photo(
        port: DjiPhotoPort,
        writer: PhotoMediaWriter = RecordingWriter(),
        scheduler: OperationScheduler = OperationScheduler { _, _ -> OperationCancellation { } },
        onCameraMediaBusy: () -> Unit = {},
        onCameraMediaReleased: () -> Unit = {},
    ): CameraPhoto =
        CameraPhoto.create(
            CameraPhotoDependencies(
                djiPort = port,
                operationCoordinator = DjiOperationCoordinator.create(
                    executor = OperationExecutor { it() },
                    scheduler = scheduler,
                ),
                mediaPublisher = PhotoMediaPublisher.create(writer, Clock(), timeoutMillis = 1_000),
                onCameraMediaBusy = onCameraMediaBusy,
                onCameraMediaReleased = onCameraMediaReleased,
            ),
        )

    private fun capture() = CommandFrame("photo-1", "camera.photo.capture", JsonObject(emptyMap()))
    private fun fetch() = CommandFrame("photo-2", "camera.photo.fetch", JsonObject(emptyMap()))

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        var result: JsonObject? = null
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun succeed(detail: String, result: JsonObject?) {
            events += "ok:$detail"
            this.result = result
        }
        override fun reject(detail: String) { events += "reject:$detail" }
        override fun reject(detail: String, result: JsonObject?) {
            events += "reject:$detail"
            this.result = result
        }
    }

    private class Port : DjiPhotoPort {
        val requests = mutableListOf<PhotoHardwareRequest>()
        private var completion: PhotoDjiCompletion? = null
        override fun execute(request: PhotoHardwareRequest, completion: PhotoDjiCompletion) {
            requests += request
            this.completion = completion
        }
        fun captured(identity: PhotoCaptureIdentity) = checkNotNull(completion).captured(identity)
        fun delivered(file: PhotoLocalFile) = checkNotNull(completion).delivered(file)
        fun fail(failure: PhotoDjiFailure? = null) = checkNotNull(completion).fail(failure)
        private var hardwareReleased: (() -> Unit)? = null
        override fun abort(completion: PhotoDjiCompletion, onHardwareReleased: () -> Unit) {
            check(checkNotNull(this.completion) === completion)
            hardwareReleased = onHardwareReleased
        }
        fun releaseHardware() = checkNotNull(hardwareReleased).invoke()
    }

    private class RecordingWriter : PhotoMediaWriter {
        val frames = mutableListOf<RelayFrame>()
        override fun write(frame: RelayFrame): Boolean {
            frames += frame
            return true
        }
    }

    private class Clock : PhotoMediaClock {
        override fun schedule(delayMillis: Long, callback: () -> Unit): PhotoMediaCancellation =
            PhotoMediaCancellation { }
    }

    private class Scheduler : OperationScheduler {
        private var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation { }
        }
        fun fire() = checkNotNull(callback).invoke()
    }
}
