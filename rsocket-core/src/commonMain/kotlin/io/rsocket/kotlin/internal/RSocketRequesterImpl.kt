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
import kotlinx.coroutines.flow.*

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

    override fun requestStream(payload: Payload): ReactiveFlow<Payload> {
        return RequestStreamSingleRequesterFlow(payload, this, state, state.defaultRequestStrategy)
    }

    override fun requestStream(payload: suspend () -> Payload): ReactiveFlow<Payload> {
        return RequestStreamMultiRequesterFlow(payload, this, state, state.defaultRequestStrategy)
    }

    override fun requestChannel(payloads: Flow<Payload>): ReactiveFlow<Payload> {
        return RequestChannelRequesterFlow(payloads, this, state, state.defaultRequestStrategy)
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
