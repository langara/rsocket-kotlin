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

package io.rsocket.kotlin

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RSocketRequestHandlerBuilder internal constructor() {
    private var metadataPush: (suspend RSocketResponder.(metadata: ByteReadPacket) -> Unit)? = null
    private var fireAndForget: (suspend RSocketResponder.(payload: Payload) -> Unit)? = null
    private var requestResponse: (suspend RSocketResponder.(payload: Payload) -> Payload)? = null
    private var requestStream: (suspend RSocketResponder.(payload: Payload) -> Flow<Payload>)? = null
    private var requestChannel: (suspend RSocketResponder.(initPayload: Payload, payloads: Flow<Payload>) -> Flow<Payload>)? = null

    public fun metadataPush(block: (suspend RSocketResponder.(metadata: ByteReadPacket) -> Unit)) {
        check(metadataPush == null) { "Metadata Push handler already configured" }
        metadataPush = block
    }

    public fun fireAndForget(block: (suspend RSocketResponder.(payload: Payload) -> Unit)) {
        check(metadataPush == null) { "Fire and Forget handler already configured" }
        fireAndForget = block
    }

    public fun requestResponse(block: (suspend RSocketResponder.(payload: Payload) -> Payload)) {
        check(metadataPush == null) { "Request Response Push handler already configured" }
        requestResponse = block
    }

    public fun requestStream(block: (suspend RSocketResponder.(payload: Payload) -> Flow<Payload>)) {
        check(metadataPush == null) { "Request Stream handler already configured" }
        requestStream = block
    }

    public fun requestChannel(block: (suspend RSocketResponder.(initPayload: Payload, payloads: Flow<Payload>) -> Flow<Payload>)) {
        check(metadataPush == null) { "Request Channel handler already configured" }
        requestChannel = block
    }

    internal fun build(job: Job): RSocketResponder =
        RSocketRequestHandler(job, metadataPush, fireAndForget, requestResponse, requestStream, requestChannel)
}

@Suppress("FunctionName")
fun RSocketRequestHandler(parentJob: Job? = null, configure: RSocketRequestHandlerBuilder.() -> Unit): RSocketResponder {
    val builder = RSocketRequestHandlerBuilder()
    builder.configure()
    return builder.build(Job(parentJob))
}

private class RSocketRequestHandler(
    override val job: Job,
    private val metadataPush: (suspend RSocketResponder.(metadata: ByteReadPacket) -> Unit)?,
    private val fireAndForget: (suspend RSocketResponder.(payload: Payload) -> Unit)?,
    private val requestResponse: (suspend RSocketResponder.(payload: Payload) -> Payload)?,
    private val requestStream: (suspend RSocketResponder.(payload: Payload) -> Flow<Payload>)?,
    private val requestChannel: (suspend RSocketResponder.(initPayload: Payload, payloads: Flow<Payload>) -> Flow<Payload>)?,
) : RSocketResponder {
    override suspend fun metadataPush(metadata: ByteReadPacket): Unit =
        metadataPush?.invoke(this, metadata) ?: super.metadataPush(metadata)

    override suspend fun fireAndForget(payload: Payload): Unit =
        fireAndForget?.invoke(this, payload) ?: super.fireAndForget(payload)

    override suspend fun requestResponse(payload: Payload): Payload =
        requestResponse?.invoke(this, payload) ?: super.requestResponse(payload)

    override suspend fun requestStream(payload: Payload): Flow<Payload> =
        requestStream?.invoke(this, payload) ?: super.requestStream(payload)

    override suspend fun requestChannel(initPayload: Payload, payloads: Flow<Payload>): Flow<Payload> =
        requestChannel?.invoke(this, initPayload, payloads) ?: super.requestChannel(initPayload, payloads)

}
