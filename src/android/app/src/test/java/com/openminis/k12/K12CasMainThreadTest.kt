package com.openminis.k12

import android.app.Application
import com.openminis.k12.tutoring.CalcResult
import com.openminis.k12.tutoring.SymjaCalcEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A4 must not initialize Symja on the Android main thread.
 * Robolectric runs the test method on the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class K12CasMainThreadTest {
    @Test
    fun mainThreadIsRefusedAndAWorkerCanEvaluate() {
        assertTrue(SymjaCalcEngine.onAndroidMainThread())
        val refused = SymjaCalcEngine.evaluate("1+1")
        assertEquals("main-thread", (refused as CalcResult.Err).message)

        val latch = CountDownLatch(1)
        val got = AtomicReference<CalcResult>()
        Thread {
            got.set(SymjaCalcEngine.evaluate("3+4"))
            latch.countDown()
        }.start()
        assertTrue(latch.await(90, TimeUnit.SECONDS))
        assertEquals(CalcResult.Ok("7"), got.get())
    }
}
