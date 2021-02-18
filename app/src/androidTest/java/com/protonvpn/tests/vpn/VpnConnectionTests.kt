/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.protonvpn.tests.vpn

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.protonvpn.android.api.GuestHole
import com.protonvpn.android.models.config.UserData
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.ui.home.ServerListUpdater
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.TrafficMonitor
import com.protonvpn.android.utils.UserPlanManager
import com.protonvpn.android.vpn.ErrorType
import com.protonvpn.android.vpn.MaintenanceTracker
import com.protonvpn.android.vpn.ProtonVpnBackendProvider
import com.protonvpn.android.vpn.SwitchServerReason
import com.protonvpn.android.vpn.VpnConnectionErrorHandler
import com.protonvpn.android.vpn.VpnFallbackResult
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStateMonitor
import com.protonvpn.di.MockNetworkManager
import com.protonvpn.di.MockVpnStateMonitor
import com.protonvpn.mocks.MockVpnBackend
import com.protonvpn.testsHelper.MockedServers
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import me.proton.core.network.domain.NetworkStatus
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.EmptyCoroutineContext

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
// These tests use mocking of final classes that's not available on API < 28
@SdkSuppress(minSdkVersion = 28)
class VpnConnectionTests {

    @get:Rule
    var rule = InstantTaskExecutorRule()

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var userData: UserData
    private lateinit var monitor: VpnStateMonitor
    private lateinit var networkManager: MockNetworkManager

    @RelaxedMockK
    lateinit var serverListUpdater: ServerListUpdater

    @RelaxedMockK
    lateinit var trafficMonitor: TrafficMonitor

    @RelaxedMockK
    lateinit var serverManager: ServerManager

    @RelaxedMockK
    lateinit var maintenanceTracker: MaintenanceTracker

    @RelaxedMockK
    lateinit var userPlanManager: UserPlanManager

    @RelaxedMockK
    lateinit var vpnErrorHandler: VpnConnectionErrorHandler

    private lateinit var mockStrongSwan: MockVpnBackend
    private lateinit var mockOpenVpn: MockVpnBackend

    private lateinit var profileSmart: Profile
    private lateinit var profileIKEv2: Profile
    private lateinit var fallbackOpenVpnProfile: Profile

    private val switchServerFlow = MutableSharedFlow<VpnFallbackResult.SwitchProfile>()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        context = InstrumentationRegistry.getInstrumentation().context
        scope = TestCoroutineScope(EmptyCoroutineContext)
        userData = UserData()
        networkManager = MockNetworkManager()

        mockStrongSwan = spyk(MockVpnBackend(VpnProtocol.IKEv2))
        mockOpenVpn = spyk(MockVpnBackend(VpnProtocol.OpenVPN))

        coEvery { vpnErrorHandler.switchConnectionFlow } returns switchServerFlow

        val backendProvider = ProtonVpnBackendProvider(strongSwan = mockStrongSwan, openVpn = mockOpenVpn)
        monitor = MockVpnStateMonitor(userData, backendProvider, serverListUpdater, trafficMonitor, networkManager,
            userPlanManager, maintenanceTracker, vpnErrorHandler, scope)

        MockNetworkManager.currentStatus = NetworkStatus.Unmetered

        val server = MockedServers.server
        profileSmart = MockedServers.getProfile(VpnProtocol.Smart, server)
        profileIKEv2 = MockedServers.getProfile(VpnProtocol.IKEv2, server)
        fallbackOpenVpnProfile = MockedServers.getProfile(VpnProtocol.OpenVPN, server, "fallback")
    }

    @Test
    fun testSmartFallbackToOpenVPN() = runBlockingTest {
        mockStrongSwan.failScanning = true
        monitor.connect(context, profileSmart)
        yield()

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Connected, vpnState.state)
        Assert.assertEquals(VpnProtocol.OpenVPN, vpnState.connectionParams?.protocol)
    }

    @Test
    fun testAllBlocked() = runBlockingTest {
        mockStrongSwan.failScanning = true
        mockOpenVpn.failScanning = true
        userData.manualProtocol = VpnProtocol.OpenVPN
        monitor.connect(context, profileSmart)
        yield()

        // When scanning fails we'll fallback to attempt connecting with default manual protocol
        coVerify(exactly = 1) {
            mockOpenVpn.prepareForConnection(any(), any(), false)
            mockOpenVpn.connect()
        }

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Connected, vpnState.state)
    }

    @Test
    fun smartNoInternet() = runBlockingTest {
        MockNetworkManager.currentStatus = NetworkStatus.Disconnected
        userData.manualProtocol = VpnProtocol.OpenVPN
        monitor.connect(context, profileSmart)
        yield()

        coVerify(exactly = 0) {
            mockStrongSwan.prepareForConnection(any(), any(), any())
        }
        coVerify(exactly = 1) {
            mockOpenVpn.prepareForConnection(any(), any(), false)
            mockOpenVpn.connect()
        }

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Connected, vpnState.state)
    }

    @Test
    fun executeInGuestHole() = runBlockingTest {
        val guestHole = GuestHole(serverManager, monitor)
        val wasConnected = guestHole.call(context) {
            monitor.isConnected
        }
        Assert.assertTrue(monitor.isDisabled)
        Assert.assertEquals(true, wasConnected)
    }

    @Test
    fun guestHoleFail() = runBlockingTest {
        mockStrongSwan.failScanning = true
        mockOpenVpn.failScanning = true
        mockOpenVpn.stateOnConnect = VpnState.Disabled

        val guestHole = GuestHole(serverManager, monitor)
        val result = guestHole.call(context) {
            Assert.fail()
        }
        Assert.assertTrue(monitor.isDisabled)
        Assert.assertEquals(null, result)
    }

    @Test
    fun authErrorHandleDowngrade() = runBlockingTest {
        mockStrongSwan.stateOnConnect = VpnState.Error(ErrorType.AUTH_FAILED_INTERNAL)
        mockOpenVpn.stateOnConnect = VpnState.Connected

        coEvery { vpnErrorHandler.onAuthError(any()) } returns
            VpnFallbackResult.SwitchProfile(fallbackOpenVpnProfile, SwitchServerReason.DowngradeToFree)

        val fallbacks = mutableListOf<SwitchServerReason>()
        val collectJob = launch {
            monitor.fallbackConnectionFlow.collect {
                fallbacks += it
            }
        }

        monitor.connect(context, profileIKEv2)
        advanceUntilIdle()
        collectJob.cancel()

        coVerify(exactly = 1) {
            mockStrongSwan.connect()
        }
        coVerify(exactly = 1) {
            mockOpenVpn.connect()
        }

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Connected, vpnState.state)
        Assert.assertEquals(fallbackOpenVpnProfile, vpnState.profile)
        Assert.assertEquals(listOf(SwitchServerReason.DowngradeToFree), fallbacks)
    }

    @Test
    fun authErrorHandleMaxSessions() = runBlockingTest {
        mockStrongSwan.stateOnConnect = VpnState.Error(ErrorType.AUTH_FAILED_INTERNAL)
        coEvery { vpnErrorHandler.onAuthError(any()) } returns VpnFallbackResult.Error(ErrorType.MAX_SESSIONS)

        val fallbacks = mutableListOf<SwitchServerReason>()
        val collectJob = launch {
            monitor.fallbackConnectionFlow.collect {
                fallbacks += it
            }
        }

        monitor.connect(context, profileIKEv2)
        advanceUntilIdle()
        collectJob.cancel()

        coVerify(exactly = 1) {
            mockStrongSwan.connect()
        }

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Error(ErrorType.MAX_SESSIONS), vpnState.state)
        Assert.assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun testSwitchingConnection() = runBlockingTest {
        val fallbacks = mutableListOf<SwitchServerReason>()
        val collectJob = launch {
            monitor.fallbackConnectionFlow.collect {
                fallbacks += it
            }
        }

        monitor.connect(context, profileIKEv2)
        advanceUntilIdle()

        Assert.assertEquals(VpnState.Connected, monitor.vpnStatus.value!!.state)

        switchServerFlow.emit(
            VpnFallbackResult.SwitchProfile(fallbackOpenVpnProfile, SwitchServerReason.DowngradeToFree))
        advanceUntilIdle()

        collectJob.cancel()

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Connected, vpnState.state)
        Assert.assertEquals(listOf(SwitchServerReason.DowngradeToFree), fallbacks)
    }

    @Test
    fun testDontSwitchWhenDisconnected() = runBlockingTest {
        val fallbacks = mutableListOf<SwitchServerReason>()
        val collectJob = launch {
            monitor.fallbackConnectionFlow.collect {
                fallbacks += it
            }
        }

        switchServerFlow.emit(
            VpnFallbackResult.SwitchProfile(fallbackOpenVpnProfile, SwitchServerReason.DowngradeToFree))
        advanceUntilIdle()

        collectJob.cancel()

        val vpnState = monitor.vpnStatus.value!!
        Assert.assertEquals(VpnState.Disabled, vpnState.state)
        Assert.assertTrue(fallbacks.isEmpty())
    }
}
