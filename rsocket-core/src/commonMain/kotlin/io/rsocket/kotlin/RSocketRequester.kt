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
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

public interface RSocketRequester : Cancelable {

    public suspend fun metadataPush(metadata: ByteReadPacket)
    public suspend fun fireAndForget(payload: Payload)
    public suspend fun requestResponse(payload: Payload): Payload

    //single collect
    public fun requestStream(payload: Payload): ReactiveFlow<Payload>

    //multi collect
    public fun requestStream(payload: suspend () -> Payload): ReactiveFlow<Payload>

    //multi collect
    public fun requestChannel(payloads: Flow<Payload>): ReactiveFlow<Payload>
}

//single collect
public fun RSocketRequester.requestChannel(channel: ReceiveChannel<Payload>): ReactiveFlow<Payload> {
    return requestChannel(channel.consumeAsFlow())
}
