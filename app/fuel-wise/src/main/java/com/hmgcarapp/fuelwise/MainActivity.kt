package com.hmgcarapp.fuelwise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.CorePermissions
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.fuelwise.app.AppController
import com.hmgcarapp.fuelwise.domain.PermissionFilter
import com.hmgcarapp.fuelwise.ui.FuelWiseApp
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme

class MainActivity : ComponentActivity() {
    private var controller: AppController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HmgLog.i("fuelwise", "onCreate (API 키 ${if (BuildConfig.API_KEY.isEmpty()) "없음 -> Fake" else "있음 -> Remote 시도"})")
        // 위치 런타임 권한은 포그라운드 서비스/구독 전에 Activity 에서 먼저 요청한다.
        // 매니페스트에 선언되지 않은 권한(POST_NOTIFICATIONS 등)은 요청하지 않는다
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val toRequest = PermissionFilter.requestable(CorePermissions.missing(this), declared)
        HmgLog.i("fuelwise", "권한 요청: $toRequest")
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        val c = AppController(this, CarSignals.create(this), BuildConfig.API_KEY)
        controller = c
        setContent {
            PleosTheme {
                PleosUxRestrictionsProvider { FuelWiseApp(c) }
            }
        }
    }

    override fun onStart() { super.onStart(); controller?.onAppStart() } // 일시정지된 신호 폴링 즉시 재시도

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller?.onAppStart() // 권한 부여 직후 즉시 재시도
    }

    override fun onStop() { controller?.saveNow(); super.onStop() }

    override fun onDestroy() { controller?.release(); controller = null; super.onDestroy() }
}
