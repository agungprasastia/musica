package com.music.musica.core.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    @Immutable
    data class Success<T>(val data: T) : UiState<T>
    @Immutable
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}
