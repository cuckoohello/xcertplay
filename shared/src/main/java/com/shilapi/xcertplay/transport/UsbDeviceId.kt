package com.shilapi.xcertplay.transport

/** A USB identity explicitly allowed by the product or deployment configuration. */
data class UsbDeviceId(val vendorId: Int, val productId: Int)
