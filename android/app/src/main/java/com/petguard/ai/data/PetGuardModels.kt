package com.petguard.ai.data

data class PetProfile(
    val id: String,
    val name: String,
    val species: String,
    val breed: String,
    val ageLabel: String
)

data class TutorProfile(val name: String, val email: String)

data class PetEvent(
    val id: String,
    val date: String,
    val category: EventCategory,
    val title: String,
    val detail: String,
    val timeLabel: String,
    val petId: String = ""
)

data class CameraAlert(
    val id: Long,
    val timestamp: String,
    val category: String,
    val priority: String,
    val message: String,
    val petId: String = "",
    val petName: String = ""
)

data class LivePetDetection(
    val trackId: Int,
    val species: String,
    val confidence: Float,
    val interaction: String?,
    val petId: String,
    val petName: String
)

data class CameraZone(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

enum class EventCategory { ATTENTION, INJURY, HEALTHY, ACTIVITY }

data class CameraDevice(
    val id: String,
    val name: String,
    val url: String = "",
    val connected: Boolean = false
)

data class PetPreferences(
    val lightTheme: Boolean = false,
    val colorAccessiblePalette: Boolean = false,
    val yoloEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true
)

data class PetGuardContent(
    val tutor: TutorProfile,
    val pets: List<PetProfile>,
    val selectedPetId: String,
    val cameras: List<CameraDevice>,
    val events: List<PetEvent>,
    val preferences: PetPreferences
)

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Failure(val message: String) : RepositoryResult<Nothing>
}
