package com.welie.blessed

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Tests for the status-aware [BluetoothCentralManager.observeConnectionState] overload that forwards
 * the [HciStatus] of each connection state transition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothCentralManagerConnectionStateTest {

    private fun newManager(): BluetoothCentralManager =
        BluetoothCentralManager(ApplicationProvider.getApplicationContext())

    private fun newPeripheral(address: String): BluetoothPeripheral =
        mockk(relaxed = true) {
            every { this@mockk.address } returns address
        }

    /**
     * Collected (state, status) events together with a latch so the test can wait for the
     * asynchronously dispatched callback before asserting.
     */
    private class Collector {
        val events = CopyOnWriteArrayList<Pair<ConnectionState, HciStatus>>()
        @Volatile
        var latch = CountDownLatch(1)

        fun record(state: ConnectionState, status: HciStatus) {
            events.add(state to status)
            latch.countDown()
        }

        fun awaitNext(): Pair<ConnectionState, HciStatus> {
            assertTrue("timed out waiting for connection state callback", latch.await(2, TimeUnit.SECONDS))
            latch = CountDownLatch(1)
            return events.last()
        }
    }

    @Test
    fun `status overload reports SUCCESS for connecting, connected and disconnecting`() {
        val manager = newManager()
        val peripheral = newPeripheral("12:34:56:78:90:AB")
        val collector = Collector()
        manager.observeConnectionState { _, state, status -> collector.record(state, status) }

        manager.internalCallback.connecting(peripheral)
        assertEquals(ConnectionState.CONNECTING to HciStatus.SUCCESS, collector.awaitNext())

        manager.internalCallback.connected(peripheral)
        assertEquals(ConnectionState.CONNECTED to HciStatus.SUCCESS, collector.awaitNext())

        manager.internalCallback.disconnecting(peripheral)
        assertEquals(ConnectionState.DISCONNECTING to HciStatus.SUCCESS, collector.awaitNext())
    }

    @Test
    fun `status overload forwards the disconnect reason`() {
        val manager = newManager()
        val peripheral = newPeripheral("AA:BB:CC:DD:EE:FF")
        val collector = Collector()
        manager.observeConnectionState { _, state, status -> collector.record(state, status) }

        manager.internalCallback.disconnected(peripheral, HciStatus.CONNECTION_TIMEOUT)

        assertEquals(ConnectionState.DISCONNECTED to HciStatus.CONNECTION_TIMEOUT, collector.awaitNext())
    }

    @Test
    fun `status overload forwards the connection failure reason`() {
        val manager = newManager()
        val peripheral = newPeripheral("11:22:33:44:55:66")
        val collector = Collector()
        manager.observeConnectionState { _, state, status -> collector.record(state, status) }

        // CONNECTION_FAILED_ESTABLISHMENT short-circuits the retry logic and goes straight to the
        // "connection failed" branch, which is the equivalent of onConnectionFailed(peripheral, status).
        manager.internalCallback.connectFailed(peripheral, HciStatus.CONNECTION_FAILED_ESTABLISHMENT)

        assertEquals(
            ConnectionState.DISCONNECTED to HciStatus.CONNECTION_FAILED_ESTABLISHMENT,
            collector.awaitNext()
        )
    }

    @Test
    fun `legacy two arg overload keeps working`() {
        val manager = newManager()
        val peripheral = newPeripheral("99:88:77:66:55:44")
        val states = CopyOnWriteArrayList<ConnectionState>()
        val latch = CountDownLatch(1)
        manager.observeConnectionState { _, state ->
            states.add(state)
            latch.countDown()
        }

        manager.internalCallback.connected(peripheral)

        assertTrue("timed out waiting for legacy callback", latch.await(2, TimeUnit.SECONDS))
        assertEquals(ConnectionState.CONNECTED, states.last())
    }
}
