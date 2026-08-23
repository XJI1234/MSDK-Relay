package com.skycommand.relay.app

import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.RuntimeState

internal object StatusLabels {
    fun runtime(value: RuntimeState): String = when (value) {
        RuntimeState.STOPPED -> "已停止"
        RuntimeState.WAITING_PERMISSIONS -> "等待权限"
        RuntimeState.STARTING_SERVICE -> "正在启动服务"
        RuntimeState.STARTING_MODULES -> "正在启动模块"
        RuntimeState.RUNNING -> "运行中"
        RuntimeState.STOPPING -> "正在停止"
        RuntimeState.FAILED -> "失败"
    }

    fun gateway(value: SessionState): String = when (value) {
        SessionState.STOPPED -> "已停止"
        SessionState.CONNECTING -> "正在连接"
        SessionState.AWAITING_PAIRING -> "等待握手"
        SessionState.ACTIVE -> "已连接"
        SessionState.RECONNECT_WAIT -> "等待重连"
    }

    fun link(value: String): String = when (value) {
        "CONNECTED" -> "已连接"
        "DISCONNECTED" -> "未连接"
        else -> value
    }

    fun pairing(value: String): String = when (value) {
        "UNKNOWN" -> "未知"
        "IDLE" -> "未对频"
        "PAIRING" -> "对频中"
        "PAIRED" -> "已对频"
        "STOPPING" -> "正在结束对频"
        "FAILED" -> "对频失败"
        else -> value
    }
}
