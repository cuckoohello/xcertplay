package com.shilapi.xcertplay.mfi

/**
 * MFi backend selection shared between the general orchestrator and the E01 wired pipeline.
 *
 * Placed in the mfi package so packages excluded from the :e01shared source set (currently
 * orchestration) do not hide it from the E01 build.
 */
enum class MfiTarget {
    USB_CH341,
    I2C,
    REMOTE,
    LOCAL,
}
