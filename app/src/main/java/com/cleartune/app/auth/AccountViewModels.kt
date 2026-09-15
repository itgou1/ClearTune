package com.cleartune.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/** Retained across activity recreation, explicitly discarded when the login session ends. */
class AccountViewModels : ViewModel() {
    val store = ViewModelStore()
    fun clearSession() = store.clear()
    override fun onCleared() = store.clear()
}
