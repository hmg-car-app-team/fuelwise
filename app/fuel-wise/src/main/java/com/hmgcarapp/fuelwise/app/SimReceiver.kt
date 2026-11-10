package com.hmgcarapp.fuelwise.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.fuelwise.domain.AlertEvent
import com.hmgcarapp.fuelwise.domain.KeyedInterval
import com.hmgcarapp.fuelwise.domain.SimField
import com.hmgcarapp.fuelwise.domain.SimInput

/** adb 시연 주입 수신기(debuggable 전용). 컨트롤러의 시연 실행부(시스템 주도, 가드 없음)만 호출한다. */
internal object SimReceiver {
    const val ACTION = "com.hmgcarapp.fuelwise.SIM"
    const val PERMISSION = "android.permission.DUMP"
    const val MIN_INTERVAL_MS = 300L

    private fun <T> SimField<T>.into(key: String, raw: Any?, set: (T?) -> Unit) {
        when (this) {
            is SimField.Set -> set(value)
            SimField.Clear -> set(null)
            SimField.Ignore -> HmgLog.w("FuelWise", "SIM 무시: $key=$raw (형식 또는 범위 오류)")
        }
    }

    /**
     * 시스템 주도 시연 경로: DRIVING 가드 예외(의도). 주행 시연 중에도 값을 주입·`accept` 할 수 있어야 하기 때문이며,
     * 음성 수락과 같은 경로(시스템 주도)를 adb 로 재현한다. debuggable 빌드에서만 등록되고 DUMP 권한(adb shell)이 있어야 보낼 수 있다.
     * 숫자 extras 는 `--ei`/`--ef` 어느 쪽이든 Number 로 받는다. 알 수 없는 extra·잘못된 값은 사유를 로그에 남기고 무시한다.
     */
    fun register(context: Context, c: AppController, monoClock: () -> Long): BroadcastReceiver {
        val debounce = KeyedInterval(MIN_INTERVAL_MS)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (!debounce.accept(i.extras?.keySet()?.sorted()?.joinToString(",") ?: "", monoClock())) { HmgLog.i("FuelWise", "SIM 무시: ${MIN_INTERVAL_MS}ms 이내 재수신"); return }
                val ex = i.extras
                fun raw(key: String): Any? = ex?.get(key)
                if (i.hasExtra("fuel")) SimInput.percentAny(raw("fuel")).into("fuel", raw("fuel"), c::simFuel)
                if (i.hasExtra("soc")) SimInput.percentAny(raw("soc")).into("soc", raw("soc"), c::simSoc)
                if (i.hasExtra("range")) SimInput.rangeAny(raw("range")).into("range", raw("range"), c::simRange)
                // 시연 모드는 메모리 전용(simMode). 저장된 설정(energyMode)에는 쓰지 않는다.
                if (i.hasExtra("mode")) SimInput.modeField(i.getStringExtra("mode")).into("mode", raw("mode"), c::simMode)
                if (i.hasExtra("drive")) when (SimInput.drive(i.getStringExtra("drive"))) {
                    is SimField.Set -> c.setDemoDrive("DRIVING")
                    SimField.Clear -> c.setDemoDrive(null)
                    SimField.Ignore -> HmgLog.w("FuelWise", "SIM 무시: drive=${raw("drive")}")
                }
                if (i.getBooleanExtra("rearm", false)) c.doRearm()
                if (i.getBooleanExtra("alert", false)) c.fireAlert("시연 알림")
                if (i.getBooleanExtra("accept", false)) c.dispatch(AlertEvent.AcceptBanner)
                HmgLog.i("FuelWise", "SIM 수신: ${ex?.keySet()}")
            }
        }
        val f = IntentFilter(ACTION)
        // adb shell(DUMP 권한 보유)만 보낼 수 있게 보호
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f, PERMISSION, null)
        return r
    }
}
