package de.voci.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val archive = AndroidArchiveVerifier(application)
    val controller = UpdateController(viewModelScope, UpdateRepository(
        UpdateTransport(), UpdateFiles(application.cacheDir), archive,
        { archive.installed().versionCode }, android.os.Build.VERSION.SDK_INT
    ))
    override fun onCleared() { controller.close() }
}
