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

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.logging.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal typealias ReconnectPredicate = suspend (cause: Throwable, attempt: Long) -> Boolean

@Suppress("FunctionName")
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal suspend fun ReconnectableRSocket(
    logger: Logger,
    connect: suspend () -> RSocketRequester,
    predicate: ReconnectPredicate,
): RSocketRequester {
    val state = MutableStateFlow<ReconnectState>(ReconnectState.Connecting)

    val job =
        connect.asFlow()
            .map<RSocketRequester, ReconnectState> { ReconnectState.Connected(it) } //if connection established - state = connected
            .onStart { emit(ReconnectState.Connecting) } //init - state = connecting
            .retryWhen { cause, attempt ->
                logger.debug(cause) { "Connection establishment failed, attempt: $attempt. Trying to reconnect..." }
                predicate(cause, attempt)
            } //reconnection logic
            .catch {
                logger.debug(it) { "Reconnection failed" }
                emit(ReconnectState.Failed(it))
            } //reconnection failed - state = failed
            .mapNotNull {
                state.value = it //set state //TODO replace with Flow.stateIn when coroutines 1.4.0-native-mt will be released

                when (it) {
                    is ReconnectState.Connected -> {
                        logger.debug { "Connection established" }
                        it.rSocket.join() //await for connection completion
                        logger.debug { "Connection closed. Reconnecting..." }
                    }
                    is ReconnectState.Failed    -> throw it.error //reconnect failed, cancel job
                    ReconnectState.Connecting   -> null //skip, still waiting for new connection
                }
            }
            .launchRestarting() //reconnect if old connection completed/failed

    //await first connection to fail fast if something
    state.mapNotNull {
        when (it) {
            is ReconnectState.Connected -> it.rSocket
            is ReconnectState.Failed    -> throw it.error
            ReconnectState.Connecting   -> null
        }
    }.take(1).collect()

    return ReconnectableRSocket(job, state)
}

private fun Flow<*>.launchRestarting(): Job = GlobalScope.launch(Dispatchers.Unconfined) {
    while (isActive) {
        try {
            collect()
        } catch (e: Throwable) {
            // KLUDGE: K/N
            cancel("Reconnection failed", e)
            break
        }
    }
}

private sealed class ReconnectState {
    object Connecting : ReconnectState()
    data class Failed(val error: Throwable) : ReconnectState()
    data class Connected(val rSocket: RSocketRequester) : ReconnectState()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
private class ReconnectableRSocket(
    override val job: Job,
    private val state: StateFlow<ReconnectState>,
) : RSocketRequester {

    private val reconnectHandler = state.mapNotNull { it.handleState { null } }.take(1)

    //null pointer will never happen
    private suspend fun currentRSocket(): RSocketRequester = state.value.handleState { reconnectHandler.first() }!!

    private inline fun ReconnectState.handleState(onReconnect: () -> RSocketRequester?): RSocketRequester? = when (this) {
        is ReconnectState.Connected -> when {
            rSocket.isActive -> rSocket //connection is ready to handle requests
            else             -> onReconnect() //reconnection
        }
        is ReconnectState.Failed    -> throw error //connection failed - fail requests
        ReconnectState.Connecting   -> onReconnect() //reconnection
    }

    override suspend fun metadataPush(metadata: ByteReadPacket): Unit = currentRSocket().metadataPush(metadata)
    override suspend fun fireAndForget(payload: Payload): Unit = currentRSocket().fireAndForget(payload)
    override suspend fun requestResponse(payload: Payload): Payload = currentRSocket().requestResponse(payload)
    override fun requestStream(payload: Payload): ReactiveFlow<Payload> = RequestStreamSingleFlow(payload)
    override fun requestStream(payload: suspend () -> Payload): ReactiveFlow<Payload> = RequestStreamMultiFlow(payload)
    override fun requestChannel(payloads: Flow<Payload>): ReactiveFlow<Payload> = RequestChannelFlow(payloads)

    @OptIn(ExperimentalStreamsApi::class)
    private inner class RequestStreamSingleFlow(
        private val payload: Payload,
        strategy: (() -> RequestStrategy)? = null,
    ) : LazyReactiveFlow<Payload>(strategy) {
        override suspend fun flow(): ReactiveFlow<Payload> = currentRSocket().requestStream(payload)
        override fun request(strategy: () -> RequestStrategy): ReactiveFlow<Payload> = RequestStreamSingleFlow(payload, strategy)
    }

    @OptIn(ExperimentalStreamsApi::class)
    private inner class RequestStreamMultiFlow(
        private val payload: suspend () -> Payload,
        strategy: (() -> RequestStrategy)? = null,
    ) : LazyReactiveFlow<Payload>(strategy) {
        override suspend fun flow(): ReactiveFlow<Payload> = currentRSocket().requestStream(payload)
        override fun request(strategy: () -> RequestStrategy): ReactiveFlow<Payload> = RequestStreamMultiFlow(payload, strategy)
    }

    @OptIn(ExperimentalStreamsApi::class)
    private inner class RequestChannelFlow(
        private val payloads: Flow<Payload>,
        strategy: (() -> RequestStrategy)? = null,
    ) : LazyReactiveFlow<Payload>(strategy) {
        override suspend fun flow(): ReactiveFlow<Payload> = currentRSocket().requestChannel(payloads)
        override fun request(strategy: () -> RequestStrategy): ReactiveFlow<Payload> = RequestChannelFlow(payloads, strategy)
    }

}

@OptIn(ExperimentalStreamsApi::class)
private abstract class LazyReactiveFlow<out T>(private val strategy: (() -> RequestStrategy)?) : ReactiveFlow<T> {

    abstract suspend fun flow(): ReactiveFlow<T>

    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<T>) {
        when (strategy) {
            null -> flow()
            else -> flow().request(strategy)
        }.collect(collector)
    }
}
