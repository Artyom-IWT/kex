package org.vorpal.research.kex.symbolic

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class SetSymbolicLongTest : SymbolicTest("set-symbolic") {
    @Test
    fun setConcolicTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/concolic/SetConcolicTests"], 1.0)
    }
}
