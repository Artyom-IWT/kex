package org.jetbrains.research.kex

import org.jetbrains.research.kex.config.RuntimeConfig
import kotlin.math.round
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class ArrayLongTest : KexRunnerTest() {
    @Test
    fun testArrays() {
        val cfg = RuntimeConfig
        val oldSlicingConfig = cfg.getBooleanValue("smt", "slicing", true)
        RuntimeConfig.setValue("smt", "slicing", false)

        val `class` = cm["$packageName/ArrayLongTests"]
        val time = measureTimeMillis {  testClassReachability(`class`) }
        println("${round(time.toFloat() / (1000 * 60))} minutes")

        RuntimeConfig.setValue("smt", "slicing", oldSlicingConfig)
    }
}