package org.jetbrains.research.kex.bmc

import org.jetbrains.research.kex.KexRunnerTest
import kotlin.math.round
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class BasicTest : KexRunnerTest() {

    @Test
    fun testBasicReachability() {
        val `class` = cm["$packageName/BasicTests"]
        val time = measureTimeMillis {  bmc(`class`) }
        println("${round(time.toFloat() / (1000 * 60))} minutes")
    }

}