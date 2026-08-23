package com.skycommand.relay.app

import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.RuntimeState
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusLabelsContractTest {
    @Test
    fun printsOperatorLanguageInsteadOfMachineWords() {
        assertEquals("运行中", StatusLabels.runtime(RuntimeState.RUNNING))
        assertEquals("等待重连", StatusLabels.gateway(SessionState.RECONNECT_WAIT))
        assertEquals("已连接", StatusLabels.gateway(SessionState.ACTIVE))
        assertEquals("已连接", StatusLabels.link("CONNECTED"))
        assertEquals("未连接", StatusLabels.link("DISCONNECTED"))
        assertEquals("未对频", StatusLabels.pairing("IDLE"))
        assertEquals("正在结束对频", StatusLabels.pairing("STOPPING"))
    }
}
