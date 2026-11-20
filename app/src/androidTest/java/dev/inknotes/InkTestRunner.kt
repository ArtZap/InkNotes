package dev.inknotes

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import dev.inknotes.data.Lang

/**
 * Runs the device tests in a set interface language, whatever the app is set to on the device:
 * Russian, or English with `-e lang en`. The tests compare texts ("Восстановленная тетрадь",
 * "2,5"); without this they would follow the language chosen in the app's settings. The app
 * may not change the language or restart for it while tests run.
 */
class InkTestRunner : AndroidJUnitRunner() {
    private var english = false

    override fun onCreate(arguments: Bundle?) {
        english = arguments?.getString("lang") == "en"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application?) {
        Lang.english = english
        Lang.fixed = true
        super.callApplicationOnCreate(app)
    }
}
