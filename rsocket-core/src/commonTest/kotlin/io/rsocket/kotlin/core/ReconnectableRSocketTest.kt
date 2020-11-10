/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.core

import app.cash.turbine.*
import io.ktor.utils.io.core.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class ReconnectableRSocketTest : SuspendTest, TestWithLeakCheck {

    //needed for native
    private val fails = atomic(0)
    private val first = atomic(true)
    private val logger = TestLoggerFactory.logger("io.rsocket.kotlin.connection")

    @Test
    fun testConnectFail() = test {
        val connect: suspend () -> RSocketRequester = { error("Failed to connect") }

        assertFailsWith(IllegalStateException::class, "Failed to connect") {
            ReconnectableRSocket(logger, connect) { cause, attempt ->
                fails.incrementAndGet()
                assertTrue(cause is IllegalStateException)
                assertEquals("Failed to connect", cause.message)
                attempt < 5
            }
        }
        assertEquals(6, fails.value)
    }

    @Test
    fun testReconnectFail() = test {
        val firstJob = Job()
        val connect: suspend () -> RSocketRequester = {
            if (first.value) {
                first.value = false
                rrHandler(firstJob)
            } else {
                error("Failed to connect")
            }
        }
        val rSocket = ReconnectableRSocket(logger, connect) { cause, attempt ->
            fails.incrementAndGet()
            assertTrue(cause is IllegalStateException)
            assertEquals("Failed to connect", cause.message)
            attempt < 5
        }

        assertEquals(Payload.Empty, rSocket.requestResponse(Payload.Empty))

        assertTrue(rSocket.isActive)
        assertEquals(0, fails.value)

        firstJob.cancelAndJoin()

        assertFailsWith(IllegalStateException::class, "Failed to connect") {
            rSocket.requestResponse(Payload.Empty)
        }

        assertFalse(rSocket.isActive)
        assertEquals(6, fails.value)
    }

    @Test
    fun testReconnectSuccess() = test {
        val handlerJob = Job()
        val connect: suspend () -> RSocketRequester = {
            if (first.value) {
                first.value = false
                error("Failed to connect")
            } else {
                rrHandler(handlerJob)
            }
        }
        val rSocket = ReconnectableRSocket(logger, connect) { cause, attempt ->
            fails.incrementAndGet()
            assertTrue(cause is IllegalStateException)
            assertEquals("Failed to connect", cause.message)
            attempt < 5
        }

        assertEquals(Payload.Empty, rSocket.requestResponse(Payload.Empty))

        assertTrue(handlerJob.isActive)
        assertTrue(rSocket.isActive)
        assertEquals(1, fails.value)
    }

    @Test
    fun testConnectSuccessAfterTime() = test {
        val connect: suspend () -> RSocketRequester = {
            if (fails.value < 5) {
                delay(200)
                error("Failed to connect")
            } else {
                delay(200) //emulate connection establishment
                rrHandler(Job())
            }
        }
        val rSocket = ReconnectableRSocket(logger, connect) { cause, attempt ->
            fails.incrementAndGet()
            assertTrue(cause is IllegalStateException)
            assertEquals("Failed to connect", cause.message)
            attempt < 5
        }

        assertEquals(Payload.Empty, rSocket.requestResponse(Payload.Empty))

        assertTrue(rSocket.isActive)
        assertEquals(5, fails.value)
    }

    @Test
    fun testReconnectSuccessAfterFail() = test {
        val firstJob = Job()
        val connect: suspend () -> RSocketRequester = {
            when {
                first.value     -> {
                    first.value = false
                    rrHandler(firstJob) //first connection
                }
                fails.value < 5 -> {
                    delay(100)
                    error("Failed to connect")
                }
                else            -> rrHandler(Job())
            }
        }
        val rSocket = ReconnectableRSocket(logger, connect) { cause, attempt ->
            fails.incrementAndGet()
            assertTrue(cause is IllegalStateException)
            assertEquals("Failed to connect", cause.message)
            attempt < 5
        }

        assertEquals(Payload.Empty, rSocket.requestResponse(Payload.Empty))

        firstJob.cancelAndJoin()

        assertEquals(Payload.Empty, rSocket.requestResponse(Payload.Empty))

        assertTrue(rSocket.isActive)
        assertEquals(5, fails.value)
    }

    @Test
    fun testReconnectSuccessAfterFaiStream() = test {
        val firstJob = Job()
        val connect: suspend () -> RSocketRequester = {
            when {
                first.value     -> {
                    first.value = false
                    streamHandler(firstJob) //first connection
                }
                fails.value < 5 -> {
                    delay(100)
                    error("Failed to connect")
                }
                else            -> streamHandler(Job())
            }
        }
        val rSocket = ReconnectableRSocket(logger, connect) { cause, attempt ->
            fails.incrementAndGet()
            assertTrue(cause is IllegalStateException)
            assertEquals("Failed to connect", cause.message)
            attempt < 5
        }

        launch {
            delay(200)
            firstJob.cancelAndJoin()
        }

        assertFailsWith(CancellationException::class) {
            rSocket.requestStream { Payload.Empty }.collect()
        }

        rSocket.requestStream { Payload.Empty }.test {
            repeat(5) {
                assertEquals(Payload.Empty, expectItem())
            }
            expectComplete()
        }

        assertTrue(rSocket.isActive)
        assertEquals(5, fails.value)
    }

    private fun rrHandler(job: Job): RSocketRequester = RSocketRequestHandler(job) { requestResponse { it } }.let(::ProxyRSocket)
    private fun streamHandler(job: Job): RSocketRequester = RSocketRequestHandler(job) {
        requestStream {
            flow {
                repeat(5) {
                    job.ensureActive()
                    delay(200)
                    emit(Payload.Empty)
                }
            }
        }
    }.let(::ProxyRSocket)
}

@OptIn(ExperimentalStreamsApi::class)
private class ProxyRSocket(private val responder: RSocketResponder) : RSocketRequester, Cancelable by responder {
    override suspend fun metadataPush(metadata: ByteReadPacket) {
        responder.metadataPush(metadata)
    }

    override suspend fun fireAndForget(payload: Payload) {
        responder.fireAndForget(payload)
    }

    override suspend fun requestResponse(payload: Payload): Payload {
        return responder.requestResponse(payload)
    }

    override fun requestStream(payload: suspend () -> Payload): Flow<Payload> = flow {
        emitAll(responder.requestStream(payload()))
    }

    override fun requestChannel(payloads: Flow<Payload>): Flow<Payload> = flow {
        val channel = payloads.produceIn(GlobalScope)
        val initialPayload = channel.receive()
        val payloadsFlow = channel.consumeAsFlow()
        responder.requestChannel(initialPayload, payloadsFlow)
    }
}
