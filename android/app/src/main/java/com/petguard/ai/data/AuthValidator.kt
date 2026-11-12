package com.petguard.ai.data

import android.util.Patterns

object AuthValidator {
    fun validateEmail(value: String): String? =
        if (Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()) null else "Digite um e-mail válido."

    fun validatePassword(value: String): String? = when {
        value.length < 8 -> "A senha deve ter pelo menos 8 caracteres."
        value.none(Char::isDigit) -> "Inclua pelo menos um número na senha."
        value.any(Char::isISOControl) -> "A senha contém caracteres inválidos."
        else -> null
    }

    fun sanitizeEmail(value: String): String = value.filter { it.isLetterOrDigit() || it in "@._+-" }.take(120)
    // Preserva caracteres válidos da senha; limita apenas o tamanho da entrada.
    fun sanitizePassword(value: String): String = value.take(128)
}
