package com.shilapi.xcertplay.shared

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder("Select a local MFi certificate and private key in the host app settings.")
            .setHeaderAction(Action.APP_ICON)
            .setTitle("xcertplay MFi status")
            .build()
    }
}
