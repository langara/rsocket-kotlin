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

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.native.concurrent.*

@ExperimentalStreamsApi
public interface RequestStrategy {
    public val initialRequest: Int
    public suspend fun nextRequest(): Int
}

public interface ReactiveFlow<out T> : Flow<T> {
    @ExperimentalStreamsApi
    public fun request(strategy: () -> RequestStrategy): ReactiveFlow<T>
}

//request `requestSize` when `requestOn` elements left for collection
//f.e. requestSize = 30, requestOn = 10, then first requestN will be 30, after 20 elements will be collected, new requestN for 30 elements will be sent
//     so collect will be smooth
@ExperimentalStreamsApi
public fun <T> ReactiveFlow<T>.requestBy(requestSize: Int, requestOn: Int = requestSize / 4): ReactiveFlow<T> {
    require(requestOn > 0)
    require(requestSize > 0)
    require(requestOn < requestSize)
    return request { PrefetchStrategy(requestSize, requestOn) }
}

//similar, but request in chunks, could be removed
@ExperimentalStreamsApi
public fun <T> ReactiveFlow<T>.requestByFixed(requestSize: Int): ReactiveFlow<T> {
    return requestBy(requestSize, 0)
}

//simple strategy to request only `count` elements
@ExperimentalStreamsApi
public fun <T> ReactiveFlow<T>.requestOnly(count: Int): ReactiveFlow<T> {
    return requestBy(count, 0).intercept { take(count) }
}


@ExperimentalStreamsApi
public fun <R, T> ReactiveFlow<T>.intercept(block: Flow<T>.() -> Flow<R>): ReactiveFlow<R> =
    InterceptedReactiveFlow(this, block)


@ExperimentalStreamsApi
private class InterceptedReactiveFlow<out T, R>(
    private val original: ReactiveFlow<T>,
    private val block: Flow<T>.() -> Flow<R>,
) : ReactiveFlow<R> {
    override fun request(strategy: () -> RequestStrategy): ReactiveFlow<R> =
        InterceptedReactiveFlow(original.request(strategy), block)

    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<R>) {
        original.block().collect(collector)
    }
}

@SharedImmutable
@ExperimentalStreamsApi
internal val DefaultStrategy: () -> RequestStrategy = { PrefetchStrategy(64, 16) }

@ExperimentalStreamsApi
private class PrefetchStrategy(
    private val requestSize: Int,
    private val requestOn: Int,
) : RequestStrategy {
    init {
        require(requestOn > 0)
        require(requestSize > 0)
        require(requestOn < requestSize)
    }

    override val initialRequest: Int get() = requestSize
    private val requested = atomic(requestSize)

    override suspend fun nextRequest(): Int {
        if (requested.decrementAndGet() != requestOn) return 0

        requested += requestSize
        return requestSize
    }
}

//
//@OptIn(ExperimentalStreamsApi::class)
//private class ChannelRequestStrategy(
//    override val initialRequest: Int,
//    private val channel: ReceiveChannel<Int>,
//) : RequestStrategy {
//    init {
//        require(initialRequest > 0)
//    }
//
//    private val requested = atomic(initialRequest)
//    override suspend fun nextRequest(): Int {
//        if (requested.decrementAndGet() != 0) return 0
//        var requestSize = 0
//        while (requestSize <= 0) requestSize = channel.receive()
//        requested += requestSize
//        return requestSize
//    }
//}
//
//@ExperimentalStreamsApi
//public fun <T> ReactiveFlow<T>.request(initialRequest: Int, channel: ReceiveChannel<Int>): ReactiveFlow<T> {
//    return request { ChannelRequestStrategy(initialRequest, channel) }
//}
