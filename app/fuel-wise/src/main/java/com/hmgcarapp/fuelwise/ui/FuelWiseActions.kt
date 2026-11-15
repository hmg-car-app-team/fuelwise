package com.hmgcarapp.fuelwise.ui

import com.hmgcarapp.fuelwise.app.AppController
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.SortKey

/**
 * 화면이 컨트롤러에 요청하는 동작. 화면 코드는 이 인터페이스만 보므로 차량 연결 없이 상태만으로도 그릴 수 있다(렌더링 테스트).
 * 정차 중에만 허용하는 등의 가드는 컨트롤러가 그대로 맡는다.
 */
internal interface FuelWiseActions {
    fun setPage(p: Page)
    fun select(id: String)
    fun setSort(k: SortKey)
    fun startGuidance(candidateId: String?)
    fun recordSelected()
    fun dismissBanner()
    fun confirmReplace()
    fun cancelReplace()
    fun clearMessage()
    fun toggleCard(id: String)
    fun editSettings(f: (Settings) -> Settings)
    fun setVoiceReply(on: Boolean)
    fun setDestination(index: Int?)
    fun clearReport()
    fun addSampleReport()
    fun setDemoDrive(v: String?)
    fun setSimFuel(v: Int?)
    fun setSimSoc(v: Int?)
    fun setSimRange(v: Float?)
    fun fireAlert(reason: String)
    fun rearm()
}

/** 실제 앱: 모든 동작을 [AppController] 로 넘긴다. */
internal class ControllerActions(private val c: AppController) : FuelWiseActions {
    override fun setPage(p: Page) = c.setPage(p)
    override fun select(id: String) = c.select(id)
    override fun setSort(k: SortKey) = c.setSort(k)
    override fun startGuidance(candidateId: String?) = c.startGuidance(candidateId)
    override fun recordSelected() = c.recordSelected()
    override fun dismissBanner() = c.dismissBanner()
    override fun confirmReplace() = c.confirmReplace()
    override fun cancelReplace() = c.cancelReplace()
    override fun clearMessage() = c.clearMessage()
    override fun toggleCard(id: String) = c.toggleCard(id)
    override fun editSettings(f: (Settings) -> Settings) = c.editSettings(f)
    override fun setVoiceReply(on: Boolean) = c.setVoiceReply(on)
    override fun setDestination(index: Int?) = c.setDestination(index)
    override fun clearReport() = c.clearReport()
    override fun addSampleReport() = c.addSampleReport()
    override fun setDemoDrive(v: String?) = c.setDemoDrive(v)
    override fun setSimFuel(v: Int?) = c.setSimFuel(v)
    override fun setSimSoc(v: Int?) = c.setSimSoc(v)
    override fun setSimRange(v: Float?) = c.setSimRange(v)
    override fun fireAlert(reason: String) = c.fireAlert(reason)
    override fun rearm() = c.rearm()
}
