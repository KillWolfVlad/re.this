package eu.vendeli.rethis.tests

import eu.vendeli.rethis.ReThisTestCtx
import eu.vendeli.rethis.commands.get
import eu.vendeli.rethis.commands.set
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*

class BenchTest : ReThisTestCtx() {
    // 30 sec
    @Test
    suspend fun `100K bench test`() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            (1..100_000).map {
                async {
                    client.set("key${it}", "value${it}") shouldBe "OK"
                    client.get("key${it}") shouldBe "value${it}"
                }
            }.awaitAll()
        }.join()
    }
}
