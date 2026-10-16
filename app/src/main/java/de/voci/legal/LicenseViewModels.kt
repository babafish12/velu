package de.voci.legal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LicenseState<out T> {
    data object Loading : LicenseState<Nothing>
    data class Ready<T>(val value: T) : LicenseState<T>
    data class Failed(val message: String) : LicenseState<Nothing>
}

class LicenseListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LicenseRepository(application.assets)
    private val mutableState = MutableStateFlow<LicenseState<List<LicenseEntry>>>(LicenseState.Loading)
    val state = mutableState.asStateFlow()
    private var loading: Job? = null

    init { retry() }

    fun retry() {
        loading?.cancel()
        mutableState.value = LicenseState.Loading
        loading = viewModelScope.launch {
            try { mutableState.value = LicenseState.Ready(repository.entries()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = LicenseState.Failed("Die lokale Lizenzliste fehlt oder ist beschädigt. Erneut versuchen.")
            }
        }
    }
}

/** One immutable document selection per navigation entry/key. A result from another
 * entry can never replace this document; clearing the owner cancels outstanding loading.
 */
class LicenseDocumentViewModel(application: Application, private val licenseId: String?) : ViewModel() {
    private val repository = LicenseRepository(application.assets)
    private val mutableState = MutableStateFlow<LicenseState<LicenseDocument>>(LicenseState.Loading)
    val state = mutableState.asStateFlow()
    private var loading: Job? = null

    init { retry() }

    fun retry() {
        loading?.cancel()
        mutableState.value = LicenseState.Loading
        loading = viewModelScope.launch {
            try {
                val document = if (licenseId == null) repository.notices() else repository.document(licenseId)
                mutableState.value = LicenseState.Ready(document)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = LicenseState.Failed("Der lokale Lizenztext fehlt oder ist ungültig. Erneut versuchen.")
            }
        }
    }

    companion object {
        fun factory(application: Application, licenseId: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == LicenseDocumentViewModel::class.java)
                    return LicenseDocumentViewModel(application, licenseId) as T
                }
            }
    }
}
