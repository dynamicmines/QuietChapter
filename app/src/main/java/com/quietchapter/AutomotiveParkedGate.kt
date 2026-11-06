package com.quietchapter

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Public AAOS UX state, not inferred gear/speed. Never affects service media controls. */
@androidx.annotation.RequiresApi(29)
class AutomotiveParkedGate(context: Context, private val changed: (Boolean, String) -> Unit) : AutoCloseable {
    private var closed = false
    private var manager: CarUxRestrictionsManager? = null
    private var car: Car? = null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { update(it) }

    init {
        changed(false, "Checking vehicle UI restrictions")
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!closed) {
                    if (!ready) {
                        manager = null
                        changed(false, "Vehicle UI status unavailable; use system media controls")
                    } else try {
                        manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager!!.registerListener(listener)
                        update(manager!!.currentCarUxRestrictions)
                    } catch (failure: Exception) {
                        Log.w("QuietChapter", "Parked UX query failed", failure)
                        changed(false, "Vehicle UI status unavailable; use system media controls")
                    }
                }
            }
        } catch (failure: Exception) {
            Log.w("QuietChapter", "Car service connection failed", failure)
            changed(false, "Vehicle UI status unavailable; use system media controls")
        }
    }

    private fun update(restrictions: CarUxRestrictions) {
        if (closed) return
        val allowed = !restrictions.isRequiresDistractionOptimization
        Log.i("QuietChapter", "Parked UI allowed=$allowed restrictions=${restrictions.activeRestrictions}")
        changed(allowed, if (allowed) "Parked experience · vehicle UI unrestricted"
            else "Parked UI unavailable while restricted; use system media controls")
    }

    override fun close() {
        closed = true
        try { manager?.unregisterListener() }
        catch (failure: Exception) { Log.w("QuietChapter", "UX listener cleanup failed", failure) }
        finally { manager = null; car?.disconnect(); car = null }
    }
}
