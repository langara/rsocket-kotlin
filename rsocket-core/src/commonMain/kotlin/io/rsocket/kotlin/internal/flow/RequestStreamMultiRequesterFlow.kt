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

package io.rsocket.kotlin.internal.flow

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalStreamsApi::class)
internal class RequestStreamMultiRequesterFlow(
    private val payloadProvider: suspend () -> Payload,
    private val requester: RSocketRequesterImpl,
    private val state: RSocketState,
    private val strategyProvider: () -> RequestStrategy,
) : ReactiveFlow<Payload> {
    override fun request(strategy: () -> RequestStrategy): ReactiveFlow<Payload> =
        RequestStreamMultiRequesterFlow(payloadProvider, requester, state, strategy)

    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<Payload>) = with(state) {
        val payload = payloadProvider()
        payload.closeOnError {
            val strategy = strategyProvider()
            val streamId = requester.createStream()
            val receiver = createReceiverFor(streamId)
            send(RequestStreamFrame(streamId, strategy.initialRequest, payload))
            collectStream(streamId, receiver, strategy, collector)
        }
    }
}
