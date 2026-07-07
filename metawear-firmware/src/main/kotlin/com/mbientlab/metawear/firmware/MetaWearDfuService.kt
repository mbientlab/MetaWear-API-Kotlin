package com.mbientlab.metawear.firmware

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService

// Android-specific companion to DFUSession.swift's Nordic wrapper: on iOS the
// Nordic library runs in-process, but the Android library performs the
// transfer inside an Android service, which must be a concrete DfuBaseService
// subclass registered in the manifest.

/**
 * The Android service that hosts the Nordic DFU transfer.
 *
 * This library's manifest registers the service, and manifest merging carries
 * that registration into the consuming app — no app-side declaration needed
 * unless you subclass this to supply a notification target activity.
 *
 * Per Nordic's documentation, apps should additionally:
 *  • Call [no.nordicsemi.android.dfu.DfuServiceInitiator.createDfuNotificationChannel]
 *    once before starting a DFU if they enable the foreground-service
 *    notification ([DfuSession] runs with the notification disabled by
 *    default, so this is only needed when you opt into it).
 *  • Hold the runtime `BLUETOOTH_CONNECT` permission (API 31+) before
 *    starting an update — the same permission the rest of the MetaWear SDK
 *    already requires. Android 14+ apps that opt into a foreground DFU
 *    service must also declare `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
 */
open class MetaWearDfuService : DfuBaseService() {

    /**
     * No notification tap-target by default: [DfuSession] disables the DFU
     * notification. Subclass and override to point at an activity if your app
     * runs the DFU as a foreground service with a visible notification.
     */
    override fun getNotificationTarget(): Class<out Activity>? = null
}
