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

package io.rsocket.kotlin.internal

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.flow.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*

internal class RSocketRequesterImpl(
    private val state: RSocketState,
    private val streamId: StreamId,
) : RSocketRequester, Cancelable by state {

    override suspend fun metadataPush(metadata: ByteReadPacket): Unit = metadata.closeOnError {
        checkAvailable()
        state.sendPrioritized(MetadataPushFrame(metadata))
    }

    override suspend fun fireAndForget(payload: Payload): Unit = payload.closeOnError {
        val streamId = createStream()
        state.send(RequestFireAndForgetFrame(streamId, payload))
    }

    override suspend fun requestResponse(payload: Payload): Payload = with(state) {
        payload.closeOnError {
            val streamId = createStream()
            val receiver = createReceiverFor(streamId)
            send(RequestResponseFrame(streamId, payload))
            consumeReceiverFor(streamId) {
                receiver.receive().payload //TODO fragmentation
            }
        }
    }

    @OptIn(ExperimentalStreamsApi::class)
    override fun requestStream(payload: suspend () -> Payload): Flow<Payload> = flow {
        val p = payload()
        p.closeOnError {
            val strategy = coroutineContext.requestStrategy()
            val initialRequest = strategy.firstRequest()
            val streamId = createStream()

            with(state) {
                val receiver = createReceiverFor(streamId)
                send(RequestStreamFrame(streamId, initialRequest, p))
                collectStream(streamId, receiver, strategy, this@flow)
            }
        }
    }

    @OptIn(ExperimentalStreamsApi::class)
    override fun requestChannel(payloads: Flow<Payload>): Flow<Payload> = flow {
        val strategy = coroutineContext.requestStrategy()
        val initialRequest = strategy.firstRequest()
        val streamId = createStream()
        val receiverDeferred = CompletableDeferred<ReceiveChannel<RequestFrame>?>()
        val request = with(state) {
            launchCancelable(streamId) {
                payloads.collectLimiting(
                    streamId,
                    RequestChannelRequesterFlowCollector(state, streamId, receiverDeferred, initialRequest)
                )
                if (receiverDeferred.isCompleted && !receiverDeferred.isCancelled) send(CompletePayloadFrame(streamId))
            }
        }

        request.invokeOnCompletion {
            if (receiverDeferred.isCompleted) {
                @OptIn(ExperimentalCoroutinesApi::class)
                if (it != null && it !is CancellationException) receiverDeferred.getCompleted()?.cancelConsumed(it)
            } else {
                if (it == null) receiverDeferred.complete(null)
                else receiverDeferred.completeExceptionally(it.cause ?: it)
            }
        }

        try {
            val receiver = receiverDeferred.await() ?: return@flow
            state.collectStream(streamId, receiver, strategy, this)
        } catch (e: Throwable) {
            if (e is CancellationException) request.cancel(e)
            else request.cancel("Receiver failed", e)
            throw e
        }
    }

    fun createStream(): Int {
        checkAvailable()
        return nextStreamId()
    }

    private fun nextStreamId(): Int = streamId.next(state.receivers)

    @OptIn(InternalCoroutinesApi::class)
    private fun checkAvailable() {
        if (isActive) return
        val error = job.getCancellationException()
        throw error.cause ?: error
    }

}
