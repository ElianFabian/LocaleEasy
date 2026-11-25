package com.elianfabian.localeeasy

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

enum class MainDestination {
	Home,
	Compose,
}

class MainViewModel(private val savedState: SavedStateHandle) : ViewModel() {
	val destination = savedState.getStateFlow(DESTINATION, MainDestination.Home)

	fun navigateTo(destination: MainDestination) {
		savedState[DESTINATION] = destination
	}

	private companion object {
		const val DESTINATION = "destination"
	}
}
