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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

internal inline fun <T> Closeable.closeOnError(block: () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        close()
        throw e
    }
}

internal fun ReceiveChannel<*>.cancelConsumed(cause: Throwable?) {
    cancel(cause?.let { it as? CancellationException ?: CancellationException("Channel was consumed, consumer had failed", it) })
}

internal fun ReceiveChannel<Closeable>.closeReceivedElements() {
    try {
        while (true) poll()?.close() ?: break
    } catch (e: Throwable) {
    }
}
