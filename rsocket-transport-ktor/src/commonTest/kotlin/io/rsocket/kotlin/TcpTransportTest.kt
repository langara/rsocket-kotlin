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

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.rsocket.kotlin.connection.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.test.*

abstract class TcpTransportTest(dispatcher: CoroutineContext, addressGenerator: () -> NetworkAddress? = { null }) : TransportTest() {
    private val selector = SelectorManager(dispatcher)
    private val builder = aSocket(selector).tcp()
    private val server = builder.bind(addressGenerator())
    private var serverJob: Job? = null
    private var clientJob: Job? = null

    @AfterTest
    override fun clean() {
        test {
            serverJob?.cancel()
            clientJob?.cancel()
            server.socketContext.cancel()
            server.close()
            selector.close()
        }
    }

    override suspend fun init(): RSocket {
        val (socket, serverSocket) = coroutineScope {
            val serverPromise = async { server.accept() }
            val clientSocket = builder.connect(server.localAddress)
            val serverSocket = serverPromise.await()
            clientSocket to serverSocket
        }
        serverJob = GlobalScope.launch {
            serverSocket.connection.startServer(SERVER_CONFIG, ACCEPTOR).join()
        }
        val client = socket.connection.connectClient(CONNECTOR_CONFIG)
        clientJob = client.job
        return client
    }
}
